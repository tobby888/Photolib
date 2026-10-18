package cn.photolib.statistics.analysis;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.statistics.analysis.WorklogAnalysisService.AnalysisRequest;
import cn.photolib.statistics.analysis.WorklogAnalysisService.AnalysisResult;
import cn.photolib.statistics.analysis.WorklogAnalysisService.Bucket;
import cn.photolib.statistics.analysis.WorklogAnalysisService.Dimension;
import cn.photolib.statistics.analysis.WorklogAnalysisService.Granularity;
import cn.photolib.statistics.analysis.WorklogAnalysisService.Row;
import cn.photolib.statistics.analysis.WorklogCondition.Field;
import cn.photolib.statistics.analysis.WorklogCondition.Logic;
import cn.photolib.statistics.analysis.WorklogCondition.Operator;
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
 * 工时分析：条件树在真实的库上筛得对、可见范围压得住、汇总加得起来。
 * 只读用例，{@code @Transactional} 回滚，不污染共用的 H2 库。
 */
@SpringBootTest
@Transactional
class WorklogAnalysisServiceTests {
    private static final LocalDate FROM = LocalDate.of(2022, 3, 1);
    private static final LocalDate TO = LocalDate.of(2022, 3, 31);

    @Autowired
    JdbcClient jdbc;
    @Autowired
    WorklogAnalysisService analysis;

    private long base;
    private long userId;
    private long campusA;
    private long campusB;
    private long projectId;
    private long otherProjectId;
    private long requestA;
    private long requestB;
    private long linkId;
    private AuthenticatedUser global;

    @BeforeEach
    void seed() {
        base = System.nanoTime() & Long.MAX_VALUE >> 4;
        userId = base;
        campusA = base + 1;
        campusB = base + 2;
        projectId = base + 3;
        otherProjectId = base + 4;
        requestA = base + 5;
        requestB = base + 6;
        linkId = base + 7;
        long otherRequest = base + 8;

        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (:id, :code, '北校区')")
                .param("id", campusA).param("code", "wa-a-" + base).update();
        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (:id, :code, '南校区')")
                .param("id", campusB).param("code", "wa-b-" + base).update();
        jdbc.sql("""
                INSERT INTO app_user(id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', '分析账号', 'ADMIN', TRUE, FALSE)
                """).param("id", userId).param("username", "worklog-analysis-" + base).update();
        insertProject(projectId, "春季运动会", "ACTIVE");
        insertProject(otherProjectId, "毕业季", "COMPLETED");
        insertRequest(requestA, projectId, "北校区跟拍", campusA);
        insertRequest(requestB, projectId, "南校区跟拍", campusB);
        insertRequest(otherRequest, otherProjectId, "毕业照", campusA);
        jdbc.sql("""
                INSERT INTO project_share_link(id, token, project_id, name, password_hash, created_by, purpose)
                VALUES (:id, :token, :projectId, '外援', 'hash', :userId, 'UPLOAD')
                """).param("id", linkId).param("token", String.format("%026d", base))
                .param("projectId", projectId).param("userId", userId).update();

        // 2022-03-07 是周一。
        insertWorklog(base + 10, requestA, null, LocalDate.of(2022, 3, 7), "张三", "S001", 120, 30, "CONFIRMED", "开幕式");
        insertWorklog(base + 11, requestB, null, LocalDate.of(2022, 3, 8), "张三", "S001", 60, 0, "SUBMITTED", null);
        insertWorklog(base + 12, requestA, null, LocalDate.of(2022, 3, 12), "李四", "S002", 30, 300, "CONFIRMED", "修图 100%");
        insertWorklog(base + 13, null, linkId, LocalDate.of(2022, 3, 13), "王外援", "S003", 240, 0, "CONFIRMED", null);
        insertWorklog(base + 14, otherRequest, null, LocalDate.of(2022, 3, 14), "李四", "S002", 45, 45, "REJECTED", null);
        // 草稿不进分析；区间外的不进分析。
        insertWorklog(base + 15, requestA, null, LocalDate.of(2022, 3, 15), "张三", "S001", 999, 999, "DRAFT", null);
        insertWorklog(base + 16, requestA, null, LocalDate.of(2022, 4, 1), "张三", "S001", 999, 999, "CONFIRMED", null);

        global = new AuthenticatedUser(userId, "analysis", "分析账号", UserRole.ADMIN, null, false);
    }

