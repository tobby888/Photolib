package cn.photolib.photo;

import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.CompletionException;

@Slf4j
@Service
@RequiredArgsConstructor
public class PreviewRegenerationService {
    private static final int SETTING_ID = 1;
    private static final int MAX_DIMENSION = 480;

    private final JdbcClient jdbc;
    private final ObjectStorageService storage;
    private final StorageProperties properties;
    private final NativeImageTaskPool nativeTasks;
    private final PhotoProcessingWorkspace workspace;
    private final TransactionTemplate transactions;

    /**
     * Builds a complete new preview generation before switching database keys.
     * Existing previews remain usable if staging fails. Once the database switch
     * commits, old preview objects are best-effort cleanup and can be retried on
     * the next startup without affecting the active generation.
     */
    public synchronized Result synchronizeCompressionRatio() {
        return synchronizeCompressionRatio(ProgressListener.NONE);
    }

    public synchronized Result synchronizeCompressionRatio(ProgressListener listener) {
        Objects.requireNonNull(listener, "listener");
        BigDecimal configured = configuredRatio();
        BigDecimal stored = storedRatio();
        List<PreviewPhoto> photos = loadPhotos();
        Map<String, ObjectStorageService.StoredObject> actual = storedObjects();

        boolean ratioChanged = stored == null || stored.compareTo(configured) != 0;
        long missingOrStale = photos.stream()
                .filter(photo -> previewMissingOrStale(photo, actual))
                .count();
        listener.started(photos.size());
        if (!ratioChanged && missingOrStale == 0) {
            log.info("预览图压缩比率未变化（{}），全部 {} 张预览图均可用",
                    configured, photos.size());
            listener.progressed(photos.size(), photos.size());
            return new Result(false, 0, 0);
        }

        String generation = generationId(configured);
        List<GeneratedPreview> generated = new ArrayList<>();
        try {
            for (PreviewPhoto photo : photos) {
                generated.add(generate(photo, generation, configured.doubleValue()));
                listener.progressed(generated.size(), photos.size());
            }
        } catch (RuntimeException exception) {
            cleanupGenerated(generated);
            throw exception;
        }

        try {
            transactions.executeWithoutResult(status -> switchGeneration(configured, stored, generated));
        } catch (RuntimeException exception) {
            cleanupGenerated(generated);
            throw exception;
        }

        Set<String> activeKeys = generated.stream()
                .map(GeneratedPreview::objectKey)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> sourceKeys = photos.stream()
                .map(PreviewPhoto::objectKey)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> oldKeys = new LinkedHashSet<>();
        photos.stream().map(PreviewPhoto::thumbnailObjectKey)
                .filter(StringUtils::hasText).forEach(oldKeys::add);
        oldKeys.removeAll(activeKeys);
        oldKeys.removeAll(sourceKeys);
        int deleted = cleanupKeys(oldKeys);

        log.info("预览图已安全切换到新版本 {}：压缩比率 {}，原缺失或异常 {} 张，生成 {} 张，清理旧对象 {} 个",
                generation, configured, missingOrStale, generated.size(), deleted);
        return new Result(true, deleted, generated.size());
    }

    private BigDecimal storedRatio() {
        return jdbc.sql("""
                SELECT compression_ratio
                FROM preview_setting
                WHERE id=:id
                """)
                .param("id", SETTING_ID)
                .query(BigDecimal.class)
                .optional()
                .orElse(null);
    }

    private List<PreviewPhoto> loadPhotos() {
        return jdbc.sql("""
                SELECT id, object_key, content_type, thumbnail_object_key, thumbnail_size
                FROM photo
                WHERE deleted=0
                  AND status IN ('AVAILABLE', 'ARCHIVED')
                  AND object_key IS NOT NULL
                ORDER BY id
                """)
                .query((rs, rowNum) -> new PreviewPhoto(
                        rs.getLong("id"),
                        rs.getString("object_key"),
                        rs.getString("content_type"),
                        rs.getString("thumbnail_object_key"),
                        rs.getObject("thumbnail_size", Long.class)))
                .list();
    }

    private Map<String, ObjectStorageService.StoredObject> storedObjects() {
        Map<String, ObjectStorageService.StoredObject> objects = new HashMap<>();
        storage.list("thumbnails/").forEach(object -> objects.put(object.objectKey(), object));
        return objects;
    }

    private boolean previewMissingOrStale(PreviewPhoto photo,
                                          Map<String, ObjectStorageService.StoredObject> actual) {
        if (!StringUtils.hasText(photo.thumbnailObjectKey()) || photo.thumbnailSize() == null) {
            return true;
        }
        ObjectStorageService.StoredObject object = actual.get(photo.thumbnailObjectKey());
        return object == null || object.size() != photo.thumbnailSize();
    }

    private GeneratedPreview generate(PreviewPhoto photo, String generation, double ratio) {
        try {
            return nativeTasks.submit(compressor -> generateOnWorker(
                    photo, generation, ratio, compressor)).join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException("重新生成预览图失败，photoId=" + photo.id(), cause);
        }
    }

