package cn.photolib.photo.batch;

import cn.photolib.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class BatchProcessingService {
    private static final long MAX_ITEM = 100L * 1024 * 1024;
    private static final long MAX_TOTAL = 10L * 1024 * 1024 * 1024;
    private final PhotoUploadBatchMapper batchMapper;
    private final PhotoUploadItemMapper itemMapper;
    private final ObjectStorageService storage;

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
        try (InputStream source = storage.open(batch.getArchiveObjectKey());
             ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = safeName(entry.getName());
                String type = contentType(name);
                if (type == null) continue;
                if (++count > 100) throw new IllegalArgumentException("ZIP 内图片超过 100 张");
                byte[] bytes = readLimited(zip, MAX_ITEM);
                total += bytes.length;
                if (total > MAX_TOTAL) throw new IllegalArgumentException("ZIP 解压总大小超过 10 GiB");
                String extension = type.equals("image/png") ? "png" : "jpg";
                String key = "temporary/batches/" + batchId + "/" + UUID.randomUUID() + "." + extension;
                storage.put(key, new ByteArrayInputStream(bytes), bytes.length, type);
                PhotoUploadItemEntity item = new PhotoUploadItemEntity();
                item.setBatchId(batchId);
                item.setOriginalFileName(name);
                item.setTempObjectKey(key);
                item.setContentType(type);
                item.setSize((long) bytes.length);
                item.setStatus(BatchItemStatus.WAITING_METADATA);
                item.setCreatedAt(LocalDateTime.now());
                item.setUpdatedAt(LocalDateTime.now());
                itemMapper.insert(item);
            }
            if (count == 0) throw new IllegalArgumentException("ZIP 中没有 JPG/PNG 图片");
            batch.setTotalCount(count);
            batch.setStatus(BatchStatus.WAITING_METADATA);
        } catch (Exception ex) {
            batch.setStatus(BatchStatus.FAILED);
            batch.setFailureReason(ex.getMessage());
        }
        batch.setUpdatedAt(LocalDateTime.now());
        batchMapper.updateById(batch);
    }

    private byte[] readLimited(InputStream input, long max) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > max) throw new IllegalArgumentException("单张图片超过 100 MiB");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
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
