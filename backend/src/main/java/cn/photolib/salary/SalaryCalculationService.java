package cn.photolib.salary;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.salary.SalaryFormula.FormulaException;
import cn.photolib.salary.SalaryFormula.Variable;
import cn.photolib.settlement.SettlementStatementService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
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
 * {@code [from, to+1)}，两端日期都含），与成员核算、清算单同一口径。每个选题里每位有记录的成员一行，
 * 工资按<b>计算时给的公式</b>（{@link SalaryFormula}，默认 {@link SalaryFormula#DEFAULT}）算，公式里的字段：</p>
 * <ul>
 *   <li>工时 / 拍摄时长 / 修图时长 = 这个选题下（需求填报的和上传链接申报的）<b>已确认</b>工时，分钟 ÷ 60，不取整；
 *       待审核时长 / 条数单列，默认公式不用；已退回、草稿不算。</li>
 *   <li>被引张数 = 这个选题里被引用的、拍摄者是他的图片张数（{@code adoption} 每个选题每张图至多一条）。</li>
 *   <li>上传张数 = 进入这个选题的、拍摄者是他的图片张数，口径同清算单的「参与的选题」
 *       （{@link SettlementStatementService#projectPhotos}：来源选题 + 后来添加进相册的，只算传完的）。</li>
 *   <li>「有记录」= 这个选题里有他的已确认或待审核工时，或者有他被引的图片。只有上传、没有工时也没被引的不算。</li>
 * </ul>
 * <p>公式结果小于 0 按 0 计，四舍五入到分。</p>
 *
 * <p><b>成员按学号归并</b>（与 {@code StatisticsService.members} 一致）：工时的 {@code member_student_id}、
 * 被引 / 图片的 {@code photographer_student_id} 相同就是同一个人；没有学号的老记录按姓名归并
 * （{@code member_key} 分别是 {@code id:<学号>} 和 {@code name:<姓名>}）。</p>
 *
 * <p><b>结果是快照</b>：存进 {@code salary_calculation(_line)}，连同公式；之后工时再被审核、被引再被取消都不改它，
 * 要新的结果就再算一次。「计算过程」不落库，查看时用快照里的公式和数值现渲染。</p>
 */
@Service
@RequiredArgsConstructor
public class SalaryCalculationService {
    static final int MAX_RANGE_DAYS = 366 * 3;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    /** 清算期内结束的选题 {@code p}。 */
    private static final String SETTLED_PROJECT = """
            p.deleted=0 AND p.status='COMPLETED' AND p.completed_at >= :fromDate AND p.completed_at < :toExclusive""";
    /** 成员归并键：学号非空按学号，否则按姓名。两个参数是学号列和姓名表达式。 */
    private static final String MEMBER_KEY = """
            CASE WHEN %1$s IS NULL OR TRIM(%1$s)='' THEN CONCAT('name:', TRIM(%2$s))
                 ELSE CONCAT('id:', TRIM(%1$s)) END""";

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

    /** 解析公式，写错时转成 400，消息里带位置。 */
    static SalaryFormula compile(String formula) {
        try {
            return SalaryFormula.compile(formula);
        } catch (FormulaException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "工资公式有误：" + e.getMessage()
                    + (e.position() >= 0 ? "（第 " + (e.position() + 1) + " 个字符）" : ""));
        }
    }

    /** 公式编辑器用：字段、函数、默认公式和最近一次计算用的公式。 */
    public FormulaReference formulaReference(AuthenticatedUser principal) {
        requireGlobal(principal);
        String last = jdbc.sql("""
                SELECT formula FROM salary_calculation WHERE deleted=FALSE ORDER BY created_at DESC, id DESC LIMIT 1
                """).query(String.class).optional().orElse(null);
        return new FormulaReference(SalaryFormula.DEFAULT, last,
                Arrays.stream(Variable.values()).map(v -> new VariableInfo(v.label(), v.alias(), v.description(),
                        v.sample())).toList(),
                Arrays.stream(SalaryFormula.Function.values()).map(f -> new FunctionInfo(f.functionName(),
                        f.chineseName(), f.signature(), f.description())).toList());
    }

    /** 检查公式；写对了再用示例值试算一次。不抛异常，错误放在结果里给编辑器显示。 */
    public FormulaCheck check(String formula, AuthenticatedUser principal) {
        requireGlobal(principal);
        try {
            SalaryFormula compiled = SalaryFormula.compile(formula);
            Map<Variable, BigDecimal> samples = SalaryFormula.sampleValues();
            try {
                return new FormulaCheck(true, null, null, compiled.source(),
                        compiled.variables().stream().map(Variable::label).toList(),
                        compiled.salary(samples), compiled.explain(samples));
            } catch (FormulaException e) {
                // 写法没错，只是示例值下算不下去（例如除以 0）：照样能用，提示一下。
                return new FormulaCheck(true, "用示例值试算时出错：" + e.getMessage(), null, compiled.source(),
                        compiled.variables().stream().map(Variable::label).toList(), null, null);
            }
        } catch (FormulaException e) {
            return new FormulaCheck(false, e.getMessage(), e.position() >= 0 ? e.position() : null,
                    formula, List.of(), null, null);
        }
    }

    /** 算一次并存成快照，返回这次计算的概况。{@code formula} 为空时用默认公式。 */
    @Transactional
    public CalculationSummary calculate(LocalDate from, LocalDate to, String formula, AuthenticatedUser principal) {
        requireGlobal(principal);
        validateRange(from, to);
        SalaryFormula compiled = compile(formula);
        List<Line> lines = compute(from, to, compiled);
        BigDecimal total = lines.stream().map(Line::salaryAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long projects = lines.stream().map(Line::projectId).distinct().count();
        long members = lines.stream().map(Line::memberKey).distinct().count();
        int pending = lines.stream().mapToInt(Line::pendingWorklogCount).sum();

        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO salary_calculation(from_date, to_date, formula, project_count, member_count, line_count,
                    total_amount, pending_worklog_count, created_by, created_by_name, created_at)
                VALUES (:fromDate, :toDate, :formula, :projects, :members, :lines, :total, :pending, :userId,
                    :userName, :now)
                """).param("fromDate", from).param("toDate", to).param("formula", compiled.source())
                .param("projects", projects).param("members", members)
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
                        uploaded_photos, salary_amount)
                    VALUES (:calculationId, :projectId, :projectTitle, :completedAt, :memberKey, :studentId,
                        :memberName, :campus, :shooting, :retouching, :confirmedCount, :pendingCount, :pendingMinutes,
                        :adopted, :uploaded, :salary)
                    """).param("calculationId", id).param("projectId", line.projectId())
                    .param("projectTitle", line.projectTitle()).param("completedAt", line.projectCompletedAt())
                    .param("memberKey", line.memberKey()).param("studentId", line.studentId())
                    .param("memberName", line.memberName()).param("campus", line.campus())
                    .param("shooting", line.shootingMinutes()).param("retouching", line.retouchingMinutes())
                    .param("confirmedCount", line.confirmedWorklogCount())
                    .param("pendingCount", line.pendingWorklogCount()).param("pendingMinutes", line.pendingMinutes())
                    .param("adopted", line.adoptedPhotos()).param("uploaded", line.uploadedPhotos())
                    .param("salary", line.salaryAmount())
                    .update();
        }
        return summary(id);
    }

    /** 公式里各字段在这一行的值。工时按分钟 ÷ 60 精确算，不先取整。 */
    static Map<Variable, BigDecimal> values(int shootingMinutes, int retouchingMinutes, int confirmedCount,
                                            int pendingCount, int pendingMinutes, int adopted, int uploaded) {
        Map<Variable, BigDecimal> values = new EnumMap<>(Variable.class);
        values.put(Variable.SHOOTING_HOURS, hours(shootingMinutes));
        values.put(Variable.RETOUCHING_HOURS, hours(retouchingMinutes));
        values.put(Variable.TOTAL_HOURS, hours(shootingMinutes + retouchingMinutes));
        values.put(Variable.ADOPTED_PHOTOS, BigDecimal.valueOf(adopted));
        values.put(Variable.UPLOADED_PHOTOS, BigDecimal.valueOf(uploaded));
        values.put(Variable.WORKLOG_COUNT, BigDecimal.valueOf(confirmedCount));
        values.put(Variable.PENDING_HOURS, hours(pendingMinutes));
        values.put(Variable.PENDING_COUNT, BigDecimal.valueOf(pendingCount));
        return values;
    }

    private static BigDecimal hours(int minutes) {
        return BigDecimal.valueOf(minutes).divide(SIXTY, MathContext.DECIMAL128);
    }

    /**
     * 现算，不落库。三条查询分别取工时、被引、上传张数（各自按「选题 + 成员」聚合），在内存里按 {@code member_key}
     * 合并——一期结束的选题是几十到几百个、每个几个到几十人的量级。
     */
    List<Line> compute(LocalDate from, LocalDate to, SalaryFormula formula) {
        record Project(String title, LocalDateTime completedAt) {}
        Map<Long, Project> projects = new LinkedHashMap<>();
        // key = projectId + 成员键
        Map<String, Accumulator> byKey = new LinkedHashMap<>();

        String worklogKey = MEMBER_KEY.formatted("w.member_student_id", "COALESCE(w.member_name, u.display_name)");
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

        String adoptionKey = MEMBER_KEY.formatted("a.photographer_student_id", "a.photographer_name");
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

        // 上传张数只补到已有的行上：只有上传、没有工时也没被引的人不算「有记录」。
        // 公式不用这个字段时也照样查——存进快照，以后换公式重算时数据口径一致。
        String photoKey = MEMBER_KEY.formatted("x.photographer_student_id", "x.photographer_name");
        jdbc.sql("""
                SELECT p.id, %1$s AS member_key, COUNT(DISTINCT x.photo_id)
                FROM (%2$s) x
                JOIN project p ON p.id=x.project_id
                WHERE %3$s
                GROUP BY p.id, %1$s
                """.formatted(photoKey, SettlementStatementService.projectPhotos(SettlementStatementService.UPLOADED_PHOTO),
                        SETTLED_PROJECT))
                .param("fromDate", from).param("toExclusive", to.plusDays(1))
                .query((rs, n) -> {
                    Accumulator acc = byKey.get(rs.getLong(1) + "\u0000" + rs.getString(2));
                    if (acc != null) acc.uploaded += rs.getInt(3);
                    return 0;
                }).list();

        List<Line> lines = new ArrayList<>();
        for (Accumulator acc : byKey.values()) {
            // 只有待审核工时、没有已确认工时也没有被引的人也列出来，提醒先审核。
            Project project = projects.get(acc.projectId);
            String name = acc.name == null ? "" : acc.name;
            BigDecimal salary;
            try {
                salary = formula.salary(values(acc.shooting, acc.retouching, acc.confirmedCount, acc.pendingCount,
                        acc.pendingMinutes, acc.adopted, acc.uploaded));
            } catch (FormulaException e) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "工资公式在「" + project.title() + "」的 "
                        + name + " 这一行算不下去：" + e.getMessage());
            }
            lines.add(new Line(acc.projectId, project.title(), project.completedAt(), acc.memberKey, acc.studentId,
                    name, acc.campusText(), acc.shooting, acc.retouching, acc.confirmedCount, acc.pendingCount,
                    acc.pendingMinutes, acc.adopted, acc.uploaded, salary, null));
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
        SalaryFormula formula = SalaryFormula.compile(summary.formula());
        List<Line> lines = jdbc.sql("""
                SELECT project_id, project_title, project_completed_at, member_key, student_id, member_name, campus,
                       shooting_minutes, retouching_minutes, confirmed_worklog_count, pending_worklog_count,
                       pending_minutes, adopted_photos, uploaded_photos, salary_amount
                FROM salary_calculation_line WHERE calculation_id=:id
                """).param("id", id)
                .query((rs, n) -> {
                    int shooting = rs.getInt(8), retouching = rs.getInt(9), confirmed = rs.getInt(10);
                    int pending = rs.getInt(11), pendingMinutes = rs.getInt(12), adopted = rs.getInt(13);
                    int uploaded = rs.getInt(14);
                    String process;
                    try {
                        process = formula.explain(values(shooting, retouching, confirmed, pending, pendingMinutes,
                                adopted, uploaded));
                    } catch (FormulaException e) {
                        process = e.getMessage();
                    }
                    return new Line(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDateTime.class),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), shooting, retouching,
                            confirmed, pending, pendingMinutes, adopted, uploaded, rs.getBigDecimal(15), process);
                })
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
            SELECT id, from_date, to_date, formula, project_count, member_count, line_count,
                   total_amount, pending_worklog_count, created_by_name, created_at
            FROM salary_calculation""";

    private static CalculationSummary summaryRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CalculationSummary(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class),
                rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getBigDecimal(8), rs.getInt(9),
                rs.getString(10), rs.getObject(11, LocalDateTime.class));
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
                    group.stream().mapToInt(Line::uploadedPhotos).sum(),
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
                    group.stream().mapToInt(Line::shootingMinutes).sum(),
                    group.stream().mapToInt(Line::retouchingMinutes).sum(),
                    group.stream().mapToInt(Line::adoptedPhotos).sum(),
                    group.stream().mapToInt(Line::uploadedPhotos).sum(),
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
        int uploaded;

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

    /**
     * 一个选题里一位成员的工资。{@code memberKey}：{@code id:<学号>} 或 {@code name:<姓名>}。
     * {@code process}：计算过程（公式代入数值），只在查看明细时有，计算时为空。
     */
    public record Line(long projectId, String projectTitle, LocalDateTime projectCompletedAt, String memberKey,
                       String studentId, String memberName, String campus, int shootingMinutes,
                       int retouchingMinutes, int confirmedWorklogCount, int pendingWorklogCount, int pendingMinutes,
                       int adoptedPhotos, int uploadedPhotos, BigDecimal salaryAmount, String process) {
        public int confirmedMinutes() {
            return shootingMinutes + retouchingMinutes;
        }
    }

    public record CalculationSummary(long id, LocalDate from, LocalDate to, String formula, int projectCount,
                                     int memberCount, int lineCount, BigDecimal totalAmount, int pendingWorklogCount,
                                     String createdByName, LocalDateTime createdAt) {}

    public record ProjectGroup(long projectId, String projectTitle, LocalDateTime completedAt, int confirmedMinutes,
                               int adoptedPhotos, int uploadedPhotos, BigDecimal salaryAmount,
                               int pendingWorklogCount, List<Line> lines) {}

    /** 一位成员这一期在各个选题的合计；{@code projectCount} 是他有记录的选题数。 */
    public record MemberTotal(String memberKey, String studentId, String memberName, String campus, int projectCount,
                              int shootingMinutes, int retouchingMinutes, int adoptedPhotos, int uploadedPhotos,
                              BigDecimal salaryAmount, int pendingWorklogCount) {
        public int confirmedMinutes() {
            return shootingMinutes + retouchingMinutes;
        }
    }

    public record Detail(CalculationSummary calculation, List<ProjectGroup> projects, List<MemberTotal> members) {}

    public record VariableInfo(String name, String alias, String description, BigDecimal sample) {}

    public record FunctionInfo(String name, String chineseName, String signature, String description) {}

    public record FormulaReference(String defaultFormula, String lastFormula, List<VariableInfo> variables,
                                   List<FunctionInfo> functions) {}

    /**
     * 公式检查结果。{@code valid} 为假时 {@code error} 是原因、{@code position} 是出错字符的下标（从 0 数，可能为空）；
     * 为真时 {@code sampleSalary} / {@code sampleProcess} 是用示例值试算的结果（试算出错时为空，原因在 {@code error}）。
     */
    public record FormulaCheck(boolean valid, String error, Integer position, String formula, List<String> variables,
                               BigDecimal sampleSalary, String sampleProcess) {}
}
