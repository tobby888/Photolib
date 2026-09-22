package cn.photolib.share;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.photo.PhotoProcessingWorkspace;
import cn.photolib.photo.batch.BatchProcessingService;
import cn.photolib.photo.batch.BatchStatus;
import cn.photolib.photo.batch.BatchUploadService;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.project.model.ProjectType;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上传链接的 ZIP 批量：解包这一步必须在事务之外跑（{@code BatchProcessingService.processZip}
 * 自己会拒绝带事务的调用），所以这个类不是 {@code @Transactional} 的，夹具靠 {@link #cleanUp}
 * 自己收拾——与 {@code BatchUploadServiceTests} 同一个写法。
 *
 * <p>这里验的是"访客的 ZIP 和站内的 ZIP 是同一条通道"：同一个解包器、同一套限额、
 * 同一张批次表，区别只在入口的授权和落照片时那份拍摄者快照。</p>
 */
@SpringBootTest
class ProjectShareZipUploadTests {
    private static final long MINISTER_ID = 7_901L;
    private static final String PASSWORD = "zip-upload-pass";

    @Autowired private ProjectShareService shareService;
    @Autowired private ProjectShareUploadService uploadService;
    @Autowired private BatchProcessingService processingService;
    @Autowired private ProjectService projectService;
    @Autowired private ObjectStorageService storage;
    @Autowired private PhotoProcessingWorkspace workspace;
    @Autowired private JdbcClient jdbc;
    @Autowired @Qualifier("photoProcessingExecutor") private ThreadPoolTaskExecutor processingExecutor;

    private AuthenticatedUser minister;
    private ProjectEntity event;
    private ProjectShareService.CreatedShareLink link;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled,
                     must_change_password, version, deleted)
                VALUES (:id, 'zip-share-minister', 'hash', '打包上传部长', 'MINISTER', null, TRUE,
                        FALSE, 1, FALSE)
                """).param("id", MINISTER_ID).update();
        minister = new AuthenticatedUser(MINISTER_ID, "zip-share-minister", "打包上传部长",
                UserRole.MINISTER, null, false);
        event = projectService.create("打包上传活动", "说明", ProjectStatus.ACTIVE, List.of(),
                ProjectType.EVENT, minister);
        link = shareService.create(event.getId(), new ProjectShareService.CreateCommand(
                ShareLinkPurpose.UPLOAD, "摄影组", PASSWORD, false, false, null), minister);
    }

    @AfterEach
    void cleanUp() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return;
        awaitPhotoProcessing();
        jdbc.sql("""
                SELECT temp_local_path FROM photo_upload_item
                WHERE batch_id IN (SELECT id FROM photo_upload_batch WHERE created_by = :user)
                  AND temp_local_path IS NOT NULL
                """).param("user", MINISTER_ID).query(String.class).list().forEach(value -> {
            Path path = Path.of(value);
            if (Files.exists(path)) workspace.deleteBatchFile(path);
        });
        jdbc.sql("""
                DELETE FROM photo_upload_item WHERE batch_id IN
                    (SELECT id FROM photo_upload_batch WHERE created_by = :user)
                """).param("user", MINISTER_ID).update();
        jdbc.sql("DELETE FROM photo_upload_batch WHERE created_by = :user")
                .param("user", MINISTER_ID).update();
        jdbc.sql("DELETE FROM photo_project WHERE project_id = :project")
                .param("project", event.getId()).update();
        jdbc.sql("DELETE FROM photo WHERE uploaded_by = :user").param("user", MINISTER_ID).update();
        jdbc.sql("""
                DELETE FROM project_share_session WHERE link_id IN
                    (SELECT id FROM project_share_link WHERE project_id = :project)
                """).param("project", event.getId()).update();
        jdbc.sql("DELETE FROM project_share_link WHERE project_id = :project")
                .param("project", event.getId()).update();
        jdbc.sql("DELETE FROM project WHERE id = :project").param("project", event.getId()).update();
        jdbc.sql("DELETE FROM app_user WHERE id = :user").param("user", MINISTER_ID).update();
    }

    /**
     * finishZip 把照片交给异步处理池，工作线程会打开批次临时文件。Windows 上删不掉仍被打开的文件，
     * 清理若抢在处理前面就会抛 AccessDenied，后面的删行全被跳过，下一个用例的 setUp 撞上残留夹具。
     */
    private void awaitPhotoProcessing() {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            // 提交发生在 finishZip 的提交后回调里、调用线程上，返回时任务已经进了队列。
            if (processingExecutor.getActiveCount() == 0
                    && processingExecutor.getThreadPoolExecutor().getQueue().isEmpty()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException("等待照片异步处理结束超时");
    }

    private ProjectShareService.GuestContext guest() {
        ProjectShareService.GuestSession session = shareService.openSession(link.link().token(),
                PASSWORD, new ProjectShareService.GuestIdentity("周打包", "20230009"));
        return shareService.resolveGuestContext(link.link().token(), session.sessionToken());
    }

    private byte[] archive() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("活动/第一张.jpg"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
            // 非图片条目由解包器静默跳过，和站内一样。
            zip.putNextEntry(new ZipEntry("说明.txt"));
            zip.write("ignored".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("活动/第二张.png"));
            zip.write(new byte[] {4, 5, 6});
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    void aGuestZipGoesThroughTheSameExtractionPipelineAsAnInAppBatch() throws Exception {
        ProjectShareService.GuestContext context = guest();
        byte[] bytes = archive();

        BatchUploadService.BatchTicket ticket = uploadService.createZipTicket(context,
                new ProjectShareUploadService.ZipCommand("活动.zip", (long) bytes.length));
        assertThat(ticket.tickets()).singleElement()
                .extracting(BatchUploadService.ItemTicket::contentType).isEqualTo("application/zip");

        // 模拟浏览器把压缩包 PUT 到预签名地址上。
        String archiveKey = jdbc.sql("SELECT archive_object_key FROM photo_upload_batch WHERE id=:id")
                .param("id", ticket.batchId()).query(String.class).single();
        storage.put(archiveKey, new ByteArrayInputStream(bytes), bytes.length, "application/zip");

        uploadService.completeZip(context, ticket.batchId());
        // 站内是 @Async 的事件监听器接手，这里直接调同一个方法，省掉等异步。
        processingService.processZip(ticket.batchId());

        ProjectShareUploadService.GuestBatch extracted =
                uploadService.batchStatus(context, ticket.batchId());
        assertThat(extracted.status()).isEqualTo(BatchStatus.WAITING_METADATA);
        assertThat(extracted.totalCount()).isEqualTo(2);

        uploadService.finishZip(context, ticket.batchId(), null);

        // 落成的照片：标题是包内原文件名，拍摄者是会话身份，口子指回这条链接。
        assertThat(jdbc.sql("""
                        SELECT p.title FROM photo p JOIN photo_project pp ON pp.photo_id = p.id
                        WHERE pp.project_id = :project ORDER BY p.id
                        """).param("project", event.getId()).query(String.class).list())
                .containsExactly("第一张", "第二张");
        assertThat(jdbc.sql("""
                        SELECT photographer_student_id FROM photo WHERE share_link_id = :link
                        """).param("link", link.link().id()).query(String.class).list())
                .containsOnly("20230009");
        assertThat(jdbc.sql("SELECT upload_count FROM project_share_link WHERE id=:id")
                .param("id", link.link().id()).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void aZipWithNoImagesInsideFailsWithAReasonTheGuestCanSee() throws Exception {
        ProjectShareService.GuestContext context = guest();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("说明.txt"));
            zip.write("nothing to see".getBytes());
            zip.closeEntry();
        }
        BatchUploadService.BatchTicket ticket = uploadService.createZipTicket(context,
                new ProjectShareUploadService.ZipCommand("空的.zip", (long) bytes.size()));
        String archiveKey = jdbc.sql("SELECT archive_object_key FROM photo_upload_batch WHERE id=:id")
                .param("id", ticket.batchId()).query(String.class).single();
        storage.put(archiveKey, new ByteArrayInputStream(bytes.toByteArray()), bytes.size(),
                "application/zip");

        uploadService.completeZip(context, ticket.batchId());
        processingService.processZip(ticket.batchId());

        ProjectShareUploadService.GuestBatch failed =
                uploadService.batchStatus(context, ticket.batchId());
        assertThat(failed.status()).isEqualTo(BatchStatus.FAILED);
        // 访客只知道"传完了"是不够的：这一包根本没进相册，必须把原因说出来。
        assertThat(failed.failureReason()).contains("没有 JPG/PNG 图片");
        assertThatThrownBy(() -> uploadService.finishZip(context, ticket.batchId(), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未完成解压");
    }
}