    @Test
    void withoutConditionsEverySubmittedReviewedOrConfirmedWorklogInTheRangeIsCountedButNeverDrafts() {
        AnalysisResult result = analyze(null, Dimension.MEMBER);

        assertThat(ids(result)).containsExactlyInAnyOrder(base + 10, base + 11, base + 12, base + 13, base + 14);
        assertThat(result.summary().worklogCount()).isEqualTo(5);
        assertThat(result.summary().shootingMinutes()).isEqualTo(120 + 60 + 30 + 240 + 45);
        assertThat(result.summary().retouchingMinutes()).isEqualTo(30 + 300 + 45);
        assertThat(result.summary().memberCount()).isEqualTo(3);
        assertThat(result.summary().projectCount()).isEqualTo(2);
        // 分组各行加起来等于合计。
        assertThat(result.groups().stream().mapToLong(Bucket::totalMinutes).sum())
                .isEqualTo(result.summary().totalMinutes());
        // 按成员分组时按总时长从高到低排：李四 420 > 王外援 240 > 张三 210。
        assertThat(result.groups()).extracting(Bucket::key).containsExactly("S002", "S003", "S001");
    }

    @Test
    void nestedAndOrConditionsSelectExactlyTheMatchingWorklogs() {
        // 已确认 且 （北校区 或 单条总时长 ≥ 4 小时）
        WorklogCondition filter = WorklogCondition.group(Logic.AND,
                WorklogCondition.leaf(Field.STATUS, Operator.EQ, "CONFIRMED"),
                WorklogCondition.group(Logic.OR,
                        WorklogCondition.leaf(Field.CAMPUS, Operator.EQ, String.valueOf(campusA)),
                        WorklogCondition.leaf(Field.TOTAL_MINUTES, Operator.GE, "240")));

        assertThat(ids(analyze(filter, Dimension.MEMBER)))
                .containsExactlyInAnyOrder(base + 10, base + 12, base + 13);
    }

    @Test
    void notGroupsAndNegativeComparisonsKeepTheLinkDeclaredWorklogsThatHaveNoCampus() {
        WorklogCondition notNorth = WorklogCondition.leaf(Field.CAMPUS, Operator.EQ, String.valueOf(campusA)).negated();
        WorklogCondition notEqualNorth = WorklogCondition.leaf(Field.CAMPUS, Operator.NE, String.valueOf(campusA));

        // 王外援那条没有校区：「不是北校区」应当包含它，两种写法结果一致。
        assertThat(ids(analyze(notNorth, Dimension.MEMBER))).containsExactlyInAnyOrder(base + 11, base + 13);
        assertThat(ids(analyze(notEqualNorth, Dimension.MEMBER))).containsExactlyInAnyOrder(base + 11, base + 13);
        assertThat(ids(analyze(WorklogCondition.leaf(Field.CAMPUS, Operator.IS_EMPTY), Dimension.MEMBER)))
                .containsExactly(base + 13);
        assertThat(ids(analyze(WorklogCondition.leaf(Field.SOURCE, Operator.EQ, "SHARE_LINK"), Dimension.MEMBER)))
                .containsExactly(base + 13);
    }

