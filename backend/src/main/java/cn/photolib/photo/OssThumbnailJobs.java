package cn.photolib.photo;

import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.storage.ObjectStorageService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

@Component
@RequiredArgsConstructor
@Slf4j
class OssThumbnailJobs {
    private final OssAvifPreview encoder;
    private final PhotoMapper photos;
    private final PreviewProfilePolicy profiles;
    private final TransactionTemplate transactions;
    private final ObjectStorageService storage;
    private final PhotoProcessingWorkspace workspace;
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("oss-avif-", 0).factory());
    private final Semaphore queued = new Semaphore(256);
    private final Semaphore stagedUploads = new Semaphore(2, true);

    /** Start cloud I/O now; only the virtual thread waits for publication. */
    Pending begin(long photoId, Path validatedFile, String contentType, long size,
                  PreviewProfilePolicy.CommitPermit permit) {
        Pending pending = new Pending(new CompletableFuture<>());
        if (!queued.tryAcquire()) return pending; // Reconciliation can repair the absent preview.
        Path snapshot = snapshot(photoId, validatedFile, size);
        try {
            workers.execute(() -> {
                try {
                    generate(photoId, snapshot, contentType, size, permit, pending);
                } finally {
                    removeSnapshot(snapshot);
                    queued.release();
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            removeSnapshot(snapshot);
            queued.release();
        }
        return pending;
    }

    private Path snapshot(long photoId, Path validatedFile, long size) {
        if (size <= 0 || size > OssAvifPreview.MAX_SOURCE_BYTES) return null;
        Path directory = null;
        try {
            directory = workspace.createTaskDirectory(photoId);
            // Link the verified bytes before the compression thread can clean up its input.
            // The browser's presigned PUT can still overwrite its original OSS upload key.
            return Files.createLink(workspace.taskFile(directory, "validated-source"), validatedFile);
        } catch (IOException | RuntimeException failure) {
            removeDirectory(directory);
            log.debug("无法保留预览输入快照，改用成品对象，photoId={}", photoId);
            return null;
        }
    }

    private void generate(long photoId, Path snapshot, String contentType, long size,
                          PreviewProfilePolicy.CommitPermit permit, Pending pending) {
        String key = OssAvifPreview.key("uploads", photoId);
        OssAvifPreview.Thumbnail saved = null;
        boolean attached = false;
        try {
            if (snapshot != null) {
                try {
                    saved = generateFromSnapshot(photoId, snapshot, contentType, size, key);
                } catch (OssAvifPreview.UnusableImage unsupportedOriginal) {
                    // A large or unusual source may become eligible after finished-image compression.
                }
            }
            Source finished = pending.committed().get();
            if (finished == null) return;
            if (saved == null) {
                if (!finished.eligible()) return;
                saved = encoder.generate(photoId, finished.key(), finished.contentType(), finished.size(), key);
            }
            long previewSize = saved.size();
            attached = Boolean.TRUE.equals(transactions.execute(status ->
                    profiles.lockAndValidateForCommit(permit)
                            && photos.attachCloudThumbnail(photoId, finished.key(), key, previewSize, LocalDateTime.now()) == 1));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            log.warn("OSS 缩略图生成或挂接失败，继续使用成品图，photoId={}，错误类型={}",
                    photoId, failure.getClass().getSimpleName());
        } finally {
            if (saved != null && !attached) encoder.delete(saved.key());
        }
    }

    private OssAvifPreview.Thumbnail generateFromSnapshot(long photoId, Path snapshot, String type,
                                                         long size, String key)
            throws IOException, InterruptedException {
        String extension = switch (type) {
            case AnimatedImages.GIF -> ".gif";
            case AnimatedImages.PNG -> ".png";
            default -> ".jpg";
        };
        String sourceKey = "temporary/avif-previews/" + UUID.randomUUID() + extension;
        stagedUploads.acquire();
        try {
            try (var input = Files.newInputStream(snapshot)) {
                storage.put(sourceKey, input, size, type);
            }
            return encoder.generate(photoId, sourceKey, type, size, key);
        } finally {
            encoder.delete(sourceKey);
            stagedUploads.release();
        }
    }

    private void removeSnapshot(Path snapshot) {
        if (snapshot != null) removeDirectory(snapshot.getParent());
    }

    private void removeDirectory(Path directory) {
        if (directory == null) return;
        try {
            workspace.deleteRecursively(directory);
        } catch (RuntimeException failure) {
            log.warn("清理 AVIF 输入临时目录失败", failure);
        }
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
    }

    record Source(String key, String contentType, long size) {
        boolean eligible() {
            return key != null && !key.isBlank() && size > 0 && size <= OssAvifPreview.MAX_SOURCE_BYTES;
        }
    }

    record Pending(CompletableFuture<Source> committed) {
        void publish(Source finished) { committed.complete(finished); }
        void discard() { committed.complete(null); }
    }
}