    private GeneratedPreview generateOnWorker(PreviewPhoto photo, String generation, double ratio,
                                               ImageCompressor compressor) throws Exception {
        Path taskDirectory = workspace.createTaskDirectory(photo.id());
        try {
            Path source = workspace.taskFile(taskDirectory, "source.img");
            ObjectStorageService.ObjectInfo info = storage.stat(photo.objectKey());
            if (info.size() <= 0 || info.size() > properties.imageMaxBytes()) {
                throw new IllegalArgumentException("预览图来源文件超过图片大小上限");
            }
            try (InputStream input = storage.open(photo.objectKey());
                 OutputStream output = Files.newOutputStream(source)) {
                copyLimited(input, output, properties.imageMaxBytes());
            }
            String contentType = normalizedContentType(source, photo.contentType());
            Path output = workspace.taskFile(taskDirectory,
                    "preview" + ("image/png".equals(contentType) ? ".png" : ".jpg"));
            ImageCompressor.FileResult preview = compressor.thumbnail(
                    source, output, contentType, MAX_DIMENSION, ratio);
            String previewKey = previewKey(generation, photo.id(), contentType);
            try (InputStream input = Files.newInputStream(preview.path())) {
                storage.put(previewKey, input, preview.size(), preview.contentType());
            }
            return new GeneratedPreview(photo.id(), previewKey, preview.size());
        } finally {
            cleanupTaskDirectory(taskDirectory);
        }
    }

    private void cleanupTaskDirectory(Path directory) {
        try {
            workspace.deleteRecursively(directory);
        } catch (RuntimeException exception) {
            log.warn("清理预览图处理辅助目录失败: {}", directory, exception);
        }
    }

    private void switchGeneration(BigDecimal configured, BigDecimal stored,
                                  List<GeneratedPreview> generated) {
        if (stored == null) {
            jdbc.sql("""
                    INSERT INTO preview_setting (id, compression_ratio)
                    VALUES (:id, :ratio)
                    """)
                    .param("id", SETTING_ID)
                    .param("ratio", configured)
                    .update();
        } else {
            jdbc.sql("""
                    UPDATE preview_setting
                    SET compression_ratio=:ratio, updated_at=CURRENT_TIMESTAMP
                    WHERE id=:id
                    """)
                    .param("id", SETTING_ID)
                    .param("ratio", configured)
                    .update();
        }
        for (GeneratedPreview preview : generated) {
            int updated = jdbc.sql("""
                    UPDATE photo
                    SET thumbnail_object_key=:key, thumbnail_size=:size,
                        version=version+1, updated_at=CURRENT_TIMESTAMP
                    WHERE id=:id AND deleted=0
                    """)
                    .param("key", preview.objectKey())
                    .param("size", preview.size())
                    .param("id", preview.photoId())
                    .update();
            if (updated != 1) {
                throw new IllegalStateException("更新预览图数据库记录失败，photoId=" + preview.photoId());
            }
        }
    }

    private void cleanupGenerated(List<GeneratedPreview> generated) {
        cleanupKeys(generated.stream().map(GeneratedPreview::objectKey).toList());
    }

    private int cleanupKeys(Iterable<String> keys) {
        int deleted = 0;
        for (String key : keys) {
            try {
                storage.delete(key);
                deleted++;
            } catch (RuntimeException exception) {
                log.warn("清理非活动预览对象失败，将在下次启动重试：{}", key, exception);
            }
        }
        return deleted;
    }

    private BigDecimal configuredRatio() {
        double value = properties.previewCompressionRatio();
        if (!Double.isFinite(value) || value <= 0 || value > 1) {
            throw new IllegalStateException("PREVIEW_COMPRESSION_RATIO 必须大于 0 且不超过 1");
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private String normalizedContentType(Path source, String declared) throws Exception {
        byte[] bytes = new byte[8];
        int length;
        try (InputStream input = Files.newInputStream(source)) {
            length = input.read(bytes);
        }
        boolean jpeg = length >= 3 && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
        boolean png = length >= 8 && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
                && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
        if (jpeg) return "image/jpeg";
        if (png) return "image/png";
        if ("image/jpeg".equals(declared) || "image/png".equals(declared)) return declared;
        throw new IllegalArgumentException("不支持的图片格式");
    }

    private void copyLimited(InputStream input, OutputStream output, long limit) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > limit) throw new IllegalArgumentException("图片超过大小上限");
            output.write(buffer, 0, read);
        }
    }

    private String generationId(BigDecimal ratio) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return timestamp + "-q" + ratio.toPlainString().replace('.', '-') + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    private String previewKey(String generation, long photoId, String contentType) {
        return "thumbnails/generations/" + generation + "/" + photoId
                + ("image/png".equals(contentType) ? ".png" : ".jpg");
    }

    private record PreviewPhoto(Long id, String objectKey, String contentType,
                                String thumbnailObjectKey, Long thumbnailSize) {
    }

    private record GeneratedPreview(Long photoId, String objectKey, long size) {
    }

    public record Result(boolean regenerated, int deletedCount, int regeneratedCount) {
    }

    public interface ProgressListener {
        ProgressListener NONE = new ProgressListener() {
            @Override
            public void started(int total) {
            }

            @Override
            public void progressed(int processed, int total) {
            }
        };

        void started(int total);

        void progressed(int processed, int total);
    }
}
