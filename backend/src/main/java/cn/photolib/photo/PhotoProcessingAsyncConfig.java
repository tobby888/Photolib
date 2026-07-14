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
}