    @Test
    void textConditionsMatchLiterallyEvenWithWildcardsAndInjectionAttempts() {
        assertThat(ids(analyze(WorklogCondition.leaf(Field.REMARK, Operator.CONTAINS, "100%"), Dimension.MEMBER)))
                .containsExactly(base + 12);
        // % 被当成普通字符：没有备注里写着「%」的其他工时被匹配进来。
        assertThat(ids(analyze(WorklogCondition.leaf(Field.REMARK, Operator.CONTAINS, "%"), Dimension.MEMBER)))
                .containsExactly(base + 12);
        assertThat(ids(analyze(WorklogCondition.leaf(Field.MEMBER_NAME, Operator.CONTAINS,
                "' OR '1'='1"), Dimension.MEMBER))).isEmpty();
        assertThat(ids(analyze(WorklogCondition.leaf(Field.MEMBER_STUDENT_ID, Operator.IN,
                "S001'); DELETE FROM worklog; --", "S002"), Dimension.MEMBER)))
                .containsExactlyInAnyOrder(base + 12, base + 14);
        // 注入的语句当然也没有被执行。
        assertThat(jdbc.sql("SELECT COUNT(*) FROM worklog WHERE id BETWEEN :lo AND :hi")
                .param("lo", base + 10).param("hi", base + 16).query(Long.class).single()).isEqualTo(7);
        // 「备注不包含 开幕」把没有备注的也算进去。
        assertThat(ids(analyze(WorklogCondition.leaf(Field.REMARK, Operator.NOT_CONTAINS, "开幕"), Dimension.MEMBER)))
                .containsExactlyInAnyOrder(base + 11, base + 12, base + 13, base + 14);
    }

    @Test
    void campusScopedAccountsStayInsideTheirCampusesWhateverTheConditionsSay() {
        AuthenticatedUser north = new AuthenticatedUser(userId, "north", "北校区负责人",
                UserRole.CAMPUS_MANAGER, campusA, false, 77L, "SCOPED_STATS", "校区统计组",
                DataScope.CAMPUS, Set.of(PermissionCode.STATISTICS_DOWNLOAD), Set.of(campusA));
        // 条件里点名要南校区、要链接申报的，用 OR 绕也不行：范围条件在条件树之外。
        WorklogCondition filter = WorklogCondition.group(Logic.OR,
                WorklogCondition.leaf(Field.CAMPUS, Operator.EQ, String.valueOf(campusB)),
                WorklogCondition.leaf(Field.SOURCE, Operator.EQ, "SHARE_LINK"),
                WorklogCondition.leaf(Field.CAMPUS, Operator.EQ, String.valueOf(campusA)));

        AnalysisResult result = analysis.analyze(new AnalysisRequest(FROM, TO, mine(filter), Dimension.CAMPUS, null), north);

        assertThat(ids(result)).containsExactlyInAnyOrder(base + 10, base + 12, base + 14);
        assertThat(result.groups()).extracting(Bucket::label).containsExactly("北校区");
        var options = analysis.options(FROM, TO, north);
        assertThat(options.campuses()).extracting(WorklogAnalysisService.Option::label).containsExactly("北校区");
        assertThat(options.members()).extracting(WorklogAnalysisService.Option::value)
                .containsExactlyInAnyOrder("S001", "S002");
    }

    @Test
    void theTrendHasAPointForEveryPeriodAndTheWeekdayBreakdownStartsOnMonday() {
        AnalysisResult daily = analysis.analyze(new AnalysisRequest(FROM, TO, mine(null), Dimension.STATUS,
                Granularity.AUTO), global);
        assertThat(daily.granularity()).isEqualTo(Granularity.DAY);
        assertThat(daily.trend()).hasSize(31);
        assertThat(daily.trend().get(6)).satisfies(point -> {
            assertThat(point.key()).isEqualTo("2022-03-07");
            assertThat(point.totalMinutes()).isEqualTo(150);
        });
        assertThat(daily.trend().get(0).totalMinutes()).isZero();
        assertThat(daily.weekdays()).extracting(Bucket::label)
                .containsExactly("周一", "周二", "周三", "周四", "周五", "周六", "周日");
        // 夹具里没有周三的工时，那一根补 0 而不是缺席。
        assertThat(daily.weekdays().get(2).totalMinutes()).isZero();
        assertThat(daily.statuses()).extracting(Bucket::key)
                .containsExactlyInAnyOrder("CONFIRMED", "SUBMITTED", "REJECTED");

        AnalysisResult weekly = analysis.analyze(new AnalysisRequest(FROM, TO, mine(null), Dimension.WEEK,
                Granularity.WEEK), global);
        // 3 月 1 日是周二，第一周从 2 月 28 日（周一）算起。
        assertThat(weekly.trend()).extracting(Bucket::key).startsWith("2022-02-28", "2022-03-07");
        assertThat(weekly.trend().stream().mapToLong(Bucket::totalMinutes).sum())
                .isEqualTo(weekly.summary().totalMinutes());
        assertThat(weekly.durations().stream().mapToLong(Bucket::worklogCount).sum()).isEqualTo(5);
    }

