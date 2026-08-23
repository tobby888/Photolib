package cn.photolib.recruitment.upload;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Serial bounded dispatch with process-local de-duplication by durable batch id. */
@Component
public class RecruitmentUploadDispatchQueue {
    private final TaskExecutor executor;
    private final Set<String> queuedOrRunning = ConcurrentHashMap.newKeySet();

    public RecruitmentUploadDispatchQueue(
            @Qualifier("recruitmentUploadExecutor") TaskExecutor executor) {
        this.executor = executor;
    }

    public DispatchResult dispatch(String batchId, Runnable work) {
        if (!queuedOrRunning.add(batchId)) return DispatchResult.DUPLICATE;
        try {
            executor.execute(() -> {
                try {
                    work.run();
                } finally {
                    queuedOrRunning.remove(batchId);
                }
            });
            return DispatchResult.ACCEPTED;
        } catch (TaskRejectedException rejected) {
            queuedOrRunning.remove(batchId);
            return DispatchResult.REJECTED;
        }
    }

    public enum DispatchResult {
        ACCEPTED,
        DUPLICATE,
        REJECTED
    }
}
