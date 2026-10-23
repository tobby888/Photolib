package cn.photolib.share;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.util.PublicId;
import cn.photolib.photo.PhotoTags;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.project.model.ProjectType;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.model.UserRole;
import cn.photolib.worklog.model.WorklogStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上传链接的两项补充能力：中途切换拍摄者（一个人替两个人交图），以及上传者给自己的图片
 * 打预设标签。规则见 {@code ProjectShareService.switchUploader} 和
 * {@code ProjectShareUploadService.updateTags} / {@code guestTags}。
 */
@SpringBootTest
@Transactional
class ProjectShareUploaderTests {
    private static final long MINISTER_ID = 7_851L;
    private static final String PASSWORD = "switch-pass";

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
                VALUES (:id, 'switch-minister', 'hash', '切换部长', 'MINISTER', null, TRUE, FALSE)
                """).param("id", MINISTER_ID).update();
        minister = new AuthenticatedUser(MINISTER_ID, "switch-minister", "切换部长",
                UserRole.MINISTER, null, false);
        event = projectService.create("迎新晚会", "活动选题", ProjectStatus.ACTIVE,
                List.of("舞台", "观众"), ProjectType.EVENT, minister);
    }

    // ------------------------------------------------------------------ 夹具

    private ProjectShareService.CreatedShareLink uploadLink(Long projectId) {
        return shareService.create(projectId, new ProjectShareService.CreateCommand(
                ShareLinkPurpose.UPLOAD, "摄影组", PASSWORD, false, false, null), minister);
    }

    private ProjectShareService.GuestContext guest(ProjectShareService.CreatedShareLink created) {
        ProjectShareService.GuestSession session = shareService.openSession(created.link().token(),
                PASSWORD, new ProjectShareService.GuestIdentity("张小拍", "20230001"));
        return context(created, session.sessionToken());
    }

    private ProjectShareService.GuestContext context(ProjectShareService.CreatedShareLink created,
                                                     String sessionToken) {
        return shareService.resolveGuestContext(created.link().token(), sessionToken);
    }

    /** 签票据 → PUT → complete，返回 photoId；照片停在 PROCESSING。 */
    private Long handIn(ProjectShareService.GuestContext context, String sha, List<String> tags) {
        var ticket = uploadService.createTicket(context, new ProjectShareUploadService.TicketCommand(
                "field.jpg", "image/jpeg", 2_048L, sha.repeat(64), LocalDateTime.now()));
        String originalKey = jdbc.sql("SELECT original_object_key FROM photo WHERE id=:id")
                .param("id", ticket.photoId()).query(String.class).single();
        byte[] bytes = "non-empty-original".getBytes(StandardCharsets.UTF_8);
        storage.put(originalKey, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
        uploadService.complete(context, ticket.photoId(),
                new ProjectShareUploadService.CompleteCommand(null, null, tags));
        return ticket.photoId();
    }

    /** 模拟压缩管线已经处理完：事务内的测试里那个异步事件不会跑。 */
    private void markAvailable(Long photoId) {
        jdbc.sql("UPDATE photo SET status = 'AVAILABLE' WHERE id = :id").param("id", photoId).update();
    }

    private List<String> tagsOf(Long photoId) {
        return PhotoTags.parse(jdbc.sql("SELECT tags_json FROM photo WHERE id=:id")
                .param("id", photoId).query(String.class).single());
    }

    private String photographerOf(Long photoId) {
        return jdbc.sql("SELECT photographer_student_id FROM photo WHERE id=:id")
                .param("id", photoId).query(String.class).single();
    }

    private ProjectShareUploadService.WorklogDeclaration declaration() {
        return new ProjectShareUploadService.WorklogDeclaration(LocalDate.now(), 60, 30, null);
    }

    // ------------------------------------------------------------------ 切换拍摄者

    @Test
    void switchingTheUploaderChangesWhoLaterUploadsAreRecordedUnder() {
        ProjectShareService.CreatedShareLink created = uploadLink(event.getId());
        ProjectShareService.GuestSession session = shareService.openSession(created.link().token(),
                PASSWORD, new ProjectShareService.GuestIdentity("张小拍", "20230001"));
        // 签出去还没传完的那张：拍摄者在签票据那一刻就定了，切换不该回头改它。
        var pending = uploadService.createTicket(context(created, session.sessionToken()),
                new ProjectShareUploadService.TicketCommand(
                        "a.jpg", "image/jpeg", 2_048L, "a".repeat(64), LocalDateTime.now()));

        ProjectShareService.GuestAccess access = shareService.switchUploader(
                context(created, session.sessionToken()),
                new ProjectShareService.GuestIdentity("  李同学 ", "２０２３０００２"));

        assertThat(access.uploaderName()).isEqualTo("李同学");
        // 学号与进门时同一套规范化：全角数字归成半角。
        assertThat(access.uploaderStudentId()).isEqualTo("20230002");
        var next = uploadService.createTicket(context(created, session.sessionToken()),
                new ProjectShareUploadService.TicketCommand(
                        "b.jpg", "image/jpeg", 2_048L, "b".repeat(64), LocalDateTime.now()));
        assertThat(next.photographerName()).isEqualTo("李同学");
        assertThat(photographerOf(next.photoId())).isEqualTo("20230002");
        assertThat(pending.photographerName()).isEqualTo("张小拍");
        assertThat(photographerOf(pending.photoId())).isEqualTo("20230001");
        // 重新解析会话读到的也是新身份：切换是落库的，不是只改了内存里那一份。
        assertThat(shareService.access(context(created, session.sessionToken())).uploaderName())
                .isEqualTo("李同学");
    }

    @Test
    void switchingOnlyAffectsTheSessionThatAskedForIt() {
        ProjectShareService.CreatedShareLink created = uploadLink(event.getId());
        ProjectShareService.GuestContext mine = guest(created);
        ProjectShareService.GuestSession someoneElse = shareService.openSession(created.link().token(),
                PASSWORD, new ProjectShareService.GuestIdentity("张小拍", "20230001"));

        shareService.switchUploader(mine, new ProjectShareService.GuestIdentity("李同学", "20230002"));

        assertThat(shareService.access(context(created, someoneElse.sessionToken())).uploaderName())
                .isEqualTo("张小拍");
    }

    @Test
    void worklogsFollowTheCurrentUploader() {
        ProjectShareService.CreatedShareLink created = uploadLink(event.getId());
        ProjectShareService.GuestSession session = shareService.openSession(created.link().token(),
                PASSWORD, new ProjectShareService.GuestIdentity("张小拍", "20230001"));
        handIn(context(created, session.sessionToken()), "c", List.of());
        uploadService.declareWorklog(context(created, session.sessionToken()), declaration());

        shareService.switchUploader(context(created, session.sessionToken()),
                new ProjectShareService.GuestIdentity("李同学", "20230002"));
        ProjectShareService.GuestContext switched = context(created, session.sessionToken());

        // 李同学还没交过图：申报的门槛按当前身份判，不能借张小拍的图报自己的工时。
        assertThatThrownBy(() -> uploadService.declareWorklog(switched, declaration()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先通过这条链接上传照片");
        // 申报记录也只列当前身份的。
        assertThat(uploadService.worklogs(switched)).isEmpty();

        handIn(switched, "d", List.of());
        var declared = uploadService.declareWorklog(switched, declaration());
        assertThat(declared.status()).isEqualTo(WorklogStatus.SUBMITTED);
        assertThat(jdbc.sql("SELECT member_name FROM worklog WHERE id=:id")
                .param("id", declared.id()).query(String.class).single()).isEqualTo("李同学");
    }

    @Test
    void theNewIdentityIsValidatedLikeTheOneAtTheDoor() {
        ProjectShareService.GuestContext context = guest(uploadLink(event.getId()));

        assertThatThrownBy(() -> shareService.switchUploader(context,
                new ProjectShareService.GuestIdentity("  ", "20230002")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("姓名");
        assertThatThrownBy(() -> shareService.switchUploader(context,
                new ProjectShareService.GuestIdentity("李同学", "?")))
                .isInstanceOf(BusinessException.class);
        assertThat(shareService.access(context).uploaderName()).isEqualTo("张小拍");
    }

    @Test
    void aBrowseLinkHasNoUploaderToSwitch() {
        ProjectShareService.CreatedShareLink browse = shareService.create(event.getId(),
                new ProjectShareService.CreateCommand(ShareLinkPurpose.BROWSE, "看图", PASSWORD,
                        false, false, null), minister);
        ProjectShareService.GuestSession session = shareService.openSession(browse.link().token(),
                PASSWORD, null);
        ProjectShareService.GuestContext context = context(browse, session.sessionToken());

        assertThatThrownBy(() -> shareService.switchUploader(context,
                new ProjectShareService.GuestIdentity("李同学", "20230002")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有开放上传");
        assertThat(shareService.access(context).presetTags()).isEmpty();
    }

    // ------------------------------------------------------------------ 标签

    @Test
    void theUploadPageGetsTheProjectPresetTags() {
        ProjectShareService.GuestContext context = guest(uploadLink(event.getId()));

        assertThat(shareService.access(context).presetTags()).containsExactly("舞台", "观众");
        assertThat(shareService.access(context).uploaderStudentId()).isEqualTo("20230001");
    }

    @Test
    void tagsChosenBeforeUploadingLandOnThePhoto() {
        ProjectShareService.GuestContext context = guest(uploadLink(event.getId()));

        Long photoId = handIn(context, "e", List.of(" 舞台 ", "舞台"));

        assertThat(tagsOf(photoId)).containsExactly("舞台");
    }

    @Test
    void onlyPresetTagsAreAccepted() {
        ProjectShareService.GuestContext context = guest(uploadLink(event.getId()));

        assertThatThrownBy(() -> handIn(context, "f", List.of("舞台", "自造标签")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("自造标签");
        // 保留标签是选片人的结论，上传者不能打。
        assertThatThrownBy(() -> handIn(context, "1", List.of(PhotoTags.DEPRECATED)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(PhotoTags.DEPRECATED);
    }

    @Test
    void aProjectWithoutPresetsTakesNoTagsFromUploaders() {
        ProjectEntity bare = projectService.create("无预设", "活动选题", ProjectStatus.ACTIVE,
                List.of(), ProjectType.EVENT, minister);
        ProjectShareService.GuestContext context = guest(uploadLink(bare.getId()));

        assertThatThrownBy(() -> handIn(context, "2", List.of("舞台")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有设置预设标签");
        // 不带标签照常能传。
        assertThat(handIn(context, "3", List.of())).isNotNull();
    }

    @Test
    void theUploaderRetagsTheirOwnPhotoWithoutTouchingTagsOutsideThePresets() {
        ProjectShareService.GuestContext context = guest(uploadLink(event.getId()));
        Long photoId = handIn(context, "4", List.of("舞台"));
        markAvailable(photoId);
        // 选片人已经打了 deprecated：上传者改标签不能把它抹掉。
        jdbc.sql("UPDATE photo SET tags_json = :tags WHERE id = :id")
                .param("tags", PhotoTags.toJson(List.of("舞台", PhotoTags.DEPRECATED)))
                .param("id", photoId).update();

        var tagged = uploadService.updateTags(context, photoId, List.of("观众"));

        assertThat(tagged.tags()).containsExactly("观众");
        assertThat(tagsOf(photoId)).containsExactlyInAnyOrder(PhotoTags.DEPRECATED, "观众");

        uploadService.updateTags(context, photoId, List.of());
        assertThat(tagsOf(photoId)).containsExactly(PhotoTags.DEPRECATED);
    }

    @Test
    void aPhotoStillProcessingCannotBeRetagged() {
        // 压缩管线提交成品时按 version 做 CAS，处理中途改标签会让那一步失败。
        ProjectShareService.GuestContext context = guest(uploadLink(event.getId()));
        Long photoId = handIn(context, "5", List.of());
        int version = jdbc.sql("SELECT version FROM photo WHERE id=:id")
                .param("id", photoId).query(Integer.class).single();

        assertThatThrownBy(() -> uploadService.updateTags(context, photoId, List.of("舞台")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("还在处理中");
        assertThat(jdbc.sql("SELECT version FROM photo WHERE id=:id")
                .param("id", photoId).query(Integer.class).single()).isEqualTo(version);
    }

    @Test
    void onlyPhotosOfTheCurrentUploaderOnThisLinkCanBeRetagged() {
        ProjectShareService.CreatedShareLink created = uploadLink(event.getId());
        ProjectShareService.GuestSession session = shareService.openSession(created.link().token(),
                PASSWORD, new ProjectShareService.GuestIdentity("张小拍", "20230001"));
        Long zhangs = handIn(context(created, session.sessionToken()), "6", List.of());
        markAvailable(zhangs);

        shareService.switchUploader(context(created, session.sessionToken()),
                new ProjectShareService.GuestIdentity("李同学", "20230002"));
        assertThatThrownBy(() -> uploadService.updateTags(context(created, session.sessionToken()),
                zhangs, List.of("舞台")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("切换到这张图的拍摄者");

        // 另一条链接上的同一个学号也不行：口子是链接。
        ProjectShareService.GuestContext otherLink = guest(uploadLink(event.getId()));
        assertThatThrownBy(() -> uploadService.updateTags(otherLink, zhangs, List.of("舞台")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不是通过这条链接上传的");

        // 切回来就能改。
        shareService.switchUploader(context(created, session.sessionToken()),
                new ProjectShareService.GuestIdentity("张小拍", "20230001"));
        uploadService.updateTags(context(created, session.sessionToken()), zhangs, List.of("舞台"));
        assertThat(tagsOf(zhangs)).containsExactly("舞台");
    }

    @Test
    void tagsCannotChangeOnceTheProjectStopsTakingUploads() {
        ProjectShareService.GuestContext context = guest(uploadLink(event.getId()));
        Long photoId = handIn(context, "7", List.of());
        markAvailable(photoId);
        jdbc.sql("UPDATE project SET status = 'COMPLETED' WHERE id = :id")
                .param("id", event.getId()).update();

        assertThatThrownBy(() -> uploadService.updateTags(context, photoId, List.of("舞台")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不再接收上传");
    }

    @Test
    void aZipTakesOneSetOfPresetTagsForTheWholeBatch() {
        ProjectShareService.CreatedShareLink created = uploadLink(event.getId());
        ProjectShareService.GuestContext context = guest(created);
        String batchId = extractedBatch(created, 2);

        assertThatThrownBy(() -> uploadService.finishZip(context, batchId, null, List.of("自造标签")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("自造标签");
        // 校验在任何状态迁移之前：批次还停在待整理，可以带对的标签再来一次。
        assertThat(jdbc.sql("SELECT status FROM photo_upload_batch WHERE id=:id")
                .param("id", batchId).query(String.class).single()).isEqualTo("WAITING_METADATA");

        uploadService.finishZip(context, batchId, null, List.of("观众"));

        assertThat(jdbc.sql("SELECT tags_json FROM photo WHERE share_link_id = :link")
                .param("link", created.link().id()).query(String.class).list())
                .hasSize(2)
                .allSatisfy(json -> assertThat(PhotoTags.parse(json)).containsExactly("观众"));
    }

    /** 一个已经解包、等着整理的批次，与 {@code ProjectShareUploadTests.extractedBatch} 同一个写法。 */
    private String extractedBatch(ProjectShareService.CreatedShareLink created, int items) {
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
}
