package cn.photolib.photo;

import cn.photolib.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PreviewRegenerationServiceTests {
    @Autowired
    private PreviewRegenerationService previews;
    @Autowired
    private ObjectStorageService storage;
    @Autowired
    private JdbcClient jdbc;

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
        jdbc.sql("UPDATE preview_setting SET compression_ratio=0.7 WHERE id=1").update();

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
        assertThat(row[0].toString()).startsWith("thumbnails/generations/").endsWith("/" + photoId + ".jpg");
        assertThat(row[1]).isEqualTo(storage.stat((String) row[0]).size());
        assertThat(jdbc.sql("SELECT compression_ratio FROM preview_setting WHERE id=1")
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.6000");
        assertThat(storage.list("legacy-previews/")).noneMatch(object -> object.objectKey().equals(staleKey));

        storage.delete(objectKey);
        storage.delete((String) row[0]);
    }

    @Test
    void regeneratesMissingPreviewEvenWhenRatioDidNotChange() throws Exception {
        long userId = 93811L;
        long photoId = 93812L;
        String objectKey = "photos/preview-regeneration/missing-preview-source.jpg";
        String missingPreviewKey = "thumbnails/missing-preview.jpg";
        byte[] image = jpegImage();
        storage.put(objectKey, new ByteArrayInputStream(image), image.length, "image/jpeg");
        insertUserAndPhoto(userId, photoId, objectKey, image.length, missingPreviewKey, 123L,
                "preview-missing-user");

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
        assertThat(row[0].toString()).startsWith("thumbnails/generations/");
        assertThat(row[1]).isEqualTo(storage.stat((String) row[0]).size());

        storage.delete(objectKey);
        storage.delete((String) row[0]);
    }

    @Test
    void keepsOldPreviewAndDatabaseKeyWhenStagingFails() {
        long userId = 93821L;
        long photoId = 93822L;
        String objectKey = "photos/preview-regeneration/invalid-source.jpg";
        String oldPreviewKey = "legacy-previews/still-active.jpg";
        byte[] invalidImage = "not-an-image".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] oldPreview = new byte[]{1, 2, 3, 4};
        storage.put(objectKey, new ByteArrayInputStream(invalidImage), invalidImage.length, "image/jpeg");
        storage.put(oldPreviewKey, new ByteArrayInputStream(oldPreview), oldPreview.length, "image/jpeg");
        insertUserAndPhoto(userId, photoId, objectKey, invalidImage.length,
                oldPreviewKey, (long) oldPreview.length, "preview-failure-user");
        jdbc.sql("UPDATE preview_setting SET compression_ratio=0.7 WHERE id=1").update();

        assertThatThrownBy(() -> previews.synchronizeCompressionRatio())
                .isInstanceOf(IllegalStateException.class);

        var row = jdbc.sql("""
                SELECT thumbnail_object_key, thumbnail_size
                FROM photo WHERE id=:id
                """).param("id", photoId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("thumbnail_object_key"),
                        rs.getLong("thumbnail_size")})
                .single();
        assertThat(row[0]).isEqualTo(oldPreviewKey);
        assertThat(row[1]).isEqualTo((long) oldPreview.length);
        assertThat(storage.open(oldPreviewKey)).hasBinaryContent(oldPreview);
        assertThat(jdbc.sql("SELECT compression_ratio FROM preview_setting WHERE id=1")
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.7000");

        storage.delete(objectKey);
        storage.delete(oldPreviewKey);
    }

    private void insertUserAndPhoto(long userId, long photoId, String objectKey, long sourceSize,
                                    String previewKey, Long previewSize, String username) {
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
                     :size, 'image/jpeg', :objectKey, :previewKey, :previewSize, :sha256,
                     'AVAILABLE', 1, false)
                """)
                .param("id", photoId)
                .param("userId", userId)
                .param("size", sourceSize)
                .param("objectKey", objectKey)
                .param("previewKey", previewKey)
                .param("previewSize", previewSize)
                .param("sha256", "c".repeat(64))
                .update();
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
}
