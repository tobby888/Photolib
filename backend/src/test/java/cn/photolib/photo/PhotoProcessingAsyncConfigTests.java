package cn.photolib.photo;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoProcessingAsyncConfigTests {

    @Test
    void photoProcessingUsesDedicatedSingleWorker() throws Exception {
        ThreadPoolTaskExecutor executor = new PhotoProcessingAsyncConfig().photoProcessingExecutor();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);

            Async async = PhotoProcessingService.class
                    .getMethod("onRequested", PhotoProcessingService.PhotoProcessRequested.class)
                    .getAnnotation(Async.class);
            assertThat(async).isNotNull();
            assertThat(async.value()).isEqualTo("photoProcessingExecutor");
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
}
