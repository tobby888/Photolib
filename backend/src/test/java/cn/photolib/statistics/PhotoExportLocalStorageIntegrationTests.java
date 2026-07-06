package cn.photolib.statistics;

import cn.photolib.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PhotoExportLocalStorageIntegrationTests {
    private static final Path STORAGE_DIRECTORY = createStorageDirectory();

    @DynamicPropertySource
    static void localStorage(DynamicPropertyRegistry properties) {
        properties.add("photolib.storage.mode", () -> "local");
        properties.add("photolib.storage.local-directory", STORAGE_DIRECTORY::toString);
        properties.add("photolib.storage.public-base-url",
                () -> "http://localhost:8080/api/v1/local-storage/objects");
        properties.add("photolib.storage.signing-secret", () -> "integration-test-secret-32chars-minimum");
    }

    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectStorageService storage;
    @Autowired
    ExportService exports;

    @Test
    void exportsSelectedPhotosAsReadableZip() throws Exception {
        Long userId = Math.abs(System.nanoTime());
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', '导出测试员', 'ADMIN', TRUE, FALSE)
                """).param("id", userId).param("username", "export-test-" + userId).update();

        byte[] image = "local-photo-bytes".getBytes(StandardCharsets.UTF_8);
        String objectKey = "photos/2026/export-test.jpg";
        storage.put(objectKey, new ByteArrayInputStream(image), image.length, "image/jpeg");

        Long photoId = userId + 1;
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by, taken_at,
                     size, content_type, object_key, stored_file_name, sha256, status)
                VALUES
                    (:id, '本地导出测试图', '20260001', '测试摄影师', :userId, :takenAt,
                     :size, 'image/jpeg', :objectKey, 'result.jpg', :sha256, 'AVAILABLE')
                """)
                .param("id", photoId)
                .param("userId", userId)
                .param("takenAt", LocalDateTime.now())
                .param("size", image.length)
                .param("objectKey", objectKey)
                .param("sha256", "0".repeat(64))
                .update();

        String jobId = "LEXP" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO export_job (id, type, status, progress, created_by)
                VALUES (:id, 'PHOTO_BATCH', 'PENDING', 0, :userId)
                """).param("id", jobId).param("userId", userId).update();

        exports.exportPhotos(new ExportService.PhotoZipRequested(jobId, List.of(photoId)));

        ExportJobEntity job = waitForCompletion(jobId);
        assertThat(job.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(job.getProgress()).isEqualTo(100);
        assertThat(job.getObjectKey()).isEqualTo("exports/" + jobId + ".zip");

        try (ZipInputStream zip = new ZipInputStream(storage.open(job.getObjectKey()));
             ByteArrayOutputStream extracted = new ByteArrayOutputStream()) {
            assertThat(zip.getNextEntry().getName()).isEqualTo(photoId + "-result.jpg");
            zip.transferTo(extracted);
            assertThat(extracted.toByteArray()).isEqualTo(image);
            assertThat(zip.getNextEntry()).isNull();
        }
    }

    private ExportJobEntity waitForCompletion(String jobId) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            ExportJobEntity job = jdbc.sql("""
                    SELECT * FROM export_job WHERE id=:id
                    """).param("id", jobId).query(ExportJobEntity.class).single();
            if (!"PENDING".equals(job.getStatus())) return job;
            Thread.sleep(100);
        }
        return jdbc.sql("SELECT * FROM export_job WHERE id=:id")
                .param("id", jobId).query(ExportJobEntity.class).single();
    }

    private static Path createStorageDirectory() {
        try {
            return Files.createTempDirectory("photolib-local-storage-test-");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