    @Test
    void invalidRangesAreRejected() {
        assertThatThrownBy(() -> analysis.analyze(new AnalysisRequest(TO, FROM, null, null, null), global))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能晚于");
        assertThatThrownBy(() -> analysis.analyze(new AnalysisRequest(null, TO, null, null, null), global))
                .isInstanceOf(BusinessException.class).hasMessageContaining("日期范围");
        assertThatThrownBy(() -> analysis.analyze(new AnalysisRequest(FROM, FROM.plusYears(4), null, null, null),
                global)).isInstanceOf(BusinessException.class).hasMessageContaining("最长三年");
    }

    private AnalysisResult analyze(WorklogCondition filter, Dimension groupBy) {
        return analysis.analyze(new AnalysisRequest(FROM, TO, mine(filter), groupBy, null), global);
    }

    /**
     * 再套一层「只看本用例的两个选题」：共用的 H2 库里可能有别的测试类留下的同期工时，
     * 合计类的断言会被它们带歪。这一层本身也顺带验了 AND 嵌套。
     */
    private WorklogCondition mine(WorklogCondition filter) {
        WorklogCondition projects = WorklogCondition.leaf(Field.PROJECT, Operator.IN,
                String.valueOf(projectId), String.valueOf(otherProjectId));
        return filter == null ? projects : WorklogCondition.group(Logic.AND, projects, filter);
    }

    /** 只看本用例造的那几条：共用的 H2 库里可能还有别的测试类留下的同期工时。 */
    private List<Long> ids(AnalysisResult result) {
        return result.rows().stream().map(Row::worklogId)
                .filter(id -> id >= base + 10 && id <= base + 16).toList();
    }

    private void insertProject(long id, String title, String status) {
        jdbc.sql("""
                INSERT INTO project(id, title, status, created_by, completed_at)
                VALUES (:id, :title, :status, :userId, :completedAt)
                """).param("id", id).param("title", title).param("status", status).param("userId", userId)
                .param("completedAt", "COMPLETED".equals(status) ? LocalDateTime.of(2022, 3, 20, 12, 0) : null)
                .update();
    }

    private void insertRequest(long id, long project, String title, long campusId) {
        jdbc.sql("""
                INSERT INTO photo_request(id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:id, :projectId, :title, :campusId, :deadline, 'ACCEPTED', :userId)
                """).param("id", id).param("projectId", project).param("title", title)
                .param("campusId", campusId).param("deadline", LocalDateTime.of(2022, 3, 30, 12, 0))
                .param("userId", userId).update();
    }

    private void insertWorklog(long id, Long requestId, Long shareLinkId, LocalDate date, String name,
                               String studentId, int shooting, int retouching, String status, String remark) {
        jdbc.sql("""
                INSERT INTO worklog(id, request_id, share_link_id, user_id, work_date, shooting_minutes,
                                    retouching_minutes, member_name, member_student_id, status, remark)
                VALUES (:id, :requestId, :shareLinkId, :userId, :workDate, :shooting, :retouching,
                        :memberName, :studentId, :status, :remark)
                """).param("id", id).param("requestId", requestId).param("shareLinkId", shareLinkId)
                .param("userId", userId).param("workDate", date).param("shooting", shooting)
                .param("retouching", retouching).param("memberName", name).param("studentId", studentId)
                .param("status", status).param("remark", remark).update();
    }
}
