package cn.photolib.photo;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一张动图走完整条处理流水线：成品图与上传的字节逐字节相同，缩略图是 WebP，
 * 照片标记为动图，视图里带上播放地址。
 */
@SpringBootTest
class AnimatedPhotoPipelineTests {
    private static final long USER_ID = 97601L;
    private static final long PHOTO_ID = 97602L;
    private static final String ORIGINAL_KEY = "temporary/photos/animated-pipeline.gif";
    private static final String PHOTO_KEY = "photos/2026/animated-pipeline.gif";
    private static final String THUMBNAIL_KEY = "thumbnails/generations/uploads/97602.webp";

    @Autowired
    private PhotoProcessingService processing;
    @Autowired
    private PhotoService photos;
    @Autowired
    private ObjectStorageService storage;
    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void tearDown() {
        for (String key : new String[]{THUMBNAIL_KEY, PHOTO_KEY, ORIGINAL_KEY}) {
            try {
                storage.delete(key);
            } catch (RuntimeException ignored) {
                // 没建出来的对象不用删。
            }
        }
        jdbc.sql("DELETE FROM photo WHERE id=:id").param("id", PHOTO_ID).update();
        jdbc.sql("DELETE FROM app_user WHERE id=:id").param("id", USER_ID).update();
    }

    @Test
    void storesTheAnimatedGifUntouchedAndMarksThePhotoAnimated() throws Exception {
        byte[] gif = AnimatedFixtures.gif(640, 360, Color.RED, Color.GREEN, Color.BLUE);
        storage.put(ORIGINAL_KEY, new ByteArrayInputStream(gif), gif.length, "image/gif");
        insertRows(gif);

        processing.submit(PHOTO_ID).get(30, TimeUnit.SECONDS);

        var row = jdbc.sql("""
                SELECT status, animated, size, width, height, stored_file_name, failure_reason
                FROM photo WHERE id=:id
                """).param("id", PHOTO_ID)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("status"), rs.getBoolean("animated"), rs.getLong("size"),
                        rs.getInt("width"), rs.getInt("height"), rs.getString("stored_file_name"),
                        rs.getString("failure_reason")})
                .single();
        assertThat(row[0]).as("failure_reason=%s", row[6]).isEqualTo("AVAILABLE");
        assertThat(row[1]).isEqualTo(true);
        assertThat((long) row[2]).isEqualTo(gif.length);
        assertThat((int) row[3]).isEqualTo(640);
        assertThat((int) row[4]).isEqualTo(360);
        assertThat((String) row[5]).endsWith(".gif");

        try (InputStream input = storage.open(PHOTO_KEY)) {
            assertThat(input.readAllBytes()).isEqualTo(gif);
        }
        try (InputStream input = storage.open(THUMBNAIL_KEY)) {
            byte[] header = input.readNBytes(12);
            assertThat(new String(header, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WEBP");
        }

        var admin = new AuthenticatedUser(USER_ID, "animated-admin", "动图管理员", UserRole.ADMIN, null, false);
        PhotoService.PhotoView view = photos.get(PHOTO_ID, admin);
        assertThat(view.animated()).isTrue();
        assertThat(view.animationUrl()).isNotBlank();
        assertThat(view.thumbnailUrl()).isNotBlank().isNotEqualTo(view.animationUrl());
    }

    private void insertRows(byte[] gif) throws Exception {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled,
                     must_change_password, version, deleted)
                VALUES (:id, 'animated-admin', 'hash', '动图管理员', 'ADMIN', true, false, 1, false)
                """).param("id", USER_ID).update();
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by,
                     taken_at, size, content_type, object_key, original_object_key,
                     sha256, status, version, deleted)
                VALUES
                    (:id, '动图', '202697601', '拍摄者', :userId,
                     :takenAt, :size, 'image/gif', :objectKey, :originalKey,
                     :sha256, 'PROCESSING', 1, false)
                """)
                .param("id", PHOTO_ID)
                .param("userId", USER_ID)
                .param("takenAt", LocalDateTime.now().minusMinutes(1))
                .param("size", (long) gif.length)
                .param("objectKey", PHOTO_KEY)
                .param("originalKey", ORIGINAL_KEY)
                .param("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(gif)))
                .update();
    }
}
