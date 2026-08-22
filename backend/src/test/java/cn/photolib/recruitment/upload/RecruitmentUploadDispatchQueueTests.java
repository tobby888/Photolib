package cn.photolib.recruitment.upload;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RecruitmentUploadDispatchQueueTests {
    @Test
    void sameBatchIsQueuedOnceUntilItsWorkFinishes() {
        List<Runnable> submitted = new ArrayList<>();
        TaskExecutor executor = submitted::add;
        RecruitmentUploadDispatchQueue queue = new RecruitmentUploadDispatchQueue(executor);
        AtomicInteger runs = new AtomicInteger();

        assertThat(queue.dispatch("batch-1", runs::incrementAndGet))
                .isEqualTo(RecruitmentUploadDispatchQueue.DispatchResult.ACCEPTED);
        assertThat(queue.dispatch("batch-1", runs::incrementAndGet))
                .isEqualTo(RecruitmentUploadDispatchQueue.DispatchResult.DUPLICATE);
        assertThat(submitted).hasSize(1);

        submitted.removeFirst().run();
        assertThat(runs).hasValue(1);
        assertThat(queue.dispatch("batch-1", runs::incrementAndGet))
                .isEqualTo(RecruitmentUploadDispatchQueue.DispatchResult.ACCEPTED);
        assertThat(submitted).hasSize(1);
    }

    @Test
    void rejectedBatchIsReleasedSoRecoveryCanDispatchItLater() {
        AtomicBoolean reject = new AtomicBoolean(true);
        List<Runnable> submitted = new ArrayList<>();
        TaskExecutor executor = task -> {
            if (reject.get()) throw new TaskRejectedException("full");
            submitted.add(task);
        };
        RecruitmentUploadDispatchQueue queue = new RecruitmentUploadDispatchQueue(executor);

        assertThat(queue.dispatch("batch-1", () -> { }))
                .isEqualTo(RecruitmentUploadDispatchQueue.DispatchResult.REJECTED);
        reject.set(false);
        assertThat(queue.dispatch("batch-1", () -> { }))
                .isEqualTo(RecruitmentUploadDispatchQueue.DispatchResult.ACCEPTED);
        assertThat(submitted).hasSize(1);
    }
}
