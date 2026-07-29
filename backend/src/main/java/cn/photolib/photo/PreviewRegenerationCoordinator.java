package cn.photolib.photo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class PreviewRegenerationCoordinator {
    private final PreviewRegenerationService regeneration;
    private final TaskExecutor previewExecutor;
    private final TaskScheduler retryScheduler;
    private final Duration retryDelay;
    private final AtomicReference<StatusView> status = new AtomicReference<>(StatusView.pending());
    private final AtomicReference<PreviewRepairRequestedEvent> pendingRepair =
            new AtomicReference<>();
    private final AtomicBoolean bootstrapRequested = new AtomicBoolean();
    private final AtomicBoolean bootstrapComplete = new AtomicBoolean();
    private final AtomicBoolean workerScheduled = new AtomicBoolean();
    private final AtomicBoolean retryScheduled = new AtomicBoolean();
    private final AtomicBoolean bootstrapRetryPending = new AtomicBoolean();

    public PreviewRegenerationCoordinator(
            PreviewRegenerationService regeneration,
            @Qualifier("previewRegenerationExecutor") TaskExecutor previewExecutor,
            TaskScheduler retryScheduler,
            @Value("${photolib.preview-bootstrap-retry-delay:30s}") Duration retryDelay) {
        this.regeneration = regeneration;
        this.previewExecutor = previewExecutor;
        this.retryScheduler = retryScheduler;
        this.retryDelay = retryDelay.isNegative() ? Duration.ZERO : retryDelay;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void regenerateAfterApplicationReady() {
        bootstrapRequested.set(true);
        scheduleWorker();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void regenerateAfterStorageReconciliation(PreviewRepairRequestedEvent event) {
        log.info("对象存储巡检请求修复 {} 张预览图", event.photoIds().size());
        pendingRepair.accumulateAndGet(event, this::mergeRepairs);
        scheduleWorker();
    }

    private void scheduleWorker() {
        if (!workerScheduled.compareAndSet(false, true)) return;
        try {
            previewExecutor.execute(this::drainWork);
        } catch (RuntimeException exception) {
            workerScheduled.set(false);
            log.warn("预览图后台执行器暂时无法接收任务，将延迟重试", exception);
            scheduleDispatchRetry();
        }
    }

    private void drainWork() {
        try {
            while (true) {
                if (bootstrapRequested.compareAndSet(true, false)) {
                    boolean succeeded = runRegeneration(
                            "应用启动核对", regeneration::synchronizeCompressionRatio);
                    if (!succeeded) {
                        bootstrapRetryPending.set(true);
                        scheduleDispatchRetry();
                        return;
                    }
                    bootstrapComplete.set(true);
                    continue;
                }

                if (!bootstrapComplete.get()) return;
                PreviewRepairRequestedEvent event = pendingRepair.getAndSet(null);
                if (event == null) return;
                runRegeneration("对象存储巡检修复",
                        listener -> regeneration.repairPreviews(event, listener));
            }
        } finally {
            workerScheduled.set(false);
            if (bootstrapRequested.get()
                    || bootstrapComplete.get() && pendingRepair.get() != null) {
                scheduleWorker();
            }
        }
    }

    private void scheduleDispatchRetry() {
        if (!retryScheduled.compareAndSet(false, true)) return;
        try {
            retryScheduler.schedule(() -> {
                retryScheduled.set(false);
                if (bootstrapRetryPending.getAndSet(false)) {
                    bootstrapRequested.set(true);
                }
                scheduleWorker();
            }, Instant.now().plus(retryDelay));
        } catch (RuntimeException exception) {
            retryScheduled.set(false);
            log.error("无法调度预览图后台重试", exception);
        }
    }

    private PreviewRepairRequestedEvent mergeRepairs(
            PreviewRepairRequestedEvent existing, PreviewRepairRequestedEvent incoming) {
        if (existing == null) return incoming;
        if (!existing.expectedProfile().equals(incoming.expectedProfile())) {
            log.info("数据库预览图 profile 已变化，丢弃尚未执行的旧 profile 修复请求");
            return incoming;
        }
        LinkedHashSet<Long> merged = new LinkedHashSet<>(existing.photoIds());
        merged.addAll(incoming.photoIds());
        return new PreviewRepairRequestedEvent(merged.stream().toList(), incoming.expectedProfile());
    }

    private boolean runRegeneration(String reason, PreviewTask task) {
        Instant startedAt = Instant.now();
        status.set(StatusView.generating(0, 0, startedAt));
        log.info("开始执行预览图后台任务：{}", reason);
        try {
            PreviewRegenerationService.Result result = task.run(
                    new PreviewRegenerationService.ProgressListener() {
                        @Override
                        public void started(int total) {
                            status.set(StatusView.generating(0, total, startedAt));
                        }

                        @Override
                        public void progressed(int processed, int total) {
                            status.set(StatusView.generating(processed, total, startedAt));
                        }
                    });
            StatusView current = status.get();
            status.set(StatusView.succeeded(current.total(), result.fallbackCount(),
                    startedAt, Instant.now()));
            if (result.fallbackCount() > 0) {
                log.warn("后台预览图任务完成，但有 {} 张暂时回退为成品图展示；后续对账会继续重试",
                        result.fallbackCount());
            }
            log.info("后台预览图任务完成：原因={}，是否重建={}，生成={}，回退成品图={}，清理旧对象={}",
                    reason, result.regenerated(), result.regeneratedCount(),
                    result.fallbackCount(), result.deletedCount());
            return true;
        } catch (Exception exception) {
            status.set(StatusView.failed(status.get(), startedAt, Instant.now()));
            log.error("后台预览图任务失败；应用继续提供其他功能：{}", reason, exception);
            return false;
        }
    }

    public StatusView status() {
        return status.get();
    }

    @FunctionalInterface
    private interface PreviewTask {
        PreviewRegenerationService.Result run(PreviewRegenerationService.ProgressListener listener);
    }

    public enum State {
        PENDING, GENERATING, SUCCEEDED, FAILED
    }

    public record StatusView(State status, int total, int processed, int percentage,
                             String message, String errorMessage,
                             Instant startedAt, Instant completedAt) {
        private static StatusView pending() {
            return new StatusView(State.PENDING, 0, 0, 0,
                    "预览图任务正在等待后台启动", null, null, null);
        }

        private static StatusView generating(int processed, int total, Instant startedAt) {
            int percentage = total == 0 ? 0 : Math.min(100, processed * 100 / total);
            return new StatusView(State.GENERATING, total, processed, percentage,
                    total == 0 ? "正在核对预览图" : "预览图正在后台生成",
                    null, startedAt, null);
        }

        /**
         * Photos that fell back to the finished object still count as a
         * successful run: the rest of the library switched, and the frontend
         * must keep receiving SUCCEEDED so galleries refresh. The count is
         * surfaced in the message rather than as a separate state.
         */
        private static StatusView succeeded(int total, int fallbackCount,
                                            Instant startedAt, Instant completedAt) {
            String message = fallbackCount > 0
                    ? "预览图已准备完成（" + fallbackCount + " 张暂时回退为原图，后台会继续重试）"
                    : "预览图已准备完成";
            return new StatusView(State.SUCCEEDED, total, total, 100,
                    message, null, startedAt, completedAt);
        }

        private static StatusView failed(StatusView current, Instant startedAt, Instant completedAt) {
            return new StatusView(State.FAILED, current.total(), current.processed(), current.percentage(),
                    "预览图后台生成失败", "请联系管理员查看服务日志，其他功能仍可继续使用。",
                    startedAt, completedAt);
        }
    }
}
