package cn.photolib.storage;

import cn.photolib.photo.PreviewMaintenanceLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Transactional
class MissingObjectPhotoCleanupJobTests {
    private static final long UPLOADER_ID = 91899L;

    @Autowired
    private ObjectStorageService storage;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PreviewMaintenanceLock maintenanceLock;
    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void isolateCandidateSet() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES
                    (:id, 'missing-object-cleanup-user', 'hash', 'cleanup test',
                     'ADMIN', true, false)
                """).param("id", UPLOADER_ID).update();
        // The job deliberately scans the whole library, so hide every pre-existing
        // row to make the circuit-breaker ratios deterministic. Rolled back with
        // the test transaction.
        jdbc.sql("UPDATE photo SET deleted=1 WHERE deleted=0").update();
    }

    @Test
    void keepsRecordsWhoseFinishedObjectStillExists() {
        String key = "photos/cleanup/present.jpg";
        putObject(key, "present");
        insertPhoto(91801L, key, "AVAILABLE", null, null);

        MissingObjectPhotoCleanupJob.CleanupResult result = job(storage, 0.2, 20).run();

        assertThat(result.checked()).isOne();
        assertThat(result.missing()).isZero();
        assertThat(result.deleted()).isZero();
        assertThat(deletedFlag(91801L)).isFalse();

        storage.delete(key);
    }

    @Test
    void softDeletesRecordsWhoseFinishedObjectIsConfirmedMissing() {
        String presentKey = "photos/cleanup/survivor.jpg";
        String leftoverPreview = "thumbnails/cleanup/orphan-preview.jpg";
        String leftoverOriginal = "originals/cleanup/orphan-original.jpg";
        putObject(presentKey, "present");
        putObject(leftoverPreview, "preview");
        putObject(leftoverOriginal, "original");
        insertPhoto(91802L, presentKey, "AVAILABLE", null, null);
        insertPhoto(91803L, "photos/cleanup/gone.jpg", "ARCHIVED",
                leftoverPreview, leftoverOriginal);

        MissingObjectPhotoCleanupJob.CleanupResult result = job(storage, 0.2, 20).run();

        assertThat(result.checked()).isEqualTo(2);
        assertThat(result.missing()).isOne();
        assertThat(result.deleted()).isOne();
        assertThat(result.aborted()).isFalse();
        assertThat(deletedFlag(91803L)).isTrue();
        assertThat(deletedFlag(91802L)).isFalse();
        // Soft delete keeps the row recoverable, exactly like the manual flow.
        assertThat(jdbc.sql("SELECT status FROM photo WHERE id=91803")
                .query(String.class).single()).isEqualTo("ARCHIVED");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM audit_log
                WHERE action='PHOTO_AUTO_CLEANUP' AND resource_type='PHOTO'
                  AND resource_id='91803' AND operator_id IS NULL
                """).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM admin_alert
                WHERE type='PHOTO_MISSING_OBJECT_CLEANED' AND resolved=0
                """).query(Long.class).single()).isOne();
        // Objects that only this record referenced are now orphans.
        assertThat(storage.find(leftoverPreview)).isEmpty();
        assertThat(storage.find(leftoverOriginal)).isEmpty();
        assertThat(storage.find(presentKey)).isPresent();

        storage.delete(presentKey);
    }

    @Test
    void keepsAdoptedPhotosAndOnlyReportsThem() {
        String presentKey = "photos/cleanup/adopted-survivor.jpg";
        putObject(presentKey, "present");
        insertPhoto(91804L, presentKey, "AVAILABLE", null, null);
        insertPhoto(91805L, "photos/cleanup/adopted-gone.jpg", "AVAILABLE", null, null);
        insertAdoption(91805L);

        MissingObjectPhotoCleanupJob.CleanupResult result = job(storage, 0.2, 20).run();

        assertThat(result.missing()).isOne();
        assertThat(result.deleted()).isZero();
        assertThat(result.skippedAdopted()).isOne();
        assertThat(deletedFlag(91805L)).isFalse();
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM admin_alert
                WHERE type='PHOTO_ADOPTED_OBJECT_MISSING' AND resolved=0
                """).query(Long.class).single()).isOne();

        storage.delete(presentKey);
    }

    @Test
    void keepsRecordsWhenTheHeadRequestFails() {
        String presentKey = "photos/cleanup/head-survivor.jpg";
        String failingKey = "photos/cleanup/head-failure.jpg";
        putObject(presentKey, "present");
        insertPhoto(91806L, presentKey, "AVAILABLE", null, null);
        insertPhoto(91807L, failingKey, "AVAILABLE", null, null);
        ObjectStorageService failingStorage = spy(storage);
        doThrow(new IllegalStateException("temporary HEAD failure"))
                .when(failingStorage).find(failingKey);

        MissingObjectPhotoCleanupJob.CleanupResult result = job(failingStorage, 0.2, 20).run();

        // An unknown HEAD result can never justify deleting a record.
        assertThat(result.headFailures()).isOne();
        assertThat(result.missing()).isZero();
        assertThat(result.deleted()).isZero();
        assertThat(deletedFlag(91807L)).isFalse();
        verify(failingStorage, never()).delete(failingKey);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM admin_alert
                WHERE type='PHOTO_CLEANUP_HEAD_FAILED' AND resolved=0
                """).query(Long.class).single()).isOne();

        storage.delete(presentKey);
    }

    @Test
    void abortsWithoutDeletingAnythingWhenTooManyObjectsLookMissing() {
        for (int index = 0; index < 7; index++) {
            String key = "photos/cleanup/bulk-present-" + index + ".jpg";
            putObject(key, "present");
            insertPhoto(91810L + index, key, "AVAILABLE", null, null);
        }
        for (int index = 0; index < 3; index++) {
            insertPhoto(91820L + index, "photos/cleanup/bulk-gone-" + index + ".jpg",
                    "AVAILABLE", null, null);
        }

        MissingObjectPhotoCleanupJob.CleanupResult result = job(storage, 0.2, 2).run();

        // 3/10 missing means a misconfigured bucket or mount, not lost photos.
        assertThat(result.aborted()).isTrue();
        assertThat(result.missing()).isEqualTo(3);
        assertThat(result.deleted()).isZero();
        for (int index = 0; index < 3; index++) {
            assertThat(deletedFlag(91820L + index)).isFalse();
        }
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM admin_alert
                WHERE type='PHOTO_MISSING_OBJECT_CLEANUP_ABORTED' AND resolved=0
                """).query(Long.class).single()).isOne();

        for (int index = 0; index < 7; index++) {
            storage.delete("photos/cleanup/bulk-present-" + index + ".jpg");
        }
    }

    @Test
    void abortsWhenEveryObjectLooksMissingEvenBelowTheAbsoluteFloor() {
        insertPhoto(91830L, "photos/cleanup/all-gone-a.jpg", "AVAILABLE", null, null);
        insertPhoto(91831L, "photos/cleanup/all-gone-b.jpg", "AVAILABLE", null, null);

        MissingObjectPhotoCleanupJob.CleanupResult result = job(storage, 0.2, 20).run();

        assertThat(result.aborted()).isTrue();
        assertThat(result.deleted()).isZero();
        assertThat(deletedFlag(91830L)).isFalse();
        assertThat(deletedFlag(91831L)).isFalse();
    }

    @Test
    void ignoresPhotosThatHaveNotFinishedProcessing() {
        insertPhoto(91840L, "photos/cleanup/uploading.jpg", "UPLOADING", null, null);
        insertPhoto(91841L, "photos/cleanup/processing.jpg", "PROCESSING", null, null);

        MissingObjectPhotoCleanupJob.CleanupResult result = job(storage, 0.2, 20).run();

        // These legitimately have no finished object yet.
        assertThat(result.checked()).isZero();
        assertThat(result.deleted()).isZero();
        assertThat(deletedFlag(91840L)).isFalse();
        assertThat(deletedFlag(91841L)).isFalse();
    }

    private MissingObjectPhotoCleanupJob job(ObjectStorageService objectStorage,
                                             double maxRatio, int minAbsolute) {
        return new MissingObjectPhotoCleanupJob(objectStorage, jdbc, maintenanceLock,
                transactions, Runnable::run, true, maxRatio, minAbsolute);
    }

    private void putObject(String key, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
    }

    private boolean deletedFlag(long photoId) {
        return jdbc.sql("SELECT deleted FROM photo WHERE id=:id")
                .param("id", photoId).query(Boolean.class).single();
    }

    private void insertPhoto(long id, String objectKey, String status,
                             String thumbnailKey, String originalKey) {
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by,
                     taken_at, size, content_type, object_key, thumbnail_object_key,
                     original_object_key, sha256, status, version, deleted)
                VALUES
                    (:id, 'cleanup test', 'test', 'test', :uploaderId, CURRENT_TIMESTAMP,
                     1000, 'image/jpeg', :objectKey, :thumbnailKey, :originalKey, :sha256,
                     :status, 1, false)
                """)
                .param("id", id)
                .param("uploaderId", UPLOADER_ID)
                .param("objectKey", objectKey)
                .param("thumbnailKey", thumbnailKey, java.sql.Types.VARCHAR)
                .param("originalKey", originalKey, java.sql.Types.VARCHAR)
                .param("sha256", "b".repeat(64))
                .param("status", status)
                .update();
    }

    private void insertAdoption(long photoId) {
        long projectId = 91890L;
        jdbc.sql("""
                INSERT INTO project (id, title, status, created_by, version, deleted)
                VALUES (:projectId, 'cleanup test project', 'ACTIVE', :uploaderId, 1, false)
                """)
                .param("projectId", projectId)
                .param("uploaderId", UPLOADER_ID)
                .update();
        jdbc.sql("""
                INSERT INTO adoption
                    (project_id, photo_id, photographer_student_id, photographer_name,
                     adopted_by, adopted_at, deleted)
                VALUES
                    (:projectId, :photoId, 'test', 'test', :uploaderId, CURRENT_TIMESTAMP, false)
                """)
                .param("projectId", projectId)
                .param("photoId", photoId)
                .param("uploaderId", UPLOADER_ID)
                .update();
    }
}
