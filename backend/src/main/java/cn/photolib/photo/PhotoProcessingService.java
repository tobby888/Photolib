package cn.photolib.photo;

import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.photo.batch.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class PhotoProcessingService {
    private final PhotoMapper photoMapper;
    private final UserMapper userMapper;
    private final ObjectStorageService storage;
    private final StorageProperties properties;
    private final NativeImageTaskPool nativeTasks;
    private final PhotoProcessingWorkspace workspace;
    private final PhotoUploadItemMapper batchItemMapper;
    private final PhotoUploadBatchMapper batchMapper;
    private final PreviewProfilePolicy previewProfiles;
    private final TransactionTemplate transactions;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(PhotoProcessRequested event) {
        submit(event.photoId()).exceptionally(exception -> {
            log.error("提交的图片处理任务异常结束: photoId={}", event.photoId(), exception);
            return null;
        });
    }

    CompletableFuture<Void> submit(Long photoId) {
        return nativeTasks.submit(compressor -> {
            process(photoId, compressor);
            return null;
        });
    }

    private void process(Long photoId, ImageCompressor compressor) {
        PhotoEntity photo = photoMapper.selectById(photoId);
        if (photo == null || photo.getStatus() != PhotoStatus.PROCESSING) return;
        PhotoUploadItemEntity batchItem = findBatchItem(photoId);
        Path batchSource = null;
        Path taskDirectory = null;
        try {
            taskDirectory = workspace.createTaskDirectory(photoId);
            Path source;
            if (batchItem != null && batchItem.getTempLocalPath() != null) {
                batchSource = workspace.resolveStoredPath(batchItem.getTempLocalPath());
                source = batchSource;
            } else {
                source = workspace.taskFile(taskDirectory, "source" + extension(photo.getContentType()));
                downloadOriginal(photo, source);
            }
            long sourceSize = Files.size(source);
            if (sourceSize <= 0 || sourceSize > properties.imageMaxBytes()) {
                throw new IllegalArgumentException("图片超过 100 MiB");
            }
            validateMagic(source, photo.getContentType());
            if (!"0".repeat(64).equals(photo.getSha256())
                    && !sha256(source).equalsIgnoreCase(photo.getSha256())) {
                throw new IllegalArgumentException("图片 SHA-256 校验失败");
            }
            if (batchSource != null) {
                upload(photo.getOriginalObjectKey(), source, sourceSize, photo.getContentType());
            }

            Path processedPath = workspace.taskFile(taskDirectory,
                    "processed" + extension(photo.getContentType()));
            ImageCompressor.FileResult result = compressor.compress(
                    source, processedPath, photo.getContentType(), properties.imageTargetBytes());
            if (result.size() > properties.imageTargetBytes()) {
                throw new IllegalArgumentException("图片无法在保留最低可用尺寸的同时压缩至 10 MiB");
            }
            upload(photo.getObjectKey(), result.path(), result.size(), result.contentType());

            Path thumbnailPath = workspace.taskFile(taskDirectory,
                    "thumbnail" + extension(photo.getContentType()));
            PreviewProfilePolicy.CommitPermit previewPermit =
                    previewProfiles.permitForNewPreview();
            PreviewProfile previewProfile = previewPermit.profile();
            ImageCompressor.FileResult thumbnail = compressor.thumbnail(
                    result.path(), thumbnailPath, result.contentType(), 480,
                    previewProfile.compressionRatio().doubleValue());
            String thumbnailKey = "thumbnails/generations/uploads/" + photo.getId()
                    + (photo.getContentType().equals("image/png") ? ".png" : ".jpg");
            String thumbnailSha256 = sha256(thumbnail.path());
            upload(thumbnailKey, thumbnail.path(), thumbnail.size(), thumbnail.contentType(),
                    previewProfile.objectMetadata(thumbnail.contentType(), thumbnailSha256));
            UserEntity uploader = userMapper.selectById(photo.getUploadedBy());
            String extension = photo.getContentType().equals("image/png") ? "png" : "jpg";
            LocalDateTime originalDeleteAfter =
                    LocalDateTime.now().plus(properties.originalRetention());
            String storedFileName = fileName(uploader.getDisplayName(),
                    photo.getPhotographerName(), photo.getTakenAt(), extension);
            completeProcessing(photo, result, thumbnail, thumbnailKey, storedFileName,
                    originalDeleteAfter, previewPermit);
        } catch (Exception ex) {
            photo = markProcessingFailed(photo, ex);
        } finally {
            if (batchSource != null && batchItem != null && cleanupBatchSource(batchSource)) {
                batchItem.setTempLocalPath(null);
                batchItemMapper.clearTempLocalPath(batchItem.getId(), LocalDateTime.now());
            }
            cleanupTaskDirectory(taskDirectory);
        }
        updateBatch(photo, batchItem);
    }

    void completeProcessing(PhotoEntity photo,
                            ImageCompressor.FileResult result,
                            ImageCompressor.FileResult thumbnail,
                            String thumbnailKey,
                            String storedFileName,
                            LocalDateTime originalDeleteAfter,
                            PreviewProfilePolicy.CommitPermit permit) {
        PreviewProfile target = permit.profile();
        Integer updated = transactions.execute(status -> {
            if (!previewProfiles.lockAndValidateForCommit(permit)) return 0;
            return photoMapper.completeProcessingWithProfileGuard(
                    photo.getId(), photo.getVersion(), storedFileName,
                    result.size(), result.width(), result.height(),
                    thumbnailKey, thumbnail.size(), originalDeleteAfter,
                    target.compressionRatio(), target.generatorFingerprint(),
                    permit.bootstrappingFlag(), permit.observedDatabaseProfileFlag(),
                    permit.observedCompressionRatioOrTarget(),
                    permit.observedGeneratorFingerprintOrTarget(), LocalDateTime.now());
        });
        if (updated == null || updated != 1) {
            throw new IllegalStateException(
                    "预览图 profile 或图片状态在处理期间已变化，请重新上传后重试");
        }

        photo.setVersion(photo.getVersion() + 1);
        photo.setStoredFileName(storedFileName);
        photo.setSize(result.size());
        photo.setWidth(result.width());
        photo.setHeight(result.height());
        photo.setThumbnailObjectKey(thumbnailKey);
        photo.setThumbnailSize(thumbnail.size());
        photo.setOriginalDeleteAfter(originalDeleteAfter);
        photo.setStatus(PhotoStatus.AVAILABLE);
        photo.setFailureReason(null);
    }

    PhotoEntity markProcessingFailed(PhotoEntity photo, Exception exception) {
        String failureReason = exception.getMessage();
        int failed = photoMapper.failProcessing(photo.getId(), photo.getVersion(),
                failureReason, LocalDateTime.now());
        if (failed == 1) {
            photo.setVersion(photo.getVersion() + 1);
            photo.setStatus(PhotoStatus.UPLOADING);
            photo.setFailureReason(failureReason);
            return photo;
        }

        PhotoEntity current = photoMapper.selectById(photo.getId());
        log.warn("图片处理失败后状态已并发变化，未覆盖当前记录: photoId={}", photo.getId());
        return current == null ? photo : current;
    }

    private PhotoUploadItemEntity findBatchItem(Long photoId) {
        return batchItemMapper.selectOne(
                Wrappers.<PhotoUploadItemEntity>lambdaQuery()
                        .eq(PhotoUploadItemEntity::getPhotoId, photoId));
    }

    private void updateBatch(PhotoEntity photo, PhotoUploadItemEntity item) {
        if (item == null) return;
        item.setStatus(photo.getStatus() == PhotoStatus.AVAILABLE
                ? BatchItemStatus.SUCCEEDED : BatchItemStatus.FAILED);
        item.setFailureReason(photo.getFailureReason());
        batchItemMapper.updateById(item);
        long success = batchItemMapper.selectCount(Wrappers.<PhotoUploadItemEntity>lambdaQuery()
                .eq(PhotoUploadItemEntity::getBatchId, item.getBatchId())
                .eq(PhotoUploadItemEntity::getStatus, BatchItemStatus.SUCCEEDED));
        long failed = batchItemMapper.selectCount(Wrappers.<PhotoUploadItemEntity>lambdaQuery()
                .eq(PhotoUploadItemEntity::getBatchId, item.getBatchId())
                .eq(PhotoUploadItemEntity::getStatus, BatchItemStatus.FAILED));
        long waitingMetadata = batchItemMapper.selectCount(Wrappers.<PhotoUploadItemEntity>lambdaQuery()
                .eq(PhotoUploadItemEntity::getBatchId, item.getBatchId())
                .eq(PhotoUploadItemEntity::getStatus, BatchItemStatus.WAITING_METADATA));
        long processing = batchItemMapper.selectCount(Wrappers.<PhotoUploadItemEntity>lambdaQuery()
                .eq(PhotoUploadItemEntity::getBatchId, item.getBatchId())
                .in(PhotoUploadItemEntity::getStatus, BatchItemStatus.PROCESSING, BatchItemStatus.UPLOADING));
        PhotoUploadBatchEntity batch = batchMapper.selectById(item.getBatchId());
        batch.setSuccessCount((int) success);
        batch.setFailureCount((int) failed);
        if (waitingMetadata == 0 && processing == 0) {
            batch.setStatus(failed > 0 ? BatchStatus.PARTIALLY_SUCCEEDED : BatchStatus.SUCCEEDED);
        } else if (waitingMetadata > 0) {
            batch.setStatus(BatchStatus.WAITING_METADATA);
        } else {
            batch.setStatus(BatchStatus.PROCESSING);
        }
        batchMapper.updateById(batch);
    }

    private String fileName(String uploader, String photographer, LocalDateTime takenAt, String extension) {
        return sanitize(uploader) + "-" + sanitize(photographer) + "-"
                + takenAt.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")) + "." + extension;
    }

    private String sanitize(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
    }

    private void downloadOriginal(PhotoEntity photo, Path destination) throws Exception {
        ObjectStorageService.ObjectInfo info = storage.stat(photo.getOriginalObjectKey());
        if (info.size() <= 0 || info.size() > properties.imageMaxBytes()) {
            throw new IllegalArgumentException("图片超过 100 MiB");
        }
        try (InputStream input = storage.open(photo.getOriginalObjectKey());
             OutputStream output = Files.newOutputStream(destination)) {
            copyLimited(input, output, properties.imageMaxBytes());
        }
    }

    private void upload(String objectKey, Path source, long size, String contentType) throws Exception {
        upload(objectKey, source, size, contentType, Map.of());
    }

    private void upload(String objectKey, Path source, long size, String contentType,
                        Map<String, String> userMetadata) throws Exception {
        try (InputStream input = Files.newInputStream(source)) {
            storage.put(objectKey, input, size, contentType, userMetadata);
        }
    }

    private void copyLimited(InputStream input, OutputStream output, long limit) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > limit) throw new IllegalArgumentException("图片超过 100 MiB");
            output.write(buffer, 0, read);
        }
    }

    private void validateMagic(Path source, String contentType) throws Exception {
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
        if (contentType.equals("image/jpeg") && !jpeg || contentType.equals("image/png") && !png) {
            throw new IllegalArgumentException("文件真实格式与声明类型不一致");
        }
    }

    private String sha256(Path source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(Files.newInputStream(source), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private boolean cleanupBatchSource(Path source) {
        try {
            workspace.deleteBatchFile(source);
            return true;
        } catch (RuntimeException exception) {
            log.warn("清理 ZIP 图片临时文件失败: {}", source, exception);
            return false;
        }
    }

    private void cleanupTaskDirectory(Path directory) {
        if (directory == null) return;
        try {
            workspace.deleteRecursively(directory);
        } catch (RuntimeException exception) {
            log.warn("清理图片处理辅助目录失败: {}", directory, exception);
        }
    }

    private String extension(String contentType) {
        return "image/png".equals(contentType) ? ".png" : ".jpg";
    }

    public record PhotoProcessRequested(Long photoId) {
    }
}
