package cn.photolib.photo.batch;

import cn.photolib.storage.ObjectStorageService;
import cn.photolib.photo.PhotoProcessingWorkspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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

    @Async("batchProcessingExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onZipRequested(ZipProcessRequested event) {
        processZip(event.batchId());
    }

    @Transactional
    public void processZip(String batchId) {
        PhotoUploadBatchEntity batch = batchMapper.selectById(batchId);
        if (batch == null || batch.getStatus() != BatchStatus.PROCESSING) return;
        int count = 0;
        long total = 0;
        List<Path> extractedFiles = new ArrayList<>();
        List<Long> insertedItems = new ArrayList<>();
        try (InputStream source = storage.open(batch.getArchiveObjectKey());
             ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = safeName(entry.getName());
                String type = contentType(name);
                if (type == null) continue;
                if (++count > 100) throw new IllegalArgumentException("ZIP 内图片超过 100 张");
                String extension = type.equals("image/png") ? ".png" : ".jpg";
                Path localFile = workspace.createBatchFile(batchId, extension);
                long size;
                try {
                    size = copyLimited(zip, localFile, MAX_ITEM);
                } catch (Exception exception) {
                    cleanupFile(localFile);
                    throw exception;
                }
                extractedFiles.add(localFile);
                total += size;
                if (total > MAX_TOTAL) throw new IllegalArgumentException("ZIP 解压总大小超过 10 GiB");
                String key = "temporary/batches/" + batchId + "/" + UUID.randomUUID() + extension;
                PhotoUploadItemEntity item = new PhotoUploadItemEntity();
                item.setBatchId(batchId);
                item.setOriginalFileName(name);
                item.setTempObjectKey(key);
                item.setTempLocalPath(localFile.toString());
                item.setContentType(type);
                item.setSize(size);
                item.setStatus(BatchItemStatus.WAITING_METADATA);
                item.setCreatedAt(LocalDateTime.now());
                item.setUpdatedAt(LocalDateTime.now());
                itemMapper.insert(item);
                insertedItems.add(item.getId());
            }
            if (count == 0) throw new IllegalArgumentException("ZIP 中没有 JPG/PNG 图片");
            batch.setTotalCount(count);
            batch.setStatus(BatchStatus.WAITING_METADATA);
        } catch (Exception ex) {
            extractedFiles.forEach(this::cleanupFile);
            insertedItems.forEach(itemMapper::deleteById);
            batch.setStatus(BatchStatus.FAILED);
            batch.setFailureReason(ex.getMessage());
        } finally {
            cleanupArchive(batch);
        }
        batch.setUpdatedAt(LocalDateTime.now());
        batchMapper.updateById(batch);
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

    private void cleanupArchive(PhotoUploadBatchEntity batch) {
        if (batch.getArchiveObjectKey() == null) return;
        try {
            storage.delete(batch.getArchiveObjectKey());
            batch.setArchiveObjectKey(null);
            batchMapper.clearArchiveObjectKey(batch.getId(), LocalDateTime.now());
        } catch (RuntimeException exception) {
            log.warn("清理已处理的 ZIP 原始对象失败: batchId={}, objectKey={}",
                    batch.getId(), batch.getArchiveObjectKey(), exception);
        }
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

    public record ZipProcessRequested(String batchId) {}
}
