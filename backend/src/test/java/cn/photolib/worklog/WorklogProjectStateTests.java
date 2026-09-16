package cn.photolib.worklog;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.common.error.BusinessException;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 已结束（已完成/已取消）项目下的需求不能再填报工时：新建、编辑、提交三个入口都要挡住，
 * 否则项目结束后还能补记工时，导出口径就对不上。持有 {@code WORKLOG_SUBMIT_ANY} 的账号例外，
 * 但仍受需求校区约束。
 */
@SpringBootTest
@Transactional
class WorklogProjectStateTests {
    @Autowired
    private WorklogService worklogs;
    @Autowired
    private CampusService campuses;
    @Autowired
    private JdbcClient jdbc;

    private long projectId;
    private long requestId;
    private long memberContactId;
    private AuthenticatedUser submitter;
    private long base;
    private long campusId;

    @BeforeEach
    void setUp() {
        base = System.nanoTime() & Long.MAX_VALUE;
        var campus = campuses.create("WL-PS-" + base, "工时项目状态校区");
        campusId = campus.getId();
        long userId = base;
        projectId = base + 1;
        requestId = base + 2;
        memberContactId = base + 3;
        jdbc.sql("""
                INSERT INTO app_user(id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', '校区负责人', 'CAMPUS_MANAGER', TRUE, FALSE)
                """).param("id", userId).param("username", "worklog-project-" + base).update();
        jdbc.sql("""
                INSERT INTO project(id, title, status, created_by)
                VALUES (:id, '工时项目状态项目', 'ACTIVE', :userId)
                """).param("id", projectId).param("userId", userId).update();
        jdbc.sql("""
                INSERT INTO photo_request(id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:id, :projectId, '工时项目状态需求', :campusId, :deadline, 'ACCEPTED', :userId)
                """).param("id", requestId).param("projectId", projectId)
                .param("campusId", campus.getId())
                .param("deadline", LocalDateTime.now().plusDays(1))
                .param("userId", userId).update();
        jdbc.sql("""
                INSERT INTO request_participant(request_id, user_id, accepted_at)
                VALUES (:requestId, :userId, :acceptedAt)
                """).param("requestId", requestId).param("userId", userId)
                .param("acceptedAt", LocalDateTime.now()).update();
        jdbc.sql("""
                INSERT INTO campus_member(id, campus_id, student_id, name, enabled)
                VALUES (:id, :campusId, :studentId, '工时成员', TRUE)
                """).param("id", memberContactId).param("campusId", campus.getId())
                .param("studentId", "PS-" + base).update();
        submitter = new AuthenticatedUser(userId, "worklog-project-" + base, "校区负责人",
                UserRole.CAMPUS_MANAGER, campus.getId(), false, 91L, "CAMPUS_MANAGER",
                "校区负责人组", DataScope.CAMPUS,
                Set.of(PermissionCode.WORKLOG_SUBMIT), Set.of(campus.getId()));
    }

    @Test
    void activeProjectStillAcceptsWorklog() {
        WorklogEntity worklog = worklogs.create(requestId, command(WorklogStatus.SUBMITTED), submitter);

        assertThat(worklog.getStatus()).isEqualTo(WorklogStatus.SUBMITTED);
        assertThat(worklog.getRequestId()).isEqualTo(requestId);
    }

    @Test
    void completedProjectRejectsNewWorklog() {
        setProjectStatus("COMPLETED");

        assertThatThrownBy(() -> worklogs.create(requestId, command(WorklogStatus.SUBMITTED), submitter))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("所属项目已结束");
    }

    @Test
    void cancelledProjectRejectsNewWorklog() {
        setProjectStatus("CANCELLED");

        assertThatThrownBy(() -> worklogs.create(requestId, command(WorklogStatus.DRAFT), submitter))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("所属项目已结束");
    }

    @Test
    void completedProjectRejectsEditingAndSubmittingAnExistingDraft() {
        WorklogEntity draft = worklogs.create(requestId, command(WorklogStatus.DRAFT), submitter);
        setProjectStatus("COMPLETED");

        assertThatThrownBy(() -> worklogs.submit(draft.getId(), 1, submitter))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("所属项目已结束");
        assertThatThrownBy(() -> worklogs.update(draft.getId(), command(WorklogStatus.DRAFT), 1, submitter))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("所属项目已结束");
        assertThat(jdbc.sql("SELECT status FROM worklog WHERE id=:id")
                .param("id", draft.getId()).query(String.class).single()).isEqualTo("DRAFT");
    }

