package cn.photolib.worklog;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.settlement.SettlementStatementService;
import cn.photolib.statistics.StatisticsService;
import cn.photolib.statistics.StatisticsService.MemberStatistics;
import cn.photolib.statistics.StatisticsService.MemberWorklogDetail;
import cn.photolib.statistics.analysis.WorklogAnalysisService;
import cn.photolib.statistics.analysis.WorklogAnalysisService.AnalysisRequest;
import cn.photolib.statistics.analysis.WorklogAnalysisService.Bucket;
import cn.photolib.statistics.analysis.WorklogAnalysisService.Dimension;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 管理员手写的自定义选题（Flyway V78，{@link WorklogProjectSql}）在各处统计里的口径：
 * 填了就算在自定义选题下、不再算进需求所属的选题；按工作日期落清算期；同名合并。
 * 工资计算的部分在 {@code SalaryCalculationServiceTests}。
 */
@SpringBootTest
@Transactional
class WorklogCustomProjectStatisticsTests {
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    @Autowired
    JdbcClient jdbc;
    @Autowired
    StatisticsService statistics;
    @Autowired
    SettlementStatementService settlements;
    @Autowired
    WorklogAnalysisService analysis;

    private long base;
    private long sequence = 100;
    private long userId;
    private long campusId;
    private long settledProject;
    private String name;
    private String studentId;

    @BeforeEach
    void setUp() {
        base = (System.nanoTime() & 0xFFFFFFFFFFL) * 1000;
        campusId = base + 1;
        userId = base + 2;
        name = "自定义甲" + base;
        studentId = "CP-" + base;
        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (:id, :code, '自定义校区')")
                .param("id", campusId).param("code", "CUSTOM-" + base).update();
        // 账号姓名与工时姓名相同：清算单按姓名匹配。
        jdbc.sql("""
                INSERT INTO app_user(id, username, password_hash, display_name, email, role, enabled,
                                     must_change_password)
                VALUES (:id, :username, 'hash', :name, 'custom@example.com', 'CAMPUS_MANAGER', TRUE, FALSE)
                """).param("id", userId).param("username", "custom-" + base).param("name", name).update();

        settledProject = project("运动会", "COMPLETED", LocalDateTime.of(2026, 10, 10, 18, 0));
        long ongoingProject = project("还没结束", "ACTIVE", null);
        long settledRequest = request(settledProject);
        long ongoingRequest = request(ongoingProject);

        // 自定义选题按工作日期算，不管需求所属选题有没有结束；同名的合并成一组。
        worklog(settledRequest, "迎新晚会", LocalDate.of(2026, 10, 5), 60);
        worklog(ongoingRequest, "迎新晚会", LocalDate.of(2026, 10, 6), 30);
        // 没有自定义选题的照旧按选题结束时间算。
        worklog(settledRequest, null, LocalDate.of(2026, 10, 5), 20);
        // 工作日期在期外的自定义选题不算；选题没结束的普通工时也不算。
        worklog(settledRequest, "迎新晚会", LocalDate.of(2026, 9, 20), 100);
        worklog(ongoingRequest, null, LocalDate.of(2026, 10, 5), 500);
    }

    @Test
    void memberStatisticsAndExportIncludeCustomProjects() {
        assertThat(statistics.members(FROM, TO, null, null, null)).filteredOn(m -> studentId.equals(m.studentId()))
                .extracting(MemberStatistics::totalMinutes).containsExactly(110);
        // 按选题筛时，填了自定义选题的工时已经不属于需求所属的选题。
        assertThat(statistics.members(FROM, TO, settledProject, null, null))
                .filteredOn(m -> studentId.equals(m.studentId()))
                .extracting(MemberStatistics::totalMinutes).containsExactly(20);

        assertThat(statistics.memberWorklogs(studentId, FROM, TO, null, null, Set.of()))
                .extracting(MemberWorklogDetail::projectId, MemberWorklogDetail::projectTitle,
                        MemberWorklogDetail::totalMinutes, MemberWorklogDetail::customProject)
                .containsExactlyInAnyOrder(
                        tuple(null, "迎新晚会", 60, true),
                        tuple(null, "迎新晚会", 30, true),
                        tuple(settledProject, "运动会", 20, false));

        assertThat(statistics.worklogs(FROM, TO)).filteredOn(row -> studentId.equals(row.studentId()))
                .extracting(StatisticsService.WorklogExportRow::totalMinutes).containsExactly(110);
    }

