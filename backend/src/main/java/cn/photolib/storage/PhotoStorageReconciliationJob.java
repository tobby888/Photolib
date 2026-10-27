package cn.photolib.storage;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <li>每天凌晨一次全量（默认 04:10，避开 00:00 的备份和 03:xx 的清理任务）；</li>
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

    public PhotoStorageReconciliationJob(PhotoStorageReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(cron = "${photolib.storage-reconciliation.full-cron:0 10 4 * * *}", zone = "Asia/Shanghai")
    public void scheduleFull() {
        submit(fullPending, this::runFull);
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

    void runFull() {
        LocalDateTime startedAt = reconciliation.databaseNow();
        reconciliation.reconcile();
        watermark = startedAt;
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

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
