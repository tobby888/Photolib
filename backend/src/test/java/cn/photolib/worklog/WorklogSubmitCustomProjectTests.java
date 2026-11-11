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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 填报工时时写自定义选题：存进 {@code custom_project_title}（去首尾空白、空串为 NULL），
 * 改草稿时能清掉，审核后保留，并按工作日期进成员核算——所属需求的选题没结束也照样算。
 */
@SpringBootTest
@Transactional
class WorklogSubmitCustomProjectTests {
    @Autowired
    private WorklogService worklogs;
    @Autowired
    private StatisticsService statistics;
    @Autowired
    private JdbcClient jdbc;

    private long base;
    private long requestId;
    private long memberContactId;
    private String studentId;
    private AuthenticatedUser filer;
    private AuthenticatedUser reviewer;

    @BeforeEach
    void setUp() {
        base = (System.nanoTime() & 0xFFFFFFFFFFL) * 1000;
        long userId = base;
        long campusId = base + 1;
        long projectId = base + 2;
        requestId = base + 3;
        memberContactId = base + 4;
        studentId = "SUB-CP-" + base;
        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (:id, :code, '填报自定义校区')")
                .param("id", campusId).param("code", "WL-SUB-CP-" + base).update();
        jdbc.sql("""
                INSERT INTO app_user(id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', '填报负责人', 'CAMPUS_MANAGER', TRUE, FALSE)
                """).param("id", userId).param("username", "worklog-sub-cp-" + base).update();
        jdbc.sql("""
                INSERT INTO app_user(id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', '部长', 'MINISTER', TRUE, FALSE)
                """).param("id", base + 9).param("username", "worklog-sub-cp-reviewer-" + base).update();
        // 选题还在进行：普通工时不进核算，写了自定义选题的按工作日期算。
        jdbc.sql("INSERT INTO project(id, title, status, created_by) VALUES (:id, '进行中选题', 'ACTIVE', :userId)")
                .param("id", projectId).param("userId", userId).update();
        jdbc.sql("""
                INSERT INTO photo_request(id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:id, :projectId, '填报需求', :campusId, :deadline, 'ACCEPTED', :userId)
                """).param("id", requestId).param("projectId", projectId).param("campusId", campusId)
                .param("deadline", LocalDateTime.now().plusDays(1)).param("userId", userId).update();
        jdbc.sql("""
                INSERT INTO request_participant(request_id, user_id, accepted_at)
                VALUES (:requestId, :userId, :acceptedAt)
                """).param("requestId", requestId).param("userId", userId)
                .param("acceptedAt", LocalDateTime.now()).update();
        jdbc.sql("""
                INSERT INTO campus_member(id, campus_id, student_id, name, enabled)
                VALUES (:id, :campusId, :studentId, '填报成员', TRUE)
                """).param("id", memberContactId).param("campusId", campusId).param("studentId", studentId).update();
        filer = new AuthenticatedUser(userId, "worklog-sub-cp-" + base, "填报负责人", UserRole.CAMPUS_MANAGER,
                campusId, false, 91L, "CAMPUS_MANAGER", "校区负责人组", DataScope.CAMPUS,
                Set.of(PermissionCode.WORKLOG_SUBMIT), Set.of(campusId));
        reviewer = new AuthenticatedUser(base + 9, "worklog-sub-cp-reviewer-" + base, "部长", UserRole.MINISTER,
                null, false, 2L, "MINISTER", "部长", DataScope.GLOBAL,
                Set.of(PermissionCode.WORKLOG_CONFIRM), Set.of());
    }

    @Test
    void theFilerWritesACustomProjectThatCountsByWorkDate() {
        WorklogEntity created = worklogs.create(requestId, command(WorklogStatus.SUBMITTED, "  迎新晚会  "), filer);
        assertThat(created.getCustomProjectTitle()).isEqualTo("迎新晚会");

        // 审核时整行写回，自定义选题保留。
        WorklogEntity confirmed = worklogs.confirm(created.getId(), 1, reviewer);
        assertThat(confirmed.getStatus()).isEqualTo(WorklogStatus.CONFIRMED);
        assertThat(confirmed.getCustomProjectTitle()).isEqualTo("迎新晚会");

        LocalDate workDate = LocalDate.now().minusDays(1);
        assertThat(statistics.members(workDate, workDate, null, null, null))
                .filteredOn(member -> studentId.equals(member.studentId()))
                .extracting(MemberStatistics::totalMinutes).containsExactly(45);
    }

    @Test
    void editingADraftCanClearTheCustomProject() {
        WorklogEntity draft = worklogs.create(requestId, command(WorklogStatus.DRAFT, "迎新晚会"), filer);

        WorklogEntity edited = worklogs.update(draft.getId(), command(WorklogStatus.DRAFT, "   "), 1, filer);

        assertThat(edited.getCustomProjectTitle()).isNull();
        assertThat(jdbc.sql("SELECT custom_project_title FROM worklog WHERE id=:id").param("id", draft.getId())
                .query(String.class).list()).containsExactly((String) null);
    }

    @Test
    void customProjectIsOptionalAndBounded() {
        assertThat(worklogs.create(requestId, command(WorklogStatus.DRAFT, null), filer).getCustomProjectTitle())
                .isNull();
        assertThatThrownBy(() -> worklogs.create(requestId, command(WorklogStatus.DRAFT, "选".repeat(201)), filer))
                .isInstanceOf(BusinessException.class).hasMessageContaining("200");
    }

    private WorklogService.WorklogCommand command(WorklogStatus status, String customProject) {
        return new WorklogService.WorklogCommand(LocalDate.now().minusDays(1), memberContactId, 45, 0, null,
                status, customProject);
    }
}
