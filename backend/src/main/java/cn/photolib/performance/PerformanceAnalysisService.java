package cn.photolib.performance;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 性能分析面板的两块读数据：
 * <ul>
 *   <li><b>实时窗口</b>：最近 N 秒（5 秒～10 分钟）里每一次功能调用的明细与按功能汇总，分位数按全部样本精确计算；</li>
 *   <li><b>一周分位数</b>：最近 7 天每个功能的 p50 / p90 / p95 / p99，以及每天的走势，来自每小时直方图（误差 ≤ 8%）。</li>
 * </ul>
 * 两块都把「图片处理」（生成缩略图、压缩原图）和 HTTP 接口分开给，面板上单独成区。
 */
@Service
public class PerformanceAnalysisService {
    public static final int MIN_WINDOW_SECONDS = 5;
    public static final int MAX_WINDOW_SECONDS = 600;
    static final int RECENT_LIMIT = 300;
    static final int WEEK_DAYS = 7;

    private final PerformanceMonitor monitor;
    private final LatencyStatsStore stats;
    private final Clock clock;

    public PerformanceAnalysisService(PerformanceMonitor monitor, LatencyStatsStore stats, Clock clock) {
        this.monitor = monitor;
        this.stats = stats;
        this.clock = clock;
    }

    /** 毫秒，保留三位小数。 */
    public record Stats(long count, long errors, double avgMs, double p50Ms, double p90Ms, double p95Ms,
                        double p99Ms, double maxMs) {
        static final Stats EMPTY = new Stats(0, 0, 0, 0, 0, 0, 0, 0);
    }

    public record FeatureStats(String kind, String key, String label, String module, Stats stats, Long lastAt) {
    }

    public record CallView(long at, String kind, String key, String label, String module, int status,
                           double durationMs, String detail) {
    }

    public record LiveView(long generatedAt, int windowSeconds, boolean truncated, int capacity,
                           double callsPerSecond, Stats requests, Stats thumbnails, Stats compressions,
                           List<FeatureStats> features, List<FeatureStats> images, List<CallView> recent) {
    }

    public record DailyPoint(LocalDate day, Stats requests, Stats thumbnails, Stats compressions) {
    }

    public record WeeklyView(LocalDateTime from, LocalDateTime to, Stats requests, Stats thumbnails,
                             Stats compressions, List<FeatureStats> features, List<FeatureStats> images,
                             List<DailyPoint> daily) {
    }

    // ------------------------------------------------------------------ 实时窗口

    public LiveView live(int windowSeconds, AuthenticatedUser user) {
        requireAdmin(user);
        if (windowSeconds < MIN_WINDOW_SECONDS || windowSeconds > MAX_WINDOW_SECONDS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "统计窗口须在 5 秒到 10 分钟之间");
        }
        long now = clock.millis();
        LiveCallBuffer.Window window = monitor.live().since(now - windowSeconds * 1000L);
        List<LiveCallBuffer.Call> calls = window.calls();

        Map<String, List<LiveCallBuffer.Call>> byFeature = new LinkedHashMap<>();
        List<LiveCallBuffer.Call> requests = new ArrayList<>();
        List<LiveCallBuffer.Call> thumbnails = new ArrayList<>();
        List<LiveCallBuffer.Call> compressions = new ArrayList<>();
        for (LiveCallBuffer.Call call : calls) {
            byFeature.computeIfAbsent(call.kind() + "\n" + call.key(), key -> new ArrayList<>()).add(call);
            if (call.kind() == LiveCallBuffer.Kind.REQUEST) requests.add(call);
            else if (PerformanceMonitor.IMAGE_THUMBNAIL.equals(call.key())) thumbnails.add(call);
            else if (PerformanceMonitor.IMAGE_COMPRESS.equals(call.key())) compressions.add(call);
        }
        List<FeatureStats> features = new ArrayList<>();
        List<FeatureStats> images = new ArrayList<>();
        for (List<LiveCallBuffer.Call> group : byFeature.values()) {
            LiveCallBuffer.Call last = group.getLast();
            String label = null;
            for (LiveCallBuffer.Call call : group) if (call.label() != null) label = call.label();
            FeatureStats feature = new FeatureStats(last.kind().name(), last.key(), label, last.module(),
                    exact(group), last.at());
            (last.kind() == LiveCallBuffer.Kind.REQUEST ? features : images).add(feature);
        }
        features.sort(Comparator.comparingLong((FeatureStats f) -> f.stats().count()).reversed()
                .thenComparing(f -> f.key()));
        images.sort(Comparator.comparing(FeatureStats::key));

