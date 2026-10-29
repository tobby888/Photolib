package cn.photolib.storage;

import cn.photolib.photo.PreviewRepairRequestedEvent;
import cn.photolib.photo.PreviewMaintenanceLock;
import cn.photolib.photo.PreviewProfile;
import cn.photolib.photo.PreviewProfilePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
@RecordApplicationEvents
class PhotoStorageReconciliationServiceTests {
    @Autowired
    private PhotoStorageReconciliationService reconciliation;
    @Autowired
    private ObjectStorageService storage;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ApplicationEvents applicationEvents;
    @Autowired
    private PreviewProfilePolicy previewProfiles;
    @Autowired
    private PreviewMaintenanceLock previewMaintenanceLock;

    @BeforeEach
    void createUploader() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES
                    (91999, 'storage-reconciliation-user', 'hash', 'storage test',
                     'ADMIN', true, false)
                """).update();
        PreviewProfile configured = PreviewProfile.configured(0.6);
        int updated = jdbc.sql("""
                UPDATE preview_setting
                SET compression_ratio=:ratio, generator_fingerprint=:generator
                WHERE id=1
                """)
                .param("ratio", configured.compressionRatio())
                .param("generator", configured.generatorFingerprint())
                .update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO preview_setting (id, compression_ratio, generator_fingerprint)
                    VALUES (1, :ratio, :generator)
                    """)
                    .param("ratio", configured.compressionRatio())
                    .param("generator", configured.generatorFingerprint())
                    .update();
        }
        previewProfiles.completeBootstrap(configured);
    }

    @Test
    void reportsMissingMainObjectWithoutDeletingDatabasePhoto() {
        insertPhoto(91001L, "photos/reconciliation/missing.jpg", 99, "image/jpeg",
                "thumbnails/reconciliation/missing.jpg", null);

        var result = reconciliation.reconcile();

        var row = jdbc.sql("SELECT deleted, status, failure_reason FROM photo WHERE id=91001")
                .query((rs, rowNum) -> new Object[]{
                        rs.getBoolean("deleted"), rs.getString("status"), rs.getString("failure_reason")})
                .single();
        assertThat(row[0]).isEqualTo(false);
        assertThat(row[1]).isEqualTo("AVAILABLE");
        assertThat(row[2]).isNull();
        assertThat(result.missing()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM admin_alert
                WHERE type='PHOTO_STORAGE_OBJECT_MISSING' AND resolved=0
                """).query(Long.class).single()).isEqualTo(1L);
        assertThat(applicationEvents.stream(PreviewRepairRequestedEvent.class)).isEmpty();
    }

    @Test
    void clearsMissingThumbnailWithoutOverwritingMainObjectMetadata() {
        String key = "photos/reconciliation/present.jpg";
        byte[] bytes = "real-file-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
        insertPhoto(91002L, key, 1, "application/octet-stream",
                "thumbnails/reconciliation/absent.jpg", 123L);

        reconciliation.reconcile();

        var row = jdbc.sql("""
                        SELECT size, content_type, thumbnail_object_key, thumbnail_size
                        FROM photo WHERE id=91002
                        """)
                .query((rs, rowNum) -> new Object[]{
                        rs.getLong("size"), rs.getString("content_type"),
                        rs.getString("thumbnail_object_key"),
                        rs.getObject("thumbnail_size", Long.class)})
                .single();
        assertThat(row[0]).isEqualTo(1L);
        assertThat(row[1]).isEqualTo("application/octet-stream");
        assertThat(row[2]).isNull();
        assertThat(row[3]).isNull();
        assertThat(applicationEvents.stream(PreviewRepairRequestedEvent.class)
                .flatMap(event -> event.photoIds().stream())).contains(91002L);
        storage.delete(key);
    }

    @Test
    void clearsMismatchedThumbnailSizeInsteadOfAcceptingCorruptedObjectSize() {
        String key = "photos/reconciliation/size-mismatch.jpg";
        String thumbnailKey = "thumbnails/reconciliation/size-mismatch.jpg";
        byte[] image = "main-image".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] thumbnail = "bad".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(image), image.length, "image/jpeg");
        storage.put(thumbnailKey, new ByteArrayInputStream(thumbnail), thumbnail.length, "image/jpeg");
        insertPhoto(91003L, key, image.length, "image/jpeg", thumbnailKey, 999L);

        var result = reconciliation.reconcile();

        var row = jdbc.sql("""
                        SELECT thumbnail_object_key, thumbnail_size, version
                        FROM photo WHERE id=91003
                        """)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"),
                        rs.getObject("thumbnail_size", Long.class),
                        rs.getInt("version")})
                .single();
        assertThat(row[0]).isNull();
        assertThat(row[1]).isNull();
        assertThat(row[2]).isEqualTo(2);
        assertThat(result.updated()).isGreaterThanOrEqualTo(1);
        assertThat(applicationEvents.stream(PreviewRepairRequestedEvent.class)
                .flatMap(event -> event.photoIds().stream())).contains(91003L);

        storage.delete(key);
        storage.delete(thumbnailKey);
    }

    @Test
    void keepsHealthyReferencesAfterExactHeadProfileValidation() {
        String key = "photos/reconciliation/eventual-main.jpg";
        String thumbnailKey = "thumbnails/reconciliation/eventual-preview.jpg";
        insertPhoto(91004L, key, 10, "image/jpeg", thumbnailKey, 4L);
        ObjectStorageService observedStorage = mock(ObjectStorageService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(observedStorage.find(key)).thenReturn(Optional.of(
                new ObjectStorageService.ObjectInfo(10, "image/jpeg")));
        var metadata = previewProfiles.requireRunningProfile().objectMetadata(
                PreviewProfile.PREVIEW_CONTENT_TYPE, "a".repeat(64));
        when(observedStorage.find(thumbnailKey)).thenReturn(Optional.of(
                new ObjectStorageService.ObjectInfo(
                        4, PreviewProfile.PREVIEW_CONTENT_TYPE, metadata)));
        PhotoStorageReconciliationService isolated = new PhotoStorageReconciliationService(
                observedStorage, jdbc, publisher, previewProfiles, previewMaintenanceLock);

        var result = isolated.reconcile();

        var row = jdbc.sql("""
                        SELECT deleted, thumbnail_object_key, thumbnail_size, version
                        FROM photo WHERE id=91004
                        """)
                .query((rs, rowNum) -> new Object[]{
                        rs.getBoolean("deleted"), rs.getString("thumbnail_object_key"),
                        rs.getObject("thumbnail_size", Long.class), rs.getInt("version")})
                .single();
        assertThat(result.checked()).isPositive();
        assertThat(result.updated()).isZero();
        assertThat(row).containsExactly(false, thumbnailKey, 4L, 1);
        verifyNoInteractions(publisher);
    }

    @Test
    void thumbnailCompareAndSetDoesNotClearAConcurrentReplacement() {
        String key = "photos/reconciliation/cas-main.jpg";
        String thumbnailKey = "thumbnails/reconciliation/cas-old.jpg";
        String replacementKey = "thumbnails/reconciliation/cas-new.jpg";
        insertPhoto(91005L, key, 10, "image/jpeg", thumbnailKey, 4L);
        ObjectStorageService observedStorage = mock(ObjectStorageService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(observedStorage.find(key)).thenReturn(Optional.of(
                new ObjectStorageService.ObjectInfo(10, "image/jpeg")));
        when(observedStorage.find(thumbnailKey)).thenAnswer(invocation -> {
            jdbc.sql("""
                    UPDATE photo
                    SET thumbnail_object_key=:key, thumbnail_size=8, version=version+1
                    WHERE id=91005
                    """).param("key", replacementKey).update();
            return Optional.empty();
        });
        PhotoStorageReconciliationService isolated = new PhotoStorageReconciliationService(
                observedStorage, jdbc, publisher, previewProfiles, previewMaintenanceLock);

        var result = isolated.reconcile();

        var row = jdbc.sql("""
                        SELECT thumbnail_object_key, thumbnail_size, version
                        FROM photo WHERE id=91005
                        """)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"),
                        rs.getObject("thumbnail_size", Long.class), rs.getInt("version")})
                .single();
        assertThat(result.updated()).isZero();
        assertThat(row).containsExactly(replacementKey, 8L, 2);
        verifyNoInteractions(publisher);
    }

    @Test
    void headTransportFailureDoesNotClearThePreviewReference() {
        long photoId = 91006L;
        String key = "photos/reconciliation/head-error-main.jpg";
        String thumbnailKey = "thumbnails/reconciliation/head-error-preview.jpg";
        insertPhoto(photoId, key, 10, "image/jpeg", thumbnailKey, 4L);
        ObjectStorageService observedStorage = mock(ObjectStorageService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(observedStorage.find(key)).thenReturn(Optional.of(
                new ObjectStorageService.ObjectInfo(10, "image/jpeg")));
        when(observedStorage.find(thumbnailKey))
                .thenThrow(new IllegalStateException("temporary HEAD failure"));
        PhotoStorageReconciliationService isolated = new PhotoStorageReconciliationService(
                observedStorage, jdbc, publisher, previewProfiles, previewMaintenanceLock);

        var result = isolated.reconcile();

        var row = jdbc.sql("""
                SELECT thumbnail_object_key, thumbnail_size, version
                FROM photo WHERE id=:id
                """).param("id", photoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"),
                        rs.getLong("thumbnail_size"), rs.getInt("version")})
                .single();
        assertThat(result.updated()).isZero();
        assertThat(row).containsExactly(thumbnailKey, 4L, 1);
        verifyNoInteractions(publisher);
    }

    @Test
    void mainHeadTransportFailureDoesNotResolveAnExistingMissingObjectAlert() {
        long photoId = 91009L;
        String key = "photos/reconciliation/main-head-error.jpg";
        insertPhoto(photoId, key, 10, "image/jpeg", null, null);
        jdbc.sql("""
                INSERT INTO admin_alert
                    (type, message, resource_type, resolved, created_at)
                VALUES
                    ('PHOTO_STORAGE_OBJECT_MISSING', 'existing alert', 'STORAGE', false,
                     CURRENT_TIMESTAMP)
                """).update();
        long alertId = jdbc.sql("""
                SELECT id FROM admin_alert
                WHERE type='PHOTO_STORAGE_OBJECT_MISSING' AND message='existing alert'
                ORDER BY id DESC LIMIT 1
                """).query(Long.class).single();
        ObjectStorageService observedStorage = mock(ObjectStorageService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(observedStorage.find(key))
                .thenThrow(new IllegalStateException("temporary main HEAD failure"));
        PhotoStorageReconciliationService isolated = new PhotoStorageReconciliationService(
                observedStorage, jdbc, publisher, previewProfiles, previewMaintenanceLock);

        isolated.reconcile();

        assertThat(jdbc.sql("""
                SELECT resolved FROM admin_alert WHERE id=:id
                """).param("id", alertId).query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM admin_alert
                WHERE type='PHOTO_STORAGE_HEAD_FAILED' AND resolved=0
                """).query(Long.class).single()).isOne();
        verifyNoInteractions(publisher);
    }

    @Test
    void clearsPreviewThatExceedsTheSafetyLimitEvenWhenDatabaseSizeMatches() {
        long photoId = 91010L;
        long oversized = 20L * 1024 * 1024 + 1;
        String key = "photos/reconciliation/oversized-main.jpg";
        String thumbnailKey = "thumbnails/reconciliation/oversized-preview.jpg";
        insertPhoto(photoId, key, 10, "image/jpeg", thumbnailKey, oversized);
        ObjectStorageService observedStorage = mock(ObjectStorageService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(observedStorage.find(key)).thenReturn(Optional.of(
                new ObjectStorageService.ObjectInfo(10, "image/jpeg")));
        when(observedStorage.find(thumbnailKey)).thenReturn(Optional.of(
                new ObjectStorageService.ObjectInfo(oversized, PreviewProfile.PREVIEW_CONTENT_TYPE,
                        previewProfiles.requireRunningProfile().objectMetadata(
                                PreviewProfile.PREVIEW_CONTENT_TYPE, "a".repeat(64)))));
        PhotoStorageReconciliationService isolated = new PhotoStorageReconciliationService(
                observedStorage, jdbc, publisher, previewProfiles, previewMaintenanceLock);

        var result = isolated.reconcile();

        var row = jdbc.sql("""
                SELECT thumbnail_object_key, thumbnail_size, version
                FROM photo WHERE id=:id
                """).param("id", photoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"),
                        rs.getObject("thumbnail_size", Long.class), rs.getInt("version")})
                .single();
        assertThat(result.updated()).isOne();
        assertThat(row).containsExactly(null, null, 2);
        org.mockito.Mockito.verify(publisher).publishEvent(
                org.mockito.ArgumentMatchers.<Object>argThat(event ->
                        event instanceof PreviewRepairRequestedEvent repair
                                && repair.photoIds().equals(List.of(photoId))));
    }

    @Test
    void clearsSameSizePreviewWhenItsOssRatioMetadataDoesNotMatchDatabase() {
        long photoId = 91007L;
        String key = "photos/reconciliation/profile-main.jpg";
        String thumbnailKey = "thumbnails/reconciliation/profile-preview.jpg";
        insertPhoto(photoId, key, 10, "image/jpeg", thumbnailKey, 4L);
        ObjectStorageService observedStorage = mock(ObjectStorageService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(observedStorage.find(key)).thenReturn(Optional.of(
                new ObjectStorageService.ObjectInfo(10, "image/jpeg")));
        when(observedStorage.find(thumbnailKey)).thenReturn(Optional.of(
                new ObjectStorageService.ObjectInfo(4, PreviewProfile.PREVIEW_CONTENT_TYPE,
                        PreviewProfile.configured(0.7).objectMetadata(
                                PreviewProfile.PREVIEW_CONTENT_TYPE, "a".repeat(64)))));
        PhotoStorageReconciliationService isolated = new PhotoStorageReconciliationService(
                observedStorage, jdbc, publisher, previewProfiles, previewMaintenanceLock);

        var result = isolated.reconcile();

        var row = jdbc.sql("""
                SELECT thumbnail_object_key, thumbnail_size, version
                FROM photo WHERE id=:id
                """).param("id", photoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"),
                        rs.getObject("thumbnail_size", Long.class), rs.getInt("version")})
                .single();
        assertThat(result.updated()).isOne();
        assertThat(row).containsExactly(null, null, 2);
        org.mockito.Mockito.verify(publisher).publishEvent(
                org.mockito.ArgumentMatchers.<Object>argThat(event ->
                        event instanceof PreviewRepairRequestedEvent repair
                                && repair.photoIds().equals(List.of(photoId))
                                && repair.expectedProfile().equals(PreviewProfile.configured(0.6))));
    }

    @Test
    void profileCasDoesNotClearWhenDatabaseProfileChangesAfterHead() {
        long photoId = 91008L;
        String key = "photos/reconciliation/profile-cas-main.jpg";
        String thumbnailKey = "thumbnails/reconciliation/profile-cas-preview.jpg";
        insertPhoto(photoId, key, 10, "image/jpeg", thumbnailKey, 4L);
        ObjectStorageService observedStorage = mock(ObjectStorageService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(observedStorage.find(key)).thenReturn(Optional.of(
                new ObjectStorageService.ObjectInfo(10, "image/jpeg")));
        when(observedStorage.find(thumbnailKey)).thenAnswer(invocation -> {
            jdbc.sql("UPDATE preview_setting SET compression_ratio=0.7000 WHERE id=1").update();
            return Optional.empty();
        });
        PhotoStorageReconciliationService isolated = new PhotoStorageReconciliationService(
                observedStorage, jdbc, publisher, previewProfiles, previewMaintenanceLock);

        var result = isolated.reconcile();

        var row = jdbc.sql("""
                SELECT thumbnail_object_key, thumbnail_size, version
                FROM photo WHERE id=:id
                """).param("id", photoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"),
                        rs.getLong("thumbnail_size"), rs.getInt("version")})
                .single();
        assertThat(result.updated()).isZero();
        assertThat(row).containsExactly(thumbnailKey, 4L, 1);
        verifyNoInteractions(publisher);
    }

    @Test
    void concurrentHeadsReachTheSameVerdictsAsTheSerialPass() {
        String healthyMain = "photos/reconciliation/concurrent-healthy.jpg";
        String healthyPreview = "thumbnails/reconciliation/concurrent-healthy.webp";
        String missingMain = "photos/reconciliation/concurrent-missing.jpg";
        String staleMain = "photos/reconciliation/concurrent-stale.jpg";
        String stalePreview = "thumbnails/reconciliation/concurrent-stale.webp";
        insertPhoto(91011L, healthyMain, 10, "image/jpeg", healthyPreview, 4L);
        insertPhoto(91012L, missingMain, 10, "image/jpeg", null, null);
        insertPhoto(91013L, staleMain, 10, "image/jpeg", stalePreview, 4L);
        var metadata = previewProfiles.requireRunningProfile().objectMetadata(
                PreviewProfile.PREVIEW_CONTENT_TYPE, "a".repeat(64));
        java.util.Set<String> headThreads = java.util.concurrent.ConcurrentHashMap.newKeySet();
        ObjectStorageService observedStorage = mock(ObjectStorageService.class);
        when(observedStorage.find(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            headThreads.add(Thread.currentThread().getName());
            String key = invocation.getArgument(0);
            if (key.equals(missingMain)) return Optional.empty();
            if (key.equals(stalePreview)) return Optional.empty();
            if (key.startsWith("thumbnails/")) {
                return Optional.of(new ObjectStorageService.ObjectInfo(
                        4, PreviewProfile.PREVIEW_CONTENT_TYPE, metadata));
            }
            return Optional.of(new ObjectStorageService.ObjectInfo(10, "image/jpeg"));
        });
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        PhotoStorageReconciliationService concurrent = new PhotoStorageReconciliationService(
                observedStorage, jdbc, publisher, previewProfiles, previewMaintenanceLock, 4);

        var result = concurrent.reconcile();

        assertThat(headThreads).isNotEmpty().allMatch(name -> name.startsWith("storage-reconcile-"));
        assertThat(result.missing()).isGreaterThanOrEqualTo(1);
        assertThat(thumbnail(91011L)).isEqualTo(healthyPreview);
        assertThat(thumbnail(91013L)).isNull();
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM admin_alert
                WHERE type='PHOTO_STORAGE_OBJECT_MISSING' AND resolved=0 AND message LIKE '%91012%'
                """).query(Long.class).single()).isOne();
        org.mockito.Mockito.verify(publisher).publishEvent(
                org.mockito.ArgumentMatchers.<Object>argThat(event ->
                        event instanceof PreviewRepairRequestedEvent repair
                                && repair.photoIds().contains(91013L)
                                && !repair.photoIds().contains(91011L)));
    }

    @Test
    void incrementalPassOnlyChecksPhotosChangedSinceTheWatermark() {
        String oldKey = "photos/reconciliation/incremental-old.jpg";
        String newKey = "photos/reconciliation/incremental-new.jpg";
        insertPhoto(91014L, oldKey, 10, "image/jpeg", null, null);
        insertPhoto(91015L, newKey, 10, "image/jpeg", null, null);
        jdbc.sql("UPDATE photo SET updated_at=TIMESTAMP '2020-01-01 00:00:00' WHERE id=91014").update();
        jdbc.sql("UPDATE photo SET updated_at=CURRENT_TIMESTAMP WHERE id=91015").update();
        ObjectStorageService observedStorage = mock(ObjectStorageService.class);
        when(observedStorage.find(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(new ObjectStorageService.ObjectInfo(10, "image/jpeg")));
        PhotoStorageReconciliationService isolated = new PhotoStorageReconciliationService(
                observedStorage, jdbc, mock(ApplicationEventPublisher.class), previewProfiles,
                previewMaintenanceLock);

        isolated.reconcileChangedSince(isolated.databaseNow().minusHours(1));

        org.mockito.Mockito.verify(observedStorage, org.mockito.Mockito.never()).find(oldKey);
        org.mockito.Mockito.verify(observedStorage).find(newKey);
    }

    @Test
    void incrementalPassNeverResolvesAlertsItCannotFullyConfirm() {
        jdbc.sql("""
                INSERT INTO admin_alert (type, message, resource_type, resolved, created_at)
                VALUES ('PHOTO_STORAGE_OBJECT_MISSING', 'full-pass missing', 'STORAGE', false, CURRENT_TIMESTAMP),
                       ('PHOTO_STORAGE_HEAD_FAILED', 'full-pass head failure', 'STORAGE', false, CURRENT_TIMESTAMP)
                """).update();
        String key = "photos/reconciliation/incremental-healthy.jpg";
        insertPhoto(91016L, key, 10, "image/jpeg", null, null);
        ObjectStorageService observedStorage = mock(ObjectStorageService.class);
        when(observedStorage.find(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(new ObjectStorageService.ObjectInfo(10, "image/jpeg")));
        PhotoStorageReconciliationService isolated = new PhotoStorageReconciliationService(
                observedStorage, jdbc, mock(ApplicationEventPublisher.class), previewProfiles,
                previewMaintenanceLock);

        isolated.reconcileChangedSince(isolated.databaseNow().minusHours(1));

        // 没查到的照片状态未知：全量巡检写下的告警既不能被标为已解决，内容也不能被改写。
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM admin_alert
                WHERE resolved=0 AND message IN ('full-pass missing', 'full-pass head failure')
                """).query(Long.class).single()).isEqualTo(2L);
    }

    /**
     * 「有没有新图」只认全量会去查的那批（未删除、AVAILABLE/ARCHIVED）。用远在未来的时间点，
     * 免得被共用 H2 库里别的测试留下的照片干扰。
     */
    @Test
    void newPhotosAreOnlyThoseAFullPassWouldCheck() {
        LocalDateTime future = LocalDateTime.of(2099, 1, 1, 12, 0);
        insertPhoto(91201, "photos/new.jpg", 10, "image/jpeg", null, null);
        jdbc.sql("UPDATE photo SET created_at=:at WHERE id=91201").param("at", future).update();

        assertThat(reconciliation.hasNewPhotosSince(future.minusHours(1))).isTrue();
        assertThat(reconciliation.hasNewPhotosSince(future.plusSeconds(1))).isFalse();

        jdbc.sql("UPDATE photo SET status='PROCESSING' WHERE id=91201").update();
        assertThat(reconciliation.hasNewPhotosSince(future.minusHours(1))).isFalse();
        jdbc.sql("UPDATE photo SET status='ARCHIVED' WHERE id=91201").update();
        assertThat(reconciliation.hasNewPhotosSince(future.minusHours(1))).isTrue();
        jdbc.sql("UPDATE photo SET deleted=true WHERE id=91201").update();
        assertThat(reconciliation.hasNewPhotosSince(future.minusHours(1))).isFalse();
    }

    @Test
    void onlyUnresolvedMissingOrHeadFailureAlertsBlockSkipping() {
        // 先在本测试事务里把库里已有的同类告警都标掉，结束时回滚。
        jdbc.sql("""
                UPDATE admin_alert SET resolved=1
                WHERE type IN ('PHOTO_STORAGE_OBJECT_MISSING', 'PHOTO_STORAGE_HEAD_FAILED')
                """).update();
        assertThat(reconciliation.hasOpenStorageAlerts()).isFalse();

        insertAlert("PREVIEW_PROFILE_INVALID", false);
        insertAlert("PHOTO_STORAGE_OBJECT_MISSING", true);
        assertThat(reconciliation.hasOpenStorageAlerts()).isFalse();

        insertAlert("PHOTO_STORAGE_HEAD_FAILED", false);
        assertThat(reconciliation.hasOpenStorageAlerts()).isTrue();
    }

    private void insertAlert(String type, boolean resolved) {
        jdbc.sql("""
                INSERT INTO admin_alert (type, message, resource_type, resolved, created_at)
                VALUES (:type, 'test', 'PHOTO', :resolved, CURRENT_TIMESTAMP)
                """).param("type", type).param("resolved", resolved).update();
    }

    private String thumbnail(long photoId) {
        return jdbc.sql("SELECT thumbnail_object_key FROM photo WHERE id=:id").param("id", photoId)
                .query(String.class).list().getFirst();
    }

    private void insertPhoto(long id, String key, long size, String contentType,
                             String thumbnailKey, Long thumbnailSize) {
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by,
                     taken_at, size, content_type, object_key, thumbnail_object_key,
                     thumbnail_size, sha256, status, version, deleted)
                VALUES
                    (:id, 'reconciliation test', 'test', 'test', 91999, CURRENT_TIMESTAMP,
                     :size, :contentType, :objectKey, :thumbnailKey, :thumbnailSize, :sha256,
                     'AVAILABLE', 1, false)
                """)
                .param("id", id)
                .param("size", size)
                .param("contentType", contentType)
                .param("objectKey", key)
                .param("thumbnailKey", thumbnailKey)
                .param("thumbnailSize", thumbnailSize, java.sql.Types.BIGINT)
                .param("sha256", "a".repeat(64))
                .update();
    }
}
