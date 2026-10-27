package cn.photolib.photo;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoProcessingAsyncConfigTests {

    @Test
    void photoProcessingUsesConfiguredParallelWorkers() throws Exception {
        PhotoProcessingProperties properties = new PhotoProcessingProperties(2, "build/test-processing");
        ThreadPoolTaskExecutor executor = new PhotoProcessingAsyncConfig()
                .photoProcessingExecutor(properties);
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(2);
            NativeImageTaskPool pool = new NativeImageTaskPool(executor, new ImageCompressor());
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            var first = pool.submit(compressor -> awaitRelease(started, release));
            var second = pool.submit(compressor -> awaitRelease(started, release));

            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).startsWith("photo-processing-");
            assertThat(second.get(5, TimeUnit.SECONDS)).startsWith("photo-processing-");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void zipProcessingUsesDedicatedSingleWorker() throws Exception {
        ThreadPoolTaskExecutor executor = new PhotoProcessingAsyncConfig().batchProcessingExecutor();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            Async async = cn.photolib.photo.batch.BatchProcessingService.class
                    .getMethod("onZipRequested",
                            cn.photolib.photo.batch.BatchProcessingService.ZipProcessRequested.class)
                    .getAnnotation(Async.class);
            assertThat(async.value()).isEqualTo("batchProcessingExecutor");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void processingThreadCountRejectsUnsafeValues() {
        assertThatThrownBy(() -> new PhotoProcessingProperties(0, "build/test-processing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 到 32");
        assertThatThrownBy(() -> new PhotoProcessingProperties(33, "build/test-processing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 到 32");
    }

    @Test
    void interactiveUploadsJumpAheadOfQueuedBatchItemsButKeepTheirOwnOrder() throws Exception {
        ThreadPoolTaskExecutor executor = new PhotoProcessingAsyncConfig()
                .photoProcessingExecutor(new PhotoProcessingProperties(1, "build/test-processing"));
        executor.initialize();
        try {
            NativeImageTaskPool pool = new NativeImageTaskPool(executor, new ImageCompressor());
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            java.util.List<String> order = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            // 先占住唯一的处理线程，后面的任务都进队列。
            var blocker = pool.submit(compressor -> awaitRelease(started, release));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            var futures = java.util.List.of(
                    pool.submit(compressor -> order.add("batch-1"), ProcessingPriority.BATCH),
                    pool.submit(compressor -> order.add("batch-2"), ProcessingPriority.BATCH),
                    pool.submit(compressor -> order.add("single-1"), ProcessingPriority.INTERACTIVE),
                    pool.submit(compressor -> order.add("batch-3"), ProcessingPriority.BATCH),
                    pool.submit(compressor -> order.add("single-2")));
            release.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            for (var future : futures) future.get(5, TimeUnit.SECONDS);

            assertThat(order).containsExactly("single-1", "single-2", "batch-1", "batch-2", "batch-3");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void priorityQueueStillRejectsTasksBeyondItsCapacity() {
        PrioritizedTaskQueue queue = new PrioritizedTaskQueue(2);
        assertThat(queue.offer(new PrioritizedTaskQueue.Task(ProcessingPriority.BATCH, () -> { }))).isTrue();
        assertThat(queue.offer(new PrioritizedTaskQueue.Task(ProcessingPriority.INTERACTIVE, () -> { }))).isTrue();
        assertThat(queue.remainingCapacity()).isZero();
        assertThat(queue.offer(new PrioritizedTaskQueue.Task(ProcessingPriority.INTERACTIVE, () -> { }))).isFalse();
    }

    private String awaitRelease(CountDownLatch started, CountDownLatch release) throws Exception {
        started.countDown();
        if (!release.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并行图片处理测试等待超时");
        }
        return Thread.currentThread().getName();
    }
}
