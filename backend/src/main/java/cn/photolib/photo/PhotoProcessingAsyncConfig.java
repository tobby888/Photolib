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
     * Native processing keeps decoded pixels outside the JVM heap, but camera
     * photos can still require substantial native memory. Keep photo processing
     * serial so concurrent uploads stay within the server's total memory budget.
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
     * decodes can take a small (1-2 core, <=2GB) production box down by
     * starving Tomcat's request threads of both CPU and heap. See
     * The native JPEG path uses libjpeg-turbo's scaled decode for regeneration
     * thumbnails, reducing memory without adding concurrency.
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

    /** ZIP expansion has an independent, serial memory budget. */
    @Bean(name = "batchProcessingExecutor")
    ThreadPoolTaskExecutor batchProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("batch-processing-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
