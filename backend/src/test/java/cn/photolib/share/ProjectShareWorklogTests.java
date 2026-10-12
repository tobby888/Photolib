package cn.photolib.share;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.common.error.BusinessException;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.project.model.ProjectType;
import cn.photolib.statistics.StatisticsService;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.model.UserRole;
import cn.photolib.worklog.WorklogService;
import cn.photolib.worklog.model.WorklogEntity;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上传链接对创作选题开放，以及传完图之后申报工时。
 *
 * <p>三件事各有一组用例：</p>
 * <ol>
 *   <li>创作选题的上传链接走的是站内「直接传进选题相册」那条路——不挂需求、不替人接单；</li>
 *   <li>申报直接落成「待确认」，出现在部长和管理员的审核面板里，申报人的身份取会话快照；</li>
 *   <li>这类工时没有需求也没有校区，但确认之后照样进统计和导出，口径与站内填报的一致。</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class ProjectShareWorklogTests {
    private static final long MINISTER_ID = 7_901L;
    private static final long CAMPUS_REVIEWER_ID = 7_902L;
    private static final String PASSWORD = "worklog-pass";
    private static final String STUDENT_ID = "20239901";

    @Autowired private ProjectShareService shareService;
    @Autowired private ProjectShareUploadService uploadService;
    @Autowired private ProjectService projectService;
    @Autowired private WorklogService worklogService;
    @Autowired private StatisticsService statistics;
    @Autowired private CampusService campuses;
    @Autowired private ObjectStorageService storage;
    @Autowired private JdbcClient jdbc;

    private AuthenticatedUser minister;
    private ProjectEntity creation;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES (:id, 'worklog-minister', 'hash', '工时部长', 'MINISTER', null, TRUE, FALSE)
                """).param("id", MINISTER_ID).update();
        minister = new AuthenticatedUser(MINISTER_ID, "worklog-minister", "工时部长",
                UserRole.MINISTER, null, false);
        creation = projectService.create("校庆特刊", "创作选题", ProjectStatus.ACTIVE, minister);
    }

    // ------------------------------------------------------------------ 夹具

    private ProjectShareService.CreatedShareLink uploadLink(Long projectId) {
        return shareService.create(projectId, new ProjectShareService.CreateCommand(
                ShareLinkPurpose.UPLOAD, "外援摄影", PASSWORD, false, false, null), minister);
    }

    private ProjectShareService.GuestContext guest(ProjectShareService.CreatedShareLink created) {
        return guest(created, "李外援", STUDENT_ID);
    }

    private ProjectShareService.GuestContext guest(ProjectShareService.CreatedShareLink created,
                                                   String name, String studentId) {
        ProjectShareService.GuestSession session = shareService.openSession(created.link().token(),
                PASSWORD, new ProjectShareService.GuestIdentity(name, studentId));
        return shareService.resolveGuestContext(created.link().token(), session.sessionToken());
    }

    /** 走完一张图的「签票据 → PUT → complete」，返回 photoId。 */
    private Long handInPhoto(ProjectShareService.GuestContext context, String sha) {
        var ticket = uploadService.createTicket(context, new ProjectShareUploadService.TicketCommand(
                "field.jpg", "image/jpeg", 2_048L, sha.repeat(64), LocalDateTime.now()));
        String originalKey = jdbc.sql("SELECT original_object_key FROM photo WHERE id=:id")
                .param("id", ticket.photoId()).query(String.class).single();
        byte[] bytes = "non-empty-original".getBytes(StandardCharsets.UTF_8);
        storage.put(originalKey, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
        uploadService.complete(context, ticket.photoId(),
                new ProjectShareUploadService.CompleteCommand(null, null));
        return ticket.photoId();
    }

    private ProjectShareUploadService.WorklogDeclaration declaration(LocalDate workDate) {
        return new ProjectShareUploadService.WorklogDeclaration(workDate, 120, 45, "  校庆晚会跟拍  ");
    }

    private List<WorklogEntity> reviewPanel(AuthenticatedUser reviewer) {
        return worklogService.list(1, 100, null, null, null, null, null, reviewer).items();
    }

    private long campus() {
        return campuses.create("WL-LINK-" + (System.nanoTime() & Long.MAX_VALUE), "链接工时校区").getId();
    }

    private AuthenticatedUser campusReviewer() {
        long campusId = campus();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES (:id, 'worklog-campus-reviewer', 'hash', '校区审核', 'CAMPUS_MANAGER', :campus, TRUE, FALSE)
                """).param("id", CAMPUS_REVIEWER_ID).param("campus", campusId).update();
        return new AuthenticatedUser(CAMPUS_REVIEWER_ID, "worklog-campus-reviewer", "校区审核",
                UserRole.CAMPUS_MANAGER, campusId, false, 92L, "CAMPUS_REVIEW", "校区工时审核组",
                DataScope.CAMPUS, Set.of(PermissionCode.WORKLOG_CONFIRM, PermissionCode.WORKLOG_SUBMIT),
                Set.of(campusId));
    }

    // ------------------------------------------------------------------ 创作选题的上传链接

    @Test
    void aCreationProjectCanIssueAnUploadLinkAndPhotosGoStraightIntoItsAlbum() {
        ProjectShareService.CreatedShareLink created = uploadLink(creation.getId());
        ProjectShareService.GuestContext context = guest(created);

        Long photoId = handInPhoto(context, "a");

        assertThat(shareService.access(context).allowUpload()).isTrue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM photo_project WHERE photo_id=:photo AND project_id=:project")
                .param("photo", photoId).param("project", creation.getId())
                .query(Long.class).single()).isEqualTo(1);
        // 走的是「直接传进选题相册」那条路：不挂需求、校区留空，不替任何人接单。
        var row = jdbc.sql("SELECT request_id, campus_id, status FROM photo WHERE id=:id")
                .param("id", photoId)
                .query((rs, n) -> new Object[]{rs.getObject(1), rs.getObject(2), rs.getString(3)}).single();
        assertThat(row[0]).isNull();
        assertThat(row[1]).isNull();
        assertThat(row[2]).isEqualTo(PhotoStatus.PROCESSING.name());
    }

    @Test
    void uploadLinksStillRequireAnActiveProjectWhateverItsType() {
        ProjectEntity draft = projectService.create("还没启动的创作", "说明", ProjectStatus.DRAFT, minister);

        assertThatThrownBy(() -> uploadLink(draft.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有进行中的选题");
    }

    // ------------------------------------------------------------------ 申报进审核面板

    @Test
    void aDeclarationLandsSubmittedInTheReviewPanelUnderTheSessionIdentity() {
        ProjectShareService.CreatedShareLink created = uploadLink(creation.getId());
        ProjectShareService.GuestContext context = guest(created);
        handInPhoto(context, "b");

        ProjectShareUploadService.GuestWorklog declared =
                uploadService.declareWorklog(context, declaration(LocalDate.now()));

        // 没有草稿这一步：交上来就是待确认，部长打开面板就能看到。
        assertThat(declared.status()).isEqualTo(WorklogStatus.SUBMITTED);
        WorklogEntity row = reviewPanel(minister).stream()
                .filter(worklog -> worklog.getId().equals(declared.id())).findFirst().orElseThrow();
        assertThat(row.getStatus()).isEqualTo(WorklogStatus.SUBMITTED);
        assertThat(row.getMemberName()).isEqualTo("李外援");
        assertThat(row.getMemberStudentId()).isEqualTo(STUDENT_ID);
        assertThat(row.getShootingMinutes()).isEqualTo(120);
        assertThat(row.getRetouchingMinutes()).isEqualTo(45);
        assertThat(row.getRemark()).isEqualTo("校庆晚会跟拍");
        // 没有需求；归属是链接，责任人是开链接的成员。面板靠 projectTitle 说清它是哪个选题的。
        assertThat(row.getRequestId()).isNull();
        assertThat(row.getRequestTitle()).isNull();
        assertThat(row.getShareLinkId()).isEqualTo(created.link().id());
        assertThat(row.getUserId()).isEqualTo(MINISTER_ID);
        assertThat(row.getProjectTitle()).isEqualTo("校庆特刊");
        assertThat(row.getShareLinkName()).isEqualTo("外援摄影");
    }

    @Test
    void eventProjectsWithoutAnyRequestCanDeclareToo() {
        ProjectEntity event = projectService.create("校运会", "活动选题", ProjectStatus.ACTIVE,
                List.of(), ProjectType.EVENT, minister);
        ProjectShareService.GuestContext context = guest(uploadLink(event.getId()));
        handInPhoto(context, "c");

        var declared = uploadService.declareWorklog(context, declaration(LocalDate.now()));

        assertThat(reviewPanel(minister)).anyMatch(worklog -> worklog.getId().equals(declared.id())
                && "校运会".equals(worklog.getProjectTitle()));
    }

    @Test
    void youCannotDeclareBeforeHandingInAnyPhoto() {
        ProjectShareService.CreatedShareLink created = uploadLink(creation.getId());
        ProjectShareService.GuestContext context = guest(created);
        // 只签了票据、字节没传完的那一张不算交过图。
        uploadService.createTicket(context, new ProjectShareUploadService.TicketCommand(
                "field.jpg", "image/jpeg", 2_048L, "d".repeat(64), LocalDateTime.now()));

        assertThatThrownBy(() -> uploadService.declareWorklog(context, declaration(LocalDate.now())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先通过这条链接上传照片");

        // 别人在同一条链接上交的图也不算：判的是「这条链接 + 这个学号」。
        handInPhoto(guest(created, "王同学", "20239902"), "e");
        assertThatThrownBy(() -> uploadService.declareWorklog(context, declaration(LocalDate.now())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先通过这条链接上传照片");
    }

    @Test
    void aNewSessionWithTheSameStudentIdCanStillDeclare() {
        // 传完图关了页面、第二天换台电脑再来报工时：会话是新的，学号是同一个。
        ProjectShareService.CreatedShareLink created = uploadLink(creation.getId());
        handInPhoto(guest(created), "f");

        var declared = uploadService.declareWorklog(guest(created), declaration(LocalDate.now()));

        assertThat(declared.status()).isEqualTo(WorklogStatus.SUBMITTED);
    }

    @Test
    void theSameDayCannotBeDeclaredTwiceUntilItIsRejected() {
        ProjectShareService.CreatedShareLink created = uploadLink(creation.getId());
        ProjectShareService.GuestContext context = guest(created);
        handInPhoto(context, "1");
        var first = uploadService.declareWorklog(context, declaration(LocalDate.now()));

        assertThatThrownBy(() -> uploadService.declareWorklog(context, declaration(LocalDate.now())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已经申报过了");
        // 换一天不受影响。
        uploadService.declareWorklog(context, declaration(LocalDate.now().minusDays(1)));

        // 退回之后重新申报正是它该走的路，而且申报人自己看得到退回原因。
        worklogService.reject(first.id(), "时长填多了", 1, minister);
        assertThat(uploadService.worklogs(context))
                .anyMatch(worklog -> worklog.id().equals(first.id())
                        && worklog.status() == WorklogStatus.REJECTED
                        && "时长填多了".equals(worklog.rejectReason()));
        var again = uploadService.declareWorklog(context, declaration(LocalDate.now()));
        assertThat(again.id()).isNotEqualTo(first.id());
    }

    @Test
    void theWorklogRulesAreTheInAppOnes() {
        ProjectShareService.GuestContext context = guest(uploadLink(creation.getId()));
        handInPhoto(context, "2");

        assertThatThrownBy(() -> uploadService.declareWorklog(context,
                new ProjectShareUploadService.WorklogDeclaration(LocalDate.now().plusDays(1), 60, 0, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能晚于今天");
        assertThatThrownBy(() -> uploadService.declareWorklog(context,
                new ProjectShareUploadService.WorklogDeclaration(LocalDate.now(), 0, 0, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能同时为0");
    }

    @Test
    void finishingTheProjectStopsDeclarationsButNotTheReview() {
        ProjectShareService.CreatedShareLink created = uploadLink(creation.getId());
        ProjectShareService.GuestContext context = guest(created);
        handInPhoto(context, "3");
        var declared = uploadService.declareWorklog(context, declaration(LocalDate.now().minusDays(1)));
        projectService.changeStatus(creation.getId(), ProjectStatus.COMPLETED,
                projectService.get(creation.getId()).getVersion(), minister);

        assertThat(shareService.access(context).allowWorklog()).isFalse();
        assertThatThrownBy(() -> uploadService.declareWorklog(context, declaration(LocalDate.now())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("所属项目已结束");
        // 审核侧不受限，否则选题结束时还没审完的申报会永远卡住。
        assertThat(worklogService.confirm(declared.id(), 1, minister).getStatus())
                .isEqualTo(WorklogStatus.CONFIRMED);
    }

    @Test
    void aBrowseLinkCannotDeclareWorklogs() {
        ProjectShareService.CreatedShareLink browse = shareService.create(creation.getId(),
                new ProjectShareService.CreateCommand(ShareLinkPurpose.BROWSE, "编辑部", PASSWORD,
                        true, false, null), minister);
        ProjectShareService.GuestSession session =
                shareService.openSession(browse.link().token(), PASSWORD, null);
        ProjectShareService.GuestContext context =
                shareService.resolveGuestContext(browse.link().token(), session.sessionToken());

        assertThat(shareService.access(context).allowWorklog()).isFalse();
        assertThatThrownBy(() -> uploadService.declareWorklog(context, declaration(LocalDate.now())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有开放上传");
        assertThatThrownBy(() -> uploadService.worklogs(context))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有开放上传");
    }

    @Test
    void aGuestOnlySeesTheirOwnDeclarations() {
        ProjectShareService.CreatedShareLink created = uploadLink(creation.getId());
        ProjectShareService.GuestContext mine = guest(created);
        ProjectShareService.GuestContext theirs = guest(created, "王同学", "20239902");
        handInPhoto(mine, "4");
        handInPhoto(theirs, "5");
        var declared = uploadService.declareWorklog(mine, declaration(LocalDate.now()));
        uploadService.declareWorklog(theirs, declaration(LocalDate.now()));

        assertThat(uploadService.worklogs(mine))
                .extracting(ProjectShareUploadService.GuestWorklog::id).containsExactly(declared.id());
    }

    // ------------------------------------------------------------------ 审核面板的边界

    @Test
    void aRevokedLinkStillTellsTheReviewerWhichProjectTheWorklogBelongsTo() {
        ProjectShareService.CreatedShareLink created = uploadLink(creation.getId());
        ProjectShareService.GuestContext context = guest(created);
        handInPhoto(context, "6");
        var declared = uploadService.declareWorklog(context, declaration(LocalDate.now()));

        shareService.delete(creation.getId(), created.link().id(), minister);

        // 链接是软删的：撤销之后已经交上来的申报还在面板里，而且说得出是哪个选题的。
        assertThat(reviewPanel(minister)).anyMatch(worklog -> worklog.getId().equals(declared.id())
                && "校庆特刊".equals(worklog.getProjectTitle()));
        assertThat(worklogService.confirm(declared.id(), 1, minister).getStatus())
                .isEqualTo(WorklogStatus.CONFIRMED);
    }

    @Test
    void campusScopedReviewersNeitherSeeNorTouchLinkWorklogs() {
        ProjectShareService.GuestContext context = guest(uploadLink(creation.getId()));
        handInPhoto(context, "7");
        var declared = uploadService.declareWorklog(context, declaration(LocalDate.now()));
        AuthenticatedUser campusReviewer = campusReviewer();

        // 这类工时不属于任何校区：列表里没有，凭 id 也动不了——两处必须一致。
        assertThat(reviewPanel(campusReviewer))
                .noneMatch(worklog -> worklog.getId().equals(declared.id()));
        assertThatThrownBy(() -> worklogService.confirm(declared.id(), 1, campusReviewer))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问该校区的工时");
        assertThatThrownBy(() -> worklogService.delete(declared.id(), campusReviewer))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问该校区的工时");
    }

    @Test
    void aLinkWorklogCannotBeEditedOrResubmittedInApp() {
        ProjectShareService.GuestContext context = guest(uploadLink(creation.getId()));
        handInPhoto(context, "8");
        var declared = uploadService.declareWorklog(context, declaration(LocalDate.now()));
        worklogService.reject(declared.id(), "请重新填", 1, minister);
        // 管理员能代任何人改站内的工时（requireOwned），所以用他来试：连他也改不了这一类。
        AuthenticatedUser admin = new AuthenticatedUser(MINISTER_ID, "worklog-minister", "工时部长",
                UserRole.ADMIN, null, false);

        // 名下的账号是开链接的成员，他不是干活的人；被退回的由申报人在上传页重新申报。
        assertThatThrownBy(() -> worklogService.submit(declared.id(), 2, admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能在站内修改");
        assertThatThrownBy(() -> worklogService.update(declared.id(),
                new WorklogService.WorklogCommand(LocalDate.now(), 1L, 30, 0, null, WorklogStatus.DRAFT),
                2, admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能在站内修改");
    }

    // ------------------------------------------------------------------ 统计与导出

    @Test
    void confirmedLinkWorklogsCountInStatisticsAndExports() {
        ProjectShareService.GuestContext context = guest(uploadLink(creation.getId()));
        handInPhoto(context, "9");
        LocalDate workDate = LocalDate.now().minusDays(1);
        var declared = uploadService.declareWorklog(context, declaration(workDate));

        // 待确认的不算数，和站内填报的口径一样。
        projectService.changeStatus(creation.getId(), ProjectStatus.COMPLETED,
                projectService.get(creation.getId()).getVersion(), minister);
        assertThat(statistics.members(null, null, creation.getId(), null, null)).isEmpty();

        worklogService.confirm(declared.id(), 1, minister);

        assertThat(statistics.members(null, null, creation.getId(), null, null))
                .singleElement().satisfies(member -> {
                    assertThat(member.studentId()).isEqualTo(STUDENT_ID);
                    assertThat(member.displayName()).isEqualTo("李外援");
                    assertThat(member.shootingMinutes()).isEqualTo(120);
                    assertThat(member.retouchingMinutes()).isEqualTo(45);
                    // 如实没有校区。
                    assertThat(member.campus()).isNull();
                });
        assertThat(statistics.memberWorklogs(STUDENT_ID, null, null, creation.getId(), null, Set.of()))
                .singleElement().satisfies(detail -> {
                    assertThat(detail.worklogId()).isEqualTo(declared.id());
                    assertThat(detail.requestId()).isNull();
                    assertThat(detail.requestTitle()).isNull();
                    assertThat(detail.viaShareLink()).isTrue();
                    assertThat(detail.projectTitle()).isEqualTo("校庆特刊");
                    assertThat(detail.totalMinutes()).isEqualTo(165);
                });
        assertThat(statistics.worklogs(workDate, workDate))
                .filteredOn(row -> STUDENT_ID.equals(row.studentId()))
                .singleElement().satisfies(row -> {
                    assertThat(row.memberName()).isEqualTo("李外援");
                    assertThat(row.totalMinutes()).isEqualTo(165);
                    assertThat(row.worklogStatus()).isEqualTo("已确认");
                });
    }

    @Test
    void linkWorklogsStayOutOfCampusFilteredStatistics() {
        ProjectShareService.GuestContext context = guest(uploadLink(creation.getId()));
        handInPhoto(context, "0");
        LocalDate workDate = LocalDate.now().minusDays(1);
        var declared = uploadService.declareWorklog(context, declaration(workDate));
        worklogService.confirm(declared.id(), 1, minister);
        projectService.changeStatus(creation.getId(), ProjectStatus.COMPLETED,
                projectService.get(creation.getId()).getVersion(), minister);
        long campusId = campus();

        // 按校区筛、或者校区范围账号来看：这类工时不属于任何校区，不该被算到某个校区头上。
        assertThat(statistics.members(null, null, creation.getId(), campusId, null)).isEmpty();
        assertThat(statistics.members(null, null, creation.getId(), null, null, Set.of(campusId))).isEmpty();
        assertThat(statistics.worklogs(workDate, workDate, Set.of(campusId)))
                .noneMatch(row -> STUDENT_ID.equals(row.studentId()));
    }
}
