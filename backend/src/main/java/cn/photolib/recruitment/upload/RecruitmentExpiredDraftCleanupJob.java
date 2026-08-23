package cn.photolib.recruitment.upload;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import cn.photolib.storage.ObjectStorageService;

import java.time.LocalDateTime;
import java.time.Clock;
import java.util.List;

/**
 * Deletes only object keys recorded against an expired, unsubmitted draft.
 * The DRAFT -> CLEANUP_PENDING claim is also the submission race barrier.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecruitmentExpiredDraftCleanupJob {
    private static final int BATCH_LIMIT = 100;

    private final RecruitmentUploadCleanupMapper cleanupMapper;
    private final RecruitmentUploadBatchMapper batchMapper;
    private final RecruitmentUploadItemMapper itemMapper;
    private final ObjectStorageService storage;
    private final TransactionTemplate transactions;
    private final Clock recruitmentClock;

    @Scheduled(fixedDelayString = "${photolib.recruitment.upload-cleanup-delay-ms:3600000}",
            initialDelayString = "${photolib.recruitment.upload-cleanup-initial-delay-ms:600000}")
    public void scheduledCleanup() {
        cleanupExpiredDrafts();
    }

    public CleanupResult cleanupExpiredDrafts() {
        LocalDateTime now = LocalDateTime.now(recruitmentClock);
        List<String> candidates = cleanupMapper.findCandidates(now, BATCH_LIMIT);
        int completed = 0;
        int failed = 0;
        int deletedObjects = 0;
        for (String draftId : candidates) {
            try {
                ClaimResult claim = claim(draftId, now);
                if (!claim.claimed()) continue;
                DraftCleanup result = cleanupClaimedDraft(draftId);
                deletedObjects += result.deletedObjects();
                if (result.complete()) completed++;
                else failed++;
            } catch (RuntimeException exception) {
                failed++;
                log.error("清理过期招募草稿失败: draftId={}", draftId, exception);
            }
        }
        if (!candidates.isEmpty()) {
            log.info("过期招募草稿清理完成: candidates={}, completed={}, pending={}, deletedObjects={}",
                    candidates.size(), completed, failed, deletedObjects);
        }
        return new CleanupResult(candidates.size(), completed, failed, deletedObjects);
    }

    private ClaimResult claim(String draftId, LocalDateTime now) {
        return transactions.execute(status -> {
            String current = cleanupMapper.status(draftId);
            if ("CLEANUP_PENDING".equals(current)) return new ClaimResult(true);
            if ("EXPIRED".equals(current)) {
                if (cleanupMapper.claimExpired(draftId, now) != 1) return new ClaimResult(false);
                cleanupMapper.failItems(draftId, now);
                cleanupMapper.failBatches(draftId, now);
                return new ClaimResult(true);
            }
            if (!"DRAFT".equals(current) || cleanupMapper.claim(draftId, now) != 1) {
                return new ClaimResult(false);
            }
            // Terminalize every upload row before touching storage. A processor
            // already uploading a final object will fail its PROCESSING CAS and
            // delete that object itself.
            cleanupMapper.failItems(draftId, now);
            cleanupMapper.failBatches(draftId, now);
            return new ClaimResult(true);
        });
    }

    private DraftCleanup cleanupClaimedDraft(String draftId) {
        LocalDateTime now = LocalDateTime.now(recruitmentClock);
        int deleted = 0;
        boolean allDeleted = true;
        List<RecruitmentUploadBatchEntity> batches = batchMapper.selectList(
                Wrappers.<RecruitmentUploadBatchEntity>lambdaQuery()
                        .eq(RecruitmentUploadBatchEntity::getDraftId, draftId)
                        .orderByAsc(RecruitmentUploadBatchEntity::getId));
        for (RecruitmentUploadBatchEntity batch : batches) {
            if (batch.getArchiveObjectKey() != null) {
                String key = batch.getArchiveObjectKey();
                if (deleteExact(key)) {
                    deleted++;
                    if (batch.getUploadUrlExpiresAt() != null
                            && !batch.getUploadUrlExpiresAt().isAfter(now)) {
                        batchMapper.clearExpiredArchiveTarget(batch.getId(), key, now);
                    }
                } else {
                    // Keep the exact key for the post-expiry replay-safe reaper.
                }
            }
            for (RecruitmentUploadItemEntity item : items(batch.getId())) {
                if (item.getTempObjectKey() != null) {
                    String key = item.getTempObjectKey();
                    if (deleteExact(key)) {
                        deleted++;
                        if (item.getUploadUrlExpiresAt() != null
                                && !item.getUploadUrlExpiresAt().isAfter(now)) {
                            itemMapper.clearExpiredTemporaryTarget(item.getId(), key, now);
                        }
                    } else {
                        // Keep the exact key for the post-expiry replay-safe reaper.
                    }
                }
                if (item.getObjectKey() != null) {
                    String key = item.getObjectKey();
                    if (deleteExact(key)) {
                        deleted++;
                        itemMapper.clearExpiredFinalObject(item.getId(), batch.getId(), key,
                                now);
                    } else {
                        allDeleted = false;
                    }
                }
            }
        }
        if (cleanupMapper.countRemainingObjectKeys(draftId) != 0) allDeleted = false;
        if (allDeleted) {
            cleanupMapper.finish(draftId, now);
        }
        return new DraftCleanup(allDeleted, deleted);
    }

    private List<RecruitmentUploadItemEntity> items(String batchId) {
        return itemMapper.selectList(Wrappers.<RecruitmentUploadItemEntity>lambdaQuery()
                .eq(RecruitmentUploadItemEntity::getBatchId, batchId)
                .orderByAsc(RecruitmentUploadItemEntity::getId));
    }

    private boolean deleteExact(String objectKey) {
        try {
            storage.delete(objectKey);
            return true;
        } catch (RuntimeException exception) {
            log.warn("删除过期招募草稿的精确对象失败: objectKey={}", objectKey, exception);
            return false;
        }
    }

    public record CleanupResult(int candidates, int completed, int pending, int deletedObjects) {
    }

    private record ClaimResult(boolean claimed) {
    }

    private record DraftCleanup(boolean complete, int deletedObjects) {
    }
}
