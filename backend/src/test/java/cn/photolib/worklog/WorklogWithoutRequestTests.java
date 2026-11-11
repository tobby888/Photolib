package cn.photolib.worklog;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.statistics.StatisticsService;
import cn.photolib.statistics.StatisticsService.MemberStatistics;
import cn.photolib.user.model.UserRole;
import cn.photolib.worklog.model.WorklogEntity;
import cn.photolib.worklog.model.WorklogStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 不关联需求、只写自定义选题的工时（V79，{@link WorklogService#createWithoutRequest}）：
 * 系统里没有这个选题也就没有需求，工作人员从任意校区通讯录里选；只给能向任意需求申报、且不限校区的账号。
 */
@SpringBootTest
@Transactional
class WorklogWithoutRequestTests {
    @Autowired
    private WorklogService worklogs;
    @Autowired
    private StatisticsService statistics;
    @Autowired
    private JdbcClient jdbc;

    private long base;
    private long campusA;
    private long campusB;
    private long memberA;
    private long memberB;
    private long disabledMember;
    private long requestId;
    private AuthenticatedUser admin;

    @BeforeEach
    void setUp() {
        base = (System.nanoTime() & 0xFFFFFFFFFFL) * 1000;
        long adminId = base;
        campusA = base + 1;
        campusB = base + 2;
        memberA = base + 3;
        memberB = base + 4;
        disabledMember = base + 5;
        requestId = base + 7;
        jdbc.sql("""
                INSERT INTO app_user(id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', '无需求管理员', 'ADMIN', TRUE, FALSE)
                """).param("id", adminId).param("username", "worklog-noreq-" + base).update();
        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (:a, :ca, '甲校区'), (:b, :cb, '乙校区')")
                .param("a", campusA).param("ca", "NOREQ-A-" + base)
                .param("b", campusB).param("cb", "NOREQ-B-" + base).update();
        jdbc.sql("""
                INSERT INTO campus_member(id, campus_id, student_id, name, enabled)
                VALUES (:a, :campusA, :sa, '甲同学', TRUE), (:b, :campusB, :sb, '乙同学', TRUE),
                       (:d, :campusB, :sd, '停用同学', FALSE)
                """).param("a", memberA).param("campusA", campusA).param("sa", "NOREQ-A-" + base)
                .param("b", memberB).param("campusB", campusB).param("sb", "NOREQ-B-" + base)
                .param("d", disabledMember).param("sd", "NOREQ-D-" + base).update();
        jdbc.sql("INSERT INTO project(id, title, status, created_by) VALUES (:id, '真实选题', 'ACTIVE', :u)")
                .param("id", base + 6).param("u", adminId).update();
        jdbc.sql("""
                INSERT INTO photo_request(id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:id, :projectId, '真实需求', :campusId, :deadline, 'ACCEPTED', :u)
                """).param("id", requestId).param("projectId", base + 6).param("campusId", campusA)
                .param("deadline", LocalDateTime.now().plusDays(1)).param("u", adminId).update();
        admin = new AuthenticatedUser(adminId, "worklog-noreq-" + base, "无需求管理员", UserRole.ADMIN, null, false,
                1L, "ADMIN", "管理员", DataScope.GLOBAL, EnumSet.allOf(PermissionCode.class), Set.of());
    }

    @Test
    void adminFilesWithoutARequestPickingAnyCampusMember() {
        WorklogEntity draft = worklogs.createWithoutRequest(command(memberA, "迎新晚会", WorklogStatus.DRAFT), admin);
        assertThat(draft.getRequestId()).isNull();
        assertThat(draft.getShareLinkId()).isNull();
        assertThat(draft.getCustomProjectTitle()).isEqualTo("迎新晚会");
        assertThat(draft.getMemberName()).isEqualTo("甲同学");

        // 改草稿时换成另一个校区的同学，再提交：都不需要需求。
        WorklogEntity edited = worklogs.update(draft.getId(), command(memberB, "迎新晚会", WorklogStatus.DRAFT), 1,
                admin);
        assertThat(edited.getMemberName()).isEqualTo("乙同学");
        WorklogEntity submitted = worklogs.submit(edited.getId(), edited.getVersion(), admin);
        assertThat(submitted.getStatus()).isEqualTo(WorklogStatus.SUBMITTED);
        WorklogEntity confirmed = worklogs.confirm(submitted.getId(), submitted.getVersion(), admin);

        // 列表里看得到（没有需求标题），按工作日期进成员核算。
        assertThat(worklogs.list(1, 20, new WorklogService.ListFilter(null, null, null, null, null, null, null,
                "迎新晚会", null), admin).items()).extracting(WorklogEntity::getId).contains(confirmed.getId());
        LocalDate workDate = LocalDate.now().minusDays(1);
        assertThat(statistics.members(workDate, workDate, null, null, null))
                .filteredOn(member -> ("NOREQ-B-" + base).equals(member.studentId()))
                .extracting(MemberStatistics::totalMinutes).containsExactly(45);
    }

    @Test
    void aCustomProjectIsRequiredAndTheMemberMustBeEnabled() {
        assertThatThrownBy(() -> worklogs.createWithoutRequest(command(memberA, "  ", WorklogStatus.SUBMITTED), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("必须填写自定义选题");
        assertThatThrownBy(() -> worklogs.createWithoutRequest(
                command(disabledMember, "迎新晚会", WorklogStatus.SUBMITTED), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("有效成员");

        WorklogEntity draft = worklogs.createWithoutRequest(command(memberA, "迎新晚会", WorklogStatus.DRAFT), admin);
        assertThatThrownBy(() -> worklogs.update(draft.getId(), command(memberA, null, WorklogStatus.DRAFT), 1, admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("必须填写自定义选题");
    }

    @Test
    void onlyGlobalAccountsThatMaySubmitAnywhereCanFileWithoutARequest() {
        AuthenticatedUser campusManager = new AuthenticatedUser(base + 8, "noreq-cm-" + base, "校区负责人",
                UserRole.CAMPUS_MANAGER, campusA, false, 91L, "CAMPUS_MANAGER", "校区负责人组", DataScope.CAMPUS,
                Set.of(PermissionCode.WORKLOG_SUBMIT, PermissionCode.WORKLOG_SUBMIT_ANY), Set.of(campusA));
        AuthenticatedUser globalFiler = new AuthenticatedUser(base + 9, "noreq-g-" + base, "普通填报人",
                UserRole.MINISTER, null, false, 2L, "MINISTER", "部长", DataScope.GLOBAL,
                Set.of(PermissionCode.WORKLOG_SUBMIT), Set.of());

        for (AuthenticatedUser user : new AuthenticatedUser[]{campusManager, globalFiler}) {
            assertThatThrownBy(() -> worklogs.createWithoutRequest(
                    command(memberA, "迎新晚会", WorklogStatus.SUBMITTED), user))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("不关联需求");
        }
    }

    @Test
    void adminCorrectionMayDropTheRequestOnlyWhenACustomProjectIsWritten() {
        long id = base + 100;
        jdbc.sql("""
                INSERT INTO worklog(id, request_id, user_id, work_date, shooting_minutes, retouching_minutes,
                                    member_name, member_student_id, status)
                VALUES (:id, :r, :u, :d, 60, 0, '甲同学', :sid, 'CONFIRMED')
                """).param("id", id).param("r", requestId).param("u", admin.id())
                .param("d", LocalDate.now().minusDays(1)).param("sid", "NOREQ-A-" + base).update();

        assertThatThrownBy(() -> worklogs.correct(id, correction(true, null), 1, admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("要么关联一条需求，要么填写自定义选题");

        WorklogEntity dropped = worklogs.correct(id, correction(true, "迎新晚会"), 1, admin).worklog();
        assertThat(dropped.getRequestId()).isNull();
        assertThat(dropped.getCustomProjectTitle()).isEqualTo("迎新晚会");

        // 已经不挂需求了，就不能再把自定义选题清掉。
        assertThatThrownBy(() -> worklogs.correct(id, correction(false, ""), 2, admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("要么关联一条需求，要么填写自定义选题");
    }

    private WorklogService.CorrectionCommand correction(boolean clearRequest, String customProject) {
        return new WorklogService.CorrectionCommand("甲同学", "NOREQ-A-" + base, LocalDate.now().minusDays(1),
                60, 0, null, null, null, clearRequest, customProject, null);
    }

    private WorklogService.WorklogCommand command(long memberId, String customProject, WorklogStatus status) {
        return new WorklogService.WorklogCommand(LocalDate.now().minusDays(1), memberId, 45, 0, null, status,
                customProject);
    }
}
