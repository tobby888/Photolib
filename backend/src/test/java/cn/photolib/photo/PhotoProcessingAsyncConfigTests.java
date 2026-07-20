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

    private String awaitRelease(CountDownLatch started, CountDownLatch release) throws Exception {
        started.countDown();
        if (!release.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并行图片处理测试等待超时");
        }
        return Thread.currentThread().getName();
    }
}
