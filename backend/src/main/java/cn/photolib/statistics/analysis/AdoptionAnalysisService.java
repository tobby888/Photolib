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
import java.time.LocalDateTime;
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
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 统计面板的「被引分析」：哪些图在什么时候、被哪些选题引用，按摄影师 / 选题 / 校区 / 时间汇总。
 *
 * <p><b>两种时间口径。</b>{@link Basis#ADOPTED} 按被引时间（{@code adoption.adopted_at}，两端日期都含）取，
 * 看「这段时间选了多少图」；{@link Basis#PROJECT_COMPLETED} 按选题结束时间取、只看已完成的选题，与
 * 成员核算（{@code StatisticsService.members} 的 {@code adoption_totals}）是同一个口径——成员核算那一行点进来看
 * 被引详情时用它，{@link Summary#photoCount()} 才和表里的被引张数对得上。无论哪种口径，走势和星期分布
 * 都按被引时间画。</p>
 *
 * <p><b>摄影师按被引时的快照算</b>（{@code adoption.photographer_student_id}），与成员核算、工时导出一致；
 * 选题内的「被引图片作者名单」（{@code AdoptionService.photographers}）取图片上现在的拍摄者，是另一套口径，
 * 不要互相统一。</p>
 *
 * <p><b>张数与次数。</b>同一张图可以被几个选题各引一次：「被引次数」数 {@code adoption} 行，「被引张数」
 * 按 {@code photo_id} 去重。按选题拆开时两者相同；跨选题加总时张数会小于各选题之和，界面上要写明。</p>
 *
 * <p>可见范围与成员核算一致：校区范围账号只看图片在授权校区的被引；授权校区为空时什么都看不到
 * （占位 id {@code -1}），不能把空集合当成「全局」。范围条件写死在基础查询里。</p>
 */
@Service
@RequiredArgsConstructor
public class AdoptionAnalysisService {
    /** 一次分析最多汇总多少条被引；超过就请用户收窄，不悄悄截断。 */
    public static final int MAX_ROWS = 50_000;
    /** 明细最多带回多少条（最新的在前）。 */
    public static final int MAX_DETAIL_ROWS = 500;
    /** 日期范围上限：三年。 */
    public static final long MAX_RANGE_DAYS = 366L * 3;

    private static final String FROM_CLAUSE = """
            FROM adoption a
            JOIN project p ON p.id = a.project_id
            JOIN photo ph ON ph.id = a.photo_id
            LEFT JOIN campus c ON c.id = ph.campus_id
            LEFT JOIN app_user u ON u.id = a.adopted_by
            WHERE a.deleted = 0 AND p.deleted = 0
              AND (:campusScoped = FALSE OR ph.campus_id IN (:campusIds))
            """;
    private static final String ADOPTED_RANGE =
            "  AND a.adopted_at >= :fromDate AND a.adopted_at < :toExclusive\n";
    private static final String COMPLETED_RANGE = """
              AND p.status = 'COMPLETED'
              AND p.completed_at >= :fromDate AND p.completed_at < :toExclusive
            """;
    private static final String NARROWING = """
              AND (:projectId = 0 OR a.project_id = :projectId)
              AND (:campusId = 0 OR ph.campus_id = :campusId)
              AND (:studentId = '' OR a.photographer_student_id = :studentId)
            """;

    private final JdbcClient jdbc;

    public AnalysisResult analyze(Query query, AuthenticatedUser principal) {
        validateRange(query.from(), query.to());
        if (principal.isCampusScoped() && query.campusId() != null && !principal.canAccessCampus(query.campusId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该校区的统计数据");
        }
        Basis basis = query.basis() == null ? Basis.ADOPTED : query.basis();
        String where = FROM_CLAUSE + range(basis) + NARROWING;
        Map<String, Object> params = baseParams(query.from(), query.to(), principal);
        params.put("projectId", query.projectId() == null ? 0L : query.projectId());
        params.put("campusId", query.campusId() == null ? 0L : query.campusId());
        params.put("studentId", query.studentId() == null ? "" : query.studentId().strip());

        long count = jdbc.sql("SELECT COUNT(*) " + where).params(params).query(Long.class).single();
        if (count > MAX_ROWS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "符合条件的被引有 " + count + " 条，超过单次分析上限 " + MAX_ROWS + " 条，请缩小日期范围或增加条件");
        }
        List<Row> rows = jdbc.sql("""
                        SELECT a.id, a.adopted_at, a.project_id, p.title, p.status, p.completed_at,
                               a.photo_id, COALESCE(ph.title, ph.stored_file_name), ph.taken_at,
                               ph.campus_id, c.name, a.photographer_student_id, a.photographer_name,
                               u.display_name, a.remark
                        """ + where + "ORDER BY a.adopted_at DESC, a.id DESC")
                .params(params)
                .query((rs, n) -> new Row(rs.getLong(1), rs.getObject(2, LocalDateTime.class),
                        rs.getLong(3), rs.getString(4), rs.getString(5), rs.getObject(6, LocalDateTime.class),
                        rs.getLong(7), rs.getString(8), rs.getObject(9, LocalDateTime.class),
                        rs.getObject(10, Long.class), rs.getString(11), rs.getString(12), rs.getString(13),
                        rs.getString(14), rs.getString(15)))
                .list();

        // 按选题结束时间取时，被引时间可能早于区间起点（选题进行中选的图、后来才结束），
        // 走势的横轴就跟着数据走，而不是硬套筛选区间、把这些点画到图外面去。
        LocalDate trendFrom = query.from();
        LocalDate trendTo = query.to();
        if (basis == Basis.PROJECT_COMPLETED && !rows.isEmpty()) {
            trendFrom = rows.stream().map(row -> row.adoptedAt().toLocalDate()).min(Comparator.naturalOrder()).orElseThrow();
            trendTo = rows.stream().map(row -> row.adoptedAt().toLocalDate()).max(Comparator.naturalOrder()).orElseThrow();
        }
        Granularity granularity = resolve(query.granularity(), trendFrom, trendTo);
        return new AnalysisResult(
                basis,
                summarize(rows),
                rows.isEmpty() && basis == Basis.PROJECT_COMPLETED ? List.of()
                        : trend(rows, granularity, trendFrom, trendTo),
                granularity,
                weekdays(rows),
                lags(rows),
                projects(rows),
                photographers(rows),
                aggregate(rows, Dimension.CAMPUS),
                rows.subList(0, Math.min(rows.size(), MAX_DETAIL_ROWS)),
                rows.size() > MAX_DETAIL_ROWS);
    }

    /** 下拉候选：这段时间里调用者看得到的被引涉及到的选题、校区和摄影师。与分析共用基础查询。 */
    public AnalysisOptions options(LocalDate from, LocalDate to, Basis basis, AuthenticatedUser principal) {
        validateRange(from, to);
        String where = FROM_CLAUSE + range(basis == null ? Basis.ADOPTED : basis);
        Map<String, Object> params = baseParams(from, to, principal);
        Collator collator = Collator.getInstance(Locale.CHINA);
        Comparator<Option> byLabel = Comparator.comparing(Option::label, Comparator.nullsLast(collator));
        List<Option> projects = jdbc.sql("SELECT DISTINCT p.id, p.title " + where).params(params)
                .query((rs, n) -> new Option(String.valueOf(rs.getLong(1)), rs.getString(2))).list();
        List<Option> campuses = jdbc.sql("SELECT DISTINCT c.id, c.name " + where + " AND c.id IS NOT NULL")
                .params(params)
                .query((rs, n) -> new Option(String.valueOf(rs.getLong(1)), rs.getString(2))).list();
        List<Option> photographers = jdbc.sql("SELECT a.photographer_student_id, MIN(a.photographer_name) " + where
                        + " GROUP BY a.photographer_student_id").params(params)
                .query((rs, n) -> new Option(rs.getString(1), rs.getString(2))).list();
        return new AnalysisOptions(projects.stream().sorted(byLabel).toList(),
                campuses.stream().sorted(byLabel).toList(), photographers.stream().sorted(byLabel).toList());
    }

    private static String range(Basis basis) {
        return basis == Basis.PROJECT_COMPLETED ? COMPLETED_RANGE : ADOPTED_RANGE;
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择日期范围");
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
        Map<String, Object> params = new HashMap<>();
        params.put("fromDate", from);
        // 结束日要算满一整天：按「次日零点之前」比，DATETIME 列上才不会漏掉当天的记录。
        params.put("toExclusive", to.plusDays(1));
        params.put("campusScoped", principal.isCampusScoped());
        // 全局账号的 scopedCampusIds 是空集合，拼进 IN () 是语法错误；校区范围账号授权为空时也落到这里，
        // 占位 id 不存在，于是什么都看不到——这正是要的结果。
        params.put("campusIds", scoped == null || scoped.isEmpty() ? Set.of(-1L) : scoped);
        return params;
    }

    private static Summary summarize(List<Row> rows) {
        Map<Long, Integer> perPhoto = new HashMap<>();
        Set<Long> projects = new HashSet<>();
        Set<String> photographers = new HashSet<>();
        Set<LocalDate> days = new HashSet<>();
        for (Row row : rows) {
            perPhoto.merge(row.photoId(), 1, Integer::sum);
            projects.add(row.projectId());
            photographers.add(row.photographerStudentId());
            days.add(row.adoptedAt().toLocalDate());
        }
        long reused = perPhoto.values().stream().filter(times -> times > 1).count();
        return new Summary(rows.size(), perPhoto.size(), projects.size(), photographers.size(),
                reused, days.size());
    }

    private static List<Bucket> aggregate(List<Row> rows, Dimension dimension) {
        Map<String, Accumulator> buckets = new LinkedHashMap<>();
        for (Row row : rows) {
            buckets.computeIfAbsent(dimension.key.apply(row), key -> new Accumulator(key, dimension.label.apply(row)))
                    .add(row);
        }
        Comparator<Bucket> order = switch (dimension) {
            case DAY, WEEK, MONTH, WEEKDAY -> Comparator.comparing(Bucket::key);
            default -> Comparator.comparingLong(Bucket::photoCount).reversed()
                    .thenComparing(Comparator.comparingLong(Bucket::adoptionCount).reversed())
                    .thenComparing(Bucket::key);
        };
        return buckets.values().stream().map(Accumulator::toBucket).sorted(order).toList();
    }

    /** 走势：区间内每个周期都有一个点，没有被引的周期补 0。 */
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
            Row probe = Row.on(cursor);
            Bucket bucket = present.get(dimension.key.apply(probe));
            series.add(bucket != null ? bucket : Bucket.empty(dimension.key.apply(probe), dimension.label.apply(probe)));
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
        if (granularity == Granularity.DAY && days > 120) granularity = Granularity.WEEK;
        if (granularity == Granularity.WEEK && days > 732) granularity = Granularity.MONTH;
        return granularity;
    }

    /** 星期分布总是周一到周日七根柱，没有被引的那几天补 0。 */
    private static List<Bucket> weekdays(List<Row> rows) {
        Map<String, Bucket> present = new HashMap<>();
        for (Bucket bucket : aggregate(rows, Dimension.WEEKDAY)) present.put(bucket.key(), bucket);
        List<Bucket> result = new ArrayList<>(7);
        for (int day = 1; day <= 7; day++) {
            Bucket bucket = present.get(String.valueOf(day));
            result.add(bucket != null ? bucket : Bucket.empty(String.valueOf(day), WEEKDAY_LABELS[day - 1]));
        }
        return result;
    }

    /** 从拍摄到被引隔了多久：图是现拍现用，还是从图库里翻出来的老图。按自然日算，拍摄当天被引算「当天」。 */
    private static List<Bucket> lags(List<Row> rows) {
        long[] bounds = {0, 3, 7, 30, 90};
        String[] labels = {"当天", "1 ~ 3 天", "4 ~ 7 天", "8 ~ 30 天", "31 ~ 90 天", "90 天以上"};
        Accumulator[] buckets = new Accumulator[labels.length];
        for (int i = 0; i < labels.length; i++) buckets[i] = new Accumulator(String.valueOf(i), labels[i]);
        for (Row row : rows) {
            if (row.takenAt() == null) continue;
            // 拍摄时间来自 EXIF 或手填，可能晚于被引时间（相机时钟不准）；这种算「当天」，不另开一档负数。
            long days = Math.max(0, ChronoUnit.DAYS.between(row.takenAt().toLocalDate(), row.adoptedAt().toLocalDate()));
            int index = 0;
            while (index < bounds.length && days > bounds[index]) index++;
            buckets[index].add(row);
        }
        List<Bucket> result = new ArrayList<>(labels.length);
        for (Accumulator bucket : buckets) result.add(bucket.toBucket());
        return result;
    }

    private static List<ProjectBucket> projects(List<Row> rows) {
        Map<Long, List<Row>> byProject = new LinkedHashMap<>();
        for (Row row : rows) byProject.computeIfAbsent(row.projectId(), id -> new ArrayList<>()).add(row);
        List<ProjectBucket> result = new ArrayList<>();
        for (Map.Entry<Long, List<Row>> entry : byProject.entrySet()) {
            List<Row> items = entry.getValue();
            Row first = items.getFirst();
            Set<Long> photos = new HashSet<>();
            Set<String> photographers = new HashSet<>();
            LocalDateTime earliest = first.adoptedAt();
            LocalDateTime latest = first.adoptedAt();
            for (Row row : items) {
                photos.add(row.photoId());
                photographers.add(row.photographerStudentId());
                if (row.adoptedAt().isBefore(earliest)) earliest = row.adoptedAt();
                if (row.adoptedAt().isAfter(latest)) latest = row.adoptedAt();
            }
            result.add(new ProjectBucket(entry.getKey(), nonNull(first.projectTitle(), "已删除的选题"),
                    first.projectStatus(), first.projectCompletedAt(), items.size(), photos.size(),
                    photographers.size(), earliest, latest));
        }
        result.sort(Comparator.comparingLong(ProjectBucket::photoCount).reversed()
                .thenComparing(ProjectBucket::lastAdoptedAt, Comparator.reverseOrder()));
        return result;
    }

    private static List<PhotographerBucket> photographers(List<Row> rows) {
        Map<String, List<Row>> byStudent = new LinkedHashMap<>();
        for (Row row : rows) byStudent.computeIfAbsent(row.photographerStudentId(), id -> new ArrayList<>()).add(row);
        Collator collator = Collator.getInstance(Locale.CHINA);
        List<PhotographerBucket> result = new ArrayList<>();
        for (Map.Entry<String, List<Row>> entry : byStudent.entrySet()) {
            Set<Long> photos = new HashSet<>();
            Set<Long> projects = new HashSet<>();
            TreeSet<String> campuses = new TreeSet<>(collator);
            // 同一学号在不同被引里的姓名快照可能不一样（多打一个空格），与成员核算一样取最小的那个。
            String name = null;
            for (Row row : entry.getValue()) {
                photos.add(row.photoId());
                projects.add(row.projectId());
                if (row.campusName() != null) campuses.add(row.campusName());
                if (name == null || row.photographerName().compareTo(name) < 0) name = row.photographerName();
            }
            result.add(new PhotographerBucket(entry.getKey(), name, entry.getValue().size(), photos.size(),
                    projects.size(), String.join("、", campuses)));
        }
        result.sort(Comparator.comparingLong(PhotographerBucket::photoCount).reversed()
                .thenComparing(Comparator.comparingLong(PhotographerBucket::adoptionCount).reversed())
                .thenComparing(PhotographerBucket::studentId));
        return result;
    }

    private static final class Accumulator {
        private final String key;
        private final String label;
        private long count;
        private final Set<Long> photos = new HashSet<>();
        private final Set<Long> projects = new HashSet<>();
        private final Set<String> photographers = new HashSet<>();

        Accumulator(String key, String label) {
            this.key = key;
            this.label = label;
        }

        void add(Row row) {
            count++;
            photos.add(row.photoId());
            projects.add(row.projectId());
            photographers.add(row.photographerStudentId());
        }

        Bucket toBucket() {
            return new Bucket(key, label, count, photos.size(), projects.size(), photographers.size());
        }
    }

    private static final String[] WEEKDAY_LABELS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    enum Dimension {
        CAMPUS(row -> row.campusId() == null ? "none" : String.valueOf(row.campusId()),
                row -> row.campusId() == null ? "未标校区" : nonNull(row.campusName(), "已删除的校区")),
        DAY(row -> row.adoptedAt().toLocalDate().toString(), row -> row.adoptedAt().toLocalDate().toString()),
        WEEK(row -> monday(row).toString(), row -> monday(row) + " 起一周"),
        MONTH(row -> row.adoptedAt().toLocalDate().toString().substring(0, 7),
                row -> row.adoptedAt().getYear() + " 年 " + row.adoptedAt().getMonthValue() + " 月"),
        WEEKDAY(row -> String.valueOf(row.adoptedAt().getDayOfWeek().getValue()),
                row -> WEEKDAY_LABELS[row.adoptedAt().getDayOfWeek().getValue() - 1]);

        final Function<Row, String> key;
        final Function<Row, String> label;

        Dimension(Function<Row, String> key, Function<Row, String> label) {
            this.key = key;
            this.label = label;
        }

        private static LocalDate monday(Row row) {
            return row.adoptedAt().toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }
    }

    private static String nonNull(String value, String fallback) {
        return value == null ? fallback : value;
    }

    /** 时间口径：按被引时间，或按选题结束时间（只看已完成的选题，与成员核算一致）。 */
    public enum Basis { ADOPTED, PROJECT_COMPLETED }

    public enum Granularity { AUTO, DAY, WEEK, MONTH }

    /**
     * @param from      起（含）
     * @param to        止（含）
     * @param studentId 只看某位摄影师（按被引时的学号快照），可空
     */
    public record Query(LocalDate from, LocalDate to, Basis basis, Long projectId, Long campusId,
                        String studentId, Granularity granularity) {
    }

    public record AnalysisResult(Basis basis, Summary summary, List<Bucket> trend, Granularity granularity,
                                 List<Bucket> weekdays, List<Bucket> lags, List<ProjectBucket> projects,
                                 List<PhotographerBucket> photographers, List<Bucket> campuses,
                                 List<Row> rows, boolean rowsTruncated) {
    }

    /**
     * @param adoptionCount  被引次数（adoption 行数）
     * @param photoCount     被引张数（按图片去重）
     * @param reusedPhotoCount 被两个及以上选题引用的图片张数
     */
    public record Summary(long adoptionCount, long photoCount, long projectCount, long photographerCount,
                          long reusedPhotoCount, long activeDays) {
    }

    public record Bucket(String key, String label, long adoptionCount, long photoCount, long projectCount,
                         long photographerCount) {
        static Bucket empty(String key, String label) {
            return new Bucket(key, label, 0, 0, 0, 0);
        }
    }

    public record ProjectBucket(long projectId, String title, String status, LocalDateTime completedAt,
                                long adoptionCount, long photoCount, long photographerCount,
                                LocalDateTime firstAdoptedAt, LocalDateTime lastAdoptedAt) {
    }

    /** {@code campus} 是这些被引图片所在的全部校区，跨校区时形如「甲校区、乙校区」。 */
    public record PhotographerBucket(String studentId, String name, long adoptionCount, long photoCount,
                                     long projectCount, String campus) {
    }

    /** 一条被引记录；{@code photoTitle} 没有标题时退回存储文件名。 */
    public record Row(long adoptionId, LocalDateTime adoptedAt, long projectId, String projectTitle,
                      String projectStatus, LocalDateTime projectCompletedAt, long photoId, String photoTitle,
                      LocalDateTime takenAt, Long campusId, String campusName, String photographerStudentId,
                      String photographerName, String adoptedByName, String remark) {
        /** 只带日期的占位行，用来给走势里没有数据的周期算 key 和 label。 */
        static Row on(LocalDate date) {
            return new Row(0, date.atStartOfDay(), 0, null, null, null, 0, null, null, null, null, "", "", null, null);
        }
    }

    public record Option(String value, String label) {
    }

    public record AnalysisOptions(List<Option> projects, List<Option> campuses, List<Option> photographers) {
    }
}
