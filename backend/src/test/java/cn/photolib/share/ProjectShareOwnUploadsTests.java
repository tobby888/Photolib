package cn.photolib.share;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.project.model.ProjectType;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上传链接的「我传的图片」：上传者按学号看到、筛选、多选删除自己通过这条链接传的图片。
 * 规则见 {@code ProjectShareUploadService.ownUploads} / {@code deleteOwnUploads}。
 */
@SpringBootTest
@Transactional
class ProjectShareOwnUploadsTests {
    private static final long MINISTER_ID = 7_861L;
    private static final long PHOTO_BASE = 786_100L;
    private static final String PASSWORD = "own-pass";
    private static final String ME = "20230001";

    @Autowired private ProjectShareService shareService;
    @Autowired private ProjectShareUploadService uploadService;
    @Autowired private ProjectService projectService;
    @Autowired private JdbcClient jdbc;

    private AuthenticatedUser minister;
    private ProjectEntity event;
    private ProjectShareService.CreatedShareLink link;
    private long nextPhoto = PHOTO_BASE;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES (:id, 'own-minister', 'hash', '自管部长', 'MINISTER', null, TRUE, FALSE)
                """).param("id", MINISTER_ID).update();
        minister = new AuthenticatedUser(MINISTER_ID, "own-minister", "自管部长",
                UserRole.MINISTER, null, false);
        event = projectService.create("运动会", "活动选题", ProjectStatus.ACTIVE,
                List.of("开幕式", "跑步"), ProjectType.EVENT, minister);
        link = uploadLink(event.getId());
    }

    // ------------------------------------------------------------------ 夹具

    private ProjectShareService.CreatedShareLink uploadLink(Long projectId) {
        return shareService.create(projectId, new ProjectShareService.CreateCommand(
                ShareLinkPurpose.UPLOAD, "摄影组", PASSWORD, false, false, null), minister);
    }

    private ProjectShareService.GuestContext guest(ProjectShareService.CreatedShareLink created,
                                                   String name, String studentId) {
        ProjectShareService.GuestSession session = shareService.openSession(created.link().token(),
                PASSWORD, new ProjectShareService.GuestIdentity(name, studentId));
        return shareService.resolveGuestContext(created.link().token(), session.sessionToken());
    }

    private ProjectShareService.GuestContext me() {
        return guest(link, "张小拍", ME);
    }

    /** 直接落一张处理完的图：这里验的是归属与删除规则，不是上传流水线。 */
    private Long photo(ProjectShareService.CreatedShareLink via, String studentId, String title,
                       String tagsJson, LocalDateTime takenAt, String status) {
        long id = nextPhoto++;
        jdbc.sql("""
                INSERT INTO photo (id, title, project_id, photographer_student_id, photographer_name, uploaded_by,
                    share_link_id, taken_at, size, content_type, object_key, sha256, status, tags_json)
                VALUES (:id, :title, :project, :student, '某同学', :user, :link, :takenAt, 2048,
                    'image/jpeg', :key, :sha, :status, :tags)
                """).param("id", id).param("title", title).param("project", event.getId())
                .param("student", studentId).param("user", MINISTER_ID)
                .param("link", via == null ? null : via.link().id())
                .param("takenAt", takenAt).param("key", "photos/2026/own-" + id + ".jpg")
                .param("sha", String.format("%064x", id)).param("status", status).param("tags", tagsJson)
                .update();
        jdbc.sql("INSERT INTO photo_project (photo_id, project_id) VALUES (:photo, :project)")
                .param("photo", id).param("project", event.getId()).update();
        return id;
    }

    private Long mine(String title) {
        return photo(link, ME, title, "[]", LocalDateTime.of(2026, 10, 1, 9, 0), "AVAILABLE");
    }

    private boolean deleted(Long photoId) {
        return jdbc.sql("SELECT deleted FROM photo WHERE id = :id").param("id", photoId)
                .query(Boolean.class).single();
    }

    private List<Long> listedIds(ProjectShareService.GuestContext context,
                                 ProjectShareUploadService.OwnUploadFilter filter) {
        return uploadService.ownUploads(context, 1, 100, filter).items().stream()
                .map(ProjectShareUploadService.OwnUpload::id).toList();
    }

    private ProjectShareUploadService.OwnUploadFilter keyword(String keyword) {
        return new ProjectShareUploadService.OwnUploadFilter(keyword, null, null, null);
    }

    // ------------------------------------------------------------------ 列表

    @Test
    void listsOnlyMyProcessedPhotosThroughThisLinkWithThumbnails() {
        Long mine = mine("我的.jpg");
        photo(link, ME, "处理中.jpg", "[]", LocalDateTime.now(), "PROCESSING");
        photo(link, "20239999", "别人的.jpg", "[]", LocalDateTime.now(), "AVAILABLE");
        photo(uploadLink(event.getId()), ME, "另一条链接.jpg", "[]", LocalDateTime.now(), "AVAILABLE");
        photo(null, ME, "站内传的.jpg", "[]", LocalDateTime.now(), "AVAILABLE");

        var page = uploadService.ownUploads(me(), 1, 24, null);

        assertThat(page.total()).isEqualTo(1);
        var item = page.items().getFirst();
        assertThat(item.id()).isEqualTo(mine);
        assertThat(item.title()).isEqualTo("我的.jpg");
        assertThat(item.thumbnailUrl()).isNotBlank();
        assertThat(item.deletable()).isTrue();
        assertThat(item.lockedReason()).isNull();
    }

    @Test
    void switchingThePhotographerShowsThatPersonsPhotosInstead() {
        Long mine = mine("我的.jpg");
        Long theirs = photo(link, "20230002", "李同学的.jpg", "[]", LocalDateTime.now(), "AVAILABLE");
        String sessionToken = shareService.openSession(link.link().token(), PASSWORD,
                new ProjectShareService.GuestIdentity("张小拍", ME)).sessionToken();
        ProjectShareService.GuestContext context =
                shareService.resolveGuestContext(link.link().token(), sessionToken);
        assertThat(listedIds(context, null)).containsExactly(mine);

        shareService.switchUploader(context, new ProjectShareService.GuestIdentity("李同学", "20230002"));
        ProjectShareService.GuestContext switched =
                shareService.resolveGuestContext(link.link().token(), sessionToken);

        assertThat(listedIds(switched, null)).containsExactly(theirs);
    }

    @Test
    void filtersByKeywordTagsAndTakenDateAndPaginates() {
        Long opening = photo(link, ME, "开幕-1.jpg", "[\"开幕式\",\"自定义\",\"deprecated\"]",
                LocalDateTime.of(2026, 10, 1, 9, 0), "AVAILABLE");
        Long running = photo(link, ME, "跑步-1.jpg", "[\"跑步\"]",
                LocalDateTime.of(2026, 10, 2, 23, 59), "AVAILABLE");
        Long both = photo(link, ME, "跑步-2.jpg", "[\"跑步\",\"开幕式\"]",
                LocalDateTime.of(2026, 10, 3, 0, 0), "AVAILABLE");
        ProjectShareService.GuestContext context = me();

        assertThat(listedIds(context, keyword("跑步"))).containsExactlyInAnyOrder(running, both);
        // 关键字也搜标签，与相册访客页同一口径。
        assertThat(listedIds(context, keyword("自定义"))).containsExactly(opening);
        assertThat(listedIds(context, new ProjectShareUploadService.OwnUploadFilter(
                null, List.of("跑步", "开幕式"), null, null))).containsExactly(both);
        // 拍摄日期两端都含整天：10-02 的 23:59 算在 10-02 里，10-03 的 00:00 不算。
        assertThat(listedIds(context, new ProjectShareUploadService.OwnUploadFilter(
                null, null, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 2)))).containsExactly(running);

        var first = uploadService.ownUploads(context, 1, 2, null);
        var second = uploadService.ownUploads(context, 2, 2, null);
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.items()).hasSize(2);
        assertThat(second.items()).hasSize(1);
        assertThat(uploadService.ownUploads(context, 9, 2, null).items()).isEmpty();

        // 保留标签是选片人的结论，访客既看不到，也不出现在筛选候选里。
        assertThat(uploadService.ownUploads(context, 1, 100, keyword("开幕-1")).items().getFirst().tags())
                .containsExactly("开幕式", "自定义");
        assertThat(uploadService.ownUploadFilterOptions(context).tags())
                .containsExactly("开幕式", "跑步", "自定义");
    }

    @Test
    void marksAdoptedPhotosAndPhotosInOtherAlbumsAsNotDeletable() {
        Long adopted = mine("被采用.jpg");
        Long elsewhere = mine("在别的选题.jpg");
        Long free = mine("可以删.jpg");
        adopt(adopted);
        addToOtherProject(elsewhere);

        var items = uploadService.ownUploads(me(), 1, 100, null).items();

        assertThat(items).filteredOn(item -> item.id().equals(adopted)).singleElement()
                .satisfies(item -> {
                    assertThat(item.deletable()).isFalse();
                    assertThat(item.lockedReason()).isEqualTo(ProjectShareUploadService.LOCKED_ADOPTED);
                });
        assertThat(items).filteredOn(item -> item.id().equals(elsewhere)).singleElement()
                .satisfies(item -> assertThat(item.lockedReason())
                        .isEqualTo(ProjectShareUploadService.LOCKED_ELSEWHERE));
        assertThat(items).filteredOn(item -> item.id().equals(free)).singleElement()
                .satisfies(item -> assertThat(item.deletable()).isTrue());
    }

    // ------------------------------------------------------------------ 删除

    @Test
    void deletesMyPhotosAndSkipsTheLockedOnes() {
        Long a = mine("a.jpg");
        Long b = mine("b.jpg");
        Long adopted = mine("被采用.jpg");
        Long elsewhere = mine("在别的选题.jpg");
        Long processing = photo(link, ME, "处理中.jpg", "[]", LocalDateTime.now(), "PROCESSING");
        adopt(adopted);
        addToOtherProject(elsewhere);

        var result = uploadService.deleteOwnUploads(me(), List.of(a, b, adopted, elsewhere, processing));

        assertThat(result.deleted()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(3);
        assertThat(result.alreadyDeleted()).isZero();
        assertThat(deleted(a)).isTrue();
        assertThat(deleted(b)).isTrue();
        assertThat(deleted(adopted)).isFalse();
        assertThat(deleted(elsewhere)).isFalse();
        assertThat(deleted(processing)).isFalse();
        assertThat(listedIds(me(), null)).containsExactlyInAnyOrder(adopted, elsewhere);
    }

    @Test
    void deletingAgainCountsAsAlreadyDeleted() {
        Long a = mine("a.jpg");
        ProjectShareService.GuestContext context = me();
        uploadService.deleteOwnUploads(context, List.of(a));

        var again = uploadService.deleteOwnUploads(context, List.of(a, a));

        assertThat(again.deleted()).isZero();
        assertThat(again.alreadyDeleted()).isEqualTo(1);
    }

    @Test
    void someoneElsesPhotoRejectsTheWholeBatchWithAConflictNotAForbidden() {
        Long mine = mine("我的.jpg");
        Long theirs = photo(link, "20230002", "李同学的.jpg", "[]", LocalDateTime.now(), "AVAILABLE");

        // 409 而不是 403/404：访客页把 403/404 当成会话失效、退回密码页。
        assertThatThrownBy(() -> uploadService.deleteOwnUploads(me(), List.of(mine, theirs)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT));
        assertThat(deleted(mine)).isFalse();
        assertThat(deleted(theirs)).isFalse();
    }

    @Test
    void photosFromAnotherLinkOrUploadedInAppCannotBeDeleted() {
        Long otherLink = photo(uploadLink(event.getId()), ME, "另一条链接.jpg", "[]", LocalDateTime.now(), "AVAILABLE");
        Long inApp = photo(null, ME, "站内.jpg", "[]", LocalDateTime.now(), "AVAILABLE");

        assertThatThrownBy(() -> uploadService.deleteOwnUploads(me(), List.of(otherLink)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT));
        assertThatThrownBy(() -> uploadService.deleteOwnUploads(me(), List.of(inApp)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> uploadService.deleteOwnUploads(me(), List.of(999_999_999L)))
                .isInstanceOf(BusinessException.class);
        assertThat(deleted(otherLink)).isFalse();
        assertThat(deleted(inApp)).isFalse();
    }

    @Test
    void publicPhotoUploadersHaveNothingToManage() {
        Long publicPhoto = photo(link, "", "公共.jpg", "[]", LocalDateTime.now(), "AVAILABLE");
        jdbc.sql("UPDATE photo SET photographer_name = '公共照片' WHERE id = :id").param("id", publicPhoto).update();
        ProjectShareService.GuestSession session = shareService.openSession(link.link().token(), PASSWORD,
                new ProjectShareService.GuestIdentity(null, null, true));
        ProjectShareService.GuestContext context =
                shareService.resolveGuestContext(link.link().token(), session.sessionToken());

        assertThat(uploadService.ownUploads(context, 1, 24, null).items()).isEmpty();
        assertThat(uploadService.ownUploadFilterOptions(context).tags()).isEmpty();
        assertThatThrownBy(() -> uploadService.deleteOwnUploads(context, List.of(publicPhoto)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT));
        assertThat(deleted(publicPhoto)).isFalse();
    }

    @Test
    void cannotDeleteOnceTheProjectStopsAcceptingUploadsButCanStillList() {
        Long mine = mine("我的.jpg");
        ProjectShareService.GuestContext context = me();
        // 走 mapper 改状态：裸 SQL 改不掉同一事务里 MyBatis 一级缓存读到的那份 ACTIVE。
        projectService.changeStatus(event.getId(), ProjectStatus.COMPLETED,
                projectService.get(event.getId()).getVersion(), minister);

        assertThat(listedIds(context, null)).containsExactly(mine);
        assertThatThrownBy(() -> uploadService.deleteOwnUploads(context, List.of(mine)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT));
        assertThat(deleted(mine)).isFalse();
    }

    @Test
    void theSameFileCanBeUploadedAgainAfterDeletingIt() {
        ProjectShareService.GuestContext context = me();
        var ticket = uploadService.createTicket(context, new ProjectShareUploadService.TicketCommand(
                "again.jpg", "image/jpeg", 2_048L, "e".repeat(64), LocalDateTime.now()));
        jdbc.sql("UPDATE photo SET status = 'AVAILABLE' WHERE id = :id").param("id", ticket.photoId()).update();

        uploadService.deleteOwnUploads(context, List.of(ticket.photoId()));

        var again = uploadService.createTicket(context, new ProjectShareUploadService.TicketCommand(
                "again.jpg", "image/jpeg", 2_048L, "e".repeat(64), LocalDateTime.now()));
        assertThat(again.photoId()).isNotEqualTo(ticket.photoId());
    }

    @Test
    void rejectsAnEmptyOrOversizedSelection() {
        assertThatThrownBy(() -> uploadService.deleteOwnUploads(me(), List.of()))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        List<Long> tooMany = java.util.stream.LongStream
                .rangeClosed(1, ProjectShareUploadService.MAX_DELETE_PER_REQUEST + 1).boxed().toList();
        assertThatThrownBy(() -> uploadService.deleteOwnUploads(me(), tooMany))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private void adopt(Long photoId) {
        jdbc.sql("""
                INSERT INTO adoption (project_id, photo_id, photographer_student_id, photographer_name,
                    adopted_by, adopted_at)
                VALUES (:project, :photo, :student, '某同学', :user, CURRENT_TIMESTAMP)
                """).param("project", event.getId()).param("photo", photoId).param("student", ME)
                .param("user", MINISTER_ID).update();
    }

    private void addToOtherProject(Long photoId) {
        ProjectEntity other = projectService.create("校报专题", "创作选题", ProjectStatus.ACTIVE, minister);
        jdbc.sql("INSERT INTO photo_project (photo_id, project_id) VALUES (:photo, :project)")
                .param("photo", photoId).param("project", other.getId()).update();
    }
}
