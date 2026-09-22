package cn.photolib.photo.batch;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
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
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
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
    @Transactional
    void batchMetadata_shouldSkipDuplicateSha256() {
        String existingSha = "a".repeat(64);
        String newSha = "c".repeat(64);
        String duplicateLocalPath = processingProperties.temporaryRoot()
                .resolve("batches").resolve("batch-dedupe-test").resolve("existing.jpg").toString();
        jdbc.sql("""
                INSERT INTO photo
                    (photographer_student_id, photographer_name, uploaded_by, campus_id, taken_at,
                     size, content_type, object_key, sha256, status, version, deleted)
                VALUES ('20268102', '测试摄影师', 8101, 8100, CURRENT_TIMESTAMP,
                        100, 'image/jpeg', 'photos/batch-dedupe-existing.jpg', :sha,
                        'AVAILABLE', 1, false)
                """).param("sha", existingSha).update();
        jdbc.sql("""
                INSERT INTO photo_upload_batch
                    (id, mode, created_by, status, total_count, success_count, failure_count)
                VALUES ('batch-dedupe-test', 'ZIP', 8101, 'WAITING_METADATA', 3, 0, 0)
                """).update();
        jdbc.sql("""
                INSERT INTO photo_upload_item
                    (batch_id, original_file_name, temp_object_key, temp_local_path,
                     content_type, size, sha256, status)
                VALUES
                    ('batch-dedupe-test', 'existing.jpg',
                     'temporary/batches/batch-dedupe-test/existing.jpg',
                     :duplicateLocalPath, 'image/jpeg', 100, :existingSha, 'WAITING_METADATA'),
                    ('batch-dedupe-test', 'new.jpg',
                     'temporary/batches/batch-dedupe-test/new.jpg',
                     NULL, 'image/jpeg', 100, :newSha, 'WAITING_METADATA'),
                    ('batch-dedupe-test', 'new-again.jpg',
                     'temporary/batches/batch-dedupe-test/new-again.jpg',
                     NULL, 'image/jpeg', 100, :newSha, 'WAITING_METADATA')
                """).param("existingSha", existingSha).param("newSha", newSha)
                .param("duplicateLocalPath", duplicateLocalPath).update();

        var result = service.setMetadataForAll("batch-dedupe-test",
                new BatchUploadService.BatchMetadata(
                        "说明", 8102L, LocalDateTime.now(), List.of()),
                manager);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM photo WHERE sha256=:sha AND deleted=0")
                .param("sha", existingSha).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM photo WHERE sha256=:sha AND deleted=0")
                .param("sha", newSha).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT status FROM photo_upload_item
                WHERE batch_id='batch-dedupe-test' AND original_file_name='existing.jpg'
                """).query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc.sql("""
                SELECT failure_reason FROM photo_upload_item
                WHERE batch_id='batch-dedupe-test' AND original_file_name='existing.jpg'
                """).query(String.class).single()).contains("已存在");
        assertThat(jdbc.sql("""
                SELECT status FROM photo_upload_item
                WHERE batch_id='batch-dedupe-test' AND original_file_name='new-again.jpg'
                """).query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM photo_upload_item
                WHERE batch_id='batch-dedupe-test' AND original_file_name='existing.jpg'
                  AND temp_local_path IS NOT NULL
                """).query(Long.class).single()).isZero();
        assertThat(result.batch().getFailureCount()).isEqualTo(2);
        assertThat(result.batch().getStatus()).isEqualTo(BatchStatus.PROCESSING);
    }

    @Test
    @Transactional
    void zipRetry_shouldReimportImagesWhoseEarlierProcessingFailed() {
        // 第一次上传同一个 ZIP：succeeded.jpg 入库成功，failed.jpg 处理失败留下 UPLOADING + 失败原因。
        String succeededSha = "d".repeat(64);
        String failedSha = "e".repeat(64);
        insertPhoto("photos/batch-retry-succeeded.jpg", succeededSha, "AVAILABLE", null);
        insertPhoto("photos/batch-retry-failed.jpg", failedSha, "UPLOADING", "图片无法压缩至 10 MiB");
        insertWaitingBatch("batch-retry-test", 2);
        insertWaitingItem("batch-retry-test", "succeeded.jpg", succeededSha);
        insertWaitingItem("batch-retry-test", "failed.jpg", failedSha);

        var result = service.setMetadataForAll("batch-retry-test",
                new BatchUploadService.BatchMetadata("说明", 8102L, LocalDateTime.now(), List.of()),
                manager);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM photo WHERE sha256=:sha AND deleted=0")
                .param("sha", succeededSha).query(Long.class).single()).isEqualTo(1);
        assertThat(itemStatus("batch-retry-test", "succeeded.jpg")).isEqualTo("FAILED");
        assertThat(itemStatus("batch-retry-test", "failed.jpg")).isEqualTo("PROCESSING");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM photo
                WHERE sha256=:sha AND deleted=0 AND status='PROCESSING'
                """).param("sha", failedSha).query(Long.class).single()).isEqualTo(1);
        assertThat(result.batch().getFailureCount()).isEqualTo(1);
        assertThat(result.batch().getStatus()).isEqualTo(BatchStatus.PROCESSING);
    }

    @Test
    @Transactional
    void batchMetadata_allDuplicates_shouldFinishBatch() {
        String sha = "f".repeat(64);
        insertPhoto("photos/batch-all-dup-existing.jpg", sha, "AVAILABLE", null);
        insertWaitingBatch("batch-all-dup-test", 2);
        insertWaitingItem("batch-all-dup-test", "one.jpg", sha);
        insertWaitingItem("batch-all-dup-test", "two.jpg", sha);

        var result = service.setMetadataForAll("batch-all-dup-test",
                new BatchUploadService.BatchMetadata("说明", 8102L, LocalDateTime.now(), List.of()),
                manager);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM photo WHERE sha256=:sha AND deleted=0")
                .param("sha", sha).query(Long.class).single()).isEqualTo(1);
        assertThat(result.batch().getSuccessCount()).isZero();
        assertThat(result.batch().getFailureCount()).isEqualTo(2);
        assertThat(result.batch().getStatus()).isEqualTo(BatchStatus.PARTIALLY_SUCCEEDED);
    }

    @Test
    @Transactional
    void itemMetadata_duplicateLastItem_shouldFinishBatch() {
        String sha = "b".repeat(64);
        insertPhoto("photos/batch-item-dup-existing.jpg", sha, "AVAILABLE", null);
        insertWaitingBatch("batch-item-dup-test", 1);
        insertWaitingItem("batch-item-dup-test", "dup.jpg", sha);
        Long itemId = jdbc.sql("SELECT id FROM photo_upload_item WHERE batch_id='batch-item-dup-test'")
                .query(Long.class).single();

        var result = service.setMetadata("batch-item-dup-test", itemId,
                new BatchUploadService.ItemMetadata("标题", "说明", 8102L, LocalDateTime.now(), List.of()),
                manager);

        assertThat(itemStatus("batch-item-dup-test", "dup.jpg")).isEqualTo("FAILED");
        assertThat(result.batch().getFailureCount()).isEqualTo(1);
        assertThat(result.batch().getStatus()).isEqualTo(BatchStatus.PARTIALLY_SUCCEEDED);
    }

    private void insertPhoto(String objectKey, String sha, String status, String failureReason) {
        jdbc.sql("""
                INSERT INTO photo
                    (photographer_student_id, photographer_name, uploaded_by, campus_id, taken_at,
                     size, content_type, object_key, sha256, status, failure_reason, version, deleted)
                VALUES ('20268102', '测试摄影师', 8101, 8100, CURRENT_TIMESTAMP,
                        100, 'image/jpeg', :objectKey, :sha, :status, :failureReason, 1, false)
                """).param("objectKey", objectKey).param("sha", sha).param("status", status)
                .param("failureReason", failureReason).update();
    }

    private void insertWaitingBatch(String batchId, int total) {
        jdbc.sql("""
                INSERT INTO photo_upload_batch
                    (id, mode, created_by, status, total_count, success_count, failure_count)
                VALUES (:id, 'ZIP', 8101, 'WAITING_METADATA', :total, 0, 0)
                """).param("id", batchId).param("total", total).update();
    }

    private void insertWaitingItem(String batchId, String fileName, String sha) {
        jdbc.sql("""
                INSERT INTO photo_upload_item
                    (batch_id, original_file_name, temp_object_key, content_type, size, sha256, status)
                VALUES (:batchId, :fileName, :key, 'image/jpeg', 100, :sha, 'WAITING_METADATA')
                """).param("batchId", batchId).param("fileName", fileName)
                .param("key", "temporary/batches/" + batchId + "/" + fileName)
                .param("sha", sha).update();
    }

    private String itemStatus(String batchId, String fileName) {
        return jdbc.sql("""
                SELECT status FROM photo_upload_item
                WHERE batch_id=:batchId AND original_file_name=:fileName
                """).param("batchId", batchId).param("fileName", fileName)
                .query(String.class).single();
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
                SELECT sha256 FROM photo_upload_item
                WHERE batch_id = 'batch-unzip-test' ORDER BY id
                """).query(String.class).list())
                .containsExactly(sha256(new byte[] {1, 2, 3}), sha256(new byte[] {4, 5, 6}));
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
    void galleryBatchCannotInjectProjectMembershipWithPhotoUploadPermissionAlone() {
        jdbc.sql("""
                INSERT INTO project (id, title, description, status, created_by, version, deleted)
                VALUES (8200, '批量上传目标', '描述', 'ACTIVE', 8101, 1, false)
                """).update();
        var uploadOnly = new AuthenticatedUser(manager.id(), "batch-upload-only", "仅批量上传",
                UserRole.CAMPUS_MANAGER, null, false, -30L, "BATCH_UPLOAD_ONLY", "仅批量上传",
                DataScope.GLOBAL, Set.of(PermissionCode.PHOTO_UPLOAD), Set.of());

        assertThatThrownBy(() -> service.create(new BatchUploadService.CreateBatch(
                BatchMode.ZIP, null, 8200L, "injected.zip", 100L, null), uploadOnly))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权将批量上传图片加入项目相册");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM photo_upload_batch WHERE project_id=8200")
                .query(Long.class).single()).isZero();
    }

    @Test
    @Transactional
    void requestBatchAlwaysUsesTheRequestsPersistedProject() {
        jdbc.sql("""
                INSERT INTO project (id, title, description, status, created_by, version, deleted)
                VALUES
                    (8201, '真实需求项目', '描述', 'ACTIVE', 8101, 1, false),
                    (8202, '客户端伪造项目', '描述', 'ACTIVE', 8101, 1, false)
                """).update();
        jdbc.sql("""
                INSERT INTO photo_request
                    (id, project_id, title, campus_id, deadline, status, created_by, version, deleted)
                VALUES (8203, 8201, '批量上传需求', 8100,
                        DATEADD('DAY', 1, CURRENT_TIMESTAMP), 'ACCEPTED', 8101, 1, false)
                """).update();
        jdbc.sql("""
                INSERT INTO request_participant(request_id, user_id, accepted_at)
                VALUES (8203, 8101, CURRENT_TIMESTAMP)
                """).update();

        var ticket = service.create(new BatchUploadService.CreateBatch(
                BatchMode.ZIP, 8203L, 8202L, "request.zip", 100L, null), manager);

        assertThat(jdbc.sql("SELECT project_id FROM photo_upload_batch WHERE id=:id")
                .param("id", ticket.batchId()).query(Long.class).single()).isEqualTo(8201L);
    }

    @Test
    @Transactional
    void requestBatchIsRejectedAfterParticipantLosesTheRequestsCampus() {
        jdbc.sql("INSERT INTO campus (id, code, name, enabled) VALUES (8103, 'BATCH-MOVED', '迁移后校区', true)")
                .update();
        jdbc.sql("""
                INSERT INTO project (id, title, description, status, created_by, version, deleted)
                VALUES (8204, '撤销校区项目', '描述', 'ACTIVE', 8101, 1, false)
                """).update();
        jdbc.sql("""
                INSERT INTO photo_request
                    (id, project_id, title, campus_id, deadline, status, created_by, version, deleted)
                VALUES (8205, 8204, '撤销校区需求', 8100,
                        DATEADD('DAY', 1, CURRENT_TIMESTAMP), 'ACCEPTED', 8101, 1, false)
                """).update();
        jdbc.sql("""
                INSERT INTO request_participant(request_id, user_id, accepted_at)
                VALUES (8205, 8101, CURRENT_TIMESTAMP)
                """).update();
        var movedManager = new AuthenticatedUser(manager.id(), manager.username(), manager.displayName(),
                UserRole.CAMPUS_MANAGER, 8103L, false, -31L, "MOVED_BATCH", "已迁移负责人",
                DataScope.CAMPUS, Set.of(PermissionCode.REQUEST_PHOTO_MANAGE), Set.of(8103L));

        assertThatThrownBy(() -> service.create(new BatchUploadService.CreateBatch(
                BatchMode.ZIP, 8205L, null, "revoked.zip", 100L, null), movedManager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权操作该校区");
    }

    @Test
    @Transactional
    void galleryBatchCannotUseRequestPermissionForFollowUpCalls() {
        var galleryUploader = new AuthenticatedUser(manager.id(), manager.username(), manager.displayName(),
                UserRole.CAMPUS_MANAGER, 8100L, false, -32L, "GALLERY_BATCH", "图库批量上传",
                DataScope.CAMPUS, Set.of(PermissionCode.PHOTO_UPLOAD), Set.of(8100L));
        var ticket = service.create(new BatchUploadService.CreateBatch(
                BatchMode.ZIP, null, null, "gallery.zip", 100L, null), galleryUploader);
        var wrongPermission = new AuthenticatedUser(manager.id(), manager.username(), manager.displayName(),
                UserRole.CAMPUS_MANAGER, 8100L, false, -32L, "GALLERY_BATCH", "图库批量上传",
                DataScope.CAMPUS, Set.of(PermissionCode.REQUEST_PHOTO_MANAGE), Set.of(8100L));

        assertThatThrownBy(() -> service.get(ticket.batchId(), wrongPermission))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前权限不能继续处理");
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

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
