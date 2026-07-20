package cn.photolib.photo.batch;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.photo.PhotoProcessingProperties;
import cn.photolib.photo.PhotoProcessingWorkspace;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class BatchUploadServiceTests {
    @Autowired
    private BatchUploadService service;
    @Autowired
    private BatchProcessingService processingService;
    @Autowired
    private ObjectStorageService storage;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PhotoUploadBatchMapper batchMapper;
    @Autowired
    private PhotoProcessingWorkspace workspace;
    @Autowired
    private PhotoProcessingProperties processingProperties;

    private AuthenticatedUser manager;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO campus (id, code, name, enabled, version, deleted)
                VALUES (8100, 'BATCH', '批量上传校区', true, 1, false)
                """).update();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id,
                     enabled, must_change_password, version, deleted)
                VALUES
                    (8101, 'batch-manager', 'hash', '批量上传负责人', 'CAMPUS_MANAGER', 8100,
                     true, false, 1, false)
                """).update();
        jdbc.sql("""
                INSERT INTO campus_member
                    (id, campus_id, student_id, name, enabled, version, deleted)
                VALUES (8102, 8100, '20268102', '测试拍摄者', true, 1, false)
                """).update();
        manager = new AuthenticatedUser(
                8101L, "batch-manager", "批量上传负责人", UserRole.CAMPUS_MANAGER, 8100L, false);
    }

    @AfterEach
    void cleanUpCommittedFixture() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return;
        jdbc.sql("""
                SELECT temp_local_path FROM photo_upload_item
                WHERE batch_id IN ('batch-unzip-test', 'batch-unzip-rollback')
                  AND temp_local_path IS NOT NULL
                """).query(String.class).list().forEach(value -> {
            Path path = Path.of(value);
            if (Files.exists(path)) workspace.deleteBatchFile(path);
        });
        deleteObject("temporary/batches/batch-unzip-test/archive.zip");
        deleteObject("temporary/batches/batch-unzip-rollback/archive.zip");
        jdbc.sql("DELETE FROM photo_upload_item WHERE batch_id IN "
                + "('batch-unzip-test', 'batch-unzip-rollback')").update();
        jdbc.sql("DELETE FROM photo_upload_batch WHERE created_by = 8101").update();
        jdbc.sql("DELETE FROM campus_member WHERE id = 8102").update();
        jdbc.sql("DELETE FROM app_user WHERE id = 8101").update();
        jdbc.sql("DELETE FROM campus WHERE id = 8100").update();
    }

    @Test
    @Transactional
    void batchMetadata_shouldUseOriginalFileNamesAsTitles() {
        jdbc.sql("""
                INSERT INTO photo_upload_batch
                    (id, mode, created_by, status, total_count, success_count, failure_count)
                VALUES ('batch-title-test', 'ZIP', 8101, 'WAITING_METADATA', 2, 0, 0)
                """).update();
        jdbc.sql("""
                INSERT INTO photo_upload_item
                    (batch_id, original_file_name, temp_object_key, content_type, size, status)
                VALUES
                    ('batch-title-test', '校园活动.终稿.jpg', 'temporary/one.jpg',
                     'image/jpeg', 100, 'WAITING_METADATA'),
                    ('batch-title-test', '合影.png', 'temporary/two.png',
                     'image/png', 200, 'WAITING_METADATA')
                """).update();

        BatchUploadService.BatchView result = service.setMetadataForAll(
                "batch-title-test",
                new BatchUploadService.BatchMetadata(
                        "统一说明", 8102L, LocalDateTime.now(), List.of("活动")),
                manager);

        assertThat(result.batch().getStatus()).isEqualTo(BatchStatus.PROCESSING);
        assertThat(jdbc.sql("SELECT title FROM photo ORDER BY id")
                .query(String.class).list())
                .containsExactly("校园活动.终稿", "合影");
        assertThat(jdbc.sql("SELECT title FROM photo_upload_item ORDER BY id")
                .query(String.class).list())
                .containsExactly("校园活动.终稿", "合影");
        assertThat(jdbc.sql("SELECT photographer_student_id FROM photo ORDER BY id")
                .query(String.class).list())
                .containsOnly("20268102");
    }

    @Test
    void processZip_shouldExtractSupportedImagesOnBackend() throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        String archiveKey = "temporary/batches/batch-unzip-test/archive.zip";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("活动/第一张.jpg"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("说明.txt"));
            zip.write("ignored".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("活动/第二张.png"));
            zip.write(new byte[] {4, 5, 6});
            zip.closeEntry();
        }
        storage.put(archiveKey, new ByteArrayInputStream(bytes.toByteArray()),
                bytes.size(), "application/zip");
        jdbc.sql("""
                INSERT INTO photo_upload_batch
                    (id, mode, created_by, archive_object_key, archive_file_name, archive_size,
                     status, total_count, success_count, failure_count)
                VALUES
                    ('batch-unzip-test', 'ZIP', 8101, :archiveKey, '活动.zip', :archiveSize,
                     'PROCESSING', 0, 0, 0)
                """)
                .param("archiveKey", archiveKey)
                .param("archiveSize", bytes.size())
                .update();

        processingService.processZip("batch-unzip-test");

        assertThat(jdbc.sql("SELECT status FROM photo_upload_batch WHERE id = 'batch-unzip-test'")
                .query(String.class).single()).isEqualTo("WAITING_METADATA");
        assertThat(jdbc.sql("""
                SELECT original_file_name FROM photo_upload_item
                WHERE batch_id = 'batch-unzip-test' ORDER BY id
                """).query(String.class).list())
                .containsExactly("第一张.jpg", "第二张.png");
        var localItems = jdbc.sql("""
                SELECT temp_local_path, temp_object_key FROM photo_upload_item
                WHERE batch_id = 'batch-unzip-test' ORDER BY id
                """).query((rs, rowNum) -> new String[]{
                        rs.getString("temp_local_path"), rs.getString("temp_object_key")}).list();
        assertThat(localItems).hasSize(2);
        for (String[] item : localItems) {
            Path path = Path.of(item[0]);
            assertThat(path).isRegularFile();
            assertThat(Files.size(path)).isEqualTo(3);
            assertThatThrownBy(() -> storage.stat(item[1]))
                    .isInstanceOf(IllegalArgumentException.class);
            workspace.deleteBatchFile(path);
        }
        assertThat(jdbc.sql("""
                SELECT archive_object_key FROM photo_upload_batch
                WHERE id = 'batch-unzip-test'
                """).query(String.class).optional()).isEmpty();
        assertThatThrownBy(() -> storage.stat(archiveKey))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processZip_shouldRollbackAllItemsWhenShortPersistenceTransactionFails() throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        String batchId = "batch-unzip-rollback";
        String archiveKey = "temporary/batches/" + batchId + "/archive.zip";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("first.jpg"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("x".repeat(260) + ".jpg"));
            zip.write(new byte[] {4, 5, 6});
            zip.closeEntry();
        }
        storage.put(archiveKey, new ByteArrayInputStream(bytes.toByteArray()),
                bytes.size(), "application/zip");
        jdbc.sql("""
                INSERT INTO photo_upload_batch
                    (id, mode, created_by, archive_object_key, archive_file_name, archive_size,
                     status, total_count, success_count, failure_count)
                VALUES
                    (:id, 'ZIP', 8101, :archiveKey, 'rollback.zip', :archiveSize,
                     'PROCESSING', 0, 0, 0)
                """)
                .param("id", batchId)
                .param("archiveKey", archiveKey)
                .param("archiveSize", bytes.size())
                .update();

        processingService.processZip(batchId);

        assertThat(jdbc.sql("SELECT status FROM photo_upload_batch WHERE id = :id")
                .param("id", batchId).query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM photo_upload_item WHERE batch_id = :id")
                .param("id", batchId).query(Long.class).single()).isZero();
        assertThat(Files.exists(processingProperties.temporaryRoot()
                .resolve("batches").resolve(batchId))).isFalse();
        assertThatThrownBy(() -> storage.stat(archiveKey))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional
    void processZip_shouldRejectInvocationInsideCallerTransaction() {
        assertThatThrownBy(() -> processingService.processZip("not-needed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能在数据库事务中执行");
    }

    @Test
    @Transactional
    void zipTicket_shouldKeepOnePointFiveGigabyteLimit() {
        BatchUploadService.CreateBatch atLimit = new BatchUploadService.CreateBatch(
                BatchMode.ZIP, null, null, "photos.zip", 1_500_000_000L, null);

        assertThat(service.create(atLimit, manager).batchId()).isNotBlank();

        BatchUploadService.CreateBatch overLimit = new BatchUploadService.CreateBatch(
                BatchMode.ZIP, null, null, "too-large.zip", 1_500_000_001L, null);
        assertThatThrownBy(() -> service.create(overLimit, manager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1.5 GB");
    }

    @Test
    @Transactional
    void batchStatusTransition_shouldOnlyBeClaimedOnce() {
        jdbc.sql("""
                INSERT INTO photo_upload_batch
                    (id, mode, created_by, status, total_count, success_count, failure_count)
                VALUES ('batch-claim-test', 'ZIP', 8101, 'WAITING_METADATA', 1, 0, 0)
                """).update();

        assertThat(batchMapper.transition("batch-claim-test", BatchStatus.WAITING_METADATA,
                BatchStatus.PROCESSING, LocalDateTime.now())).isEqualTo(1);
        assertThat(batchMapper.transition("batch-claim-test", BatchStatus.WAITING_METADATA,
                BatchStatus.PROCESSING, LocalDateTime.now())).isZero();
    }

    private void deleteObject(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException ignored) {
            // Cleanup is best effort when processing already removed the object.
        }
    }
}
