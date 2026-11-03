package cn.photolib.salary;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 工资计算（统计面板「工资计算」）。
 *
 * <p><b>按选题结算。</b>只算清算期内结束的选题（{@code status='COMPLETED'}、{@code completed_at} 落在
 * {@code [from, to+1)}，两端日期都含），与成员核算、清算单同一口径。每个选题里每位有记录的成员一行：</p>
 * <pre>工资 = max(已确认工时（小时）× {@link #HOURLY_RATE} 元, 被引张数 × {@link #ADOPTION_RATE} 元)</pre>
 * <ul>
 *   <li>工时 = 这个选题下（需求填报的和上传链接申报的）已确认工时的拍摄 + 修图分钟数 ÷ 60，不取整；
 *       工时工资四舍五入到分。待审核的不算钱，只记条数提醒（先审完再算）；已退回、草稿不算。</li>
 *   <li>被引张数 = 这个选题里被引用的、拍摄者是他的图片张数（{@code adoption} 每个选题每张图至多一条）。</li>
 *   <li>「有记录」= 这个选题里有他的已确认或待审核工时，或者有他被引的图片。</li>
 * </ul>
 *
 * <p><b>成员按学号归并</b>（与 {@code StatisticsService.members} 一致）：工时的 {@code member_student_id}、
 * 被引的 {@code photographer_student_id} 相同就是同一个人；没有学号的老记录按姓名归并
 * （{@code member_key} 分别是 {@code id:<学号>} 和 {@code name:<姓名>}）。</p>
 *
 * <p><b>结果是快照</b>：存进 {@code salary_calculation(_line)}，之后工时再被审核、被引再被取消都不改它；
 * 要新的结果就再算一次。时薪和单价也存在计算上。</p>
 */
@Service
@RequiredArgsConstructor
public class SalaryCalculationService {
    /** 每小时工时折算的工资（元）。 */
    public static final BigDecimal HOURLY_RATE = new BigDecimal("20.00");
    /** 每张被引图片折算的工资（元）。 */
    public static final BigDecimal ADOPTION_RATE = new BigDecimal("10.00");
    static final int MAX_RANGE_DAYS = 366 * 3;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    /** 清算期内结束的选题 {@code p}。 */
    private static final String SETTLED_PROJECT = """
            p.deleted=0 AND p.status='COMPLETED' AND p.completed_at >= :fromDate AND p.completed_at < :toExclusive""";

    private final JdbcClient jdbc;

    static void requireGlobal(AuthenticatedUser principal) {
        if (principal.isCampusScoped()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "工资计算汇总全部校区的记录，校区范围的账号不能使用");
        }
    }

    static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择选题结束时间范围");
        if (to.isBefore(from)) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "结束日期不能早于开始日期");
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "时间范围最长三年");
        }
    }

    /** 工时工资：分钟 × 时薪 ÷ 60，四舍五入到分。 */
    static BigDecimal hourAmount(int minutes, BigDecimal hourlyRate) {
        return hourlyRate.multiply(BigDecimal.valueOf(minutes)).divide(SIXTY, 2, RoundingMode.HALF_UP);
    }

    static BigDecimal adoptionAmount(int photos, BigDecimal adoptionRate) {
        return adoptionRate.multiply(BigDecimal.valueOf(photos)).setScale(2, RoundingMode.HALF_UP);
    }

    /** 两者取高；相等时记 EQUAL，都为 0 记 NONE。 */
    static Basis basis(BigDecimal hourAmount, BigDecimal adoptionAmount) {
        int compare = hourAmount.compareTo(adoptionAmount);
        if (compare > 0) return Basis.HOURS;
        if (compare < 0) return Basis.ADOPTIONS;
        return hourAmount.signum() == 0 ? Basis.NONE : Basis.EQUAL;
    }

    /** 算一次并存成快照，返回这次计算的概况。 */
    @Transactional
    public CalculationSummary calculate(LocalDate from, LocalDate to, AuthenticatedUser principal) {
        requireGlobal(principal);
        validateRange(from, to);
        List<Line> lines = compute(from, to);
        BigDecimal total = lines.stream().map(Line::salaryAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long projects = lines.stream().map(Line::projectId).distinct().count();
        long members = lines.stream().map(Line::memberKey).distinct().count();
        int pending = lines.stream().mapToInt(Line::pendingWorklogCount).sum();

        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO salary_calculation(from_date, to_date, hourly_rate, adoption_rate, project_count,
                    member_count, line_count, total_amount, pending_worklog_count, created_by, created_by_name,
                    created_at)
                VALUES (:fromDate, :toDate, :hourlyRate, :adoptionRate, :projects, :members, :lines, :total,
                    :pending, :userId, :userName, :now)
                """).param("fromDate", from).param("toDate", to).param("hourlyRate", HOURLY_RATE)
                .param("adoptionRate", ADOPTION_RATE).param("projects", projects).param("members", members)
                .param("lines", lines.size()).param("total", total).param("pending", pending)
                .param("userId", principal.id()).param("userName", principal.displayName())
                .param("now", LocalDateTime.now(ZONE))
                .update(keys, "id");
        long id = keys.getKeyAs(Number.class).longValue();
        for (Line line : lines) {
            jdbc.sql("""
                    INSERT INTO salary_calculation_line(calculation_id, project_id, project_title, project_completed_at,
                        member_key, student_id, member_name, campus, shooting_minutes, retouching_minutes,
                        confirmed_worklog_count, pending_worklog_count, pending_minutes, adopted_photos,
                        hour_amount, adoption_amount, salary_amount, basis)
                    VALUES (:calculationId, :projectId, :projectTitle, :completedAt, :memberKey, :studentId,
                        :memberName, :campus, :shooting, :retouching, :confirmedCount, :pendingCount, :pendingMinutes,
                        :adopted, :hourAmount, :adoptionAmount, :salary, :basis)
                    """).param("calculationId", id).param("projectId", line.projectId())
                    .param("projectTitle", line.projectTitle()).param("completedAt", line.projectCompletedAt())
                    .param("memberKey", line.memberKey()).param("studentId", line.studentId())
                    .param("memberName", line.memberName()).param("campus", line.campus())
                    .param("shooting", line.shootingMinutes()).param("retouching", line.retouchingMinutes())
                    .param("confirmedCount", line.confirmedWorklogCount())
                    .param("pendingCount", line.pendingWorklogCount()).param("pendingMinutes", line.pendingMinutes())
                    .param("adopted", line.adoptedPhotos()).param("hourAmount", line.hourAmount())
                    .param("adoptionAmount", line.adoptionAmount()).param("salary", line.salaryAmount())
                    .param("basis", line.basis().name())
                    .update();
        }
        return summary(id);
    }

    /**
     * 现算，不落库。两条查询分别取工时和被引（各自按「选题 + 成员」聚合），在内存里按 {@code member_key} 合并——
     * 一期结束的选题是几十到几百个、每个几个到几十人的量级。
     */
    List<Line> compute(LocalDate from, LocalDate to) {
        record Project(String title, LocalDateTime completedAt) {}
        Map<Long, Project> projects = new LinkedHashMap<>();
        // key = projectId + 成员键
        Map<String, Accumulator> byKey = new LinkedHashMap<>();
        String memberKeySql = """
                CASE WHEN %1$s IS NULL OR TRIM(%1$s)='' THEN CONCAT('name:', TRIM(%2$s))
                     ELSE CONCAT('id:', TRIM(%1$s)) END""";

        String worklogKey = memberKeySql.formatted("w.member_student_id", "COALESCE(w.member_name, u.display_name)");
        jdbc.sql("""
                SELECT p.id, p.title, p.completed_at, %1$s AS member_key,
                       MIN(NULLIF(TRIM(w.member_student_id), '')), MIN(TRIM(COALESCE(w.member_name, u.display_name))),
                       GROUP_CONCAT(DISTINCT c.name ORDER BY c.name SEPARATOR '、'),
                       SUM(CASE WHEN w.status='CONFIRMED' THEN w.shooting_minutes ELSE 0 END),
                       SUM(CASE WHEN w.status='CONFIRMED' THEN w.retouching_minutes ELSE 0 END),
                       SUM(CASE WHEN w.status='CONFIRMED' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN w.status='SUBMITTED' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN w.status='SUBMITTED' THEN w.shooting_minutes + w.retouching_minutes ELSE 0 END)
                FROM worklog w
                LEFT JOIN photo_request r ON r.id=w.request_id
                LEFT JOIN project_share_link l ON l.id=w.share_link_id
                JOIN project p ON p.id=COALESCE(r.project_id, l.project_id)
                LEFT JOIN campus c ON c.id=r.campus_id
                LEFT JOIN app_user u ON u.id=w.user_id
                WHERE w.deleted=0 AND w.status IN ('CONFIRMED', 'SUBMITTED') AND %2$s
                  AND COALESCE(NULLIF(TRIM(w.member_student_id), ''), NULLIF(TRIM(COALESCE(w.member_name, u.display_name)), ''))
                      IS NOT NULL
                GROUP BY p.id, p.title, p.completed_at, %1$s
                """.formatted(worklogKey, SETTLED_PROJECT))
                .param("fromDate", from).param("toExclusive", to.plusDays(1))
                .query((rs, n) -> {
                    long projectId = rs.getLong(1);
                    projects.putIfAbsent(projectId, new Project(rs.getString(2), rs.getObject(3, LocalDateTime.class)));
                    String memberKey = rs.getString(4);
                    Accumulator acc = byKey.computeIfAbsent(projectId + "\u0000" + memberKey,
                            k -> new Accumulator(projectId, memberKey));
                    acc.studentId(rs.getString(5));
                    acc.name(rs.getString(6));
                    acc.campuses(rs.getString(7));
                    acc.shooting += rs.getInt(8);
                    acc.retouching += rs.getInt(9);
                    acc.confirmedCount += rs.getInt(10);
                    acc.pendingCount += rs.getInt(11);
                    acc.pendingMinutes += rs.getInt(12);
                    return 0;
                }).list();

        String adoptionKey = memberKeySql.formatted("a.photographer_student_id", "a.photographer_name");
        jdbc.sql("""
                SELECT p.id, p.title, p.completed_at, %1$s AS member_key,
                       MIN(NULLIF(TRIM(a.photographer_student_id), '')), MIN(TRIM(a.photographer_name)),
                       GROUP_CONCAT(DISTINCT c.name ORDER BY c.name SEPARATOR '、'),
                       COUNT(DISTINCT a.photo_id)
                FROM adoption a
                JOIN project p ON p.id=a.project_id
                JOIN photo ph ON ph.id=a.photo_id
                LEFT JOIN campus c ON c.id=ph.campus_id
                WHERE a.deleted=0 AND %2$s
                  AND COALESCE(NULLIF(TRIM(a.photographer_student_id), ''), NULLIF(TRIM(a.photographer_name), ''))
                      IS NOT NULL
                GROUP BY p.id, p.title, p.completed_at, %1$s
                """.formatted(adoptionKey, SETTLED_PROJECT))
                .param("fromDate", from).param("toExclusive", to.plusDays(1))
                .query((rs, n) -> {
                    long projectId = rs.getLong(1);
                    projects.putIfAbsent(projectId, new Project(rs.getString(2), rs.getObject(3, LocalDateTime.class)));
                    String memberKey = rs.getString(4);
                    Accumulator acc = byKey.computeIfAbsent(projectId + "\u0000" + memberKey,
                            k -> new Accumulator(projectId, memberKey));
                    acc.studentId(rs.getString(5));
                    acc.name(rs.getString(6));
                    acc.campuses(rs.getString(7));
                    acc.adopted += rs.getInt(8);
                    return 0;
                }).list();

        List<Line> lines = new ArrayList<>();
        for (Accumulator acc : byKey.values()) {
            // 只有待审核工时、没有已确认工时也没有被引的人也列出来（工资为 0），提醒先审核。
            Project project = projects.get(acc.projectId);
            BigDecimal hours = hourAmount(acc.shooting + acc.retouching, HOURLY_RATE);
            BigDecimal adoptions = adoptionAmount(acc.adopted, ADOPTION_RATE);
            lines.add(new Line(acc.projectId, project.title(), project.completedAt(), acc.memberKey, acc.studentId,
                    acc.name == null ? "" : acc.name, acc.campusText(), acc.shooting, acc.retouching,
                    acc.confirmedCount, acc.pendingCount, acc.pendingMinutes, acc.adopted, hours, adoptions,
                    hours.max(adoptions), basis(hours, adoptions)));
        }
        lines.sort(LINE_ORDER);
        return lines;
    }

    /** 选题按结束时间、同一选题里工资高的在前，再按姓名。 */
    static final Comparator<Line> LINE_ORDER = Comparator.comparing(Line::projectCompletedAt)
            .thenComparing(Line::projectId)
            .thenComparing(Line::salaryAmount, Comparator.reverseOrder())
            .thenComparing(Line::memberName)
            .thenComparing(Line::memberKey);

    public List<CalculationSummary> list(int limit, AuthenticatedUser principal) {
        requireGlobal(principal);
        return jdbc.sql(SUMMARY_SQL + " WHERE deleted=FALSE ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("limit", Math.max(1, Math.min(limit, 200)))
                .query((rs, n) -> summaryRow(rs)).list();
    }

    public Detail detail(long id, AuthenticatedUser principal) {
        requireGlobal(principal);
        CalculationSummary summary = summary(id);
        List<Line> lines = jdbc.sql("""
                SELECT project_id, project_title, project_completed_at, member_key, student_id, member_name, campus,
                       shooting_minutes, retouching_minutes, confirmed_worklog_count, pending_worklog_count,
                       pending_minutes, adopted_photos, hour_amount, adoption_amount, salary_amount, basis
                FROM salary_calculation_line WHERE calculation_id=:id
                """).param("id", id)
                .query((rs, n) -> new Line(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDateTime.class),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8),
                        rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12), rs.getInt(13),
                        rs.getBigDecimal(14), rs.getBigDecimal(15), rs.getBigDecimal(16), Basis.valueOf(rs.getString(17))))
                .list();
        List<Line> sorted = new ArrayList<>(lines);
        sorted.sort(LINE_ORDER);
        return new Detail(summary, projects(sorted), members(sorted));
    }

    /** 软删除：删了的不再出现在列表里，也不能再看、再导出。清算单以后引用它时仍能查到原始数据。 */
    @Transactional
    public void delete(long id, AuthenticatedUser principal) {
        requireGlobal(principal);
        int updated = jdbc.sql("UPDATE salary_calculation SET deleted=TRUE WHERE id=:id AND deleted=FALSE")
                .param("id", id).update();
        if (updated == 0) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "这次工资计算不存在或已删除");
    }

    private CalculationSummary summary(long id) {
        return jdbc.sql(SUMMARY_SQL + " WHERE id=:id AND deleted=FALSE").param("id", id)
                .query((rs, n) -> summaryRow(rs)).optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "这次工资计算不存在或已删除"));
    }

    private static final String SUMMARY_SQL = """
            SELECT id, from_date, to_date, hourly_rate, adoption_rate, project_count, member_count, line_count,
                   total_amount, pending_worklog_count, created_by_name, created_at
            FROM salary_calculation""";

    private static CalculationSummary summaryRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CalculationSummary(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class),
                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
                rs.getBigDecimal(9), rs.getInt(10), rs.getString(11), rs.getObject(12, LocalDateTime.class));
    }

    /** 按选题分组，组内顺序沿用 {@link #LINE_ORDER}。 */
    static List<ProjectGroup> projects(List<Line> sorted) {
        Map<Long, List<Line>> grouped = new LinkedHashMap<>();
        sorted.forEach(line -> grouped.computeIfAbsent(line.projectId(), id -> new ArrayList<>()).add(line));
        List<ProjectGroup> result = new ArrayList<>();
        grouped.forEach((projectId, group) -> {
            Line head = group.get(0);
            result.add(new ProjectGroup(projectId, head.projectTitle(), head.projectCompletedAt(),
                    group.stream().mapToInt(Line::confirmedMinutes).sum(),
                    group.stream().mapToInt(Line::adoptedPhotos).sum(),
                    group.stream().map(Line::salaryAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                    group.stream().mapToInt(Line::pendingWorklogCount).sum(), List.copyOf(group)));
        });
        return result;
    }

    /** 按成员汇总（跨选题）。应发合计高的在前。 */
    static List<MemberTotal> members(List<Line> lines) {
        Map<String, List<Line>> grouped = new LinkedHashMap<>();
        lines.forEach(line -> grouped.computeIfAbsent(line.memberKey(), key -> new ArrayList<>()).add(line));
        List<MemberTotal> result = new ArrayList<>();
        grouped.forEach((key, group) -> {
            Line head = group.get(0);
            Set<String> campuses = new TreeSet<>();
            group.stream().map(Line::campus).filter(c -> c != null && !c.isBlank())
                    .forEach(c -> campuses.addAll(List.of(c.split("、"))));
            result.add(new MemberTotal(key, head.studentId(), head.memberName(),
                    campuses.isEmpty() ? null : String.join("、", campuses), group.size(),
                    group.stream().mapToInt(Line::confirmedMinutes).sum(),
                    group.stream().mapToInt(Line::adoptedPhotos).sum(),
                    group.stream().map(Line::hourAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                    group.stream().map(Line::adoptionAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                    group.stream().map(Line::salaryAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                    group.stream().mapToInt(Line::pendingWorklogCount).sum()));
        });
        result.sort(Comparator.comparing(MemberTotal::salaryAmount, Comparator.reverseOrder())
                .thenComparing(MemberTotal::memberName).thenComparing(MemberTotal::memberKey));
        return result;
    }

    private static final class Accumulator {
        final long projectId;
        final String memberKey;
        String studentId;
        String name;
        final Set<String> campuses = new LinkedHashSet<>();
        int shooting;
        int retouching;
        int confirmedCount;
        int pendingCount;
        int pendingMinutes;
        int adopted;

        Accumulator(long projectId, String memberKey) {
            this.projectId = projectId;
            this.memberKey = memberKey;
        }

        void studentId(String value) {
            if (studentId == null && value != null && !value.isBlank()) studentId = value.trim();
        }

        /** 工时里的姓名优先（先查的工时），被引里的姓名只在没有工时时用。 */
        void name(String value) {
            if (name == null && value != null && !value.isBlank()) name = value.trim();
        }

        void campuses(String value) {
            if (value == null || value.isBlank()) return;
            for (String campus : value.split("、")) if (!campus.isBlank()) campuses.add(campus);
        }

        String campusText() {
            return campuses.isEmpty() ? null : String.join("、", new TreeSet<>(campuses));
        }
    }

    public enum Basis {
        /** 按工时算的更高。 */
        HOURS,
        /** 按被引算的更高。 */
        ADOPTIONS,
        /** 两者相等（且大于 0）。 */
        EQUAL,
        /** 都为 0：只有待审核的工时。 */
        NONE
    }

    /** 一个选题里一位成员的工资。{@code memberKey}：{@code id:<学号>} 或 {@code name:<姓名>}。 */
    public record Line(long projectId, String projectTitle, LocalDateTime projectCompletedAt, String memberKey,
                       String studentId, String memberName, String campus, int shootingMinutes,
                       int retouchingMinutes, int confirmedWorklogCount, int pendingWorklogCount, int pendingMinutes,
                       int adoptedPhotos, BigDecimal hourAmount, BigDecimal adoptionAmount, BigDecimal salaryAmount,
                       Basis basis) {
        public int confirmedMinutes() {
            return shootingMinutes + retouchingMinutes;
        }
    }

    public record CalculationSummary(long id, LocalDate from, LocalDate to, BigDecimal hourlyRate,
                                     BigDecimal adoptionRate, int projectCount, int memberCount, int lineCount,
                                     BigDecimal totalAmount, int pendingWorklogCount, String createdByName,
                                     LocalDateTime createdAt) {}

    public record ProjectGroup(long projectId, String projectTitle, LocalDateTime completedAt, int confirmedMinutes,
                               int adoptedPhotos, BigDecimal salaryAmount, int pendingWorklogCount, List<Line> lines) {}

    /** 一位成员这一期在各个选题的合计；{@code projectCount} 是他有记录的选题数。 */
    public record MemberTotal(String memberKey, String studentId, String memberName, String campus, int projectCount,
                              int confirmedMinutes, int adoptedPhotos, BigDecimal hourAmount,
                              BigDecimal adoptionAmount, BigDecimal salaryAmount, int pendingWorklogCount) {}

    public record Detail(CalculationSummary calculation, List<ProjectGroup> projects, List<MemberTotal> members) {}
}
