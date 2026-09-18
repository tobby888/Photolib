package cn.photolib.statistics;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 成员统计的归并口径与工时明细。只读用例，用 {@code @Transactional} 回滚，
 * 避免这些行留在整轮共用的 H2 内存库里影响别的测试。
 */
@SpringBootTest
@Transactional
class MemberStatisticsTests {
    private static final LocalDate FROM = LocalDate.of(2021, 11, 1);
    private static final LocalDate TO = LocalDate.of(2021, 11, 30);

    @Autowired
    JdbcClient jdbc;
    @Autowired
    StatisticsService statistics;

    private long base;
    private long userId;
    private long campusA;
    private long campusB;
    private long projectId;
    private long otherProjectId;
    private long requestA;
    private long requestB;
    private long otherRequest;

    @BeforeEach
    void seed() {
        base = System.nanoTime() & Long.MAX_VALUE;
        userId = base;
        campusA = base + 1;
        campusB = base + 2;
        projectId = base + 3;
        otherProjectId = base + 4;
        requestA = base + 5;
        requestB = base + 6;
        otherRequest = base + 7;

        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (:id, :code, '北校区')")
                .param("id", campusA).param("code", "member-a-" + base).update();
        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (:id, :code, '南校区')")
                .param("id", campusB).param("code", "member-b-" + base).update();
        jdbc.sql("""
                INSERT INTO app_user(id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', '统计账号', 'ADMIN', TRUE, FALSE)
                """).param("id", userId).param("username", "member-stats-" + base).update();
        insertProject(projectId, "跨校区项目", LocalDateTime.of(2021, 11, 15, 12, 0));
        // 结束时间落在统计区间外的项目，其工时不该进统计，也不该进明细。
        insertProject(otherProjectId, "区间外项目", LocalDateTime.of(2021, 12, 15, 12, 0));
        insertRequest(requestA, projectId, "北校区需求", campusA);
        insertRequest(requestB, projectId, "南校区需求", campusB);
        insertRequest(otherRequest, otherProjectId, "区间外需求", campusA);
    }