    @Test
    void settlementStatementIncludesCustomProjects() {
        SettlementStatementService.Recipient recipient = settlements.recipient(userId, FROM, TO);
        assertThat(recipient.confirmedMinutes()).isEqualTo(110);
        assertThat(recipient.worklogCount()).isEqualTo(3);

        SettlementStatementService.Statement statement = settlements.statement(recipient, FROM, TO);
        assertThat(statement.totals().confirmedMinutes()).isEqualTo(110);
        // 真实选题在前，自定义选题（没有结束时间）在后。
        assertThat(statement.worklogs()).extracting(SettlementStatementService.WorklogLine::projectTitle,
                        line -> line.projectCompletedAt() == null)
                .containsExactly(tuple("运动会", false), tuple("迎新晚会", true), tuple("迎新晚会", true));
    }

    @Test
    void worklogAnalysisGroupsCustomProjectsByName() {
        AuthenticatedUser admin = new AuthenticatedUser(userId, "custom-" + base, name, UserRole.ADMIN, null, false,
                1L, "ADMIN", "管理员", DataScope.GLOBAL, EnumSet.allOf(PermissionCode.class), Set.of());

        WorklogAnalysisService.AnalysisResult result = analysis.analyze(
                new AnalysisRequest(FROM, TO, null, Dimension.PROJECT, null), admin);

        List<Bucket> custom = result.groups().stream().filter(b -> b.key().equals("custom:迎新晚会")).toList();
        assertThat(custom).extracting(Bucket::label, Bucket::totalMinutes)
                .containsExactly(tuple("迎新晚会（自定义）", 90L));
        assertThat(result.rows()).filteredOn(row -> studentId.equals(row.memberStudentId()))
                .extracting(WorklogAnalysisService.Row::projectId, WorklogAnalysisService.Row::projectTitle)
                .contains(tuple(null, "迎新晚会"), tuple(settledProject, "运动会"));
    }

    private long project(String title, String status, LocalDateTime completedAt) {
        long id = base + sequence++;
        jdbc.sql("""
                INSERT INTO project(id, title, status, created_by, completed_at)
                VALUES (:id, :title, :status, :userId, :completedAt)
                """).param("id", id).param("title", title).param("status", status).param("userId", userId)
                .param("completedAt", completedAt).update();
        return id;
    }

    private long request(long project) {
        long id = base + sequence++;
        jdbc.sql("""
                INSERT INTO photo_request(id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:id, :projectId, '自定义需求', :campusId, :deadline, 'ACCEPTED', :userId)
                """).param("id", id).param("projectId", project).param("campusId", campusId)
                .param("deadline", LocalDateTime.now().plusDays(3)).param("userId", userId).update();
        return id;
    }

    private void worklog(long request, String customProject, LocalDate workDate, int minutes) {
        jdbc.sql("""
                INSERT INTO worklog(id, request_id, user_id, work_date, shooting_minutes, retouching_minutes,
                                    member_name, member_student_id, status, custom_project_title)
                VALUES (:id, :requestId, :userId, :workDate, :minutes, 0, :name, :studentId, 'CONFIRMED',
                        :customProject)
                """).param("id", base + sequence++).param("requestId", request).param("userId", userId)
                .param("workDate", workDate).param("minutes", minutes).param("name", name)
                .param("studentId", studentId).param("customProject", customProject).update();
    }
}
