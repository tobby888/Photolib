package cn.photolib.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PhotoStorageReconciliationServiceTests {
    @Autowired
    private PhotoStorageReconciliationService reconciliation;
    @Autowired
    private ObjectStorageService storage;
    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void createUploader() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES
                    (91999, 'storage-reconciliation-user', 'hash', 'storage test',
                     'ADMIN', true, false)
                """).update();
    }

    @Test
    void marksDatabasePhotoDeletedWhenMainObjectIsMissing() {
        insertPhoto(91001L, "photos/reconciliation/missing.jpg", 99, "image/jpeg",
                "thumbnails/reconciliation/missing.jpg");

        var result = reconciliation.reconcile();

        var row = jdbc.sql("SELECT deleted, status, failure_reason FROM photo WHERE id=91001")
                .query((rs, rowNum) -> new Object[]{
                        rs.getBoolean("deleted"), rs.getString("status"), rs.getString("failure_reason")})
                .single();
        assertThat(row[0]).isEqualTo(true);
        assertThat(row[1]).isEqualTo("DELETED");
        assertThat(row[2]).isEqualTo("对象存储中的图片文件不存在");
        assertThat(result.missing()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void refreshesMetadataAndClearsMissingThumbnailFromStorageReality() {
        String key = "photos/reconciliation/present.jpg";
        byte[] bytes = "real-file-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
        insertPhoto(91002L, key, 1, "application/octet-stream",
                "thumbnails/reconciliation/absent.jpg");

        reconciliation.reconcile();

        var row = jdbc.sql("""
                        SELECT size, content_type, thumbnail_object_key
                        FROM photo WHERE id=91002
                        """)
                .query((rs, rowNum) -> new Object[]{
                        rs.getLong("size"), rs.getString("content_type"),
                        rs.getString("thumbnail_object_key")})
                .single();
        assertThat(row[0]).isEqualTo((long) bytes.length);
        assertThat(row[1]).isEqualTo("image/jpeg");
        assertThat(row[2]).isNull();
        storage.delete(key);
    }

    private void insertPhoto(long id, String key, long size, String contentType, String thumbnailKey) {
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by,
                     taken_at, size, content_type, object_key, thumbnail_object_key,
                     sha256, status, version, deleted)
                VALUES
                    (:id, 'reconciliation test', 'test', 'test', 91999, CURRENT_TIMESTAMP,
                     :size, :contentType, :objectKey, :thumbnailKey, :sha256,
                     'AVAILABLE', 1, false)
                """)
                .param("id", id)
                .param("size", size)
                .param("contentType", contentType)
                .param("objectKey", key)
                .param("thumbnailKey", thumbnailKey)
                .param("sha256", "a".repeat(64))
                .update();
    }
}
