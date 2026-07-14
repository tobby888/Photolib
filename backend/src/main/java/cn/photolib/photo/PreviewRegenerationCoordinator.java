package cn.photolib.photo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class PreviewRegenerationCoordinator {
    private final PreviewRegenerationService regeneration;
    private final AtomicReference<StatusView> status = new AtomicReference<>(StatusView.pending());

    @Async("previewRegenerationExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void regenerateAfterApplicationReady() {
        Instant startedAt = Instant.now();
        status.set(StatusView.generating(0, 0, startedAt));
        log.info("应用已可用，开始在后台核对并生成预览图");
        try {
            PreviewRegenerationService.Result result = regeneration.synchronizeCompressionRatio(
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
            status.set(StatusView.succeeded(current.total(), startedAt, Instant.now()));
            log.info("后台预览图任务完成：是否重建={}，生成={}，清理旧对象={}",
                    result.regenerated(), result.regeneratedCount(), result.deletedCount());
        } catch (Exception exception) {
            status.set(StatusView.failed(status.get(), startedAt, Instant.now()));
            log.error("后台预览图任务失败；应用继续提供其他功能", exception);
        }
    }

    public StatusView status() {
        return status.get();
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

        private static StatusView succeeded(int total, Instant startedAt, Instant completedAt) {
            return new StatusView(State.SUCCEEDED, total, total, 100,
                    "预览图已准备完成", null, startedAt, completedAt);
        }

        private static StatusView failed(StatusView current, Instant startedAt, Instant completedAt) {
            return new StatusView(State.FAILED, current.total(), current.processed(), current.percentage(),
                    "预览图后台生成失败", "请联系管理员查看服务日志，其他功能仍可继续使用。",
                    startedAt, completedAt);
        }
    }
}
