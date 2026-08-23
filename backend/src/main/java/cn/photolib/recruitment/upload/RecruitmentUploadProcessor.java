package cn.photolib.recruitment.upload;

import cn.photolib.common.upload.ImageSignature;
import cn.photolib.common.upload.ImageUploadPolicy;
import cn.photolib.common.upload.SafeImageZipExtractor;
import cn.photolib.photo.ImageCompressor;
import cn.photolib.photo.PhotoProcessingWorkspace;
import cn.photolib.storage.ObjectStorageService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.DigestOutputStream;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class RecruitmentUploadProcessor {
    private final RecruitmentUploadBatchMapper batchMapper;
    private final RecruitmentUploadItemMapper itemMapper;
    private final ObjectStorageService storage;
    private final PhotoProcessingWorkspace workspace;
    private final SafeImageZipExtractor zipExtractor;
    private final ImageCompressor imageValidator;
    private final TransactionTemplate transactions;
    private final RecruitmentUploadDispatchQueue dispatchQueue;

    public RecruitmentUploadProcessor(
            RecruitmentUploadBatchMapper batchMapper,
            RecruitmentUploadItemMapper itemMapper,
            ObjectStorageService storage,
            PhotoProcessingWorkspace workspace,
            SafeImageZipExtractor zipExtractor,
            ImageCompressor imageValidator,
            TransactionTemplate transactions,
            RecruitmentUploadDispatchQueue dispatchQueue) {
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.storage = storage;
        this.workspace = workspace;
        this.zipExtractor = zipExtractor;
        this.imageValidator = imageValidator;
        this.transactions = transactions;
        this.dispatchQueue = dispatchQueue;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(ProcessRequested event) {
        RecruitmentUploadDispatchQueue.DispatchResult result = dispatchQueue.dispatch(
                event.batchId(), () -> process(event.batchId()));
        if (result == RecruitmentUploadDispatchQueue.DispatchResult.REJECTED) {
            // PROCESSING is already durable. The recovery scan will submit it
            // once the bounded serial queue has capacity.
            log.warn("招募上传队列已满，留待恢复扫描重试: batchId={}", event.batchId());
        }
    }

    /** Idempotent entry point used by both the event listener and recovery scan. */
    public void process(String batchId) {
        requireNoActiveTransaction();
        RecruitmentUploadBatchEntity batch = batchMapper.selectById(batchId);
        if (batch == null || batch.getStatus() != RecruitmentUploadBatchStatus.PROCESSING) return;
        try {
            if (batch.getMode() == RecruitmentUploadMode.ZIP) processZip(batch);
            else processFiles(batch);
        } catch (RuntimeException exception) {
            log.error("招募上传批次处理发生内部异常，将保留状态重试: batchId={}", batchId, exception);
        }
    }

    private void processFiles(RecruitmentUploadBatchEntity batch) {
        for (RecruitmentUploadItemEntity item : processingItems(batch.getId())) {
            Path localFile = null;
            try {
                if (item.getTempObjectKey() == null
                        || storage.find(item.getTempObjectKey()).isEmpty()) {
                    throw rejected(RecruitmentUploadMessages.IMAGE_MISSING, "临时对象不存在");
                }
                localFile = workspace.createBatchFile(batch.getId(),
                        ImageUploadPolicy.extension(item.getContentType()));
                CopiedFile copied;
                try (InputStream source = storage.open(item.getTempObjectKey())) {
                    copied = copyLimited(source, localFile);
                }
                if (copied.size() != item.getSize()) {
                    throw rejected(RecruitmentUploadMessages.IMAGE_SIZE_INVALID,
                            "实际大小=" + copied.size() + ", 声明大小=" + item.getSize());
                }
                if (!copied.sha256().equalsIgnoreCase(item.getSha256())) {
                    throw rejected(RecruitmentUploadMessages.IMAGE_CONTENT_INVALID, "SHA-256 不匹配");
                }
                validateStructure(localFile, item.getContentType());
                finalizeOriginal(batch, item, localFile, copied.size(), copied.sha256());
                deleteTemporaryNow(item.getTempObjectKey(), "item", String.valueOf(item.getId()));
            } catch (RejectedUploadException rejected) {
                log.warn("招募图片校验拒绝: batchId={}, itemId={}, internalReason={}",
                        batch.getId(), item.getId(), rejected.getMessage(), rejected);
                if (failItem(item, rejected.safeReason())) {
                    deleteTemporaryNow(item.getTempObjectKey(), "item", String.valueOf(item.getId()));
                    cleanupReservedFinal(item);
                }
            } catch (Exception exception) {
                log.error("招募图片处理内部失败，将保留 PROCESSING 重试: batchId={}, itemId={}",
                        batch.getId(), item.getId(), exception);
            } finally {
                cleanupLocal(localFile);
            }
        }
        finishBatchIfReady(batch.getId(), null);
    }

    private void processZip(RecruitmentUploadBatchEntity batch) {
        List<SafeImageZipExtractor.ExtractedImage> extracted = List.of();
        try {
            if (batch.getArchiveObjectKey() == null
                    || storage.find(batch.getArchiveObjectKey()).isEmpty()) {
                rejectZip(batch, "ZIP 临时对象不存在");
                return;
            }
            try (InputStream archive = storage.open(batch.getArchiveObjectKey())) {
                extracted = zipExtractor.extract(archive,
                        extension -> workspace.createBatchFile(batch.getId(), extension), 255);
            }
            List<RecruitmentUploadItemEntity> items = allItems(batch.getId());
            if (items.isEmpty()) items = persistExtracted(batch.getId(), extracted);
            else if (!sameExtraction(items, extracted)) {
                rejectZip(batch, "恢复解压内容与已持久化条目不一致");
                return;
            }

            for (int index = 0; index < items.size(); index++) {
                RecruitmentUploadItemEntity item = items.get(index);
                if (item.getStatus() != RecruitmentUploadItemStatus.PROCESSING) continue;
                Path localFile = extracted.get(index).localFile();
                try {
                    String actualSha = ImageSignature.sha256(localFile);
                    if (!actualSha.equalsIgnoreCase(item.getSha256())) {
                        throw rejected(RecruitmentUploadMessages.IMAGE_CONTENT_INVALID,
                                "ZIP 条目 SHA-256 不匹配");
                    }
                    validateStructure(localFile, item.getContentType());
                    finalizeOriginal(batch, item, localFile, Files.size(localFile), actualSha);
                } catch (RejectedUploadException rejected) {
                    log.warn("招募 ZIP 图片校验拒绝: batchId={}, itemId={}, internalReason={}",
                            batch.getId(), item.getId(), rejected.getMessage(), rejected);
                    if (failItem(item, rejected.safeReason())) cleanupReservedFinal(item);
                } catch (Exception exception) {
                    log.error("招募 ZIP 图片处理内部失败，将保留 PROCESSING 重试: batchId={}, itemId={}",
                            batch.getId(), item.getId(), exception);
                }
            }
            if (finishBatchIfReady(batch.getId(), null)) {
                deleteTemporaryNow(batch.getArchiveObjectKey(), "batch", batch.getId());
            }
        } catch (IllegalArgumentException | IOException exception) {
            log.warn("招募 ZIP 解压校验失败: batchId={}", batch.getId(), exception);
            rejectZip(batch, exception.getClass().getSimpleName());
        } finally {
            extracted.forEach(image -> cleanupLocal(image.localFile()));
        }
    }

    private void rejectZip(RecruitmentUploadBatchEntity batch, String internalReason) {
        log.warn("招募 ZIP 被拒绝: batchId={}, internalReason={}", batch.getId(), internalReason);
        for (RecruitmentUploadItemEntity item : processingItems(batch.getId())) {
            if (failItem(item, RecruitmentUploadMessages.IMAGE_INVALID)) cleanupReservedFinal(item);
        }
        if (finishBatchIfReady(batch.getId(), RecruitmentUploadMessages.ZIP_INVALID)) {
            deleteTemporaryNow(batch.getArchiveObjectKey(), "batch", batch.getId());
        }
    }

    private List<RecruitmentUploadItemEntity> persistExtracted(
            String batchId, List<SafeImageZipExtractor.ExtractedImage> extracted) {
        List<RecruitmentUploadItemEntity> result = transactions.execute(ignored -> {
            LocalDateTime now = LocalDateTime.now();
            List<RecruitmentUploadItemEntity> persisted = new ArrayList<>();
            for (SafeImageZipExtractor.ExtractedImage image : extracted) {
                RecruitmentUploadItemEntity item = new RecruitmentUploadItemEntity();
                item.setBatchId(batchId);
                item.setOriginalFileName(image.originalFileName());
                item.setContentType(image.contentType());
                item.setSize(image.size());
                item.setSha256(image.sha256());
                item.setStatus(RecruitmentUploadItemStatus.PROCESSING);
                item.setCreatedAt(now);
                item.setUpdatedAt(now);
                itemMapper.insert(item);
                persisted.add(item);
            }
            return List.copyOf(persisted);
        });
        return Objects.requireNonNull(result, "持久化 ZIP 条目结果为空");
    }

    private boolean sameExtraction(List<RecruitmentUploadItemEntity> items,
                                   List<SafeImageZipExtractor.ExtractedImage> extracted) {
        if (items.size() != extracted.size()) return false;
        for (int index = 0; index < items.size(); index++) {
            RecruitmentUploadItemEntity item = items.get(index);
            SafeImageZipExtractor.ExtractedImage image = extracted.get(index);
            if (!Objects.equals(item.getOriginalFileName(), image.originalFileName())
                    || !Objects.equals(item.getContentType(), image.contentType())
                    || item.getSize() != image.size()
                    || !Objects.equals(item.getSha256(), image.sha256())) return false;
        }
        return true;
    }

    private void validateStructure(Path source, String contentType) throws RejectedUploadException {
        try {
            if (!ImageSignature.matches(source, contentType)) throw new IOException("魔数不匹配");
            imageValidator.validateStructure(source, contentType);
        } catch (IOException | RuntimeException exception) {
            throw new RejectedUploadException(RecruitmentUploadMessages.IMAGE_STRUCTURE_INVALID,
                    "原生结构解析失败", exception);
        }
    }

    /** Reserves the final key durably before PUT so a crash leaves a recoverable target. */
    private void finalizeOriginal(RecruitmentUploadBatchEntity batch,
                                  RecruitmentUploadItemEntity item, Path localFile,
                                  long actualSize, String actualSha) throws IOException {
        if (actualSize <= 0 || actualSize > ImageUploadPolicy.MAX_IMAGE_BYTES) {
            throw rejected(RecruitmentUploadMessages.IMAGE_SIZE_INVALID,
                    "最终源文件大小越界: " + actualSize);
        }
        String objectKey = reserveFinalObjectKey(batch, item);
        try (InputStream input = Files.newInputStream(localFile)) {
            storage.put(objectKey, input, actualSize, item.getContentType(), Map.of("sha256", actualSha));
        }
        RuntimeException finalizationFailure;
        try {
            Boolean updated = transactions.execute(ignored -> itemMapper.succeed(
                    item.getId(), batch.getId(), objectKey, actualSize, actualSha,
                    LocalDateTime.now()) == 1);
            if (Boolean.TRUE.equals(updated)) {
                markSucceeded(item, objectKey, actualSize, actualSha);
                return;
            }
            finalizationFailure = new IllegalStateException("最终对象已写入但条目状态发生并发变化");
        } catch (RuntimeException exception) {
            finalizationFailure = exception;
        }

        RecruitmentUploadItemEntity latest;
        try {
            latest = itemMapper.selectById(item.getId());
        } catch (RuntimeException lookupFailure) {
            // A successful commit can still surface as an exception to the caller.
            // Without an authoritative read, deleting could remove that committed object.
            finalizationFailure.addSuppressed(lookupFailure);
            throw finalizationFailure;
        }
        if (latest != null && latest.getStatus() == RecruitmentUploadItemStatus.SUCCEEDED
                && objectKey.equals(latest.getObjectKey())) {
            markSucceeded(item, objectKey, actualSize, actualSha);
            return;
        }
        if (latest != null && latest.getStatus() == RecruitmentUploadItemStatus.PROCESSING
                && objectKey.equals(latest.getObjectKey())) {
            // Another worker can still commit succeed(K). The durable reservation
            // already gives recovery/reaping an exact target, so leave it intact.
            throw finalizationFailure;
        }

        cleanupUnreferencedPut(item, objectKey, latest);
        throw finalizationFailure;
    }

    private void markSucceeded(RecruitmentUploadItemEntity item, String objectKey,
                               long actualSize, String actualSha) {
        item.setObjectKey(objectKey);
        item.setStatus(RecruitmentUploadItemStatus.SUCCEEDED);
        item.setSize(actualSize);
        item.setSha256(actualSha);
    }

    /**
     * Deletes only the exact key written by this attempt. The caller has already
     * verified that the latest row does not successfully reference it.
     */
    private void cleanupUnreferencedPut(RecruitmentUploadItemEntity item, String objectKey,
                                        RecruitmentUploadItemEntity latest) {
        try {
            storage.delete(objectKey);
        } catch (RuntimeException deleteFailure) {
            preserveAbandonedKey(item, objectKey, latest, deleteFailure);
            log.warn("清理未提交的招募附件最终对象失败，保留精确键重试: itemId={}, objectKey={}",
                    item.getId(), objectKey, deleteFailure);
            return;
        }
        try {
            transactions.executeWithoutResult(ignored -> itemMapper.clearReservedObjectKey(
                    item.getId(), item.getBatchId(), objectKey, LocalDateTime.now()));
            item.setObjectKey(null);
        } catch (RuntimeException clearFailure) {
            // The object is already gone. Retaining the exact key is safe and lets
            // the idempotent abandoned-final reaper retry the delete before clearing it.
            log.warn("未提交的招募附件对象已删除，但清理预留键失败: itemId={}, objectKey={}",
                    item.getId(), objectKey, clearFailure);
        }
    }

    private void preserveAbandonedKey(RecruitmentUploadItemEntity item, String objectKey,
                                      RecruitmentUploadItemEntity latest,
                                      RuntimeException deleteFailure) {
        if (latest != null && latest.getObjectKey() != null) return;
        try {
            Boolean restored = transactions.execute(ignored -> itemMapper.restoreAbandonedObjectKey(
                    item.getId(), item.getBatchId(), objectKey, LocalDateTime.now()) == 1);
            if (Boolean.TRUE.equals(restored)) item.setObjectKey(objectKey);
        } catch (RuntimeException restoreFailure) {
            deleteFailure.addSuppressed(restoreFailure);
            log.error("无法恢复待清理招募附件的精确对象键: itemId={}, objectKey={}",
                    item.getId(), objectKey, restoreFailure);
        }
    }

    private String reserveFinalObjectKey(RecruitmentUploadBatchEntity batch,
                                         RecruitmentUploadItemEntity item) {
        if (item.getObjectKey() != null) return item.getObjectKey();
        String candidate = "recruitments/applications/" + batch.getDraftId() + "/"
                + UUID.randomUUID() + ImageUploadPolicy.extension(item.getContentType());
        Boolean reserved = transactions.execute(ignored -> itemMapper.reserveObjectKey(
                item.getId(), batch.getId(), candidate, LocalDateTime.now()) == 1);
        if (Boolean.TRUE.equals(reserved)) {
            item.setObjectKey(candidate);
            return candidate;
        }
        RecruitmentUploadItemEntity latest = itemMapper.selectById(item.getId());
        if (latest != null && latest.getStatus() == RecruitmentUploadItemStatus.PROCESSING
                && latest.getObjectKey() != null) {
            item.setObjectKey(latest.getObjectKey());
            return latest.getObjectKey();
        }
        throw new IllegalStateException("无法持久化最终附件对象键");
    }

    private CopiedFile copyLimited(InputStream input, Path destination) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 不支持 SHA-256", impossible);
        }
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (OutputStream file = Files.newOutputStream(destination);
             DigestOutputStream output = new DigestOutputStream(file, digest)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > ImageUploadPolicy.MAX_IMAGE_BYTES) {
                    throw rejected(RecruitmentUploadMessages.IMAGE_SIZE_INVALID, "流式读取超过 100 MiB");
                }
                output.write(buffer, 0, read);
            }
        }
        return new CopiedFile(total, HexFormat.of().formatHex(digest.digest()));
    }

    private boolean failItem(RecruitmentUploadItemEntity item, String safeReason) {
        try {
            Boolean updated = transactions.execute(ignored -> itemMapper.fail(
                    item.getId(), item.getBatchId(), safeReason, LocalDateTime.now()) == 1);
            if (Boolean.TRUE.equals(updated)) item.setStatus(RecruitmentUploadItemStatus.FAILED);
            return Boolean.TRUE.equals(updated);
        } catch (RuntimeException databaseFailure) {
            log.error("记录招募附件失败状态时发生数据库异常: itemId={}", item.getId(), databaseFailure);
            return false;
        }
    }

    private boolean finishBatchIfReady(String batchId, String fatalFailure) {
        try {
            List<RecruitmentUploadItemEntity> items = allItems(batchId);
            if (items.stream().anyMatch(item -> item.getStatus() == RecruitmentUploadItemStatus.PROCESSING
                    || item.getStatus() == RecruitmentUploadItemStatus.UPLOADING)) return false;
            int total = items.size();
            int succeeded = (int) items.stream()
                    .filter(item -> item.getStatus() == RecruitmentUploadItemStatus.SUCCEEDED).count();
            int failed = total - succeeded;
            RecruitmentUploadBatchStatus next;
            String reason = fatalFailure;
            if (fatalFailure != null || total == 0 || succeeded == 0) {
                next = RecruitmentUploadBatchStatus.FAILED;
                if (reason == null) reason = RecruitmentUploadMessages.ALL_FAILED;
            } else if (succeeded == total) {
                next = RecruitmentUploadBatchStatus.SUCCEEDED;
                reason = null;
            } else {
                next = RecruitmentUploadBatchStatus.PARTIALLY_SUCCEEDED;
                reason = RecruitmentUploadMessages.PARTIAL_FAILED;
            }
            String safeReason = reason;
            Boolean updated = transactions.execute(ignored -> batchMapper.finish(
                    batchId, next, total, succeeded, failed, safeReason, LocalDateTime.now()) == 1);
            return Boolean.TRUE.equals(updated);
        } catch (RuntimeException exception) {
            log.error("汇总招募上传批次失败，保留 PROCESSING 重试: batchId={}", batchId, exception);
            return false;
        }
    }

    private void cleanupReservedFinal(RecruitmentUploadItemEntity item) {
        String key = item.getObjectKey();
        if (key == null) return;
        try {
            storage.delete(key);
            transactions.executeWithoutResult(ignored -> itemMapper.clearReservedObjectKey(
                    item.getId(), item.getBatchId(), key, LocalDateTime.now()));
            item.setObjectKey(null);
        } catch (RuntimeException exception) {
            log.warn("清理失败条目的预留最终对象失败，保留精确键重试: itemId={}, objectKey={}",
                    item.getId(), key, exception);
        }
    }

    private void deleteTemporaryNow(String objectKey, String kind, String id) {
        if (objectKey == null) return;
        try {
            storage.delete(objectKey);
        } catch (RuntimeException exception) {
            log.warn("立即删除招募临时对象失败，将在签名过期后重试: kind={}, id={}, objectKey={}",
                    kind, id, objectKey, exception);
        }
        // Never clear the key here; the presigned PUT may still be replayed.
    }

    private List<RecruitmentUploadItemEntity> processingItems(String batchId) {
        return itemMapper.selectList(Wrappers.<RecruitmentUploadItemEntity>lambdaQuery()
                .eq(RecruitmentUploadItemEntity::getBatchId, batchId)
                .eq(RecruitmentUploadItemEntity::getStatus, RecruitmentUploadItemStatus.PROCESSING)
                .orderByAsc(RecruitmentUploadItemEntity::getId));
    }

    private List<RecruitmentUploadItemEntity> allItems(String batchId) {
        return itemMapper.selectList(Wrappers.<RecruitmentUploadItemEntity>lambdaQuery()
                .eq(RecruitmentUploadItemEntity::getBatchId, batchId)
                .orderByAsc(RecruitmentUploadItemEntity::getId));
    }

    private void cleanupLocal(Path path) {
        if (path == null) return;
        try {
            workspace.deleteBatchFile(path);
        } catch (RuntimeException exception) {
            log.warn("清理招募上传本地临时文件失败: {}", path, exception);
        }
    }

    private RejectedUploadException rejected(String safeReason, String internalReason) {
        return new RejectedUploadException(safeReason, internalReason, null);
    }

    private void requireNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("招募附件处理不能在数据库事务中执行");
        }
    }

    public record ProcessRequested(String batchId) {
    }

    private record CopiedFile(long size, String sha256) {
    }

    private static final class RejectedUploadException extends IOException {
        private final String safeReason;

        private RejectedUploadException(String safeReason, String internalReason, Throwable cause) {
            super(internalReason, cause);
            this.safeReason = safeReason;
        }

        private String safeReason() {
            return safeReason;
        }
    }
}
