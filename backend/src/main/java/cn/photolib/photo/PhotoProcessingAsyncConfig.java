package cn.photolib.photo;

import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PhotoProcessingProperties.class)
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
     * Every production call into the Zig image processor is dispatched through
     * this shared pool. Operators choose its fixed concurrency from .env so the
     * server can trade throughput for a bounded native-memory budget.
     */
    @Bean(name = "photoProcessingExecutor")
    ThreadPoolTaskExecutor photoProcessingExecutor(PhotoProcessingProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.threads());
        executor.setMaxPoolSize(properties.threads());
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("photo-processing-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }

    /**
     * Preview regeneration can take a long time for a large library. Run it on
     * its own serial coordinator after the application is ready so it does not
     * delay login or occupy the new-upload queue. It remains single-threaded;
     * operators must budget for one preview decode alongside upload processing.
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

    /** ZIP expansion streams entries to disk on an independent serial worker. */
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
