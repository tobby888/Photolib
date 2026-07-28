package cn.photolib.photo;

import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PhotoProcessingProfileGuardTests {
    private static final long USER_ID = 93911L;
    private static final long PHOTO_ID = 93912L;

    @Autowired
    private PhotoProcessingService processing;
    @Autowired
    private PreviewProfilePolicy profiles;
    @Autowired
    private PreviewProfileRepository profileRepository;
    @Autowired
    private PhotoMapper photoMapper;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void profileSwitchAfterEncodingCannotPublishAvailableWithOldMetadata() {
        Optional<PreviewProfileRepository.StoredProfile> original = profileRepository.findStored();
        cleanupRows();
        try {
            setProfile("0.6000");
            insertPhoto();
            PreviewProfilePolicy.CommitPermit permit = profiles.permitForNewPreview();

            // Another instance completes a generation switch after this worker
            // chose and encoded the old database profile.
            setProfile("0.7000");
            PhotoEntity photo = photoMapper.selectById(PHOTO_ID);
            ImageCompressor.FileResult processed = new ImageCompressor.FileResult(
                    Path.of("processed.jpg"), 500L, 1200, 800, "image/jpeg");
            ImageCompressor.FileResult thumbnail = new ImageCompressor.FileResult(
                    Path.of("thumbnail.jpg"), 100L, 480, 320, "image/jpeg");

            assertThatThrownBy(() -> processing.completeProcessing(
                    photo, processed, thumbnail, "thumbnails/93912.jpg",
                    "stored.jpg", LocalDateTime.now().plusDays(30), permit))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("profile")
                    .hasMessageContaining("重新上传");

            var rejected = jdbc.sql("""
                    SELECT status, size, thumbnail_object_key, version
                    FROM photo WHERE id=:id
                    """).param("id", PHOTO_ID)
                    .query((rs, rowNum) -> new Object[]{
                            rs.getString("status"), rs.getLong("size"),
                            rs.getString("thumbnail_object_key"), rs.getInt("version")})
                    .single();
            assertThat(rejected).containsExactly("PROCESSING", 123L, null, 1);

            processing.markProcessingFailed(photo,
                    new IllegalStateException("预览图 profile 已切换"));
            var retryable = jdbc.sql("""
                    SELECT status, failure_reason, version
                    FROM photo WHERE id=:id
                    """).param("id", PHOTO_ID)
                    .query((rs, rowNum) -> new Object[]{
                            rs.getString("status"), rs.getString("failure_reason"),
                            rs.getInt("version")})
                    .single();
            assertThat(retryable).containsExactly(
                    "UPLOADING", "预览图 profile 已切换", 2);
        } finally {
            cleanupRows();
            restoreProfile(original);
        }
    }

    @Test
    void bootstrapPermitPublishesEnvironmentTargetWhileObservedDatabaseIsStillOld() {
        Optional<PreviewProfileRepository.StoredProfile> original = profileRepository.findStored();
        cleanupRows();
        try {
            setProfile("0.7000");
            insertPhoto();
            PreviewProfilePolicy.CommitPermit permit = new PreviewProfilePolicy.CommitPermit(
                    PreviewProfile.configured(0.6), true,
                    profileRepository.findStored().orElseThrow());
            PhotoEntity photo = photoMapper.selectById(PHOTO_ID);

            processing.completeProcessing(
                    photo,
                    new ImageCompressor.FileResult(
                            Path.of("processed.jpg"), 500L, 1200, 800, "image/jpeg"),
                    new ImageCompressor.FileResult(
                            Path.of("thumbnail.jpg"), 100L, 480, 320, "image/jpeg"),
                    "thumbnails/93912.jpg", "stored.jpg",
                    LocalDateTime.now().plusDays(30), permit);

            var published = jdbc.sql("""
                    SELECT status, thumbnail_object_key, version
                    FROM photo WHERE id=:id
                    """).param("id", PHOTO_ID)
                    .query((rs, rowNum) -> new Object[]{
                            rs.getString("status"), rs.getString("thumbnail_object_key"),
                            rs.getInt("version")})
                    .single();
            assertThat(published).containsExactly(
                    "AVAILABLE", "thumbnails/93912.jpg", 2);
        } finally {
            cleanupRows();
            restoreProfile(original);
        }
    }

    private void insertPhoto() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled,
                     must_change_password, version, deleted)
                VALUES
                    (:id, 'profile-guard-user', 'hash', 'profile guard', 'ADMIN', true,
                     false, 1, false)
                """).param("id", USER_ID).update();
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by,
                     taken_at, size, content_type, object_key, sha256, status, version, deleted)
                VALUES
                    (:id, 'profile guard', '202693911', 'profile photographer', :userId,
                     :takenAt, 123, 'image/jpeg', 'photos/profile-guard.jpg', :sha256,
                     'PROCESSING', 1, false)
                """)
                .param("id", PHOTO_ID)
                .param("userId", USER_ID)
                .param("takenAt", LocalDateTime.now().minusMinutes(1))
                .param("sha256", "0".repeat(64))
                .update();
    }

    private void setProfile(String ratio) {
        int updated = jdbc.sql("""
                UPDATE preview_setting
                SET compression_ratio=:ratio, generator_fingerprint=:generator
                WHERE id=1
                """)
                .param("ratio", new BigDecimal(ratio))
                .param("generator", PreviewProfile.CURRENT_GENERATOR_FINGERPRINT)
                .update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO preview_setting (id, compression_ratio, generator_fingerprint)
                    VALUES (1, :ratio, :generator)
                    """)
                    .param("ratio", new BigDecimal(ratio))
                    .param("generator", PreviewProfile.CURRENT_GENERATOR_FINGERPRINT)
                    .update();
        }
    }

    private void restoreProfile(Optional<PreviewProfileRepository.StoredProfile> original) {
        if (original.isEmpty()) {
            jdbc.sql("DELETE FROM preview_setting WHERE id=1").update();
            return;
        }
        PreviewProfileRepository.StoredProfile stored = original.orElseThrow();
        int updated = jdbc.sql("""
                UPDATE preview_setting
                SET compression_ratio=:ratio, generator_fingerprint=:generator
                WHERE id=1
                """)
                .param("ratio", stored.compressionRatio())
                .param("generator", stored.generatorFingerprint())
                .update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO preview_setting (id, compression_ratio, generator_fingerprint)
                    VALUES (1, :ratio, :generator)
                    """)
                    .param("ratio", stored.compressionRatio())
                    .param("generator", stored.generatorFingerprint())
                    .update();
        }
    }

    private void cleanupRows() {
        jdbc.sql("DELETE FROM photo WHERE id=:id").param("id", PHOTO_ID).update();
        jdbc.sql("DELETE FROM app_user WHERE id=:id").param("id", USER_ID).update();
    }
}
