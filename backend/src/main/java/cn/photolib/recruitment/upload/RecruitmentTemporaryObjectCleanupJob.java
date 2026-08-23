package cn.photolib.recruitment.upload;

import cn.photolib.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/** Re-deletes expired presigned PUT targets before forgetting their exact keys. */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecruitmentTemporaryObjectCleanupJob {
    private static final int LIMIT = 500;

    private final RecruitmentUploadBatchMapper batchMapper;
    private final RecruitmentUploadItemMapper itemMapper;
    private final ObjectStorageService storage;
    private final Clock recruitmentClock;

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOnStartup() {
        cleanupExpiredTargets();
    }

    @Scheduled(fixedDelayString = "${photolib.recruitment.temporary-cleanup-delay-ms:300000}",
            initialDelayString = "${photolib.recruitment.temporary-cleanup-initial-delay-ms:60000}")
    public void scheduledCleanup() {
        cleanupExpiredTargets();
    }

    public CleanupResult cleanupExpiredTargets() {
        LocalDateTime now = LocalDateTime.now(recruitmentClock);
        int deleted = 0;
        int pending = 0;
        Set<String> affectedFileBatches = new HashSet<>();
        for (RecruitmentUploadItemEntity item : itemMapper.findAbandonedFinalTargets(LIMIT)) {
            if (deleteExact(item.getObjectKey(), "reserved-final", String.valueOf(item.getId()))) {
                if (itemMapper.clearReservedObjectKey(item.getId(), item.getBatchId(),
                        item.getObjectKey(), now) == 1) deleted++;
            } else {
                pending++;
            }
        }
        for (RecruitmentUploadBatchEntity batch : batchMapper.findExpiredArchiveTargets(now, LIMIT)) {
            if (deleteExact(batch.getArchiveObjectKey(), "batch", batch.getId())) {
                if (batchMapper.clearExpiredArchiveTarget(batch.getId(),
                        batch.getArchiveObjectKey(), now) == 1) deleted++;
            } else {
                pending++;
            }
        }
        for (RecruitmentUploadItemEntity item : itemMapper.findExpiredTemporaryTargets(now, LIMIT)) {
            if (deleteExact(item.getTempObjectKey(), "item", String.valueOf(item.getId()))) {
                if (itemMapper.clearExpiredTemporaryTarget(item.getId(),
                        item.getTempObjectKey(), now) == 1) {
                    deleted++;
                    affectedFileBatches.add(item.getBatchId());
                }
            } else {
                pending++;
            }
        }
        affectedFileBatches.forEach(batchId ->
                batchMapper.failExpiredFileBatchWithoutSources(batchId, now));
        return new CleanupResult(deleted, pending);
    }

    private boolean deleteExact(String objectKey, String kind, String id) {
        try {
            storage.delete(objectKey);
            return true;
        } catch (RuntimeException exception) {
            log.warn("签名过期临时对象删除失败，保留精确键重试: kind={}, id={}, objectKey={}",
                    kind, id, objectKey, exception);
            return false;
        }
    }

    public record CleanupResult(int deleted, int pending) {
    }
}
