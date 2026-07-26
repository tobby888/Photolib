package cn.photolib.worklog;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.common.error.BusinessException;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.user.model.UserRole;
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

@SpringBootTest
@Transactional
class WorklogPermissionScopeTests {
    @Autowired
    private WorklogService worklogs;
    @Autowired
    private CampusService campuses;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void campusScopedReviewerOnlyListsAndConfirmsAuthorizedCampusWorklogs() {
        long base = System.nanoTime() & Long.MAX_VALUE;
        var campusA = campuses.create("WL-A-" + base, "工时授权校区");
        var campusB = campuses.create("WL-B-" + base, "工时未授权校区");
        long memberId = base;
        long projectId = base + 1;
        long requestA = base + 2;
        long requestB = base + 3;
        long worklogA = base + 4;
        long worklogB = base + 5;
        jdbc.sql("""
                INSERT INTO app_user(id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', '工时成员', 'CAMPUS_MANAGER', TRUE, FALSE)
                """).param("id", memberId).param("username", "worklog-scope-" + base).update();
        jdbc.sql("""
                INSERT INTO project(id, title, status, created_by)
                VALUES (:id, '工时范围项目', 'ACTIVE', :userId)
                """).param("id", projectId).param("userId", memberId).update();
        jdbc.sql("""
                INSERT INTO photo_request(id, project_id, title, campus_id, deadline, status, created_by)
                VALUES
                    (:requestA, :projectId, '授权需求', :campusA, :deadline, 'ACCEPTED', :userId),
                    (:requestB, :projectId, '未授权需求', :campusB, :deadline, 'ACCEPTED', :userId)
                """).param("requestA", requestA).param("requestB", requestB).param("projectId", projectId)
                .param("campusA", campusA.getId()).param("campusB", campusB.getId())
                .param("deadline", LocalDateTime.now().plusDays(1)).param("userId", memberId).update();
        jdbc.sql("""
                INSERT INTO worklog(id, request_id, user_id, work_date, shooting_minutes,
                                    retouching_minutes, member_name, member_student_id, status)
                VALUES
                    (:worklogA, :requestA, :userId, :workDate, 60, 0, '授权成员', 'A-1', 'SUBMITTED'),
                    (:worklogB, :requestB, :userId, :workDate, 60, 0, '未授权成员', 'B-1', 'SUBMITTED')
                """).param("worklogA", worklogA).param("requestA", requestA)
                .param("worklogB", worklogB).param("requestB", requestB)
                .param("userId", memberId).param("workDate", LocalDate.now()).update();
        var reviewer = new AuthenticatedUser(memberId, "reviewer", "校区审核员",
                UserRole.CAMPUS_MANAGER, campusA.getId(), false, 90L, "CAMPUS_REVIEWER",
                "校区工时审核组", DataScope.CAMPUS,
                Set.of(PermissionCode.WORKLOG_CONFIRM, PermissionCode.WORKLOG_EXPORT),
                Set.of(campusA.getId()));

        assertThat(worklogs.list(1, 20, null, null, null, null, null, reviewer).items())
                .extracting(cn.photolib.worklog.model.WorklogEntity::getId)
                .containsExactly(worklogA);
        assertThat(worklogs.confirm(worklogA, 1, reviewer).getStatus().name()).isEqualTo("CONFIRMED");
        assertThatThrownBy(() -> worklogs.confirm(worklogB, 1, reviewer))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权访问该校区");
    }
}
