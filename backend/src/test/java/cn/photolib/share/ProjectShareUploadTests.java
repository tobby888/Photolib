package cn.photolib.share;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.upload.ImageUploadPolicy;
import cn.photolib.common.util.PublicId;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.project.model.ProjectType;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 活动选题的上传链接。
 *
 * <p>用例围着 {@link ProjectShareUploadService} 的四条不变量转，外加一条贯穿两半的：
 * <b>看和传是两种链接，能力互斥</b>——上传链接翻不到相册，浏览链接签不出票据。</p>
 */
@SpringBootTest
@Transactional
class ProjectShareUploadTests {
    private static final long MINISTER_ID = 7_801L;
    private static final String PASSWORD = "upload-pass";

    @Autowired private ProjectShareService shareService;
    @Autowired private ProjectShareUploadService uploadService;
    @Autowired private ProjectService projectService;
    @Autowired private ObjectStorageService storage;
    @Autowired private JdbcClient jdbc;

    private AuthenticatedUser minister;
    private ProjectEntity event;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES (:id, 'upload-minister', 'hash', '上传部长', 'MINISTER', null, TRUE, FALSE)
                """).param("id", MINISTER_ID).update();
        minister = new AuthenticatedUser(MINISTER_ID, "upload-minister", "上传部长",
                UserRole.MINISTER, null, false);
        event = projectService.create("校运会", "活动选题", ProjectStatus.ACTIVE, List.of(),
                ProjectType.EVENT, minister);
    }

    // ------------------------------------------------------------------ 夹具

    private ProjectShareService.CreatedShareLink uploadLink() {
        return uploadLink(event.getId());
    }

    private ProjectShareService.CreatedShareLink uploadLink(Long projectId) {
        return shareService.create(projectId, new ProjectShareService.CreateCommand(
                ShareLinkPurpose.UPLOAD, "摄影组", PASSWORD, false, false, null), minister);
    }

    private ProjectShareService.GuestContext guest(ProjectShareService.CreatedShareLink created) {
        ProjectShareService.GuestSession session = shareService.openSession(created.link().token(),
                PASSWORD, new ProjectShareService.GuestIdentity("张小拍", "20230001"));
        return shareService.resolveGuestContext(created.link().token(), session.sessionToken());
    }

    private ProjectShareUploadService.TicketCommand file(String sha) {
        return new ProjectShareUploadService.TicketCommand(
                "field.jpg", "image/jpeg", 2_048L, sha.repeat(64), LocalDateTime.now());
    }

    /** 模拟浏览器已经把字节 PUT 到预签名地址上了。 */
    private void putOriginal(Long photoId) {
        String originalKey = jdbc.sql("SELECT original_object_key FROM photo WHERE id=:id")
                .param("id", photoId).query(String.class).single();
        byte[] bytes = "non-empty-original".getBytes(StandardCharsets.UTF_8);
        storage.put(originalKey, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
    }

    // ------------------------------------------------------------------ 主流程

    @Test
    void aGuestWithTheLinkAndPasswordUploadsStraightIntoTheProjectAlbum() {
        ProjectShareService.CreatedShareLink created = uploadLink();
        ProjectShareService.GuestContext context = guest(created);

        ProjectShareUploadService.UploadTicket ticket = uploadService.createTicket(context, file("a"));
        putOriginal(ticket.photoId());
        ProjectShareUploadService.UploadedPhoto uploaded = uploadService.complete(context,
                ticket.photoId(), new ProjectShareUploadService.CompleteCommand("入场式", null));

        assertThat(ticket.uploadUrl()).isNotBlank();
        assertThat(uploaded.status()).isEqualTo(PhotoStatus.PROCESSING);
        // 图片进的是同一张 photo_project：站内打开选题就能看到，不存在第二份相册。
        assertThat(jdbc.sql("SELECT COUNT(*) FROM photo_project WHERE photo_id=:photo AND project_id=:project")
                .param("photo", ticket.photoId()).param("project", event.getId())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theUploaderIdentityIsSnapshottedFromTheSessionNotTheRequest() {
        ProjectShareService.CreatedShareLink created = uploadLink();
        ProjectShareUploadService.UploadTicket ticket =
                uploadService.createTicket(guest(created), file("b"));

        var photo = jdbc.sql("""
                        SELECT photographer_name, photographer_student_id, uploaded_by, share_link_id
                        FROM photo WHERE id=:id
                        """).param("id", ticket.photoId())
                .query((rs, row) -> List.of(rs.getString(1), rs.getString(2),
                        rs.getLong(3), rs.getLong(4))).single();

        assertThat(photo.get(0)).isEqualTo("张小拍");
        assertThat(photo.get(1)).isEqualTo("20230001");
        // 站内可追责的人是开链接的成员；口子是链接本身。
        assertThat(photo.get(2)).isEqualTo(MINISTER_ID);
        assertThat(photo.get(3)).isEqualTo(created.link().id());
    }

    @Test
    void theUploadCountTracksWhatActuallyLanded() {
        ProjectShareService.CreatedShareLink created = uploadLink();
        ProjectShareService.GuestContext context = guest(created);

        // 只签了票据还没提交的那一张不算数：它可能永远不会被传上来。
        uploadService.createTicket(context, file("c"));
        var landed = uploadService.createTicket(context, file("d"));
        putOriginal(landed.photoId());
        uploadService.complete(context, landed.photoId(),
                new ProjectShareUploadService.CompleteCommand(null, null));

        assertThat(shareService.list(event.getId(), minister).stream()
                .filter(link -> link.id().equals(created.link().id()))
                .findFirst().orElseThrow().uploadCount()).isEqualTo(1);
    }

    @Test
    void aFailedUploadIsVisibleToTheGuestWhoSentIt() {
        ProjectShareService.CreatedShareLink created = uploadLink();
        ProjectShareService.GuestContext context = guest(created);
        var ticket = uploadService.createTicket(context, file("e"));

        // 处理管线把压不动的图片打回 UPLOADING 并写上原因，访客查得到这件事。
        jdbc.sql("UPDATE photo SET failure_reason='图片 SHA-256 校验失败' WHERE id=:id")
                .param("id", ticket.photoId()).update();

        var status = uploadService.status(context, ticket.photoId());
        assertThat(status.status()).isEqualTo(PhotoStatus.UPLOADING);
        assertThat(status.failureReason()).isEqualTo("图片 SHA-256 校验失败");
    }

    // ------------------------------------------------------------------ 两种链接的边界

    @Test
    void anUploadLinkCannotBrowseTheAlbum() {
        ProjectShareService.GuestContext context = guest(uploadLink());

        assertThatThrownBy(() -> shareService.photos(context.link(), 1, 30, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能用来浏览项目相册");
        assertThatThrownBy(() -> shareService.download(context.link(), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能用来浏览项目相册");
    }

    @Test
    void aBrowseLinkCannotUpload() {
        ProjectShareService.CreatedShareLink browse = shareService.create(event.getId(),
                new ProjectShareService.CreateCommand(ShareLinkPurpose.BROWSE, "校报", PASSWORD,
                        true, true, null), minister);
        ProjectShareService.GuestContext context = shareService.resolveGuestContext(
                browse.link().token(),
                shareService.openSession(browse.link().token(), PASSWORD, null).sessionToken());

        assertThatThrownBy(() -> uploadService.createTicket(context, file("f")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有开放上传");
    }

    @Test
    void anUploadLinkNeverCarriesDownloadOrAdoptionEvenWhenAskedFor() {
        // 前端漏传一个字段、或者有人直接照着浏览链接的请求体发过来，都不能让
        // 「只上传」变成「连整个相册一起交出去」。
        ProjectShareService.CreatedShareLink created = shareService.create(event.getId(),
                new ProjectShareService.CreateCommand(ShareLinkPurpose.UPLOAD, "摄影组", PASSWORD,
                        true, true, null), minister);

        assertThat(created.link().allowDownload()).isFalse();
        assertThat(created.link().allowAdoption()).isFalse();
        assertThat(created.link().purpose()).isEqualTo(ShareLinkPurpose.UPLOAD);
    }

    @Test
    void oneLinkCannotCompleteAnotherLinksUpload() {
        ProjectShareService.GuestContext first = guest(uploadLink());
        ProjectShareService.GuestContext second = guest(uploadLink());
        var ticket = uploadService.createTicket(first, file("7"));
        putOriginal(ticket.photoId());

        assertThatThrownBy(() -> uploadService.complete(second, ticket.photoId(),
                new ProjectShareUploadService.CompleteCommand(null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不是通过这条链接上传的");
    }

    @Test
    void thesameUploadCannotBeCompletedTwice() {
        ProjectShareService.GuestContext context = guest(uploadLink());
        var ticket = uploadService.createTicket(context, file("8"));
        putOriginal(ticket.photoId());
        uploadService.complete(context, ticket.photoId(),
                new ProjectShareUploadService.CompleteCommand(null, null));

        assertThatThrownBy(() -> uploadService.complete(context, ticket.photoId(),
                new ProjectShareUploadService.CompleteCommand(null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已经提交过了");
    }

    // ------------------------------------------------------------------ ZIP 批量
    //
    // 解包本身（同一个 SafeImageZipExtractor、同一套限额）在 ProjectShareZipUploadTests
    // 里端到端跑，那边不能带事务。这里验的是访客这一侧的边界。

    /** 解包的结果，直接照 BatchProcessingService 写回的形态造，省掉一次真解包。 */
    private String extractedBatch(ProjectShareService.CreatedShareLink created, int items) {
        // 批次 id 是 VARCHAR(26)，与线上一样用 PublicId 生成。
        String batchId = PublicId.next();
        jdbc.sql("""
                INSERT INTO photo_upload_batch
                    (id, mode, project_id, created_by, share_link_id, status,
                     total_count, success_count, failure_count)
                VALUES (:id, 'ZIP', :project, :user, :link, 'WAITING_METADATA', :total, 0, 0)
                """).param("id", batchId).param("project", event.getId()).param("user", MINISTER_ID)
                .param("link", created.link().id()).param("total", items).update();
        for (int index = 1; index <= items; index++) {
            jdbc.sql("""
                    INSERT INTO photo_upload_item
                        (batch_id, original_file_name, temp_object_key, content_type, size, status)
                    VALUES (:batch, :name, :key, 'image/jpeg', 1024, 'WAITING_METADATA')
                    """).param("batch", batchId).param("name", "现场" + index + ".jpg")
                    .param("key", "temporary/batches/" + batchId + "/" + index + ".jpg").update();
        }
        return batchId;
    }

    @Test
    void aFinishedZipLandsAsPhotosCarryingTheSessionIdentityAndTheLink() {
        ProjectShareService.CreatedShareLink created = uploadLink();
        ProjectShareService.GuestContext context = guest(created);
        String batchId = extractedBatch(created, 2);

        uploadService.finishZip(context, batchId, LocalDateTime.now().minusDays(1));

        // 标题取包内原文件名——那是访客和选片人之间唯一的共同语言。
        assertThat(jdbc.sql("""
                        SELECT p.title FROM photo p JOIN photo_project pp ON pp.photo_id = p.id
                        WHERE pp.project_id = :project ORDER BY p.id
                        """).param("project", event.getId()).query(String.class).list())
                .containsExactly("现场1", "现场2");
        assertThat(jdbc.sql("SELECT photographer_name FROM photo WHERE share_link_id = :link")
                .param("link", created.link().id()).query(String.class).list())
                .containsOnly("张小拍");
        assertThat(shareService.list(event.getId(), minister).stream()
                .filter(view -> view.id().equals(created.link().id()))
                .findFirst().orElseThrow().uploadCount()).isEqualTo(2);
    }

    @Test
    void oneLinkCannotTouchAnotherLinksBatch() {
        ProjectShareService.CreatedShareLink first = uploadLink();
        ProjectShareService.GuestContext second = guest(uploadLink());
        String batchId = extractedBatch(first, 1);

        // 批次的 created_by 是链接创建者（两条链接是同一个人开的），所以只认 share_link_id。
        assertThatThrownBy(() -> uploadService.batchStatus(second, batchId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于这条链接");
        assertThatThrownBy(() -> uploadService.finishZip(second, batchId, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于这条链接");
    }

    @Test
    void theZipLimitsAreTheOnesTheInAppBatchUploadUses() {
        ProjectShareService.GuestContext context = guest(uploadLink());

        assertThatThrownBy(() -> uploadService.createZipTicket(context,
                new ProjectShareUploadService.ZipCommand("活动.zip",
                        ImageUploadPolicy.MAX_ARCHIVE_BYTES + 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1.5 GB");
        // 这条通道只收压缩包；单张图片走 upload-tickets 那一条。
        assertThatThrownBy(() -> uploadService.createZipTicket(context,
                new ProjectShareUploadService.ZipCommand("活动.rar", 1024L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(".zip");
    }

    @Test
    void aFinishedZipCannotSlipPastTheLinkCapacity() {
        ProjectShareService.CreatedShareLink created = uploadLink();
        ProjectShareService.GuestContext context = guest(created);
        String batchId = extractedBatch(created, 3);
        jdbc.sql("UPDATE project_share_link SET upload_count = :count WHERE id = :id")
                .param("count", ProjectShareUploadService.MAX_UPLOADS_PER_LINK - 2)
                .param("id", created.link().id()).update();

        // 剩 2 张额度，这一批有 3 张：整批拒掉，而不是传一半。
        assertThatThrownBy(() -> uploadService.finishZip(context, batchId, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("装不下这一批");
    }

    @Test
    void aClosedProjectStopsZipBatchesToo() {
        ProjectShareService.CreatedShareLink created = uploadLink();
        ProjectShareService.GuestContext context = guest(created);
        String batchId = extractedBatch(created, 1);
        projectService.changeStatus(event.getId(), ProjectStatus.COMPLETED,
                projectService.get(event.getId()).getVersion(), minister);

        assertThatThrownBy(() -> uploadService.createZipTicket(context,
                new ProjectShareUploadService.ZipCommand("活动.zip", 1024L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不再接收上传");
        assertThatThrownBy(() -> uploadService.finishZip(context, batchId, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不再接收上传");
    }

    // ------------------------------------------------------------------ 选题状态

    @Test
    void onlyAnActiveEventProjectCanIssueAnUploadLink() {
        ProjectEntity creation = projectService.create("创作选题", "说明", ProjectStatus.ACTIVE, minister);
        assertThatThrownBy(() -> uploadLink(creation.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有活动选题");

        ProjectEntity draft = projectService.create("未开始的活动", "说明", ProjectStatus.DRAFT,
                List.of(), ProjectType.EVENT, minister);
        assertThatThrownBy(() -> uploadLink(draft.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有进行中的活动选题");
    }

    @Test
    void finishingTheProjectStopsUploadsImmediately() {
        // 链接还没过期，会话也还在手上——但选题已经收工了，下一次请求就该被挡住，
        // 而不是等 6 小时的会话自然过期。
        ProjectShareService.GuestContext context = guest(uploadLink());
        var ticket = uploadService.createTicket(context, file("9"));
        putOriginal(ticket.photoId());
        projectService.changeStatus(event.getId(), ProjectStatus.COMPLETED,
                projectService.get(event.getId()).getVersion(), minister);

        assertThatThrownBy(() -> uploadService.createTicket(context, file("0")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不再接收上传");
        assertThatThrownBy(() -> uploadService.complete(context, ticket.photoId(),
                new ProjectShareUploadService.CompleteCommand(null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不再接收上传");
    }

    // ------------------------------------------------------------------ 身份

    @Test
    void anUploadSessionRequiresAName() {
        ProjectShareService.CreatedShareLink created = uploadLink();

        assertThatThrownBy(() -> shareService.openSession(created.link().token(), PASSWORD,
                new ProjectShareService.GuestIdentity("  ", "20230001")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传者姓名");
        assertThatThrownBy(() -> shareService.openSession(created.link().token(), PASSWORD, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传者姓名");
    }

    @Test
    void anUploadSessionRequiresAWellFormedStudentId() {
        ProjectShareService.CreatedShareLink created = uploadLink();

        assertThatThrownBy(() -> shareService.openSession(created.link().token(), PASSWORD,
                new ProjectShareService.GuestIdentity("张小拍", "?")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("学号");
    }

    @Test
    void theGreetingSaysWhichKindOfLinkThisIsSoTheRightPageCanTakeIt() {
        // 访客拿到的只是一个 token，页面得先知道该把他领到相册还是上传台；
        // 这个回答本身不泄露选题的任何内容。
        assertThat(shareService.greet(uploadLink().link().token()).purpose())
                .isEqualTo(ShareLinkPurpose.UPLOAD);
    }
}
