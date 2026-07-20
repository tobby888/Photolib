package cn.photolib.photo;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class NativeImageTaskPool {
    private final ThreadPoolTaskExecutor executor;
    private final ImageCompressor compressor;

    public NativeImageTaskPool(
            @Qualifier("photoProcessingExecutor") ThreadPoolTaskExecutor executor,
            ImageCompressor compressor) {
        this.executor = executor;
        this.compressor = compressor;
    }

    public <T> CompletableFuture<T> submit(Task<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    result.complete(task.run(compressor));
                } catch (Throwable throwable) {
                    result.completeExceptionally(throwable);
                }
            });
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    @FunctionalInterface
    public interface Task<T> {
        T run(ImageCompressor compressor) throws Exception;
    }
}
