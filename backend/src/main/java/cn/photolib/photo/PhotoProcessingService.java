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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class PhotoProcessingService {
    private final PhotoMapper photoMapper;
    private final UserMapper userMapper;
    private final ObjectStorageService storage;
    private final StorageProperties properties;
    private final ImageCompressor compressor;
    private final PhotoUploadItemMapper batchItemMapper;
    private final PhotoUploadBatchMapper batchMapper;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(PhotoProcessRequested event) {
        process(event.photoId());
    }

    @Transactional
    public void process(Long photoId) {
        PhotoEntity photo = photoMapper.selectById(photoId);
        if (photo == null || photo.getStatus() != PhotoStatus.PROCESSING) return;
        try {
            ObjectStorageService.ObjectInfo info = storage.stat(photo.getOriginalObjectKey());
            if (info.size() > properties.imageMaxBytes()) {
                throw new IllegalArgumentException("图片超过 100 MiB");
            }
            byte[] source;
            try (InputStream input = storage.open(photo.getOriginalObjectKey())) {
                source = input.readAllBytes();
            }
            validateMagic(source, photo.getContentType());
            if (!"0".repeat(64).equals(photo.getSha256())
                    && !sha256(source).equalsIgnoreCase(photo.getSha256())) {
                throw new IllegalArgumentException("图片 SHA-256 校验失败");
            }
            ImageCompressor.Result result = compressor.compress(
                    source, photo.getContentType(), properties.imageTargetBytes());
            if (result.bytes().length > properties.imageTargetBytes()) {
                throw new IllegalArgumentException("图片无法在保留最低可用尺寸的同时压缩至 10 MiB");
            }
            storage.put(photo.getObjectKey(), new ByteArrayInputStream(result.bytes()),
                    result.bytes().length, result.contentType());
            ImageCompressor.Result thumbnail = compressor.thumbnail(result.bytes(), result.contentType(), 480);
            String thumbnailKey = "thumbnails/" + photo.getId()
                    + (photo.getContentType().equals("image/png") ? ".png" : ".jpg");
            storage.put(thumbnailKey, new ByteArrayInputStream(thumbnail.bytes()),
                    thumbnail.bytes().length, thumbnail.contentType());
            UserEntity uploader = userMapper.selectById(photo.getUploadedBy());
            String extension = photo.getContentType().equals("image/png") ? "png" : "jpg";
            photo.setStoredFileName(fileName(uploader.getDisplayName(), photo.getPhotographerName(),
                    photo.getTakenAt(), extension));
            photo.setSize((long) result.bytes().length);
            photo.setWidth(result.width());
            photo.setHeight(result.height());
            photo.setThumbnailObjectKey(thumbnailKey);
            photo.setOriginalDeleteAfter(LocalDateTime.now().plus(properties.originalRetention()));
            photo.setStatus(PhotoStatus.AVAILABLE);
            photo.setFailureReason(null);
        } catch (Exception ex) {
            photo.setStatus(PhotoStatus.UPLOADING);
            photo.setFailureReason(ex.getMessage());
        }
        photoMapper.updateById(photo);
        updateBatch(photo);
    }

    private void updateBatch(PhotoEntity photo) {
        PhotoUploadItemEntity item = batchItemMapper.selectOne(
                Wrappers.<PhotoUploadItemEntity>lambdaQuery()
                        .eq(PhotoUploadItemEntity::getPhotoId, photo.getId()));
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
        long waiting = batchItemMapper.selectCount(Wrappers.<PhotoUploadItemEntity>lambdaQuery()
                .eq(PhotoUploadItemEntity::getBatchId, item.getBatchId())
                .in(PhotoUploadItemEntity::getStatus, BatchItemStatus.WAITING_METADATA,
                        BatchItemStatus.PROCESSING, BatchItemStatus.UPLOADING));
        PhotoUploadBatchEntity batch = batchMapper.selectById(item.getBatchId());
        batch.setSuccessCount((int) success);
        batch.setFailureCount((int) failed);
        if (waiting == 0) {
            batch.setStatus(failed > 0 ? BatchStatus.PARTIALLY_SUCCEEDED : BatchStatus.SUCCEEDED);
        } else {
            batch.setStatus(BatchStatus.WAITING_METADATA);
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

    private void validateMagic(byte[] bytes, String contentType) {
        boolean jpeg = bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
        boolean png = bytes.length >= 8 && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
                && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
        if (contentType.equals("image/jpeg") && !jpeg || contentType.equals("image/png") && !png) {
            throw new IllegalArgumentException("文件真实格式与声明类型不一致");
        }
    }

    private String sha256(byte[] bytes) throws java.security.NoSuchAlgorithmException {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public record PhotoProcessRequested(Long photoId) {
    }
}
