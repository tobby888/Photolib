package cn.photolib.photo;

import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.OssAccessMonitor;
import com.aliyun.oss.OSSException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Component
@RequiredArgsConstructor
@Slf4j
class OssAvifPreview {
    static final long MAX_SOURCE_BYTES = 20L * 1024 * 1024;
    static final long MAX_PREVIEW_BYTES = 20L * 1024 * 1024;
    private static final Set<String> UNUSABLE_IMAGES = Set.of(
            "BadImage", "InvalidImage", "ImageTooLarge", "ImageFormatNotSupported");

    private final ObjectStorageService storage;
    private final PhotoProcessingWorkspace workspace;
    private final OssAccessMonitor access;
    private final Semaphore concurrentConversions = new Semaphore(2, true);

    static String key(String directory, long id) {
        return PreviewProfile.OSS_KEY_PREFIX + directory + "/" + id + "-" + UUID.randomUUID() + ".avif";
    }

    Thumbnail generate(long photoId, String sourceKey, String contentType, long size,
                       String destinationKey) throws IOException, InterruptedException {
        if (size <= 0 || size > MAX_SOURCE_BYTES) {
            throw new UnusableImage("图片大小不在 OSS 图片处理允许的范围内");
        }
        concurrentConversions.acquire();
        try {
            if (AnimatedImages.GIF.equals(contentType)) {
                saveGifPoster(photoId, sourceKey, destinationKey);
            } else {
                save(sourceKey, destinationKey);
            }
            ObjectStorageService.ObjectInfo saved = storage.stat(destinationKey);
            if (!PreviewProfile.ossAvif().matchesStored(destinationKey, saved)
                    || saved.size() > MAX_PREVIEW_BYTES) {
                throw new IOException("OSS 转存对象的类型或体积无效");
            }
            access.conversionSucceeded();
            return new Thumbnail(destinationKey, saved.size());
        } catch (IOException | RuntimeException failure) {
            delete(destinationKey);
            throw failure;
        } finally {
            concurrentConversions.release();
        }
    }

    private void save(String sourceKey, String destinationKey) throws UnusableImage {
        try {
            storage.saveAvifPreview(sourceKey, destinationKey);
        } catch (RuntimeException failure) {
            access.conversionFailed(failure);
            if (failure instanceof OSSException oss && oss.getErrorCode() != null
                    && UNUSABLE_IMAGES.contains(oss.getErrorCode())) {
                throw new UnusableImage("OSS 无法处理这张图片", failure);
            }
            throw failure;
        }
    }

    private void saveGifPoster(long photoId, String sourceKey, String destinationKey) throws IOException {
        Path directory = workspace.createTaskDirectory(photoId);
        String posterKey = "temporary/avif-previews/" + UUID.randomUUID() + ".png";
        boolean attemptedPut = false;
        try {
            Path gif = workspace.taskFile(directory, "source.gif");
            Path poster = workspace.taskFile(directory, "poster.png");
            try (InputStream input = storage.open(sourceKey);
                 var output = Files.newOutputStream(gif)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                for (int read; (read = input.read(buffer)) != -1; ) {
                    total += read;
                    if (total > MAX_SOURCE_BYTES) throw new UnusableImage("GIF 超出 OSS 图片处理大小限制");
                    output.write(buffer, 0, read);
                }
            }
            try {
                GifFirstFrame.writePng(gif, AnimatedImages.inspectGif(gif), poster);
            } catch (IOException | RuntimeException failure) {
                throw new UnusableImage("无法读取 GIF 首帧", failure);
            }
            long size = Files.size(poster);
            if (size > MAX_SOURCE_BYTES) throw new UnusableImage("GIF 首帧超出 OSS 图片处理大小限制");
            try (InputStream input = Files.newInputStream(poster)) {
                attemptedPut = true;
                storage.put(posterKey, input, size, AnimatedImages.PNG);
            }
            save(posterKey, destinationKey);
        } finally {
            if (attemptedPut) delete(posterKey);
            try {
                workspace.deleteRecursively(directory);
            } catch (RuntimeException failure) {
                log.warn("清理 GIF 临时目录失败，photoId={}", photoId, failure);
            }
        }
    }

    void delete(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException failure) {
            log.warn("清理 AVIF 转存对象失败，key={}，错误类型={}", key, failure.getClass().getSimpleName());
        }
    }

    record Thumbnail(String key, long size) {}

    static final class UnusableImage extends IOException {
        UnusableImage(String message) { super(message); }
        UnusableImage(String message, Throwable cause) { super(message, cause); }
    }
}
