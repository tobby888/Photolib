package cn.photolib.recruitment.upload;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-dispatches durable PROCESSING rows after crashes or rejected async work. */
@Component
@Slf4j
public class RecruitmentUploadRecoveryCoordinator {
    private static final int LIMIT = 200;

    private final RecruitmentUploadBatchMapper batchMapper;
    private final RecruitmentUploadProcessor processor;
    private final RecruitmentUploadDispatchQueue dispatchQueue;

    public RecruitmentUploadRecoveryCoordinator(
            RecruitmentUploadBatchMapper batchMapper,
            RecruitmentUploadProcessor processor,
            RecruitmentUploadDispatchQueue dispatchQueue) {
        this.batchMapper = batchMapper;
        this.processor = processor;
        this.dispatchQueue = dispatchQueue;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recoverStuckUploads();
    }

    @Scheduled(fixedDelayString = "${photolib.recruitment.upload-recovery-delay-ms:60000}",
            initialDelayString = "${photolib.recruitment.upload-recovery-initial-delay-ms:60000}")
    public void scheduledRecovery() {
        recoverStuckUploads();
    }

    public int recoverStuckUploads() {
        var ids = batchMapper.findProcessingIds(LIMIT);
        int dispatched = 0;
        for (String id : ids) {
            RecruitmentUploadDispatchQueue.DispatchResult result = dispatchQueue.dispatch(id, () -> {
                try {
                    processor.process(id);
                } catch (RuntimeException exception) {
                    log.error("恢复招募上传批次失败，将由后续扫描重试: batchId={}", id, exception);
                }
            });
            if (result == RecruitmentUploadDispatchQueue.DispatchResult.ACCEPTED) {
                dispatched++;
            } else if (result == RecruitmentUploadDispatchQueue.DispatchResult.REJECTED) {
                log.warn("招募上传恢复队列已满，本轮停止派发；剩余批次将在下轮重试: batchId={}", id);
                break;
            }
        }
        return dispatched;
    }
}
