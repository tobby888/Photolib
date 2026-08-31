package cn.photolib.photo;

import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
class PreviewRegenerationServiceTests {
    private static final long TEST_ID_MIN = 93790L;
    private static final long TEST_ID_MAX = 93899L;

    @Autowired
    private PreviewRegenerationService previews;
    @Autowired
    private ObjectStorageService storage;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private StorageProperties properties;
    @Autowired
    private ImageCompressor compressor;
    @Autowired
    private PhotoProcessingWorkspace workspace;
    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void cleanBefore() {
        cleanupTestData();
    }

    @AfterEach
    void cleanAfter() {
        cleanupTestData();
    }

    @Test
    void rejectsAmbientTransactionsBeforeTouchingObjectStorage() {
        ObjectStorageService untouchedStorage = mock(ObjectStorageService.class);
        PreviewRegenerationService isolated = new PreviewRegenerationService(
                jdbc, untouchedStorage, properties, compressor, workspace, transactions);

        assertThatThrownBy(() -> transactions.execute(
                status -> isolated.synchronizeCompressionRatio()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能在外层数据库事务中执行");
        verifyNoInteractions(untouchedStorage);
    }

    @Test
    void unchangedDatabaseProfileStillHeadsEveryPreviewObject() {
        long userId = 93791L;
        long photoId = 93792L;
        insertUserAndPhoto(userId, photoId, "photos/preview-regeneration/fast-path.jpg", 456,
                "thumbnails/preview-regeneration/fast-path.jpg", 123L, "preview-fast-path-user");
        setStoredRatio("0.6000");
        ObjectStorageService observedStorage = mock(ObjectStorageService.class);
        when(observedStorage.find("thumbnails/preview-regeneration/fast-path.jpg"))
                .thenReturn(java.util.Optional.of(new ObjectStorageService.ObjectInfo(
                        123L, PreviewProfile.PREVIEW_CONTENT_TYPE, previewMetadata("a".repeat(64)))));
        PreviewRegenerationService isolated = new PreviewRegenerationService(
                jdbc, observedStorage, properties, compressor, workspace, transactions);

        PreviewRegenerationService.Result result = isolated.synchronizeCompressionRatio();

        assertThat(result.regenerated()).isFalse();
        verify(observedStorage).find("thumbnails/preview-regeneration/fast-path.jpg");
    }

    @Test
    void headTransportFailureKeepsTheExistingPreviewReference() {
        long userId = 93871L;
        long photoId = 93872L;
        String previewKey = "thumbnails/preview-regeneration/head-failure.jpg";
        insertUserAndPhoto(userId, photoId,
                "photos/preview-regeneration/head-failure-source.jpg", 456,
                previewKey, 123L, "preview-head-failure-user");
        setStoredRatio("0.6000");
        ObjectStorageService failingStorage = mock(ObjectStorageService.class);
        when(failingStorage.find(previewKey))
                .thenThrow(new IllegalStateException("temporary HEAD failure"));
        PreviewRegenerationService isolated = new PreviewRegenerationService(
                jdbc, failingStorage, properties, compressor, workspace, transactions);

        assertThatThrownBy(isolated::synchronizeCompressionRatio)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("保留数据库引用");

        var row = jdbc.sql("""
                SELECT thumbnail_object_key, thumbnail_size, version
                FROM photo WHERE id=:id
                """).param("id", photoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"),
                        rs.getLong("thumbnail_size"), rs.getInt("version")})
                .single();
        assertThat(row).containsExactly(previewKey, 123L, 1);
        verify(failingStorage, never()).delete(previewKey);
    }

    @Test
    void sameDatabaseProfileReencodesAnOssObjectWithDifferentRatioMetadata() throws Exception {
        long userId = 93873L;
        long photoId = 93874L;
        String sourceKey = "photos/preview-regeneration/oss-profile-source.jpg";
        String oldPreviewKey = "thumbnails/preview-regeneration/oss-profile-old.jpg";
        byte[] image = jpegImage();
        byte[] oldPreview = new byte[321];
        storage.put(sourceKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
        storage.put(oldPreviewKey, new ByteArrayInputStream(oldPreview), oldPreview.length,
                PreviewProfile.PREVIEW_CONTENT_TYPE, PreviewProfile.configured(0.7)
                        .objectMetadata(PreviewProfile.PREVIEW_CONTENT_TYPE, sha256(oldPreview)));
        insertUserAndPhoto(userId, photoId, sourceKey, image.length,
                oldPreviewKey, (long) oldPreview.length, "preview-oss-profile-user");
        setStoredRatio("0.6000");

        PreviewRegenerationService.Result result = previews.synchronizeCompressionRatio();

        String generatedKey = jdbc.sql("SELECT thumbnail_object_key FROM photo WHERE id=:id")
                .param("id", photoId).query(String.class).single();
        assertThat(result.regeneratedCount()).isOne();
        assertThat(generatedKey).isNotEqualTo(oldPreviewKey);
        assertThat(storage.stat(generatedKey).userMetadata())
                .containsEntry(PreviewProfile.METADATA_RATIO, "0.6000")
                .containsEntry(PreviewProfile.METADATA_EFFECTIVE_QUALITY, "60")
                .containsEntry(PreviewProfile.METADATA_GENERATOR,
                        PreviewProfile.WEBP_OBJECT_GENERATOR);

        storage.delete(sourceKey);
        storage.delete(generatedKey);
    }

    @Test
    void sameProfileBootstrapRejectsTargetedPutWithTamperedMetadata() throws Exception {
        long userId = 93885L;
        long photoId = 93886L;
        String sourceKey = "photos/preview-regeneration/targeted-metadata-source.jpg";
        String oldPreviewKey = "thumbnails/preview-regeneration/targeted-metadata-old.jpg";
        byte[] image = jpegImage();
        byte[] oldPreview = new byte[123];
        storage.put(sourceKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
        storage.put(oldPreviewKey, new ByteArrayInputStream(oldPreview), oldPreview.length,
                PreviewProfile.PREVIEW_CONTENT_TYPE, PreviewProfile.configured(0.7)
                        .objectMetadata(PreviewProfile.PREVIEW_CONTENT_TYPE, sha256(oldPreview)));
        insertUserAndPhoto(userId, photoId, sourceKey, image.length,
                oldPreviewKey, (long) oldPreview.length, "preview-targeted-metadata-user");
        setStoredRatio("0.6000");

        ObjectStorageService tamperedStorage = spy(storage);
        java.util.concurrent.atomic.AtomicReference<String> generatedKey =
                new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            InputStream input = invocation.getArgument(1);
            long size = invocation.getArgument(2);
            String contentType = invocation.getArgument(3);
            Map<String, String> metadata = invocation.getArgument(4);
            Map<String, String> corrupted = new HashMap<>(metadata);
            corrupted.put(PreviewProfile.METADATA_RATIO, "0.7000");
            storage.put(key, input, size, contentType, corrupted);
            generatedKey.set(key);
            return null;
        }).when(tamperedStorage).put(anyString(), any(InputStream.class), anyLong(),
                anyString(), anyMap());
        PreviewProfileRepository repository = new PreviewProfileRepository(jdbc);
        PreviewProfilePolicy policy = new PreviewProfilePolicy(properties, repository);
        PreviewRegenerationService isolated = new PreviewRegenerationService(
                jdbc, tamperedStorage, properties, compressor, workspace, transactions,
                repository, policy, new PreviewMaintenanceLock());

        PreviewRegenerationService.Result result = isolated.synchronizeCompressionRatio();

        // The tampered object must never become the active reference, but one bad
        // object may no longer pin the profile policy in BOOTSTRAPPING either.
        assertThat(result.regeneratedCount()).isZero();
        assertThat(result.fallbackCount()).isZero();
        assertThat(policy.phase()).isEqualTo(PreviewProfilePolicy.Phase.RUNNING);
        assertThat(jdbc.sql("SELECT thumbnail_object_key FROM photo WHERE id=:id")
                .param("id", photoId).query(String.class).single()).isEqualTo(oldPreviewKey);
        assertThat(storage.find(generatedKey.get())).isEmpty();
    }

    @Test
    void sameProfileBootstrapRejectsTargetedPutWhoseBytesDoNotMatchMetadata() throws Exception {
        long userId = 93887L;
        long photoId = 93888L;
        String sourceKey = "photos/preview-regeneration/targeted-content-source.jpg";
        String oldPreviewKey = "thumbnails/preview-regeneration/targeted-content-old.jpg";
        byte[] image = jpegImage();
        byte[] oldPreview = new byte[123];
        storage.put(sourceKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
        storage.put(oldPreviewKey, new ByteArrayInputStream(oldPreview), oldPreview.length,
                PreviewProfile.PREVIEW_CONTENT_TYPE, PreviewProfile.configured(0.7)
                        .objectMetadata(PreviewProfile.PREVIEW_CONTENT_TYPE, sha256(oldPreview)));
        insertUserAndPhoto(userId, photoId, sourceKey, image.length,
                oldPreviewKey, (long) oldPreview.length, "preview-targeted-content-user");
        setStoredRatio("0.6000");

        ObjectStorageService tamperedStorage = spy(storage);
        java.util.concurrent.atomic.AtomicReference<String> generatedKey =
                new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            InputStream input = invocation.getArgument(1);
            long size = invocation.getArgument(2);
            String contentType = invocation.getArgument(3);
            Map<String, String> metadata = invocation.getArgument(4);
            storage.put(key, input, size, contentType, metadata);
            byte[] corrupted = new byte[Math.toIntExact(size)];
            storage.put(key, new ByteArrayInputStream(corrupted), corrupted.length,
                    contentType, metadata);
            generatedKey.set(key);
            return null;
        }).when(tamperedStorage).put(anyString(), any(InputStream.class), anyLong(),
                anyString(), anyMap());
        PreviewProfileRepository repository = new PreviewProfileRepository(jdbc);
        PreviewProfilePolicy policy = new PreviewProfilePolicy(properties, repository);
        PreviewRegenerationService isolated = new PreviewRegenerationService(
                jdbc, tamperedStorage, properties, compressor, workspace, transactions,
                repository, policy, new PreviewMaintenanceLock());

        PreviewRegenerationService.Result result = isolated.synchronizeCompressionRatio();

        assertThat(result.regeneratedCount()).isZero();
        assertThat(result.fallbackCount()).isZero();
        assertThat(policy.phase()).isEqualTo(PreviewProfilePolicy.Phase.RUNNING);
        assertThat(jdbc.sql("SELECT thumbnail_object_key FROM photo WHERE id=:id")
                .param("id", photoId).query(String.class).single()).isEqualTo(oldPreviewKey);
        assertThat(storage.find(generatedKey.get())).isEmpty();
    }

