package cn.photolib.dashboard;

import cn.photolib.dashboard.mapper.DashboardStatsMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据面板的一次快照：业务计数（账号、图片、选题…）、请求流量、JVM 与服务器运行状态。
 *
 * <p>业务计数要查库，缓存 30 秒；流量和运行状态是进程内的数，每次都取最新的。查库失败时
 * 不让整次请求失败——数据库出问题时，流量和运行状态恰恰是管理员最想看的——业务计数那一块
 * 返回空并附上原因，上一次成功的结果若还在就接着用。</p>
 */
@Service
@RequiredArgsConstructor
public class DashboardMetricsService {
    private static final Logger log = LoggerFactory.getLogger(DashboardMetricsService.class);
    static final long STATS_TTL_MS = 30_000;
    static final int DAILY_SERIES_DAYS = 30;
    private static final int TOP_ENDPOINTS = 20;

    private final DashboardStatsMapper stats;
    private final RequestMetrics requests;
    private final Clock clock;

    private BusinessStats cached;
    private long cachedAt;

    public DashboardMetrics snapshot() {
        BusinessStats business = null;
        String businessError = null;
        try {
            business = businessStats();
        } catch (RuntimeException e) {
            log.warn("数据面板的业务计数查询失败", e);
            businessError = "数据库暂时无法访问，业务计数稍后自动恢复";
            synchronized (this) {
                business = cached;
            }
        }
        return new DashboardMetrics(requests.now(), business, businessError, requests.activeUsers(),
                requests.snapshot(TOP_ENDPOINTS), runtime());
    }

    private synchronized BusinessStats businessStats() {
        long now = requests.now();
        if (cached != null && now - cachedAt < STATS_TTL_MS) return cached;
        LocalDateTime current = LocalDateTime.now(clock);
        LocalDate today = current.toLocalDate();
        LocalDateTime seriesStart = today.minusDays(DAILY_SERIES_DAYS - 1L).atStartOfDay();
        cached = new BusinessStats(
                now,
                new UserStats(stats.countUsers(), stats.countEnabledUsers(),
                        stats.countUsersCreatedSince(today.minusDays(6).atStartOfDay()),
                        stats.countUsersCreatedSince(today.minusDays(29).atStartOfDay()),
                        stats.countPendingRegistrations(), stats.countLiveSessions(current)),
                new ContentStats(stats.countPhotos(), stats.sumPhotoBytes(),
                        stats.countPhotosCreatedSince(today.atStartOfDay()),
                        stats.countProjects(), stats.countActiveProjects(), stats.countOpenRequests(),
                        stats.countUnresolvedAlerts()),
                daily(stats.dailyUsers(seriesStart), today),
                daily(stats.dailyPhotos(seriesStart), today));
        cachedAt = now;
        return cached;
    }

    /** 补齐没有数据的日子，最近 30 天每天一格，按日期升序。 */
    static List<DailyCount> daily(List<DashboardStatsMapper.DayCount> rows, LocalDate today) {
        Map<LocalDate, Long> byDay = new HashMap<>();
        for (DashboardStatsMapper.DayCount row : rows) {
            if (row.getStatDay() != null) byDay.merge(row.getStatDay(), row.getTotal(), Long::sum);
        }
        List<DailyCount> result = new ArrayList<>(DAILY_SERIES_DAYS);
        for (int i = DAILY_SERIES_DAYS - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            result.add(new DailyCount(day, byDay.getOrDefault(day, 0L)));
        }
        return result;
    }

    static RuntimeStats runtime() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long heapMax = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        Double processCpu = null;
        Double systemCpu = null;
        Long memoryTotal = null;
        Long memoryFree = null;
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            processCpu = ratio(sun.getProcessCpuLoad());
            systemCpu = ratio(sun.getCpuLoad());
            memoryTotal = sun.getTotalMemorySize();
            memoryFree = sun.getFreeMemorySize();
        }
        double loadAverage = os.getSystemLoadAverage();
        // 应用的工作目录：本地存储、处理中的临时文件和备份的落脚处都在这块盘上。
        File workingDirectory = new File(".").getAbsoluteFile();
        return new RuntimeStats(runtime.getStartTime(), runtime.getUptime(),
                heap.getUsed(), heapMax, processCpu, systemCpu,
                loadAverage >= 0 ? loadAverage : null, os.getAvailableProcessors(),
                ManagementFactory.getThreadMXBean().getThreadCount(),
                memoryTotal, memoryFree,
                workingDirectory.getTotalSpace(), workingDirectory.getUsableSpace(),
                Runtime.version().toString());
    }

    /** MXBean 取不到时返回负数；面板上显示「—」比显示 -100% 诚实。 */
    private static Double ratio(double value) {
        return value >= 0 && Double.isFinite(value) ? value : null;
    }

    public record DashboardMetrics(long generatedAt, BusinessStats business, String businessError,
                                   RequestMetrics.ActiveUsers activeUsers, RequestMetrics.Snapshot traffic,
                                   RuntimeStats runtime) {
    }

    public record BusinessStats(long updatedAt, UserStats users, ContentStats content,
                                List<DailyCount> dailyUsers, List<DailyCount> dailyPhotos) {
    }

    public record UserStats(long total, long enabled, long newLast7Days, long newLast30Days,
                            long pendingRegistrations, long liveSessions) {
    }

    public record ContentStats(long photos, long photoBytes, long photosToday, long projects,
                               long activeProjects, long openRequests, long unresolvedAlerts) {
    }

    public record DailyCount(LocalDate day, long count) {
    }

    public record RuntimeStats(long startedAt, long uptimeMs, long heapUsed, long heapMax,
                               Double processCpu, Double systemCpu, Double loadAverage, int processors,
                               int threads, Long memoryTotal, Long memoryFree,
                               long diskTotal, long diskUsable, String javaVersion) {
    }
}
