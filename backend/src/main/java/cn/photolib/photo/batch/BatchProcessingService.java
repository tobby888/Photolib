package cn.photolib.photo.batch;

import cn.photolib.common.upload.SafeImageZipExtractor;
import cn.photolib.common.upload.UploadFailureMessage;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.photo.PhotoProcessingWorkspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BatchProcessingService {
    private final PhotoUploadBatchMapper batchMapper;
    private final PhotoUploadItemMapper itemMapper;
    private final ObjectStorageService storage;
    private final PhotoProcessingWorkspace workspace;
    private final SafeImageZipExtractor zipExtractor;
    private final TransactionTemplate transactions;

    @Async("batchProcessingExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onZipRequested(ZipProcessRequested event) {
        processZip(event.batchId());
    }

    public void processZip(String batchId) {
        requireNoActiveTransaction();
        PhotoUploadBatchEntity batch = batchMapper.selectById(batchId);
        if (batch == null || batch.getStatus() != BatchStatus.PROCESSING) return;
        List<ExtractedItem> extracted = new ArrayList<>();
        String failureReason = null;
        try (InputStream source = storage.open(batch.getArchiveObjectKey())) {
            var images = zipExtractor.extract(source,
                    extension -> workspace.createBatchFile(batchId, extension));
            for (SafeImageZipExtractor.ExtractedImage image : images) {
                String key = "temporary/batches/" + batchId + "/" + UUID.randomUUID()
                        + cn.photolib.common.upload.ImageUploadPolicy.extension(image.contentType());
                extracted.add(new ExtractedItem(image.originalFileName(), key,
                        image.localFile(), image.contentType(), image.size()));
            }
        } catch (Exception ex) {
            failureReason = failureReason(batchId, ex);
        }

        if (failureReason == null) {
            try {
                persistExtracted(batchId, extracted);
            } catch (RuntimeException exception) {
                failureReason = failureReason(batchId, exception);
            }
        }
        if (failureReason != null) {
            extracted.stream().map(ExtractedItem::localFile).forEach(this::cleanupFile);
            if (!markFailed(batchId, failureReason)) {
                log.warn("ZIP 解压结果未能写回数据库，保留原 ZIP 对象供后续排查或重试: batchId={}", batchId);
                return;
            }
        }
        cleanupArchive(batchId, batch.getArchiveObjectKey());
    }

    private void persistExtracted(String batchId, List<ExtractedItem> extracted) {
        transactions.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            for (ExtractedItem extractedItem : extracted) {
                PhotoUploadItemEntity item = new PhotoUploadItemEntity();
                item.setBatchId(batchId);
                item.setOriginalFileName(extractedItem.originalFileName());
                item.setTempObjectKey(extractedItem.tempObjectKey());
                item.setTempLocalPath(extractedItem.localFile().toString());
                item.setContentType(extractedItem.contentType());
                item.setSize(extractedItem.size());
                item.setStatus(BatchItemStatus.WAITING_METADATA);
                item.setCreatedAt(now);
                item.setUpdatedAt(now);
                itemMapper.insert(item);
            }
            if (batchMapper.finishExtraction(batchId, extracted.size(), now) != 1) {
                throw new IllegalStateException("ZIP 批次状态已变化，无法提交解压结果");
            }
        });
    }

    private boolean markFailed(String batchId, String failureReason) {
        try {
            Boolean updated = transactions.execute(status -> batchMapper.failExtraction(
                    batchId, failureReason, LocalDateTime.now()) == 1);
            if (!Boolean.TRUE.equals(updated)) {
                log.warn("ZIP 解压失败但批次状态已变化，未覆盖当前状态: batchId={}", batchId);
                return false;
            }
            return true;
        } catch (RuntimeException exception) {
            log.error("记录 ZIP 解压失败状态时发生数据库异常: batchId={}", batchId, exception);
            return false;
        }
    }

    private void cleanupFile(Path file) {
        try {
            workspace.deleteBatchFile(file);
        } catch (RuntimeException exception) {
            log.warn("清理 ZIP 解压临时文件失败: {}", file, exception);
        }
    }

    private void cleanupArchive(String batchId, String archiveObjectKey) {
        if (archiveObjectKey == null) return;
        try {
            storage.delete(archiveObjectKey);
        } catch (RuntimeException exception) {
            log.warn("清理已处理的 ZIP 原始对象失败: batchId={}, objectKey={}",
                    batchId, archiveObjectKey, exception);
            return;
        }
        try {
            transactions.executeWithoutResult(status -> batchMapper.clearArchiveObjectKey(
                    batchId, archiveObjectKey, LocalDateTime.now()));
        } catch (RuntimeException exception) {
            log.warn("ZIP 原始对象已删除，但清空数据库对象键失败: batchId={}, objectKey={}",
                    batchId, archiveObjectKey, exception);
        }
    }

    private void requireNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("ZIP 文件读取与解压不能在数据库事务中执行");
        }
    }

    /**
     * 解包失败的原因，写进 {@code failure_reason} 之后会一路回到上传者的界面上
     * （站内的批量上传页，以及上传链接那条匿名通道）。所以只有"压缩包本身的毛病"
     * 照原样给出去，其余一律换成通用提示、原文进日志——理由见
     * {@link UploadFailureMessage}。
     */
    private String failureReason(String batchId, Throwable exception) {
        if (UploadFailureMessage.isInternal(exception)) {
            log.error("ZIP 解包因非校验类错误失败，对外只回通用提示: batchId={}", batchId, exception);
        }
        return UploadFailureMessage.forUploader(exception,
                "压缩包没能处理完成。请确认它是完整的 .zip 后重新上传；如果反复失败，请联系管理员。");
    }

    private record ExtractedItem(String originalFileName, String tempObjectKey, Path localFile,
                                 String contentType, long size) {
    }

    public record ZipProcessRequested(String batchId) {}
}
