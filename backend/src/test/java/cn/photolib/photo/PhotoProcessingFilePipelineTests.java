package cn.photolib.photo;

import cn.photolib.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PhotoProcessingFilePipelineTests {
    private static final long USER_ID = 93901L;
    private static final long PHOTO_ID = 93902L;
    private static final String BATCH_ID = "batch-file-pipeline";
    private static final String ORIGINAL_KEY = "temporary/batches/batch-file-pipeline/original.jpg";
    private static final String PHOTO_KEY = "photos/2026/file-pipeline.jpg";
    private static final String THUMBNAIL_KEY = "thumbnails/93902.jpg";

    @Autowired
    private PhotoProcessingService processing;
    @Autowired
    private PhotoProcessingWorkspace workspace;
    @Autowired
    private ObjectStorageService storage;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void processesZipLocalFileUploadsAllObjectsAndDeletesAuxiliaryFiles() throws Exception {
        Path source = workspace.createBatchFile(BATCH_ID, ".jpg");
        writeJpeg(source);
        long sourceSize = Files.size(source);
        insertRows(source, sourceSize);

        try {
            processing.submit(PHOTO_ID).get(30, TimeUnit.SECONDS);

            var photo = jdbc.sql("""
                    SELECT status, size, width, height, thumbnail_size, original_delete_after
                    FROM photo WHERE id=:id
                    """).param("id", PHOTO_ID)
                    .query((rs, rowNum) -> new Object[]{
                            rs.getString("status"), rs.getLong("size"),
                            rs.getInt("width"), rs.getInt("height"),
                            rs.getLong("thumbnail_size"), rs.getTimestamp("original_delete_after")})
                    .single();
            assertThat(photo[0]).isEqualTo("AVAILABLE");
            assertThat((long) photo[1]).isEqualTo(storage.stat(PHOTO_KEY).size());
            assertThat((int) photo[2]).isEqualTo(1200);
            assertThat((int) photo[3]).isEqualTo(800);
            assertThat((long) photo[4]).isEqualTo(storage.stat(THUMBNAIL_KEY).size());
            assertThat(photo[5]).isNotNull();
            assertThat(storage.stat(ORIGINAL_KEY).size()).isEqualTo(sourceSize);
            assertJpeg(PHOTO_KEY);
            assertJpeg(THUMBNAIL_KEY);

            var item = jdbc.sql("""
                    SELECT status, temp_local_path FROM photo_upload_item
                    WHERE batch_id=:batchId
                    """).param("batchId", BATCH_ID)
                    .query((rs, rowNum) -> new String[]{
                            rs.getString("status"), rs.getString("temp_local_path")})
                    .single();
            assertThat(item[0]).isEqualTo("SUCCEEDED");
            assertThat(item[1]).isNull();
            assertThat(Files.exists(source)).isFalse();
            assertThat(jdbc.sql("SELECT status FROM photo_upload_batch WHERE id=:id")
                    .param("id", BATCH_ID).query(String.class).single()).isEqualTo("SUCCEEDED");
            try (Stream<Path> files = Files.walk(workspace.root().resolve("tasks"))) {
                assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
            }
        } finally {
            deleteObject(THUMBNAIL_KEY);
            deleteObject(PHOTO_KEY);
            deleteObject(ORIGINAL_KEY);
            jdbc.sql("DELETE FROM photo_upload_item WHERE batch_id=:id").param("id", BATCH_ID).update();
            jdbc.sql("DELETE FROM photo_upload_batch WHERE id=:id").param("id", BATCH_ID).update();
            jdbc.sql("DELETE FROM photo WHERE id=:id").param("id", PHOTO_ID).update();
            jdbc.sql("DELETE FROM app_user WHERE id=:id").param("id", USER_ID).update();
            if (Files.exists(source)) workspace.deleteBatchFile(source);
        }
    }

    private void insertRows(Path source, long sourceSize) {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled,
                     must_change_password, version, deleted)
                VALUES
                    (:id, 'file-pipeline-user', 'hash', 'file pipeline', 'ADMIN', true,
                     false, 1, false)
                """).param("id", USER_ID).update();
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by,
                     taken_at, size, content_type, object_key, original_object_key,
                     sha256, status, version, deleted)
                VALUES
                    (:id, 'file pipeline', '202693901', 'file photographer', :userId,
                     :takenAt, :size, 'image/jpeg', :objectKey, :originalKey,
                     :sha256, 'PROCESSING', 1, false)
                """)
                .param("id", PHOTO_ID)
                .param("userId", USER_ID)
                .param("takenAt", LocalDateTime.now().minusMinutes(1))
                .param("size", sourceSize)
                .param("objectKey", PHOTO_KEY)
                .param("originalKey", ORIGINAL_KEY)
                .param("sha256", "0".repeat(64))
                .update();
        jdbc.sql("""
                INSERT INTO photo_upload_batch
                    (id, mode, created_by, status, total_count, success_count, failure_count)
                VALUES (:id, 'ZIP', :userId, 'PROCESSING', 1, 0, 0)
                """).param("id", BATCH_ID).param("userId", USER_ID).update();
        jdbc.sql("""
                INSERT INTO photo_upload_item
                    (batch_id, original_file_name, temp_object_key, temp_local_path,
                     content_type, size, status, photo_id)
                VALUES
                    (:batchId, 'source.jpg', :objectKey, :localPath,
                     'image/jpeg', :size, 'PROCESSING', :photoId)
                """)
                .param("batchId", BATCH_ID)
                .param("objectKey", ORIGINAL_KEY)
                .param("localPath", source.toString())
                .param("size", sourceSize)
                .param("photoId", PHOTO_ID)
                .update();
    }

    private void writeJpeg(Path path) throws Exception {
        BufferedImage image = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, new Color((x * 17 + y) & 255,
                        (x + y * 11) & 255, (x * y) & 255).getRGB());
            }
        }
        ImageIO.write(image, "jpg", path.toFile());
    }

    private void assertJpeg(String objectKey) throws Exception {
        try (InputStream input = storage.open(objectKey)) {
            byte[] magic = input.readNBytes(3);
            assertThat(magic).containsExactly((byte) 0xff, (byte) 0xd8, (byte) 0xff);
        }
    }

    private void deleteObject(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException ignored) {
            // Test cleanup is best effort when the object was never created.
        }
    }
}