        List<CallView> recent = new ArrayList<>(Math.min(RECENT_LIMIT, calls.size()));
        for (int i = calls.size() - 1; i >= 0 && recent.size() < RECENT_LIMIT; i--) {
            LiveCallBuffer.Call call = calls.get(i);
            recent.add(new CallView(call.at(), call.kind().name(), call.key(), call.label(), call.module(),
                    call.status(), round(call.micros() / 1000.0), call.detail()));
        }
        return new LiveView(now, windowSeconds, window.truncated(), monitor.live().capacity(),
                round((double) requests.size() / windowSeconds), exact(requests), exact(thumbnails),
                exact(compressions), features, images, recent);
    }

    /** 全部样本排序后按最近秩（nearest-rank）取分位数。 */
    static Stats exact(List<LiveCallBuffer.Call> calls) {
        if (calls.isEmpty()) return Stats.EMPTY;
        long[] micros = new long[calls.size()];
        long errors = 0;
        long total = 0;
        for (int i = 0; i < micros.length; i++) {
            LiveCallBuffer.Call call = calls.get(i);
            micros[i] = call.micros();
            total += call.micros();
            if (!call.success()) errors++;
        }
        Arrays.sort(micros);
        return new Stats(micros.length, errors, round(total / 1000.0 / micros.length),
                ms(rank(micros, 0.50)), ms(rank(micros, 0.90)), ms(rank(micros, 0.95)), ms(rank(micros, 0.99)),
                ms(micros[micros.length - 1]));
    }

    static long rank(long[] sorted, double quantile) {
        int index = (int) Math.ceil(quantile * sorted.length) - 1;
        return sorted[Math.clamp(index, 0, sorted.length - 1)];
    }

    // ------------------------------------------------------------------ 一周分位数

    public WeeklyView weekly(AuthenticatedUser user) {
        requireAdmin(user);
        LocalDateTime to = LocalDateTime.now(clock);
        // 按整点对齐：从 7×24 小时前所在的那个整点算起。
        LocalDateTime from = to.minusDays(WEEK_DAYS).truncatedTo(ChronoUnit.HOURS);
        LatencyHistogram requests = new LatencyHistogram();
        List<FeatureStats> features = new ArrayList<>();
        List<FeatureStats> images = new ArrayList<>();
        LatencyHistogram thumbnails = new LatencyHistogram();
        LatencyHistogram compressions = new LatencyHistogram();
        for (LatencyStatsStore.Series series : stats.since(from)) {
            FeatureStats feature = new FeatureStats(series.kind(), series.opKey(), series.label(), series.module(),
                    of(series.histogram()), null);
            if (PerformanceMonitor.KIND_REQUEST.equals(series.kind())) {
                requests.merge(series.histogram());
                features.add(feature);
            } else {
                images.add(feature);
                if (PerformanceMonitor.IMAGE_THUMBNAIL.equals(series.opKey())) thumbnails.merge(series.histogram());
                if (PerformanceMonitor.IMAGE_COMPRESS.equals(series.opKey())) compressions.merge(series.histogram());
            }
        }
        features.sort(Comparator.comparingLong((FeatureStats f) -> f.stats().count()).reversed()
                .thenComparing(FeatureStats::key));
        images.sort(Comparator.comparing(FeatureStats::key));

        Map<LocalDate, LatencyHistogram[]> byDay = new HashMap<>();
        for (LatencyStatsStore.DailySeries series : stats.dailySince(from)) {
            LatencyHistogram[] day = byDay.computeIfAbsent(series.day().toLocalDate(),
                    key -> new LatencyHistogram[]{new LatencyHistogram(), new LatencyHistogram(), new LatencyHistogram()});
            if (PerformanceMonitor.KIND_REQUEST.equals(series.kind())) day[0].merge(series.histogram());
            else if (PerformanceMonitor.IMAGE_THUMBNAIL.equals(series.opKey())) day[1].merge(series.histogram());
            else if (PerformanceMonitor.IMAGE_COMPRESS.equals(series.opKey())) day[2].merge(series.histogram());
        }
        List<DailyPoint> daily = new ArrayList<>();
        for (LocalDate day = from.toLocalDate(); !day.isAfter(to.toLocalDate()); day = day.plusDays(1)) {
            LatencyHistogram[] values = byDay.get(day);
            daily.add(values == null
                    ? new DailyPoint(day, Stats.EMPTY, Stats.EMPTY, Stats.EMPTY)
                    : new DailyPoint(day, of(values[0]), of(values[1]), of(values[2])));
        }
        return new WeeklyView(from, to, of(requests), of(thumbnails), of(compressions), features, images, daily);
    }

    static Stats of(LatencyHistogram histogram) {
        if (histogram.count() == 0) return Stats.EMPTY;
        return new Stats(histogram.count(), histogram.errors(),
                round(histogram.totalMicros() / 1000.0 / histogram.count()),
                ms(histogram.percentile(0.50)), ms(histogram.percentile(0.90)), ms(histogram.percentile(0.95)),
                ms(histogram.percentile(0.99)), ms(histogram.maxMicros()));
    }

    private static double ms(long micros) {
        return round(micros / 1000.0);
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static void requireAdmin(AuthenticatedUser user) {
        if (user == null || !user.isAdministrator()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有系统管理员可以使用性能分析");
        }
    }
}
