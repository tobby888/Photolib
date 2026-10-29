package cn.photolib.storage;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对象存储对账的调度。
 *
 * <p>以前对账直接跑在 Spring 唯一的定时任务线程 {@code scheduling-1} 上，每小时一次全量、每次 6～7 分钟，
 * 这段时间导出看门狗、性能统计落库、上传恢复等定时任务全部停摆（10·26 活动当天最长 416 秒）。现在：
 * <ul>
 *   <li>定时方法只负责投递，真正的巡检跑在本类自己的单线程执行器上，{@code scheduling-1} 立即返回；</li>
 *   <li>每天凌晨一次全量（默认 04:10，避开 00:00 的备份和 03:xx 的清理任务）。<b>自上次全量以来没有新增图片
 *       就跳过</b>，但三种情况照跑：距上次全量已满 {@code full-max-skip-days} 天（兜底，桶里丢图与有没有
 *       新图无关）、还有未解决的存储告警（只有全量能把它们标为已解决）、本进程还没做过全量；</li>
 *   <li>白天每小时一次增量，只查上次巡检以来 {@code updated_at} 变过的照片，水位线往前多看
 *       {@link #OVERLAP}，容忍时钟误差和长事务晚提交。</li>
 * </ul>
 * 水位线只在内存里：进程刚启动或上次巡检失败时，下一次增量自动退化成全量，和以前的启动后行为一致。
 * 全量与增量排在同一个执行器上，不会并行；同一类巡检已在排队时不再重复投递。
 */
@Component
public class PhotoStorageReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(PhotoStorageReconciliationJob.class);
    static final Duration OVERLAP = Duration.ofMinutes(10);
    /** 兜底天数的容差：定时任务每天触发的时刻有抖动，差几分钟不该让它多拖一天。 */
    static final Duration MAX_SKIP_TOLERANCE = Duration.ofHours(1);

    private final PhotoStorageReconciliationService reconciliation;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "storage-reconciliation");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean fullPending = new AtomicBoolean();
    private final AtomicBoolean incrementalPending = new AtomicBoolean();
    /** 上一次成功巡检开始时的数据库时间；null 表示下一次增量要做全量。 */
    private volatile LocalDateTime watermark;
    /** 上一次成功<b>全量</b>开始时的数据库时间；null 表示本进程还没做过，定时全量不能跳过。 */
    private volatile LocalDateTime lastFull;
    /** 没有新图时最多连续跳过几天；0 表示从不跳过（关掉这项优化）。 */
    private final int maxSkipDays;

    /** 测试用：兜底 7 天。 */
    PhotoStorageReconciliationJob(PhotoStorageReconciliationService reconciliation) {
        this(reconciliation, 7);
    }

    @Autowired
    public PhotoStorageReconciliationJob(PhotoStorageReconciliationService reconciliation,
                                         @Value("${photolib.storage-reconciliation.full-max-skip-days:7}")
                                         int maxSkipDays) {
        if (maxSkipDays < 0 || maxSkipDays > 30) {
            throw new IllegalArgumentException("STORAGE_RECONCILIATION_FULL_MAX_SKIP_DAYS 必须在 0 到 30 之间");
        }
        this.reconciliation = reconciliation;
        this.maxSkipDays = maxSkipDays;
    }

    @Scheduled(cron = "${photolib.storage-reconciliation.full-cron:0 10 4 * * *}", zone = "Asia/Shanghai")
    public void scheduleFull() {
        submit(fullPending, this::runScheduledFull);
    }

    @Scheduled(fixedDelayString = "${photolib.storage-reconciliation.incremental-delay-ms:3600000}",
            initialDelayString = "${photolib.storage-reconciliation.initial-delay-ms:600000}")
    public void scheduleIncremental() {
        submit(incrementalPending, this::runIncremental);
    }

    private void submit(AtomicBoolean pending, Runnable task) {
        if (!pending.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                pending.set(false);
                try {
                    task.run();
                } catch (Exception exception) {
                    log.error("定时对账任务执行失败", exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            pending.set(false);
            log.warn("对账执行器已关闭，跳过本次对账");
        }
    }

    /** 定时全量：判断能不能跳过，不能就照常跑。返回是否真的跑了。 */
    boolean runScheduledFull() {
        String reason = skipReason();
        if (reason != null) {
            log.info("跳过本次全量对账：{}", reason);
            return false;
        }
        runFull();
        return true;
    }

    /**
     * 能跳过时返回给日志看的理由，不能跳过返回 null。顺序是先问不用查库的，再问便宜的：
     * 没做过全量 → 兜底到期 → 有未解决告警 → 有新图。
     */
    private String skipReason() {
        LocalDateTime last = lastFull;
        if (maxSkipDays == 0 || last == null) return null;
        LocalDateTime now = reconciliation.databaseNow();
        if (!now.isBefore(last.plusDays(maxSkipDays).minus(MAX_SKIP_TOLERANCE))) return null;
        if (reconciliation.hasOpenStorageAlerts()) return null;
        // 往前多看 OVERLAP，与增量同一个理由：长事务晚提交的照片 created_at 可能早于上次全量的开始时间。
        if (reconciliation.hasNewPhotosSince(last.minus(OVERLAP))) return null;
        return "自 " + last + " 上次全量以来没有新增图片、也没有未解决的存储告警；最晚 "
                + last.plusDays(maxSkipDays).toLocalDate() + " 前后兜底跑一次";
    }

    void runFull() {
        LocalDateTime startedAt = reconciliation.databaseNow();
        try {
            reconciliation.reconcile();
        } catch (RuntimeException exception) {
            // 全量失败等于这段时间状态未知：下一次定时全量不能拿「上次跑过、没新图」当理由跳过。
            lastFull = null;
            throw exception;
        }
        watermark = startedAt;
        lastFull = startedAt;
    }

    void runIncremental() {
        LocalDateTime since = watermark;
        if (since == null) {
            runFull();
            return;
        }
        LocalDateTime startedAt = reconciliation.databaseNow();
        reconciliation.reconcileChangedSince(since.minus(OVERLAP));
        watermark = startedAt;
    }

    LocalDateTime watermark() {
        return watermark;
    }

    LocalDateTime lastFull() {
        return lastFull;
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
