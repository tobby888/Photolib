package cn.photolib.statistics.analysis;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 统计面板的「工时分析」：按用户拼的条件筛工时，再按维度汇总、出趋势和分布。
 *
 * <p><b>口径与成员统计不同，刻意如此。</b>成员统计（{@code StatisticsService.members}）是核工资用的，
 * 只看已确认工时、按选题结束时间落区间；这里是看「这段时间大家干了多少活」，按工时日期
 * （{@code work_date}，两端都含）取，状态由用户自己筛（界面默认只勾「已确认」）。草稿始终不算：
 * 它还没交上来，而且是填报人自己的东西。</p>
 *
 * <p>可见范围与工时导出一致：校区范围账号只看授权校区的需求下的工时，链接申报的工时（没有校区）
 * 对它不出现。这条范围条件是写死在基础查询里的，用户的条件树只能在它之内再收窄。</p>
 *
 * <p>筛选在 SQL 里做（条件树经 {@link WorklogConditionCompiler} 编译成参数化的 WHERE），
 * 汇总在 Java 里做：按月 / 按周 / 按星期分组在 MySQL 与 H2 之间没有一套通用的写法，
 * 而命中的行数先数一遍、超过 {@link #MAX_ROWS} 就请用户收窄，内存是有界的。</p>
 */
@Service
@RequiredArgsConstructor
public class WorklogAnalysisService {
    /** 一次分析最多汇总多少条工时；超过就让用户缩小范围，而不是悄悄截断给出错误的合计。 */
    public static final int MAX_ROWS = 50_000;
    /** 明细表最多带回多少条（最新的在前）。 */
    public static final int MAX_DETAIL_ROWS = 500;
    /** 日期范围上限：三年。按月出趋势时是 36 个点。 */
    public static final long MAX_RANGE_DAYS = 366L * 3;

    private static final String FROM_CLAUSE = """
            FROM worklog w
            LEFT JOIN photo_request r ON r.id = w.request_id
            LEFT JOIN project_share_link l ON l.id = w.share_link_id
            JOIN project p ON p.id = COALESCE(r.project_id, l.project_id)
            LEFT JOIN campus c ON c.id = r.campus_id
            LEFT JOIN app_user u ON u.id = w.user_id
            WHERE w.deleted = 0 AND p.deleted = 0 AND w.status <> 'DRAFT'
              AND w.work_date BETWEEN :fromDate AND :toDate
              AND (:campusScoped = FALSE OR r.campus_id IN (:campusIds))
            """;

    private final JdbcClient jdbc;

    public AnalysisResult analyze(AnalysisRequest request, AuthenticatedUser principal) {
        validateRange(request.from(), request.to());
        WorklogConditionCompiler.Compiled filter = WorklogConditionCompiler.compile(request.filter());
        String where = FROM_CLAUSE + "  AND " + filter.sql() + "\n";
        Map<String, Object> params = baseParams(request.from(), request.to(), principal);
        params.putAll(filter.params());

        long count = jdbc.sql("SELECT COUNT(*) " + where).params(params).query(Long.class).single();
        if (count > MAX_ROWS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "符合条件的工时有 " + count + " 条，超过单次分析上限 " + MAX_ROWS + " 条，请缩小日期范围或增加条件");
        }
        List<Row> rows = jdbc.sql("""
                        SELECT w.id, w.work_date, w.member_student_id, w.member_name,
                               w.shooting_minutes, w.retouching_minutes, w.status,
                               w.request_id, r.title, p.id, p.title, r.campus_id, c.name,
                               w.user_id, u.display_name, w.remark
                        """ + where + "ORDER BY w.work_date DESC, w.id DESC")
                .params(params)
                .query((rs, n) -> new Row(rs.getLong(1), rs.getObject(2, LocalDate.class),
                        rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getString(7),
                        rs.getObject(8, Long.class), rs.getString(9), rs.getLong(10), rs.getString(11),
                        rs.getObject(12, Long.class), rs.getString(13), rs.getLong(14), rs.getString(15),
                        rs.getString(16)))
                .list();

        Dimension groupBy = request.groupBy() == null ? Dimension.MEMBER : request.groupBy();
        Granularity granularity = resolve(request.granularity(), request.from(), request.to());
        return new AnalysisResult(
                summarize(rows),
                aggregate(rows, groupBy),
                groupBy,
                trend(rows, granularity, request.from(), request.to()),
                granularity,
                weekdays(rows),
                aggregate(rows, Dimension.STATUS),
                aggregate(rows, Dimension.SOURCE),
                durationBuckets(rows),
                aggregate(rows, Dimension.DAY),
                rows.subList(0, Math.min(rows.size(), MAX_DETAIL_ROWS)),
                rows.size() > MAX_DETAIL_ROWS);
    }

    /**
     * 拼条件时下拉框的候选：这段时间里调用者看得到的工时涉及到的选题、校区、成员和填报账号。
     * 和分析共用同一个基础查询，所以候选里不会出现选了以后必然是空结果的值，
     * 也不会把校区范围账号看不到的人和选题泄露出去。
     */
    public AnalysisOptions options(LocalDate from, LocalDate to, AuthenticatedUser principal) {
        validateRange(from, to);
        Map<String, Object> params = baseParams(from, to, principal);
        Collator collator = Collator.getInstance(Locale.CHINA);
        List<Option> projects = jdbc.sql("SELECT DISTINCT p.id, p.title " + FROM_CLAUSE).params(params)
                .query((rs, n) -> new Option(String.valueOf(rs.getLong(1)), rs.getString(2))).list();
        List<Option> campuses = jdbc.sql("SELECT DISTINCT c.id, c.name " + FROM_CLAUSE + " AND c.id IS NOT NULL")
                .params(params)
                .query((rs, n) -> new Option(String.valueOf(rs.getLong(1)), rs.getString(2))).list();
        List<Option> members = jdbc.sql("SELECT w.member_student_id, MIN(w.member_name) " + FROM_CLAUSE
                        + " GROUP BY w.member_student_id").params(params)
                .query((rs, n) -> new Option(rs.getString(1), rs.getString(2))).list();
        List<Option> submitters = jdbc.sql("SELECT DISTINCT w.user_id, u.display_name " + FROM_CLAUSE)
                .params(params)
                .query((rs, n) -> new Option(String.valueOf(rs.getLong(1)),
                        rs.getString(2) == null ? "已删除的成员" : rs.getString(2))).list();
        Comparator<Option> byLabel = Comparator.comparing(Option::label, Comparator.nullsLast(collator));
        return new AnalysisOptions(sorted(projects, byLabel), sorted(campuses, byLabel),
                sorted(members, byLabel), sorted(submitters, byLabel));
    }

    private static List<Option> sorted(List<Option> options, Comparator<Option> order) {
        return options.stream().sorted(order).toList();
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择工时日期范围");
        }
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "日期范围最长三年");
        }
    }

    private static Map<String, Object> baseParams(LocalDate from, LocalDate to, AuthenticatedUser principal) {
        Set<Long> scoped = principal.scopedCampusIds();
        boolean campusScoped = principal.isCampusScoped();
        Map<String, Object> params = new HashMap<>();
        params.put("fromDate", from);
        params.put("toDate", to);
        params.put("campusScoped", campusScoped);
        // 全局账号的 scopedCampusIds 是空集合，拼进 IN () 是语法错误，给一个不存在的 id 占位。
        params.put("campusIds", scoped == null || scoped.isEmpty() ? Set.of(-1L) : scoped);
        return params;
    }

    private static Summary summarize(List<Row> rows) {
        long shooting = 0;
        long retouching = 0;
        Set<String> members = new HashSet<>();
        Set<Long> projects = new HashSet<>();
        Set<LocalDate> days = new HashSet<>();
        for (Row row : rows) {
            shooting += row.shootingMinutes();
            retouching += row.retouchingMinutes();
            members.add(row.memberStudentId());
            projects.add(row.projectId());
            days.add(row.workDate());
        }
        return new Summary(rows.size(), shooting, retouching, shooting + retouching,
                members.size(), projects.size(), days.size());
    }

    private static List<Bucket> aggregate(List<Row> rows, Dimension dimension) {
        Map<String, Accumulator> buckets = new LinkedHashMap<>();
        for (Row row : rows) {
            String key = dimension.key.apply(row);
            buckets.computeIfAbsent(key, k -> new Accumulator(k, dimension.label.apply(row))).add(row);
        }
        Comparator<Bucket> order = switch (dimension) {
            case DAY, WEEK, MONTH, WEEKDAY -> Comparator.comparing(Bucket::key);
            default -> Comparator.comparingLong(Bucket::totalMinutes).reversed()
                    .thenComparing(Bucket::key);
        };
        return buckets.values().stream().map(Accumulator::toBucket).sorted(order).toList();
    }

    /** 趋势：区间内每个周期都有一个点，没有工时的周期补 0，折线才不会把空档连成一条斜线。 */
    private static List<Bucket> trend(List<Row> rows, Granularity granularity, LocalDate from, LocalDate to) {
        Dimension dimension = switch (granularity) {
            case DAY -> Dimension.DAY;
            case WEEK -> Dimension.WEEK;
            default -> Dimension.MONTH;
        };
        Map<String, Bucket> present = new HashMap<>();
        for (Bucket bucket : aggregate(rows, dimension)) present.put(bucket.key(), bucket);
        List<Bucket> series = new ArrayList<>();
        LocalDate cursor = switch (granularity) {
            case DAY -> from;
            case WEEK -> from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            default -> from.withDayOfMonth(1);
        };
        while (!cursor.isAfter(to)) {
            String key = dimension.key.apply(Row.on(cursor));
            Bucket bucket = present.get(key);
            series.add(bucket != null ? bucket
                    : new Bucket(key, dimension.label.apply(Row.on(cursor)), 0, 0, 0, 0, 0));
            cursor = switch (granularity) {
                case DAY -> cursor.plusDays(1);
                case WEEK -> cursor.plusWeeks(1);
                default -> cursor.plusMonths(1);
            };
        }
        return series;
    }

    private static Granularity resolve(Granularity requested, LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        Granularity granularity = requested == null || requested == Granularity.AUTO
                ? days <= 62 ? Granularity.DAY : days <= 366 ? Granularity.WEEK : Granularity.MONTH
                : requested;
        // 按天画三年就是一千多个点，既看不清也没必要；太细的就自动放粗一档。
        if (granularity == Granularity.DAY && days > 120) granularity = Granularity.WEEK;
        if (granularity == Granularity.WEEK && days > 732) granularity = Granularity.MONTH;
        return granularity;
    }

    /** 星期分布总是周一到周日七根柱：没有工时的那几天补 0，否则「周末没人干活」在图上就看不出来。 */
    private static List<Bucket> weekdays(List<Row> rows) {
        Map<String, Bucket> present = new HashMap<>();
        for (Bucket bucket : aggregate(rows, Dimension.WEEKDAY)) present.put(bucket.key(), bucket);
        List<Bucket> result = new ArrayList<>(7);
        for (int day = 1; day <= 7; day++) {
            Bucket bucket = present.get(String.valueOf(day));
            result.add(bucket != null ? bucket
                    : new Bucket(String.valueOf(day), WEEKDAY_LABELS[day - 1], 0, 0, 0, 0, 0));
        }
        return result;
    }

    /** 单条工时的时长分布：多数人一次报多久，有没有异常长的。 */
    private static List<Bucket> durationBuckets(List<Row> rows) {
        int[] bounds = {60, 120, 240, 480};
        String[] labels = {"1 小时以内", "1 ~ 2 小时", "2 ~ 4 小时", "4 ~ 8 小时", "8 小时以上"};
        Accumulator[] buckets = new Accumulator[labels.length];
        for (int i = 0; i < labels.length; i++) buckets[i] = new Accumulator(String.valueOf(i), labels[i]);
        for (Row row : rows) {
            int total = row.shootingMinutes() + row.retouchingMinutes();
            int index = 0;
            while (index < bounds.length && total > bounds[index]) index++;
            buckets[index].add(row);
        }
        List<Bucket> result = new ArrayList<>();
        for (Accumulator bucket : buckets) result.add(bucket.toBucket());
        return result;
    }

    private static final class Accumulator {
        private final String key;
        private final String label;
        private long count;
        private long shooting;
        private long retouching;
        private final Set<String> members = new HashSet<>();

        Accumulator(String key, String label) {
            this.key = key;
            this.label = label;
        }

        void add(Row row) {
            count++;
            shooting += row.shootingMinutes();
            retouching += row.retouchingMinutes();
            members.add(row.memberStudentId());
        }

        Bucket toBucket() {
            return new Bucket(key, label, count, shooting, retouching, shooting + retouching, members.size());
        }
    }

    private static final String[] WEEKDAY_LABELS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /** 汇总维度。{@code key} 决定分组和时间维度的排序，{@code label} 是给人看的名字。 */
    public enum Dimension {
        MEMBER(Row::memberStudentId, row -> row.memberName() + "（" + row.memberStudentId() + "）"),
        CAMPUS(row -> row.campusId() == null ? "none" : String.valueOf(row.campusId()),
                row -> row.campusId() == null ? "上传链接申报（无校区）" : nonNull(row.campusName(), "已删除的校区")),
        PROJECT(row -> String.valueOf(row.projectId()), row -> nonNull(row.projectTitle(), "已删除的选题")),
        SUBMITTER(row -> String.valueOf(row.userId()), row -> nonNull(row.userDisplayName(), "已删除的成员")),
        STATUS(Row::status, row -> switch (row.status()) {
            case "CONFIRMED" -> "已确认";
            case "SUBMITTED" -> "待确认";
            case "REJECTED" -> "已退回";
            default -> row.status();
        }),
        SOURCE(row -> row.requestId() == null ? "SHARE_LINK" : "IN_APP",
                row -> row.requestId() == null ? "上传链接申报" : "站内填报"),
        DAY(row -> row.workDate().toString(), row -> row.workDate().toString()),
        WEEK(row -> row.workDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
                row -> row.workDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) + " 起一周"),
        MONTH(row -> row.workDate().toString().substring(0, 7),
                row -> row.workDate().getYear() + " 年 " + row.workDate().getMonthValue() + " 月"),
        WEEKDAY(row -> String.valueOf(row.workDate().getDayOfWeek().getValue()),
                row -> WEEKDAY_LABELS[row.workDate().getDayOfWeek().getValue() - 1]);

        final Function<Row, String> key;
        final Function<Row, String> label;

        Dimension(Function<Row, String> key, Function<Row, String> label) {
            this.key = key;
            this.label = label;
        }
    }

    private static String nonNull(String value, String fallback) {
        return value == null ? fallback : value;
    }

    public enum Granularity { AUTO, DAY, WEEK, MONTH }

    /**
     * @param from    工时日期起（含）
     * @param to      工时日期止（含）
     * @param filter  条件树，可空（不额外筛选）
     * @param groupBy 汇总维度，默认按成员
     */
    public record AnalysisRequest(LocalDate from, LocalDate to, WorklogCondition filter,
                                  Dimension groupBy, Granularity granularity) {
    }

    public record AnalysisResult(Summary summary, List<Bucket> groups, Dimension groupBy,
                                 List<Bucket> trend, Granularity granularity,
                                 List<Bucket> weekdays, List<Bucket> statuses, List<Bucket> sources,
                                 List<Bucket> durations, List<Bucket> daily,
                                 List<Row> rows, boolean rowsTruncated) {
    }

    public record Summary(long worklogCount, long shootingMinutes, long retouchingMinutes, long totalMinutes,
                          long memberCount, long projectCount, long activeDays) {
    }

    public record Bucket(String key, String label, long worklogCount, long shootingMinutes,
                         long retouchingMinutes, long totalMinutes, long memberCount) {
    }

    /** 一条命中的工时；{@code requestId} / {@code campusId} 为空的是通过上传链接申报的。 */
    public record Row(long worklogId, LocalDate workDate, String memberStudentId, String memberName,
                      int shootingMinutes, int retouchingMinutes, String status,
                      Long requestId, String requestTitle, long projectId, String projectTitle,
                      Long campusId, String campusName, long userId, String userDisplayName, String remark) {
        /** 只带日期的占位行，用来给趋势里没有数据的周期算 key 和 label。 */
        static Row on(LocalDate date) {
            return new Row(0, date, "", "", 0, 0, "", null, null, 0, null, null, null, 0, null, null);
        }
    }

    public record Option(String value, String label) {
    }

    public record AnalysisOptions(List<Option> projects, List<Option> campuses,
                                  List<Option> members, List<Option> submitters) {
    }
}
