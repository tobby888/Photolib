package cn.photolib.photo.batch;

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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class BatchProcessingService {
    private static final long MAX_ITEM = 100L * 1024 * 1024;
    private static final long MAX_TOTAL = 10L * 1024 * 1024 * 1024;
    private final PhotoUploadBatchMapper batchMapper;
    private final PhotoUploadItemMapper itemMapper;
    private final ObjectStorageService storage;
    private final PhotoProcessingWorkspace workspace;
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
        long total = 0;
        List<ExtractedItem> extracted = new ArrayList<>();
        String failureReason = null;
        try (InputStream source = storage.open(batch.getArchiveObjectKey());
             ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = safeName(entry.getName());
                String type = contentType(name);
                if (type == null) continue;
                if (extracted.size() >= 100) throw new IllegalArgumentException("ZIP 内图片超过 100 张");
                String extension = type.equals("image/png") ? ".png" : ".jpg";
                Path localFile = workspace.createBatchFile(batchId, extension);
                long size;
                try {
                    size = copyLimited(zip, localFile, MAX_ITEM);
                } catch (Exception exception) {
                    cleanupFile(localFile);
                    throw exception;
                }
                total += size;
                if (total > MAX_TOTAL) {
                    cleanupFile(localFile);
                    throw new IllegalArgumentException("ZIP 解压总大小超过 10 GiB");
                }
                String key = "temporary/batches/" + batchId + "/" + UUID.randomUUID() + extension;
                extracted.add(new ExtractedItem(name, key, localFile, type, size));
            }
            if (extracted.isEmpty()) throw new IllegalArgumentException("ZIP 中没有 JPG/PNG 图片");
        } catch (Exception ex) {
            failureReason = failureReason(ex);
        }

        if (failureReason == null) {
            try {
                persistExtracted(batchId, extracted);
            } catch (RuntimeException exception) {
                failureReason = failureReason(exception);
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

    private long copyLimited(InputStream input, Path destination, long max) throws java.io.IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (OutputStream output = Files.newOutputStream(destination)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > max) throw new IllegalArgumentException("单张图片超过 100 MiB");
                output.write(buffer, 0, read);
            }
        }
        return total;
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

    private String failureReason(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        int[] codePoints = message.codePoints().limit(1000).toArray();
        return new String(codePoints, 0, codePoints.length);
    }

    private String safeName(String name) {
        String normalized = name.replace('\\', '/');
        if (normalized.contains("../") || normalized.startsWith("/")) {
            throw new IllegalArgumentException("ZIP 包含非法路径");
        }
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private String contentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        return null;
    }

    private record ExtractedItem(String originalFileName, String tempObjectKey, Path localFile,
                                 String contentType, long size) {
    }

    public record ZipProcessRequested(String batchId) {}
}