    @Test
    void mergesOneStudentAcrossCampusesEvenWhenNameSnapshotsDiffer() {
        // 同一个人在两个校区通讯录里各有一行，姓名快照多了个空格——这在过去会把他劈成两行工时。
        insertWorklog(base + 10, requestA, LocalDate.of(2021, 11, 3), "李四", "20210001", 60, 30);
        insertWorklog(base + 11, requestB, LocalDate.of(2021, 11, 8), "李四 ", "20210001", 30, 0);

        List<StatisticsService.MemberStatistics> rows =
                statistics.members(FROM, TO, projectId, null, null);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.studentId()).isEqualTo("20210001");
            assertThat(row.shootingMinutes()).isEqualTo(90);
            assertThat(row.retouchingMinutes()).isEqualTo(30);
            assertThat(row.totalMinutes()).isEqualTo(120);
            // 校区列给出全部校区，取其中一个会让跨校区的人看起来只在一个校区干过活。
            assertThat(row.campus()).isEqualTo("北校区、南校区");
        });
    }

    @Test
    void memberWorklogDetailsAddUpToTheAggregatedRow() {
        insertWorklog(base + 10, requestA, LocalDate.of(2021, 11, 3), "李四", "20210001", 60, 30);
        insertWorklog(base + 11, requestB, LocalDate.of(2021, 11, 8), "李四", "20210001", 30, 0);
        // 未确认工时、区间外项目的工时、别人的工时都不该出现在明细里。
        insertWorklog(base + 12, requestA, LocalDate.of(2021, 11, 9), "李四", "20210001",
                500, 500, "SUBMITTED");
        insertWorklog(base + 13, otherRequest, LocalDate.of(2021, 11, 10), "李四", "20210001", 400, 400);
        insertWorklog(base + 14, requestA, LocalDate.of(2021, 11, 4), "王五", "20210002", 45, 0);

        List<StatisticsService.MemberWorklogDetail> details =
                statistics.memberWorklogs("20210001", FROM, TO, projectId, null, Set.of());

        assertThat(details).hasSize(2);
        assertThat(details).extracting(StatisticsService.MemberWorklogDetail::requestTitle)
                .containsExactly("南校区需求", "北校区需求");
        assertThat(details).extracting(StatisticsService.MemberWorklogDetail::campus)
                .containsExactly("南校区", "北校区");
        assertThat(details).allSatisfy(detail ->
                assertThat(detail.projectTitle()).isEqualTo("跨校区项目"));
        assertThat(details).extracting(StatisticsService.MemberWorklogDetail::workDate)
                .containsExactly(LocalDate.of(2021, 11, 8), LocalDate.of(2021, 11, 3));
        // 明细各行加起来必须等于表里那一行的合计，否则点开详情只会让人更糊涂。
        assertThat(details.stream().mapToInt(StatisticsService.MemberWorklogDetail::totalMinutes).sum())
                .isEqualTo(statistics.members(FROM, TO, projectId, null, null).stream()
                        .filter(row -> row.studentId().equals("20210001"))
                        .mapToInt(StatisticsService.MemberStatistics::totalMinutes).sum());
    }

    @Test
    void memberWorklogDetailsStayInsideTheAuthorizedCampuses() {
        insertWorklog(base + 10, requestA, LocalDate.of(2021, 11, 3), "李四", "20210001", 60, 30);
        insertWorklog(base + 11, requestB, LocalDate.of(2021, 11, 8), "李四", "20210001", 30, 0);
        AuthenticatedUser scoped = new AuthenticatedUser(userId, "scoped", "北校区负责人",
                UserRole.CAMPUS_MANAGER, campusA, false, 77L, "SCOPED_STATS", "校区统计组",
                DataScope.CAMPUS, Set.of(PermissionCode.STATISTICS_DOWNLOAD), Set.of(campusA));

        List<StatisticsService.MemberWorklogDetail> details =
                statistics.memberWorklogs("20210001", FROM, TO, projectId, null, scoped);

        assertThat(details).singleElement().satisfies(detail -> {
            assertThat(detail.campus()).isEqualTo("北校区");
            assertThat(detail.totalMinutes()).isEqualTo(90);
        });
        assertThatThrownBy(() -> statistics.memberWorklogs("20210001", FROM, TO, projectId, campusB, scoped))
                .hasMessageContaining("无权查看该校区");
    }

    private void insertProject(long id, String title, LocalDateTime completedAt) {
        jdbc.sql("""
                INSERT INTO project(id, title, status, created_by, completed_at)
                VALUES (:id, :title, 'COMPLETED', :userId, :completedAt)
                """).param("id", id).param("title", title).param("userId", userId)
                .param("completedAt", completedAt).update();
    }

    private void insertRequest(long id, long project, String title, long campusId) {
        jdbc.sql("""
                INSERT INTO photo_request(id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:id, :projectId, :title, :campusId, :deadline, 'COMPLETED', :userId)
                """).param("id", id).param("projectId", project).param("title", title)
                .param("campusId", campusId).param("deadline", LocalDateTime.of(2021, 11, 20, 12, 0))
                .param("userId", userId).update();
    }

    private void insertWorklog(long id, long requestId, LocalDate date, String name,
                               String studentId, int shooting, int retouching) {
        insertWorklog(id, requestId, date, name, studentId, shooting, retouching, "CONFIRMED");
    }

    private void insertWorklog(long id, long requestId, LocalDate date, String name,
                               String studentId, int shooting, int retouching, String status) {
        jdbc.sql("""
                INSERT INTO worklog(id, request_id, user_id, work_date, shooting_minutes,
                                    retouching_minutes, member_name, member_student_id, status)
                VALUES (:id, :requestId, :userId, :workDate, :shooting, :retouching,
                        :memberName, :studentId, :status)
                """).param("id", id).param("requestId", requestId).param("userId", userId)
                .param("workDate", date).param("shooting", shooting).param("retouching", retouching)
                .param("memberName", name).param("studentId", studentId).param("status", status).update();
    }
}