    @Test
    void submitAnyPermissionFilesWorklogOnAnyRequestOfAnEndedProject() {
        setProjectStatus("COMPLETED");
        AuthenticatedUser admin = anyRequestSubmitter(base + 10, DataScope.GLOBAL, Set.of());

        // 管理员不是该需求的参与人，项目也已完成，仍然可以新建、编辑、提交。
        WorklogEntity draft = worklogs.create(requestId, command(WorklogStatus.DRAFT), admin);
        WorklogEntity edited = worklogs.update(draft.getId(), command(WorklogStatus.DRAFT), 1, admin);
        WorklogEntity submitted = worklogs.submit(edited.getId(), edited.getVersion(), admin);

        assertThat(submitted.getStatus()).isEqualTo(WorklogStatus.SUBMITTED);
        assertThat(submitted.getUserId()).isEqualTo(base + 10);

        setProjectStatus("CANCELLED");
        assertThat(worklogs.create(requestId, command(WorklogStatus.SUBMITTED), admin).getStatus())
                .isEqualTo(WorklogStatus.SUBMITTED);
    }

    @Test
    void submitAnyPermissionStillRequiresTheRequestCampusMember() {
        setProjectStatus("COMPLETED");
        AuthenticatedUser admin = anyRequestSubmitter(base + 10, DataScope.GLOBAL, Set.of());
        var otherCampus = campuses.create("WL-PS-O-" + base, "工时项目状态其他校区");
        long otherMember = base + 11;
        jdbc.sql("""
                INSERT INTO campus_member(id, campus_id, student_id, name, enabled)
                VALUES (:id, :campusId, :studentId, '其他校区成员', TRUE)
                """).param("id", otherMember).param("campusId", otherCampus.getId())
                .param("studentId", "PS-O-" + base).update();

        assertThatThrownBy(() -> worklogs.create(requestId, new WorklogService.WorklogCommand(
                LocalDate.now(), otherMember, 60, 0, "跨校区成员", WorklogStatus.SUBMITTED), admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("需求所属校区通讯录");
    }

    @Test
    void submitAnyPermissionDoesNotReachRequestsOutsideTheAuthorizedCampus() {
        setProjectStatus("COMPLETED");
        var otherCampus = campuses.create("WL-PS-S-" + base, "工时项目状态授权校区");
        AuthenticatedUser scoped = anyRequestSubmitter(base + 12, DataScope.CAMPUS, Set.of(otherCampus.getId()));

        assertThatThrownBy(() -> worklogs.create(requestId, command(WorklogStatus.SUBMITTED), scoped))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问该校区");

        AuthenticatedUser inCampus = anyRequestSubmitter(base + 13, DataScope.CAMPUS, Set.of(campusId));
        assertThat(worklogs.create(requestId, command(WorklogStatus.SUBMITTED), inCampus).getStatus())
                .isEqualTo(WorklogStatus.SUBMITTED);
    }

    private AuthenticatedUser anyRequestSubmitter(long userId, DataScope scope, Set<Long> campusIds) {
        jdbc.sql("""
                INSERT INTO app_user(id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', '补录工时账号', 'ADMIN', TRUE, FALSE)
                """).param("id", userId).param("username", "worklog-any-" + userId).update();
        return new AuthenticatedUser(userId, "worklog-any-" + userId, "补录工时账号",
                UserRole.ADMIN, null, false, 92L, "CUSTOM", "补录工时组", scope,
                Set.of(PermissionCode.WORKLOG_SUBMIT, PermissionCode.WORKLOG_SUBMIT_ANY), campusIds);
    }

    private void setProjectStatus(String status) {
        jdbc.sql("UPDATE project SET status=:status WHERE id=:id")
                .param("status", status).param("id", projectId).update();
    }

    private WorklogService.WorklogCommand command(WorklogStatus status) {
        return new WorklogService.WorklogCommand(LocalDate.now(), memberContactId,
                60, 30, "项目状态过滤测试", status);
    }
}
