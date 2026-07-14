package cn.photolib.photo;

import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class PhotoProcessingAsyncConfig {

    /**
     * Declaring a specialized Executor makes Boot back off its generic executor
     * bean. Recreate that default from Boot's configured builder so existing
     * unqualified @Async work (mail and exports) keeps its original behavior.
     */
    @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
    @Primary
    ThreadPoolTaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
        return builder.build();
    }

    /**
     * ImageIO expands compressed camera photos to large in-memory pixel buffers.
     * Keep photo processing serial so several uploads cannot exhaust the JVM heap.
     * Other asynchronous work continues to use Spring's default executor.
     */
    @Bean(name = "photoProcessingExecutor")
    ThreadPoolTaskExecutor photoProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("photo-processing-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }

    /**
     * Preview regeneration can take a long time for a large library. Run it on
     * its own serial executor after the application is ready so it neither
     * delays login nor competes with newly uploaded photo processing. Kept
     * strictly serial (not fanned out) — concurrent full-resolution JPEG
     * decodes previously took a small (1-2 core, <=2GB) production box down by
     * starving Tomcat's request threads of both CPU and heap. See
     * ImageCompressor.thumbnail()'s subsampled decode for how regeneration
     * time is instead reduced without adding concurrency.
     */
    @Bean(name = "previewRegenerationExecutor")
    ThreadPoolTaskExecutor previewRegenerationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("preview-regeneration-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