    @Test
    void healthyProfileCleansAStageLeftByARevertedProfile() {
        long userId = 93793L;
        long photoId = 93794L;
        String staleStageKey = "thumbnails/generations/reverted-profile/" + photoId + ".jpg";
        byte[] staleBytes = new byte[]{1, 2, 3, 4};
        insertUserAndPhoto(userId, photoId,
                "photos/preview-regeneration/reverted-profile-source.jpg", 456,
                "thumbnails/preview-regeneration/reverted-profile-active.jpg", 123L,
                "preview-reverted-profile-user");
        setStoredRatio("0.6000");
        byte[] activeBytes = new byte[123];
        storage.put("thumbnails/preview-regeneration/reverted-profile-active.jpg",
                new ByteArrayInputStream(activeBytes), activeBytes.length, "image/jpeg",
                previewMetadata("a".repeat(64)));
        storage.put(staleStageKey, new ByteArrayInputStream(staleBytes), staleBytes.length,
                "image/jpeg");
        jdbc.sql("""
                INSERT INTO preview_regeneration_stage
                    (photo_id, profile_fingerprint, source_object_key,
                     staged_object_key, staged_size, staged_content_type,
                     staged_sha256)
                VALUES
                    (:photoId, :profile, :sourceKey, :stagedKey, :size,
                     'image/webp', :sha256)
                """)
                .param("photoId", photoId)
                .param("profile", PreviewRegenerationService.GENERATOR_FINGERPRINT + "|ratio=0.7000")
                .param("sourceKey", "photos/preview-regeneration/reverted-profile-source.jpg")
                .param("stagedKey", staleStageKey)
                .param("size", staleBytes.length)
                .param("sha256", "0".repeat(64))
                .update();

        PreviewRegenerationService.Result result = previews.synchronizeCompressionRatio();

        assertThat(result.regenerated()).isFalse();
        assertThat(result.deletedCount()).isOne();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM preview_regeneration_stage")
                .query(Integer.class).single()).isZero();
        assertThat(storage.find(staleStageKey)).isEmpty();
    }

    @Test
    void keepsAnAbandonedStageRowWhenObjectDeletionTemporarilyFails() {
        long userId = 93795L;
        long photoId = 93796L;
        String staleStageKey = "thumbnails/generations/delete-retry/" + photoId + ".jpg";
        insertUserAndPhoto(userId, photoId,
                "photos/preview-regeneration/delete-retry-source.jpg", 456,
                "thumbnails/preview-regeneration/delete-retry-active.jpg", 123L,
                "preview-delete-retry-user");
        setStoredRatio("0.6000");
        jdbc.sql("""
                INSERT INTO preview_regeneration_stage
                    (photo_id, profile_fingerprint, source_object_key,
                     staged_object_key, staged_size, staged_content_type,
                     staged_sha256)
                VALUES
                    (:photoId, :profile, :sourceKey, :stagedKey, 4,
                     'image/webp', :sha256)
                """)
                .param("photoId", photoId)
                .param("profile", PreviewRegenerationService.GENERATOR_FINGERPRINT + "|ratio=0.7000")
                .param("sourceKey", "photos/preview-regeneration/delete-retry-source.jpg")
                .param("stagedKey", staleStageKey)
                .param("sha256", "0".repeat(64))
                .update();
        ObjectStorageService failingStorage = mock(ObjectStorageService.class);
        when(failingStorage.find("thumbnails/preview-regeneration/delete-retry-active.jpg"))
                .thenReturn(java.util.Optional.of(new ObjectStorageService.ObjectInfo(
                        123L, PreviewProfile.PREVIEW_CONTENT_TYPE, previewMetadata("a".repeat(64)))));
        doThrow(new IllegalStateException("temporary storage failure"))
                .when(failingStorage).delete(staleStageKey);
        PreviewRegenerationService isolated = new PreviewRegenerationService(
                jdbc, failingStorage, properties, compressor, workspace, transactions);

        PreviewRegenerationService.Result result = isolated.synchronizeCompressionRatio();

        assertThat(result.regenerated()).isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM preview_regeneration_stage WHERE photo_id=:id")
                .param("id", photoId).query(Integer.class).single()).isOne();
        verify(failingStorage).delete(staleStageKey);
    }

    @Test
    void updatesGeneratorFingerprintEvenWhenCompressionRatioDidNotChange() {
        setStoredRatio("0.6000");
        jdbc.sql("UPDATE preview_setting SET generator_fingerprint='legacy-generator' WHERE id=1")
                .update();

        PreviewRegenerationService.Result result = previews.synchronizeCompressionRatio();

        assertThat(result.regenerated()).isTrue();
        assertThat(jdbc.sql("SELECT generator_fingerprint FROM preview_setting WHERE id=1")
                .query(String.class).single())
                .isEqualTo(PreviewRegenerationService.GENERATOR_FINGERPRINT);
    }

    @Test
    void rebuildsAllPreviewsAndPersistsTheirSizesWhenRatioChanges() throws Exception {
        long userId = 93801L;
        long photoId = 93802L;
        String objectKey = "photos/preview-regeneration/source.jpg";
        String staleKey = "legacy-previews/orphan-preview.jpg";
        byte[] image = jpegImage();
        storage.put(objectKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
        storage.put(staleKey, new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, "image/jpeg");

        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES
                    (:id, 'preview-regeneration-user', 'hash', 'preview test',
                     'ADMIN', true, false)
                """).param("id", userId).update();
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by,
                     taken_at, size, content_type, object_key, thumbnail_object_key,
                     thumbnail_size, sha256, status, version, deleted)
                VALUES
                    (:id, 'preview test', 'test', 'test', :userId, CURRENT_TIMESTAMP,
                     :size, 'image/jpeg', :objectKey, :staleKey, 3, :sha256,
                     'AVAILABLE', 1, false)
                """)
                .param("id", photoId)
                .param("userId", userId)
                .param("size", image.length)
                .param("objectKey", objectKey)
                .param("staleKey", staleKey)
                .param("sha256", "b".repeat(64))
                .update();
        setStoredRatio("0.7000");

        PreviewRegenerationService.Result result = previews.synchronizeCompressionRatio();

        var row = jdbc.sql("""
                SELECT thumbnail_object_key, thumbnail_size
                FROM photo WHERE id=:id
                """).param("id", photoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"),
                        rs.getLong("thumbnail_size")})
                .single();
        assertThat(result.regenerated()).isTrue();
        assertThat(result.regeneratedCount()).isEqualTo(1);
        assertThat(row[0].toString()).startsWith("thumbnails/generations/")
                .endsWith("/" + photoId + ".webp");
        assertThat(row[1]).isEqualTo(storage.stat((String) row[0]).size());
        assertThat(jdbc.sql("SELECT compression_ratio FROM preview_setting WHERE id=1")
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.6000");
        assertThat(storage.list("legacy-previews/")).anyMatch(object -> object.objectKey().equals(staleKey));

        storage.delete(objectKey);
        storage.delete((String) row[0]);
        storage.delete(staleKey);
    }

    @Test
    void repairsOnlyMissingDatabasePreviewMetadataWhenRatioDidNotChange() throws Exception {
        long userId = 93811L;
        long photoId = 93812L;
        long healthyUserId = 93813L;
        long healthyPhotoId = 93814L;
        String objectKey = "photos/preview-regeneration/missing-preview-source.jpg";
        String healthyPreviewKey = "thumbnails/preview-regeneration/healthy.jpg";
        byte[] image = jpegImage();
        storage.put(objectKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
        insertUserAndPhoto(userId, photoId, objectKey, image.length, "", 123L,
                "preview-missing-user");
        insertUserAndPhoto(healthyUserId, healthyPhotoId,
                "photos/preview-regeneration/healthy-source.jpg", image.length,
                healthyPreviewKey, 321L, "preview-healthy-user");
        byte[] healthyPreview = new byte[321];
        storage.put(healthyPreviewKey, new ByteArrayInputStream(healthyPreview),
                healthyPreview.length, PreviewProfile.PREVIEW_CONTENT_TYPE, previewMetadata("d".repeat(64)));
        setStoredRatio("0.6000");

        PreviewRegenerationService.Result result = previews.synchronizeCompressionRatio();

        var row = jdbc.sql("""
                SELECT thumbnail_object_key, thumbnail_size
                FROM photo WHERE id=:id
                """).param("id", photoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"),
                        rs.getLong("thumbnail_size")})
                .single();
        assertThat(result.regenerated()).isTrue();
        assertThat(result.regeneratedCount()).isEqualTo(1);
        assertThat(row[0].toString()).startsWith("thumbnails/generations/");
        assertThat(row[1]).isEqualTo(storage.stat((String) row[0]).size());
        assertThat(jdbc.sql("SELECT thumbnail_object_key FROM photo WHERE id=:id")
                .param("id", healthyPhotoId).query(String.class).single()).isEqualTo(healthyPreviewKey);

        storage.delete(objectKey);
        storage.delete((String) row[0]);
    }

    @Test
    void storageRepairProcessesOnlyTheRequestedPhotoIds() throws Exception {
        long requestedUserId = 93815L;
        long requestedPhotoId = 93816L;
        long unrelatedUserId = 93817L;
        long unrelatedPhotoId = 93818L;
        String objectKey = "photos/preview-regeneration/requested-repair.jpg";
        byte[] image = jpegImage();
        storage.put(objectKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
        insertUserAndPhoto(requestedUserId, requestedPhotoId, objectKey, image.length,
                "", null, "preview-requested-repair-user");
        insertUserAndPhoto(unrelatedUserId, unrelatedPhotoId,
                "photos/preview-regeneration/unrelated-missing-source.jpg", image.length,
                "", null, "preview-unrelated-repair-user");
        setStoredRatio("0.6000");

        PreviewRegenerationService.Result result = previews.repairPreviews(
                List.of(requestedPhotoId), PreviewRegenerationService.ProgressListener.NONE);

        String generatedKey = jdbc.sql("SELECT thumbnail_object_key FROM photo WHERE id=:id")
                .param("id", requestedPhotoId).query(String.class).single();
        assertThat(result.regeneratedCount()).isEqualTo(1);
        assertThat(generatedKey).startsWith("thumbnails/generations/");
        assertThat(jdbc.sql("SELECT thumbnail_object_key FROM photo WHERE id=:id")
                .param("id", unrelatedPhotoId).query(String.class).single()).isEmpty();

        storage.delete(objectKey);
        storage.delete(generatedKey);
    }

    @Test
    void cleanupNeverDeletesAnObjectStillUsedAsAnotherPhotoSource() throws Exception {
        long repairUserId = 93851L;
        long repairPhotoId = 93852L;
        long sourceUserId = 93853L;
        long sourcePhotoId = 93854L;
        String repairSourceKey = "photos/preview-regeneration/protected-source-repair.jpg";
        String protectedKey = "thumbnails/preview-regeneration/also-a-photo-source.jpg";
        byte[] image = jpegImage();
        storage.put(repairSourceKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
        storage.put(protectedKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
        insertUserAndPhoto(repairUserId, repairPhotoId, repairSourceKey, image.length,
                protectedKey, 0L, "preview-protected-repair-user");
        insertUserAndPhoto(sourceUserId, sourcePhotoId, protectedKey, image.length,
                "thumbnails/preview-regeneration/source-owner-preview.jpg", 123L,
                "preview-protected-source-user");
        setStoredRatio("0.6000");

        PreviewRegenerationService.Result result = previews.repairPreviews(
                List.of(repairPhotoId), PreviewRegenerationService.ProgressListener.NONE);

        assertThat(result.regeneratedCount()).isEqualTo(1);
        assertThat(storage.stat(protectedKey).size()).isEqualTo(image.length);
        String generatedKey = jdbc.sql("SELECT thumbnail_object_key FROM photo WHERE id=:id")
                .param("id", repairPhotoId).query(String.class).single();
        storage.delete(repairSourceKey);
        storage.delete(protectedKey);
        storage.delete(generatedKey);
    }

    @Test
    void fallsBackToTheFinishedObjectWhenOneSourceCannotBeEncoded() {
        long userId = 93821L;
        long photoId = 93822L;
        String objectKey = "photos/preview-regeneration/invalid-source.jpg";
        String oldPreviewKey = "thumbnails/preview-regeneration/still-active.jpg";
        byte[] invalidImage = "not-an-image".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] oldPreview = new byte[]{1, 2, 3, 4};
        storage.put(objectKey, new ByteArrayInputStream(invalidImage), invalidImage.length, "image/jpeg");
        storage.put(oldPreviewKey, new ByteArrayInputStream(oldPreview), oldPreview.length, "image/jpeg");
        insertUserAndPhoto(userId, photoId, objectKey, invalidImage.length,
                oldPreviewKey, (long) oldPreview.length, "preview-failure-user");
        setStoredRatio("0.7000");

        PreviewRegenerationService.Result result = previews.synchronizeCompressionRatio();

        assertThat(result.fallbackCount()).isOne();
        assertThat(result.regeneratedCount()).isZero();
        var row = jdbc.sql("""
                SELECT thumbnail_object_key, thumbnail_size
                FROM photo WHERE id=:id
                """).param("id", photoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"),
                        rs.getObject("thumbnail_size", Long.class)})
                .single();
        assertThat(row[0]).isNull();
        assertThat(row[1]).isNull();
        // The profile must still switch: otherwise one undecodable source keeps
        // the whole library rebuilding on every startup, forever.
        assertThat(jdbc.sql("SELECT compression_ratio FROM preview_setting WHERE id=1")
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.6000");
        assertThat(storage.find(oldPreviewKey)).isEmpty();

        storage.delete(objectKey);
    }

    @Test
    void neverClearsPreviewReferencesWhenTheObjectStoreIsUnavailable() throws Exception {
        long userId = 93827L;
        long photoId = 93828L;
        String objectKey = "photos/preview-regeneration/storage-outage.jpg";
        String oldPreviewKey = "thumbnails/preview-regeneration/storage-outage-old.jpg";
        byte[] image = jpegImage();
        byte[] oldPreview = new byte[]{9, 8, 7};
        storage.put(objectKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
        storage.put(oldPreviewKey, new ByteArrayInputStream(oldPreview), oldPreview.length, "image/jpeg");
        insertUserAndPhoto(userId, photoId, objectKey, image.length,
                oldPreviewKey, (long) oldPreview.length, "preview-outage-user");
        setStoredRatio("0.7000");

        ObjectStorageService failingStorage = spy(storage);
        doThrow(new IllegalStateException("OSS unavailable"))
                .when(failingStorage).put(anyString(), any(InputStream.class), anyLong(),
                        anyString(), anyMap());
        PreviewRegenerationService isolated = new PreviewRegenerationService(
                jdbc, failingStorage, properties, compressor, workspace, transactions);

        assertThatThrownBy(isolated::synchronizeCompressionRatio)
                .isInstanceOf(IllegalStateException.class);

        // A transport failure is not a source problem, so the round must abort
        // rather than downgrade every photo to the finished object.
        assertThat(jdbc.sql("SELECT thumbnail_object_key FROM photo WHERE id=:id")
                .param("id", photoId).query(String.class).single()).isEqualTo(oldPreviewKey);
        assertThat(jdbc.sql("SELECT compression_ratio FROM preview_setting WHERE id=1")
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.7000");
    }

    @Test
    void resumesFullGenerationFromPersistentCheckpointsAfterOneStorageFailure() throws Exception {
        long badUserId = 93823L;
        long badPhotoId = 93824L;
        long goodUserId = 93825L;
        long goodPhotoId = 93826L;
        String badSourceKey = "photos/preview-regeneration/checkpoint-bad.jpg";
        String goodSourceKey = "photos/preview-regeneration/checkpoint-good.jpg";
        String badOldPreview = "thumbnails/preview-regeneration/checkpoint-bad-old.jpg";
        String goodOldPreview = "thumbnails/preview-regeneration/checkpoint-good-old.jpg";
        byte[] image = jpegImage();
        try {
            storage.put(badSourceKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
            storage.put(goodSourceKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
            insertUserAndPhoto(badUserId, badPhotoId, badSourceKey, image.length,
                    badOldPreview, 111L, "preview-checkpoint-bad-user");
            insertUserAndPhoto(goodUserId, goodPhotoId, goodSourceKey, image.length,
                    goodOldPreview, 222L, "preview-checkpoint-good-user");
            setStoredRatio("0.7000");

            ObjectStorageService observedStorage = spy(storage);
            AtomicInteger badOpenFailures = new AtomicInteger(1);
            doAnswer(invocation -> {
                String key = invocation.getArgument(0);
                if (badSourceKey.equals(key) && badOpenFailures.getAndDecrement() > 0) {
                    throw new IllegalStateException("temporary transport failure");
                }
                return storage.open(key);
            }).when(observedStorage).open(anyString());
            PreviewRegenerationService firstProcess = new PreviewRegenerationService(
                    jdbc, observedStorage, properties, compressor, workspace, transactions);

            assertThatThrownBy(firstProcess::synchronizeCompressionRatio)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已成功暂存的照片将在下次任务中直接复用");

            var checkpoint = jdbc.sql("""
                    SELECT photo_id, staged_object_key
                    FROM preview_regeneration_stage
                    """).query((rs, rowNum) -> new Object[]{
                            rs.getLong("photo_id"), rs.getString("staged_object_key")})
                    .single();
            assertThat(checkpoint[0]).isEqualTo(goodPhotoId);
            String goodStagedKey = (String) checkpoint[1];
            assertThat(storage.find(goodStagedKey)).isPresent();
            assertThat(jdbc.sql("SELECT thumbnail_object_key FROM photo WHERE id=:id")
                    .param("id", goodPhotoId).query(String.class).single())
                    .isEqualTo(goodOldPreview);

            clearInvocations(observedStorage);
            PreviewRegenerationService restartedProcess = new PreviewRegenerationService(
                    jdbc, observedStorage, properties, compressor, workspace, transactions);

            PreviewRegenerationService.Result result = restartedProcess.synchronizeCompressionRatio();

            assertThat(result.regeneratedCount()).isEqualTo(2);
            assertThat(result.fallbackCount()).isZero();
            verify(observedStorage, never()).stat(goodSourceKey);
            verify(observedStorage, never()).open(goodSourceKey);
            verify(observedStorage).find(goodStagedKey);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM preview_regeneration_stage")
                    .query(Integer.class).single()).isZero();
            assertThat(jdbc.sql("SELECT compression_ratio FROM preview_setting WHERE id=1")
                    .query(BigDecimal.class).single()).isEqualByComparingTo("0.6000");
            assertThat(jdbc.sql("SELECT generator_fingerprint FROM preview_setting WHERE id=1")
                    .query(String.class).single())
                    .isEqualTo(PreviewRegenerationService.GENERATOR_FINGERPRINT);
        } finally {
            List<String> generatedKeys = jdbc.sql("""
                    SELECT thumbnail_object_key FROM photo
                    WHERE id IN (:ids) AND thumbnail_object_key IS NOT NULL
                    """).param("ids", List.of(badPhotoId, goodPhotoId))
                    .query(String.class).list();
            List<String> stagedKeys = jdbc.sql("""
                    SELECT staged_object_key FROM preview_regeneration_stage
                    WHERE photo_id IN (:ids)
                    """).param("ids", List.of(badPhotoId, goodPhotoId))
                    .query(String.class).list();
            jdbc.sql("DELETE FROM preview_regeneration_stage WHERE photo_id IN (:ids)")
                    .param("ids", List.of(badPhotoId, goodPhotoId)).update();
            jdbc.sql("DELETE FROM photo WHERE id IN (:ids)")
                    .param("ids", List.of(badPhotoId, goodPhotoId)).update();
            jdbc.sql("DELETE FROM app_user WHERE id IN (:ids)")
                    .param("ids", List.of(badUserId, goodUserId)).update();
            jdbc.sql("DELETE FROM preview_setting WHERE id=1").update();

            storage.delete(badSourceKey);
            storage.delete(goodSourceKey);
            for (String key : generatedKeys) storage.delete(key);
            for (String key : stagedKeys) storage.delete(key);
        }
    }

    @Test
    void regeneratesAReusableCheckpointWhenItsObjectIsMissing() throws Exception {
        long userId = 93827L;
        long photoId = 93828L;
        String sourceKey = "photos/preview-regeneration/checkpoint-object-missing.jpg";
        String oldPreview = "thumbnails/preview-regeneration/checkpoint-object-old.jpg";
        String missingStage = "thumbnails/generations/missing-checkpoint/" + photoId + ".jpg";
        byte[] image = jpegImage();
        storage.put(sourceKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
        insertUserAndPhoto(userId, photoId, sourceKey, image.length,
                oldPreview, 123L, "preview-checkpoint-missing-user");
        setStoredRatio("0.7000");
        jdbc.sql("""
                INSERT INTO preview_regeneration_stage
                    (photo_id, profile_fingerprint, source_object_key,
                     staged_object_key, staged_size, staged_content_type,
                     staged_sha256)
                VALUES
                    (:photoId, :profile, :sourceKey, :stagedKey, 456,
                     'image/webp', :sha256)
                """)
                .param("photoId", photoId)
                .param("profile", PreviewRegenerationService.GENERATOR_FINGERPRINT + "|ratio=0.6000")
                .param("sourceKey", sourceKey)
                .param("stagedKey", missingStage)
                .param("sha256", "0".repeat(64))
                .update();
        ObjectStorageService observedStorage = spy(storage);
        PreviewRegenerationService isolated = new PreviewRegenerationService(
                jdbc, observedStorage, properties, compressor, workspace, transactions);

        PreviewRegenerationService.Result result = isolated.synchronizeCompressionRatio();

        String generatedKey = jdbc.sql("SELECT thumbnail_object_key FROM photo WHERE id=:id")
                .param("id", photoId).query(String.class).single();
        assertThat(result.regeneratedCount()).isOne();
        assertThat(generatedKey).startsWith("thumbnails/generations/").isNotEqualTo(missingStage);
        verify(observedStorage).find(missingStage);
        verify(observedStorage).open(sourceKey);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM preview_regeneration_stage")
                .query(Integer.class).single()).isZero();

        storage.delete(sourceKey);
        storage.delete(generatedKey);
    }

    @Test
    void rejectsReusableCheckpointWhoseProfileMetadataDiffersDespiteMatchingDigest() throws Exception {
        long userId = 93829L;
        long photoId = 93830L;
        String sourceKey = "photos/preview-regeneration/checkpoint-checksum-source.jpg";
        String oldPreview = "thumbnails/preview-regeneration/checkpoint-checksum-old.jpg";
        String corruptStage = "thumbnails/generations/corrupt-checkpoint/" + photoId + ".jpg";
        byte[] image = jpegImage();
        byte[] corrupt = new byte[456];
        java.util.Arrays.fill(corrupt, (byte) 0x5a);
        String stagedSha256 = sha256(corrupt);
        storage.put(sourceKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
        storage.put(corruptStage, new ByteArrayInputStream(corrupt), corrupt.length, "image/jpeg",
                PreviewProfile.configured(0.7)
                        .objectMetadata(PreviewProfile.PREVIEW_CONTENT_TYPE, stagedSha256));
        insertUserAndPhoto(userId, photoId, sourceKey, image.length,
                oldPreview, 123L, "preview-checkpoint-checksum-user");
        setStoredRatio("0.7000");
        jdbc.sql("""
                INSERT INTO preview_regeneration_stage
                    (photo_id, profile_fingerprint, source_object_key,
                     staged_object_key, staged_size, staged_content_type,
                     staged_sha256)
                VALUES
                    (:photoId, :profile, :sourceKey, :stagedKey, :size,
                     'image/webp', :sha256)
                """)
                .param("photoId", photoId)
                .param("profile", PreviewRegenerationService.GENERATOR_FINGERPRINT + "|ratio=0.6000")
                .param("sourceKey", sourceKey)
                .param("stagedKey", corruptStage)
                .param("size", corrupt.length)
                .param("sha256", stagedSha256)
                .update();

        PreviewRegenerationService.Result result = previews.synchronizeCompressionRatio();

        String generatedKey = jdbc.sql("SELECT thumbnail_object_key FROM photo WHERE id=:id")
                .param("id", photoId).query(String.class).single();
        assertThat(result.regeneratedCount()).isOne();
        assertThat(generatedKey).startsWith("thumbnails/generations/").isNotEqualTo(corruptStage);
        assertThat(storage.find(corruptStage)).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM preview_regeneration_stage")
                .query(Integer.class).single()).isZero();

        storage.delete(sourceKey);
        storage.delete(generatedKey);
    }

    @Test
    void rebuildsAndRevalidatesACurrentRoundStageThatWasTruncatedAfterPut() throws Exception {
        long userId = 93875L;
        long photoId = 93876L;
        String sourceKey = "photos/preview-regeneration/current-stage-truncated-source.jpg";
        String oldPreview = "thumbnails/preview-regeneration/current-stage-truncated-old.jpg";
        byte[] image = jpegImage();
        storage.put(sourceKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
        insertUserAndPhoto(userId, photoId, sourceKey, image.length,
                oldPreview, 123L, "preview-current-stage-truncated-user");
        setStoredRatio("0.7000");

        ObjectStorageService observedStorage = spy(storage);
        AtomicInteger stagedPuts = new AtomicInteger();
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            InputStream input = invocation.getArgument(1);
            long size = invocation.getArgument(2);
            String contentType = invocation.getArgument(3);
            Map<String, String> metadata = invocation.getArgument(4);
            storage.put(key, input, size, contentType, metadata);
            if (key.startsWith("thumbnails/generations/")
                    && stagedPuts.incrementAndGet() == 1) {
                byte[] truncated = new byte[]{1, 2, 3};
                storage.put(key, new ByteArrayInputStream(truncated), truncated.length,
                        contentType, metadata);
            }
            return null;
        }).when(observedStorage).put(anyString(), any(InputStream.class), anyLong(),
                anyString(), anyMap());
        PreviewRegenerationService isolated = new PreviewRegenerationService(
                jdbc, observedStorage, properties, compressor, workspace, transactions);

        PreviewRegenerationService.Result result = isolated.synchronizeCompressionRatio();

        var row = jdbc.sql("""
                SELECT thumbnail_object_key, thumbnail_size, version
                FROM photo WHERE id=:id
                """).param("id", photoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"),
                        rs.getLong("thumbnail_size"), rs.getInt("version")})
                .single();
        String generatedKey = (String) row[0];
        assertThat(stagedPuts).hasValue(2);
        assertThat(result.regeneratedCount()).isOne();
        assertThat(row[1]).isEqualTo(storage.stat(generatedKey).size());
        assertThat(row[2]).isEqualTo(2);
        assertThat(storage.stat(generatedKey).userMetadata())
                .containsEntry(PreviewProfile.METADATA_RATIO, "0.6000");
        assertThat(jdbc.sql("SELECT compression_ratio FROM preview_setting WHERE id=1")
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.6000");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM preview_regeneration_stage WHERE photo_id=:id")
                .param("id", photoId).query(Integer.class).single()).isZero();
    }

    @Test
    void neverSwitchesDatabaseWhenCurrentRoundPutKeepsLosingProfileMetadata() throws Exception {
        long userId = 93877L;
        long photoId = 93878L;
        String sourceKey = "photos/preview-regeneration/current-stage-metadata-source.jpg";
        String oldPreview = "thumbnails/preview-regeneration/current-stage-metadata-old.jpg";
        byte[] image = jpegImage();
        storage.put(sourceKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
        insertUserAndPhoto(userId, photoId, sourceKey, image.length,
                oldPreview, 123L, "preview-current-stage-metadata-user");
        setStoredRatio("0.7000");

        ObjectStorageService observedStorage = spy(storage);
        AtomicInteger stagedPuts = new AtomicInteger();
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            InputStream input = invocation.getArgument(1);
            long size = invocation.getArgument(2);
            String contentType = invocation.getArgument(3);
            Map<String, String> metadata = invocation.getArgument(4);
            Map<String, String> corrupted = new HashMap<>(metadata);
            corrupted.put(PreviewProfile.METADATA_RATIO, "0.7000");
            storage.put(key, input, size, contentType, corrupted);
            if (key.startsWith("thumbnails/generations/")) stagedPuts.incrementAndGet();
            return null;
        }).when(observedStorage).put(anyString(), any(InputStream.class), anyLong(),
                anyString(), anyMap());
        PreviewRegenerationService isolated = new PreviewRegenerationService(
                jdbc, observedStorage, properties, compressor, workspace, transactions);

        assertThatThrownBy(isolated::synchronizeCompressionRatio)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("再次校验失败")
                .hasMessageContaining("数据库尚未切换");

        var row = jdbc.sql("""
                SELECT thumbnail_object_key, thumbnail_size, version
                FROM photo WHERE id=:id
                """).param("id", photoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"),
                        rs.getLong("thumbnail_size"), rs.getInt("version")})
                .single();
        assertThat(stagedPuts).hasValue(2);
        assertThat(row).containsExactly(oldPreview, 123L, 1);
        assertThat(jdbc.sql("SELECT compression_ratio FROM preview_setting WHERE id=1")
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.7000");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM preview_regeneration_stage WHERE photo_id=:id")
                .param("id", photoId).query(Integer.class).single()).isOne();
    }

    @Test
    void currentRoundStageOpenFailureKeepsCheckpointAndNeverSwitchesDatabase() throws Exception {
        long userId = 93879L;
        long photoId = 93880L;
        String sourceKey = "photos/preview-regeneration/current-stage-open-source.jpg";
        String oldPreview = "thumbnails/preview-regeneration/current-stage-open-old.jpg";
        byte[] image = jpegImage();
        storage.put(sourceKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
        insertUserAndPhoto(userId, photoId, sourceKey, image.length,
                oldPreview, 123L, "preview-current-stage-open-user");
        setStoredRatio("0.7000");

        ObjectStorageService observedStorage = spy(storage);
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (key.startsWith("thumbnails/generations/")) {
                throw new IllegalStateException("temporary staged object open failure");
            }
            return storage.open(key);
        }).when(observedStorage).open(anyString());
        PreviewRegenerationService isolated = new PreviewRegenerationService(
                jdbc, observedStorage, properties, compressor, workspace, transactions);

        assertThatThrownBy(isolated::synchronizeCompressionRatio)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("检查点已保留");

        var row = jdbc.sql("""
                SELECT thumbnail_object_key, thumbnail_size, version
                FROM photo WHERE id=:id
                """).param("id", photoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"),
                        rs.getLong("thumbnail_size"), rs.getInt("version")})
                .single();
        assertThat(row).containsExactly(oldPreview, 123L, 1);
        assertThat(jdbc.sql("SELECT compression_ratio FROM preview_setting WHERE id=1")
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.7000");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM preview_regeneration_stage WHERE photo_id=:id")
                .param("id", photoId).query(Integer.class).single()).isOne();
    }

    @Test
    void rebasesTheCasWhenOnlyAnUnrelatedPhotoVersionChanges() throws Exception {
        long userId = 93831L;
        long photoId = 93832L;
        String objectKey = "photos/preview-regeneration/concurrent-source.jpg";
        String oldPreviewKey = "thumbnails/preview-regeneration/concurrent-old.jpg";
        byte[] image = jpegImage();
        insertUserAndPhoto(userId, photoId, objectKey, image.length,
                oldPreviewKey, 456L, "preview-concurrent-user");
        setStoredRatio("0.7000");
        storage.put(objectKey, new ByteArrayInputStream(image), image.length, "image/jpeg");

        ObjectStorageService isolatedStorage = spy(storage);
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            storage.put(key, invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4));
            if (key.startsWith("thumbnails/generations/")) {
                jdbc.sql("UPDATE photo SET version=version+1 WHERE id=:id")
                        .param("id", photoId).update();
            }
            return null;
        }).when(isolatedStorage).put(anyString(), any(InputStream.class), anyLong(), anyString(), anyMap());
        PreviewRegenerationService isolated = new PreviewRegenerationService(
                jdbc, isolatedStorage, properties, compressor, workspace, transactions);

        PreviewRegenerationService.Result result = isolated.synchronizeCompressionRatio();

        var row = jdbc.sql("SELECT thumbnail_object_key, version FROM photo WHERE id=:id")
                .param("id", photoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"), rs.getInt("version")})
                .single();
        assertThat(result.regeneratedCount()).isEqualTo(1);
        assertThat(row[0].toString()).startsWith("thumbnails/generations/");
        assertThat(row[1]).isEqualTo(3);
        assertThat(jdbc.sql("SELECT compression_ratio FROM preview_setting WHERE id=1")
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.6000");
        verify(isolatedStorage).delete(oldPreviewKey);
    }

    @Test
    void rollsBackAtomicSwitchWhenThePreviewReferenceChangesConcurrently() throws Exception {
        long userId = 93841L;
        long photoId = 93842L;
        String objectKey = "photos/preview-regeneration/concurrent-preview-source.jpg";
        String oldPreviewKey = "thumbnails/preview-regeneration/concurrent-preview-old.jpg";
        String replacementKey = "thumbnails/preview-regeneration/concurrent-preview-new.jpg";
        byte[] image = jpegImage();
        insertUserAndPhoto(userId, photoId, objectKey, image.length,
                oldPreviewKey, 456L, "preview-concurrent-reference-user");
        setStoredRatio("0.7000");
        storage.put(objectKey, new ByteArrayInputStream(image), image.length, "image/jpeg");

        ObjectStorageService isolatedStorage = spy(storage);
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            storage.put(key, invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4));
            if (key.startsWith("thumbnails/generations/")) {
                jdbc.sql("""
                        UPDATE photo
                        SET thumbnail_object_key=:key, thumbnail_size=789, version=version+1
                        WHERE id=:id
                        """).param("key", replacementKey).param("id", photoId).update();
            }
            return null;
        }).when(isolatedStorage).put(anyString(), any(InputStream.class), anyLong(), anyString(), anyMap());
        PreviewRegenerationService isolated = new PreviewRegenerationService(
                jdbc, isolatedStorage, properties, compressor, workspace, transactions);

        assertThatThrownBy(isolated::synchronizeCompressionRatio)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("原子切换遇到持续并发变化");

        var row = jdbc.sql("SELECT thumbnail_object_key, thumbnail_size, version FROM photo WHERE id=:id")
                .param("id", photoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"), rs.getLong("thumbnail_size"),
                        rs.getInt("version")})
                .single();
        assertThat(row).containsExactly(replacementKey, 789L, 2);
        assertThat(jdbc.sql("SELECT compression_ratio FROM preview_setting WHERE id=1")
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.7000");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM preview_regeneration_stage WHERE photo_id=:id")
                .param("id", photoId).query(Integer.class).single()).isOne();
        verify(isolatedStorage, never()).delete(argThat(key -> key.startsWith("thumbnails/generations/")
                && key.endsWith("/" + photoId + ".jpg")));
        verify(isolatedStorage, never()).delete(replacementKey);
    }

    @Test
    void rollsBackEveryPhotoWhenAFullGenerationSwitchConflictsPartwayThrough() throws Exception {
        long firstUserId = 93861L;
        long firstPhotoId = 93862L;
        long secondUserId = 93863L;
        long secondPhotoId = 93864L;
        String firstSource = "photos/preview-regeneration/atomic-first.jpg";
        String secondSource = "photos/preview-regeneration/atomic-second.jpg";
        String firstOldPreview = "thumbnails/preview-regeneration/atomic-first-old.jpg";
        String secondOldPreview = "thumbnails/preview-regeneration/atomic-second-old.jpg";
        String concurrentPreview = "thumbnails/preview-regeneration/atomic-second-concurrent.jpg";
        byte[] image = jpegImage();
        insertUserAndPhoto(firstUserId, firstPhotoId, firstSource, image.length,
                firstOldPreview, 111L, "preview-atomic-first-user");
        insertUserAndPhoto(secondUserId, secondPhotoId, secondSource, image.length,
                secondOldPreview, 222L, "preview-atomic-second-user");
        setStoredRatio("0.7000");
        storage.put(firstSource, new ByteArrayInputStream(image), image.length, "image/jpeg");
        storage.put(secondSource, new ByteArrayInputStream(image), image.length, "image/jpeg");

        ObjectStorageService isolatedStorage = spy(storage);
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            storage.put(key, invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4));
            if (key.endsWith("/" + secondPhotoId + ImageCompressor.PREVIEW_EXTENSION)) {
                jdbc.sql("""
                        UPDATE photo
                        SET thumbnail_object_key=:key, thumbnail_size=333, version=version+1
                        WHERE id=:id
                        """).param("key", concurrentPreview).param("id", secondPhotoId).update();
            }
            return null;
        }).when(isolatedStorage).put(anyString(), any(InputStream.class), anyLong(), anyString(), anyMap());
        PreviewRegenerationService isolated = new PreviewRegenerationService(
                jdbc, isolatedStorage, properties, compressor, workspace, transactions);

        assertThatThrownBy(isolated::synchronizeCompressionRatio)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("原子切换遇到持续并发变化");

        var first = jdbc.sql("SELECT thumbnail_object_key, thumbnail_size, version FROM photo WHERE id=:id")
                .param("id", firstPhotoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"), rs.getLong("thumbnail_size"),
                        rs.getInt("version")})
                .single();
        var second = jdbc.sql("SELECT thumbnail_object_key, thumbnail_size, version FROM photo WHERE id=:id")
                .param("id", secondPhotoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"), rs.getLong("thumbnail_size"),
                        rs.getInt("version")})
                .single();
        assertThat(first).containsExactly(firstOldPreview, 111L, 1);
        assertThat(second).containsExactly(concurrentPreview, 333L, 2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM preview_regeneration_stage")
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT compression_ratio FROM preview_setting WHERE id=1")
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.7000");
    }

    @Test
    void rollsBackAndRetriesWhenAnOldInstancePublishesAfterTheGenerationSnapshot() throws Exception {
        long firstUserId = 93881L;
        long firstPhotoId = 93882L;
        long lateUserId = 93883L;
        long latePhotoId = 93884L;
        String firstSource = "photos/preview-regeneration/rolling-first.jpg";
        String lateSource = "photos/preview-regeneration/rolling-late.jpg";
        String firstOldPreview = "thumbnails/preview-regeneration/rolling-first-old.jpg";
        String lateOldPreview = "thumbnails/preview-regeneration/rolling-late-old.jpg";
        byte[] image = jpegImage();
        insertUserAndPhoto(firstUserId, firstPhotoId, firstSource, image.length,
                firstOldPreview, 111L, "preview-rolling-first-user");
        setStoredRatio("0.7000");
        storage.put(firstSource, new ByteArrayInputStream(image), image.length, "image/jpeg");
        storage.put(lateSource, new ByteArrayInputStream(image), image.length, "image/jpeg");

        ObjectStorageService isolatedStorage = spy(storage);
        AtomicInteger latePublishes = new AtomicInteger();
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            storage.put(key, invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4));
            if (key.startsWith("thumbnails/generations/")
                    && latePublishes.getAndIncrement() == 0) {
                // Models an old RUNNING instance that read the 0.7000 database
                // profile before this instance took the switch lock.
                insertUserAndPhoto(lateUserId, latePhotoId, lateSource, image.length,
                        lateOldPreview, 222L, "preview-rolling-late-user");
            }
            return null;
        }).when(isolatedStorage).put(anyString(), any(InputStream.class), anyLong(),
                anyString(), anyMap());
        PreviewRegenerationService isolated = new PreviewRegenerationService(
                jdbc, isolatedStorage, properties, compressor, workspace, transactions);

        assertThatThrownBy(isolated::synchronizeCompressionRatio)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("可用照片集合发生变化")
                .hasMessageContaining("回滚");

        assertThat(jdbc.sql("SELECT compression_ratio FROM preview_setting WHERE id=1")
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.7000");
        assertThat(jdbc.sql("SELECT thumbnail_object_key FROM photo WHERE id=:id")
                .param("id", firstPhotoId).query(String.class).single())
                .isEqualTo(firstOldPreview);
        assertThat(jdbc.sql("SELECT thumbnail_object_key FROM photo WHERE id=:id")
                .param("id", latePhotoId).query(String.class).single())
                .isEqualTo(lateOldPreview);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM preview_regeneration_stage")
                .query(Integer.class).single()).isOne();

        PreviewRegenerationService.Result retried = isolated.synchronizeCompressionRatio();

        assertThat(retried.regeneratedCount()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT compression_ratio FROM preview_setting WHERE id=1")
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.6000");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM photo
                WHERE id IN (:firstId, :lateId)
                  AND thumbnail_object_key LIKE 'thumbnails/generations/%'
                """)
                .param("firstId", firstPhotoId)
                .param("lateId", latePhotoId)
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM preview_regeneration_stage")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void rollsBackWhenAnotherInstanceClaimsCleanupAfterStageValidation() throws Exception {
        long userId = 93889L;
        long photoId = 93890L;
        String sourceKey = "photos/preview-regeneration/cross-instance-cleanup-source.jpg";
        String oldPreviewKey = "thumbnails/preview-regeneration/cross-instance-cleanup-old.jpg";
        byte[] image = jpegImage();
        byte[] oldPreview = new byte[123];
        storage.put(sourceKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
        storage.put(oldPreviewKey, new ByteArrayInputStream(oldPreview), oldPreview.length,
                PreviewProfile.PREVIEW_CONTENT_TYPE, PreviewProfile.configured(0.7)
                        .objectMetadata(PreviewProfile.PREVIEW_CONTENT_TYPE, sha256(oldPreview)));
        insertUserAndPhoto(userId, photoId, sourceKey, image.length,
                oldPreviewKey, (long) oldPreview.length, "preview-cross-cleanup-user");
        setStoredRatio("0.7000");

        StorageProperties oldInstanceProperties = mock(StorageProperties.class);
        when(oldInstanceProperties.previewCompressionRatio()).thenReturn(0.7d);
        PreviewRegenerationService oldInstance = new PreviewRegenerationService(
                jdbc, storage, oldInstanceProperties, compressor, workspace, transactions);

        PreviewProfileRepository repository = spy(new PreviewProfileRepository(jdbc));
        PreviewProfilePolicy policy = new PreviewProfilePolicy(properties, repository);
        PreviewRegenerationService newInstance = new PreviewRegenerationService(
                jdbc, storage, properties, compressor, workspace, transactions,
                repository, policy, new PreviewMaintenanceLock());
        AtomicInteger cleanupRuns = new AtomicInteger();
        doAnswer(invocation -> {
            cleanupRuns.incrementAndGet();
            java.util.concurrent.CompletableFuture.runAsync(
                    oldInstance::synchronizeCompressionRatio).join();
            invocation.callRealMethod();
            return null;
        }).when(repository).save(any(PreviewProfile.class),
                any(PreviewProfileRepository.StoredProfile.class));

        assertThatThrownBy(newInstance::synchronizeCompressionRatio)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("暂存检查点")
                .hasMessageContaining("回滚");

        assertThat(cleanupRuns).hasValue(1);
        assertThat(policy.phase()).isEqualTo(PreviewProfilePolicy.Phase.BOOTSTRAPPING);
        assertThat(jdbc.sql("SELECT compression_ratio FROM preview_setting WHERE id=1")
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.7000");
        assertThat(jdbc.sql("SELECT thumbnail_object_key FROM photo WHERE id=:id")
                .param("id", photoId).query(String.class).single()).isEqualTo(oldPreviewKey);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM preview_regeneration_stage")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void successfulSwitchPreservesAnUnrelatedClaimedStageForCleanupRetry() throws Exception {
        long activeUserId = 93891L;
        long activePhotoId = 93892L;
        long cleanupUserId = 93893L;
        long cleanupPhotoId = 93894L;
        byte[] image = jpegImage();
        String activeSource = "photos/preview-regeneration/claimed-active.jpg";
        String cleanupSource = "photos/preview-regeneration/claimed-ineligible.jpg";
        String cleanupStage = "thumbnails/generations/claimed-ineligible.jpg";
        storage.put(activeSource, new ByteArrayInputStream(image), image.length, "image/jpeg");
        storage.put(cleanupStage, new ByteArrayInputStream(image), image.length, "image/jpeg");
        insertUserAndPhoto(activeUserId, activePhotoId, activeSource, image.length,
                "thumbnails/preview-regeneration/claimed-active-old.jpg", 123L,
                "preview-claimed-active-user");
        insertUserAndPhoto(cleanupUserId, cleanupPhotoId, cleanupSource, image.length,
                null, null, "preview-claimed-cleanup-user");
        jdbc.sql("UPDATE photo SET status='UPLOADING' WHERE id=:id")
                .param("id", cleanupPhotoId).update();
        setStoredRatio("0.7000");
        jdbc.sql("""
                INSERT INTO preview_regeneration_stage
                    (photo_id, profile_fingerprint, source_object_key,
                     staged_object_key, staged_size, staged_content_type,
                     staged_sha256, cleanup_token, cleanup_claimed_at)
                VALUES
                    (:photoId, :profile, :sourceKey, :stagedKey, :size,
                     'image/webp', :sha256, 'other-instance', CURRENT_TIMESTAMP)
                """)
                .param("photoId", cleanupPhotoId)
                .param("profile", PreviewProfile.configured(0.6).fingerprint())
                .param("sourceKey", cleanupSource)
                .param("stagedKey", cleanupStage)
                .param("size", image.length)
                .param("sha256", sha256(image))
                .update();

        PreviewRegenerationService.Result result = previews.synchronizeCompressionRatio();

        assertThat(result.regeneratedCount()).isOne();
        assertThat(jdbc.sql("SELECT cleanup_token FROM preview_regeneration_stage WHERE photo_id=:id")
                .param("id", cleanupPhotoId).query(String.class).single())
                .isEqualTo("other-instance");
        assertThat(storage.find(cleanupStage)).isPresent();
    }

    @Test
    void targetedRepairFallsBackInsteadOfBlockingBootstrapWhenTheSourceIsUnusable() {
        long userId = 93831L;
        long photoId = 93832L;
        String objectKey = "photos/preview-regeneration/repair-unusable.jpg";
        String missingPreviewKey = "thumbnails/preview-regeneration/repair-gone.jpg";
        byte[] invalidImage = "not-an-image".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        storage.put(objectKey, new ByteArrayInputStream(invalidImage), invalidImage.length,
                "image/jpeg");
        insertUserAndPhoto(userId, photoId, objectKey, invalidImage.length,
                missingPreviewKey, 123L, "preview-repair-unusable-user");
        setStoredRatio("0.6000");

        // A throw here used to skip completeBootstrap(), pinning the profile
        // policy in BOOTSTRAPPING for the rest of the process lifetime.
        PreviewRegenerationService.Result result = previews.synchronizeCompressionRatio();

        assertThat(result.fallbackCount()).isOne();
        var row = jdbc.sql("""
                SELECT thumbnail_object_key, thumbnail_size
                FROM photo WHERE id=:id
                """).param("id", photoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"),
                        rs.getObject("thumbnail_size", Long.class)})
                .single();
        assertThat(row[0]).isNull();
        assertThat(row[1]).isNull();

        storage.delete(objectKey);
    }

    @Test
    void targetedRepairKeepsAStalePreviewWhoseObjectStillExists() throws Exception {
        long userId = 93833L;
        long photoId = 93834L;
        String objectKey = "photos/preview-regeneration/repair-stale.jpg";
        String stalePreviewKey = "thumbnails/preview-regeneration/repair-stale-preview.jpg";
        byte[] invalidImage = "not-an-image".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] stalePreview = jpegImage();
        storage.put(objectKey, new ByteArrayInputStream(invalidImage), invalidImage.length,
                "image/jpeg");
        // Present, but encoded by an older ratio, so the audit reports it
        // unhealthy and asks for a targeted repair.
        storage.put(stalePreviewKey, new ByteArrayInputStream(stalePreview), stalePreview.length,
                "image/jpeg", new PreviewProfile(new BigDecimal("0.9000"),
                        PreviewProfile.CURRENT_GENERATOR_FINGERPRINT)
                        .objectMetadata(PreviewProfile.PREVIEW_CONTENT_TYPE, sha256(stalePreview)));
        insertUserAndPhoto(userId, photoId, objectKey, invalidImage.length,
                stalePreviewKey, (long) stalePreview.length, "preview-repair-stale-user");
        setStoredRatio("0.6000");

        PreviewRegenerationService.Result result = previews.synchronizeCompressionRatio();

        // Re-encoding is impossible, but a slightly off-ratio thumbnail still
        // beats making every gallery tile download the finished object.
        assertThat(result.fallbackCount()).isZero();
        assertThat(jdbc.sql("SELECT thumbnail_object_key FROM photo WHERE id=:id")
                .param("id", photoId).query(String.class).single()).isEqualTo(stalePreviewKey);
        assertThat(storage.find(stalePreviewKey)).isPresent();

        storage.delete(objectKey);
    }

    @Test
    void rebuildsLegacyPhotoWhoseDeclaredContentTypeDisagreesWithItsRealBytes() throws Exception {
        long userId = 93835L;
        long photoId = 93836L;
        String objectKey = "photos/preview-regeneration/legacy-mislabelled.png";
        byte[] png = pngImage();
        storage.put(objectKey, new ByteArrayInputStream(png), png.length, "image/png");
        // Legacy migration copies the old system's mime_type verbatim and falls
        // back to application/octet-stream, so a real PNG can carry a declared
        // type that is anything but image/png. Deriving the expected preview MIME
        // from that column used to reject the generated preview forever: the
        // rebuild failed identically on every 30s retry and blocked the whole
        // library's generation switch.
        insertUserAndPhotoWithContentType(userId, photoId, objectKey, png.length,
                null, null, "preview-legacy-mime-user", "application/octet-stream");
        setStoredRatio("0.7000");

        PreviewRegenerationService.Result result = previews.synchronizeCompressionRatio();

        assertThat(result.fallbackCount()).isZero();
        assertThat(result.regeneratedCount()).isOne();
        var row = jdbc.sql("""
                SELECT thumbnail_object_key, thumbnail_size FROM photo WHERE id=:id
                """).param("id", photoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"),
                        rs.getObject("thumbnail_size", Long.class)})
                .single();
        // The source is really PNG, but every preview is encoded as WebP.
        assertThat((String) row[0]).startsWith("thumbnails/generations/").endsWith(".webp");
        assertThat((Long) row[1]).isPositive();
        assertThat(jdbc.sql("SELECT compression_ratio FROM preview_setting WHERE id=1")
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.6000");
        // The follow-up audit must accept it too, otherwise every startup would
        // re-encode and re-upload the same preview.
        assertThat(previews.repairPreviews(List.of(photoId),
                PreviewRegenerationService.ProgressListener.NONE).regeneratedCount()).isZero();

        storage.delete(objectKey);
        storage.delete((String) row[0]);
    }

    /**
     * A generation switch must re-encode only the previews that are actually
     * out of date. The whole library used to be rebuilt whenever the umbrella
     * fingerprint moved, which meant downloading, decoding and re-uploading
     * every finished object just to produce previews that were already correct.
     *
     * <p>The migration to WebP is exactly this shape: leftover JPEG/PNG
     * previews must go, previews already written by the current encoder must
     * stay untouched.</p>
     */
    @Test
    void changedProfileOnlyReplacesPreviewsThatAreActuallyOutOfDate() throws Exception {
        long userId = 93895L;
        long currentPhotoId = 93896L;
        long stalePhotoId = 93897L;
        String currentSourceKey = "photos/preview-regeneration/format-scope-current.jpg";
        String staleSourceKey = "photos/preview-regeneration/format-scope-stale.jpg";
        String currentPreviewKey =
                "thumbnails/generations/preview-regeneration/format-scope-current.webp";
        String stalePreviewKey =
                "thumbnails/generations/preview-regeneration/format-scope-stale.jpg";
        byte[] source = jpegImage();
        byte[] currentPreview = new byte[257];
        byte[] stalePreview = new byte[311];

        storage.put(currentSourceKey, new ByteArrayInputStream(source), source.length,
                "image/jpeg");
        storage.put(staleSourceKey, new ByteArrayInputStream(source), source.length,
                "image/jpeg");
        // Already carries the current preview identity: nothing about it changed.
        storage.put(currentPreviewKey, new ByteArrayInputStream(currentPreview),
                currentPreview.length, PreviewProfile.PREVIEW_CONTENT_TYPE,
                PreviewProfile.configured(0.6)
                        .objectMetadata(PreviewProfile.PREVIEW_CONTENT_TYPE,
                                sha256(currentPreview)));
        // A pre-WebP preview: plausible ratio metadata, wrong container.
        storage.put(stalePreviewKey, new ByteArrayInputStream(stalePreview),
                stalePreview.length, "image/jpeg", Map.of(
                        PreviewProfile.METADATA_RATIO, "0.6000",
                        PreviewProfile.METADATA_EFFECTIVE_QUALITY, "60",
                        PreviewProfile.METADATA_GENERATOR, PreviewProfile.WEBP_OBJECT_GENERATOR,
                        PreviewProfile.METADATA_SHA256, sha256(stalePreview)));

        insertUserAndPhotoWithContentType(userId, currentPhotoId, currentSourceKey, source.length,
                currentPreviewKey, (long) currentPreview.length, "preview-format-scope-user",
                "image/jpeg");
        insertPhotoWithContentType(stalePhotoId, userId, staleSourceKey, source.length,
                stalePreviewKey, (long) stalePreview.length, "image/jpeg");
        setStoredProfile("0.6000", "hybrid-v5/superseded-by-the-webp-encoder");

        ObjectStorageService trackedStorage = spy(storage);
        PreviewRegenerationService isolated = new PreviewRegenerationService(
                jdbc, trackedStorage, properties, compressor, workspace, transactions);

        PreviewRegenerationService.Result result = isolated.synchronizeCompressionRatio();

        assertThat(result.regeneratedCount()).isOne();
        // The up-to-date preview keeps its object, its row and its version, and
        // was never re-uploaded or deleted.
        var currentRow = jdbc.sql("""
                SELECT thumbnail_object_key, thumbnail_size, version
                FROM photo WHERE id=:id
                """).param("id", currentPhotoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"),
                        rs.getLong("thumbnail_size"), rs.getInt("version")})
                .single();
        assertThat(currentRow)
                .containsExactly(currentPreviewKey, (long) currentPreview.length, 1);
        assertThat(storage.find(currentPreviewKey)).isPresent();
        verify(trackedStorage, never()).put(eq(currentPreviewKey), any(InputStream.class),
                anyLong(), anyString(), anyMap());
        verify(trackedStorage, never()).delete(currentPreviewKey);

        // The stale one is re-encoded onto a new WebP key.
        String regeneratedKey = jdbc.sql("SELECT thumbnail_object_key FROM photo WHERE id=:id")
                .param("id", stalePhotoId).query(String.class).single();
        assertThat(regeneratedKey).isNotEqualTo(stalePreviewKey).endsWith(".webp");
        assertThat(storage.stat(regeneratedKey).contentType())
                .isEqualTo(PreviewProfile.PREVIEW_CONTENT_TYPE);
        assertThat(storage.stat(regeneratedKey).userMetadata())
                .containsEntry(PreviewProfile.METADATA_RATIO, "0.6000")
                .containsEntry(PreviewProfile.METADATA_EFFECTIVE_QUALITY, "60")
                .containsEntry(PreviewProfile.METADATA_GENERATOR,
                        PreviewProfile.WEBP_OBJECT_GENERATOR);
        assertThat(jdbc.sql("SELECT generator_fingerprint FROM preview_setting WHERE id=1")
                .query(String.class).single())
                .isEqualTo(PreviewProfile.CURRENT_GENERATOR_FINGERPRINT);

        storage.delete(currentSourceKey);
        storage.delete(staleSourceKey);
        storage.delete(currentPreviewKey);
        storage.delete(regeneratedKey);
    }

    private void insertUserAndPhoto(long userId, long photoId, String objectKey, long sourceSize,
                                    String previewKey, Long previewSize, String username) {
        insertUserAndPhotoWithContentType(userId, photoId, objectKey, sourceSize,
                previewKey, previewSize, username, "image/jpeg");
    }

    private void insertUserAndPhotoWithContentType(long userId, long photoId, String objectKey,
                                                   long sourceSize, String previewKey,
                                                   Long previewSize, String username,
                                                   String contentType) {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES
                    (:id, :username, 'hash', 'preview test', 'ADMIN', true, false)
                """).param("id", userId).param("username", username).update();
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by,
                     taken_at, size, content_type, object_key, thumbnail_object_key,
                     thumbnail_size, sha256, status, version, deleted)
                VALUES
                    (:id, 'preview test', 'test', 'test', :userId, CURRENT_TIMESTAMP,
                     :size, :contentType, :objectKey, :previewKey, :previewSize, :sha256,
                     'AVAILABLE', 1, false)
                """)
                .param("id", photoId)
                .param("userId", userId)
                .param("size", sourceSize)
                .param("contentType", contentType)
                .param("objectKey", objectKey)
                .param("previewKey", previewKey)
                .param("previewSize", previewSize)
                .param("sha256", "c".repeat(64))
                .update();
    }

    /** A second photo for an already-inserted uploader. */
    private void insertPhotoWithContentType(long photoId, long userId, String objectKey,
                                            long sourceSize, String previewKey,
                                            Long previewSize, String contentType) {
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by,
                     taken_at, size, content_type, object_key, thumbnail_object_key,
                     thumbnail_size, sha256, status, version, deleted)
                VALUES
                    (:id, 'preview test', 'test', 'test', :userId, CURRENT_TIMESTAMP,
                     :size, :contentType, :objectKey, :previewKey, :previewSize, :sha256,
                     'AVAILABLE', 1, false)
                """)
                .param("id", photoId)
                .param("userId", userId)
                .param("size", sourceSize)
                .param("contentType", contentType)
                .param("objectKey", objectKey)
                .param("previewKey", previewKey)
                .param("previewSize", previewSize)
                .param("sha256", "d".repeat(64))
                .update();
    }

    private void setStoredProfile(String ratio, String fingerprint) {
        setStoredRatio(ratio);
        jdbc.sql("UPDATE preview_setting SET generator_fingerprint=:fingerprint WHERE id=1")
                .param("fingerprint", fingerprint)
                .update();
    }

    private void setStoredRatio(String value) {
        BigDecimal ratio = new BigDecimal(value);
        int updated = jdbc.sql("""
                UPDATE preview_setting
                SET compression_ratio=:ratio, generator_fingerprint=:fingerprint
                WHERE id=1
                """)
                .param("ratio", ratio)
                .param("fingerprint", PreviewRegenerationService.GENERATOR_FINGERPRINT)
                .update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO preview_setting (id, compression_ratio, generator_fingerprint)
                    VALUES (1, :ratio, :fingerprint)
                    """)
                    .param("ratio", ratio)
                    .param("fingerprint", PreviewRegenerationService.GENERATOR_FINGERPRINT)
                    .update();
        }
    }

    private java.util.Map<String, String> previewMetadata(String sha256) {
        return PreviewProfile.configured(properties.previewCompressionRatio())
                .objectMetadata(PreviewProfile.PREVIEW_CONTENT_TYPE, sha256);
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void cleanupTestData() {
        List<String> objectKeys = jdbc.sql("""
                SELECT staged_object_key AS object_key
                FROM preview_regeneration_stage
                WHERE photo_id BETWEEN :minId AND :maxId
                UNION
                SELECT object_key
                FROM photo
                WHERE id BETWEEN :minId AND :maxId AND object_key IS NOT NULL
                UNION
                SELECT thumbnail_object_key AS object_key
                FROM photo
                WHERE id BETWEEN :minId AND :maxId AND thumbnail_object_key IS NOT NULL
                """)
                .param("minId", TEST_ID_MIN)
                .param("maxId", TEST_ID_MAX)
                .query(String.class).list().stream()
                .filter(objectKey -> objectKey != null && !objectKey.isBlank())
                .distinct()
                .toList();
        jdbc.sql("""
                DELETE FROM preview_regeneration_stage
                WHERE photo_id BETWEEN :minId AND :maxId
                """).param("minId", TEST_ID_MIN).param("maxId", TEST_ID_MAX).update();
        jdbc.sql("DELETE FROM photo WHERE id BETWEEN :minId AND :maxId")
                .param("minId", TEST_ID_MIN).param("maxId", TEST_ID_MAX).update();
        jdbc.sql("DELETE FROM app_user WHERE id BETWEEN :minId AND :maxId")
                .param("minId", TEST_ID_MIN).param("maxId", TEST_ID_MAX).update();
        jdbc.sql("DELETE FROM preview_setting WHERE id=1").update();
        for (String objectKey : objectKeys) {
            storage.delete(objectKey);
        }
    }

    private byte[] jpegImage() throws Exception {
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, new Color((x * 13 + y) & 255,
                        (x + y * 7) & 255, (x * y) & 255).getRGB());
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpg", output);
            return output.toByteArray();
        }
    }

    private byte[] pngImage() throws Exception {
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, new Color((x * 5 + y) & 255,
                        (x + y * 3) & 255, (x ^ y) & 255, 255).getRGB());
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
