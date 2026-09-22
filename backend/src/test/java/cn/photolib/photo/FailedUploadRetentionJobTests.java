package cn.photolib.photo;

import cn.photolib.common.error.BusinessException;
import cn.photolib.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 处理失败、原图却还在桶里的上传记录：以前没有任何任务收走，图库里永远挂着一张
 * "上传中"。{@link FailedUploadRetentionJob} 给它们一个保留期，查重也不再把它们算作
 * "已经上传过"。
 */
@SpringBootTest
@Transactional
class FailedUploadRetentionJobTests {
    private static final long UPLOADER_ID = 88_501L;
    private static final long RETENTION_DAYS = 7;

    @Autowired private PhotoService photoService;
    @Autowired private ObjectStorageService storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private Clock clock;

    @BeforeEach
    void isolateCandidateSet() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled,
                     must_change_password, version, deleted)
                VALUES (:id, 'failed-upload-retention-user', 'hash', '保留期测试上传者', 'MINISTER',
                        null, TRUE, FALSE, 1, FALSE)
                """).param("id", UPLOADER_ID).update();
        // 候选集是全库的，先把既有行藏起来；随测试事务一起回滚。
        jdbc.sql("UPDATE photo SET deleted = 1 WHERE deleted = 0").update();
    }

    @Test
    void aFailedUploadPastTheRetentionLosesItsRowAndItsOriginal() {
        String original = "temporary/photos/retention-expired.jpg";
        putObject(original);
        insertPhoto(88_511L, "UPLOADING", original, "超大渐进式 JPEG", daysAgo(RETENTION_DAYS + 1), sha(1));

        int cleaned = job().cleanup();

        assertThat(cleaned).isOne();
        assertThat(deletedFlag(88_511L)).isTrue();
        assertThat(storage.find(original)).isEmpty();
        assertThat(jdbc.sql("SELECT failure_reason FROM photo WHERE id = 88511")
                .query(String.class).single()).isEqualTo(FailedUploadRetentionJob.CLEANED_REASON);
        // 原来的失败原因留在审计里，误删时能查到、也能按 deleted=0 恢复这一行。
        assertThat(jdbc.sql("""
                SELECT detail_json FROM audit_log
                WHERE action = 'PHOTO_AUTO_CLEANUP' AND resource_type = 'PHOTO'
                  AND resource_id = '88511' AND operator_id IS NULL
                """).query(String.class).single())
                .contains("FAILED_UPLOAD_EXPIRED").contains("超大渐进式 JPEG");
    }

    @Test
    void aRecentFailureIsKeptSoTheUploaderCanStillRetry() {
        String original = "temporary/photos/retention-recent.jpg";
        putObject(original);
        insertPhoto(88_512L, "UPLOADING", original, "处理失败", daysAgo(RETENTION_DAYS - 1), sha(2));

        assertThat(job().cleanup()).isZero();
        assertThat(deletedFlag(88_512L)).isFalse();
        assertThat(storage.find(original)).isPresent();
        storage.delete(original);
    }

    @Test
    void rowsThatNeverFailedAreNotThisJobsBusiness() {
        // 没 complete 过的（failure_reason 为空）归 AbandonedUploadCleanupJob；
        // PROCESSING / AVAILABLE 更不能碰。
        insertPhoto(88_513L, "UPLOADING", "temporary/photos/retention-abandoned.jpg", null,
                daysAgo(30), sha(3));
        insertPhoto(88_514L, "PROCESSING", "temporary/photos/retention-processing.jpg", null,
                daysAgo(30), sha(4));
        insertPhoto(88_515L, "AVAILABLE", "temporary/photos/retention-available.jpg", null,
                daysAgo(30), sha(5));

        assertThat(job().cleanup()).isZero();
        assertThat(deletedFlag(88_513L)).isFalse();
        assertThat(deletedFlag(88_514L)).isFalse();
        assertThat(deletedFlag(88_515L)).isFalse();
    }

    @Test
    void aFailedUploadNoLongerBlocksTheSameFileFromBeingUploadedAgain() {
        // 查重按 sha256 + deleted=0：失败行以前会让上传者一直看到"已经上传过该图片"。
        insertPhoto(88_516L, "UPLOADING", "temporary/photos/retention-dedup.jpg", "处理失败",
                daysAgo(0), sha(6));

        assertThatCode(() -> photoService.requireUniqueSha256(sha(6), null))
                .doesNotThrowAnyException();
    }

    @Test
    void anUploadStillInFlightOrPublishedStillCountsAsADuplicate() {
        insertPhoto(88_517L, "UPLOADING", "temporary/photos/retention-in-flight.jpg", null,
                daysAgo(0), sha(7));
        insertPhoto(88_518L, "AVAILABLE", "temporary/photos/retention-published.jpg", null,
                daysAgo(0), sha(8));

        assertThatThrownBy(() -> photoService.requireUniqueSha256(sha(7), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> photoService.requireUniqueSha256(sha(8), null))
                .isInstanceOf(BusinessException.class);
    }

    private FailedUploadRetentionJob job() {
        return new FailedUploadRetentionJob(storage, jdbc, transactions, clock, RETENTION_DAYS, true);
    }

    private LocalDateTime daysAgo(long days) {
        return LocalDateTime.now(clock).minusDays(days).minusMinutes(1);
    }

    private void insertPhoto(long id, String status, String originalObjectKey, String failureReason,
                             LocalDateTime updatedAt, String sha256) {
        jdbc.sql("""
                INSERT INTO photo
                    (id, photographer_student_id, photographer_name, uploaded_by, taken_at, size,
                     content_type, object_key, original_object_key, sha256, status, failure_reason,
                     title, version, deleted, created_at, updated_at)
                VALUES (:id, '20230001', '张三', :uploader, :takenAt, 1024, 'image/jpeg',
                        :objectKey, :originalKey, :sha, :status, :failureReason,
                        '保留期测试图片', 1, FALSE, :updatedAt, :updatedAt)
                """)
                .param("id", id).param("uploader", UPLOADER_ID)
                .param("takenAt", LocalDateTime.now(clock).minusDays(40))
                .param("objectKey", "photos/retention/" + id + ".jpg")
                .param("originalKey", originalObjectKey)
                .param("sha", sha256).param("status", status)
                .param("failureReason", failureReason)
                .param("updatedAt", updatedAt)
                .update();
    }

    private void putObject(String key) {
        byte[] bytes = "original".getBytes(StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
    }

    private boolean deletedFlag(long photoId) {
        return jdbc.sql("SELECT deleted FROM photo WHERE id = :id")
                .param("id", photoId).query(Boolean.class).single();
    }

    private String sha(int seed) {
        return String.format("%064x", 0xFA1_0000L + seed);
    }
}
