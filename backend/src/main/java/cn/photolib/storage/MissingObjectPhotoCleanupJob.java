package cn.photolib.storage;

import cn.photolib.photo.PreviewMaintenanceLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Removes photo records whose finished object no longer exists in the backing
 * store, once per application start.
 *
 * <p>This is the one place allowed to delete photo records from a storage
 * observation, and it is deliberately conservative:</p>
 * <ul>
 *   <li>Only an exact HEAD returning a <em>confirmed</em> not-found counts as
 *       missing. A listing snapshot is never used, and any HEAD error keeps the
 *       record and raises an alert.</li>
 *   <li>Only {@code object_key} is examined. A missing preview is a recoverable
 *       state handled by the preview repair pipeline, and
 *       {@code original_object_key} is deleted on purpose once its retention
 *       window passes — neither may delete a record.</li>
 *   <li>A circuit breaker aborts the whole sweep when too large a share of the
 *       library looks missing. That pattern means a misconfigured bucket,
 *       prefix or mount, not genuinely lost photos.</li>
 *   <li>Photos an active adoption points at are never deleted, only reported.</li>
 *   <li>Deletion is the same soft delete the manual gallery flow performs, so an
 *       operator can restore a record with {@code deleted=0}, and every removal
 *       leaves an audit row.</li>
 * </ul>
 */
@Slf4j
@Component
public class MissingObjectPhotoCleanupJob {
    private static final String AUDIT_ACTION = "PHOTO_AUTO_CLEANUP";
    private static final String ALERT_CLEANED = "PHOTO_MISSING_OBJECT_CLEANED";
    private static final String ALERT_ABORTED = "PHOTO_MISSING_OBJECT_CLEANUP_ABORTED";
    private static final String ALERT_ADOPTED = "PHOTO_ADOPTED_OBJECT_MISSING";
    private static final String ALERT_HEAD_FAILED = "PHOTO_CLEANUP_HEAD_FAILED";

    private final ObjectStorageService storage;
    private final JdbcClient jdbc;
    private final PreviewMaintenanceLock maintenanceLock;
    private final TransactionTemplate transactions;
    private final TaskExecutor executor;
    private final boolean enabled;
    private final double maxRatio;
    private final int minAbsolute;

    public MissingObjectPhotoCleanupJob(
            ObjectStorageService storage,
            JdbcClient jdbc,
            PreviewMaintenanceLock maintenanceLock,
            TransactionTemplate transactions,
            @Qualifier("applicationTaskExecutor") TaskExecutor executor,
            @Value("${photolib.startup-missing-object-cleanup.enabled:true}") boolean enabled,
            @Value("${photolib.startup-missing-object-cleanup.max-ratio:0.2}") double maxRatio,
            @Value("${photolib.startup-missing-object-cleanup.min-absolute:20}") int minAbsolute) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.maintenanceLock = maintenanceLock;
        this.transactions = transactions;
        this.executor = executor;
        this.enabled = enabled;
        this.maxRatio = maxRatio;
        this.minAbsolute = minAbsolute;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupAfterApplicationReady() {
        if (!enabled) {
            log.info("启动时清理缺失成品图的图片记录已被配置关闭");
            return;
        }
        // Run off the startup thread so a large library cannot delay login. The
        // general-purpose pool is used on purpose: previewRegenerationExecutor
        // has a queue capacity of one and belongs to the preview coordinator.
        executor.execute(() -> {
            try {
                run();
            } catch (RuntimeException exception) {
                log.error("启动时清理缺失成品图的图片记录失败；未删除任何记录时其余功能不受影响",
                        exception);
            }
        });
    }

    /** Serialized against preview generation, reconciliation and repair. */
    public CleanupResult run() {
        return maintenanceLock.exclusively(this::runLocked);
    }

    private CleanupResult runLocked() {
        List<PhotoRecord> photos = loadCandidates();
        List<PhotoRecord> missing = new ArrayList<>();
        List<Long> headFailures = new ArrayList<>();
        for (PhotoRecord photo : photos) {
            Optional<ObjectStorageService.ObjectInfo> object;
            try {
                object = exactObject(photo.objectKey());
            } catch (RuntimeException exception) {
                // An unknown HEAD result can never justify deleting a record.
                headFailures.add(photo.id());
                log.error("HEAD 成品图失败，保留数据库记录等待下次启动: photoId={}, objectKey={}",
                        photo.id(), photo.objectKey(), exception);
                continue;
            }
            if (object.isEmpty()) missing.add(photo);
        }
        synchronizeAlert(ALERT_HEAD_FAILED, headFailures.isEmpty() ? null
                : "启动清理有 " + headFailures.size() + " 个精确 HEAD 请求失败；相关数据库记录均已保留，"
                + "请检查网络、权限和对象存储状态。示例 photoId：" + examples(headFailures));

        if (missing.isEmpty()) {
            synchronizeAlert(ALERT_CLEANED, null);
            synchronizeAlert(ALERT_ABORTED, null);
            synchronizeAlert(ALERT_ADOPTED, null);
            log.info("启动清理完成：核对 {} 张，未发现成品图缺失的记录", photos.size());
            return new CleanupResult(photos.size(), 0, 0, 0, headFailures.size(), false);
        }

        if (tripsCircuitBreaker(photos.size(), missing.size())) {
            String message = "启动清理发现 " + missing.size() + "/" + photos.size()
                    + " 张成品图缺失，超过安全阈值，已中止且未删除任何记录。"
                    + "请先核对存储挂载、Bucket、Endpoint 和对象前缀配置。示例 photoId："
                    + examples(missing.stream().map(PhotoRecord::id).toList());
            synchronizeAlert(ALERT_ABORTED, message);
            log.error("启动清理触发熔断，未删除任何记录：缺失 {}/{} 张", missing.size(), photos.size());
            return new CleanupResult(photos.size(), missing.size(), 0, 0,
                    headFailures.size(), true);
        }
        synchronizeAlert(ALERT_ABORTED, null);

        List<Long> adopted = new ArrayList<>();
        List<PhotoRecord> deletable = new ArrayList<>();
        for (PhotoRecord photo : missing) {
            if (activelyAdopted(photo.id())) {
                adopted.add(photo.id());
            } else {
                deletable.add(photo);
            }
        }
        synchronizeAlert(ALERT_ADOPTED, adopted.isEmpty() ? null
                : "有 " + adopted.size() + " 张已被项目采用的图片其成品图在对象存储中缺失；"
                + "记录已保留，请人工核对后再决定归档或删除。示例 photoId：" + examples(adopted));

        int deleted = 0;
        for (PhotoRecord photo : deletable) {
            if (softDelete(photo)) {
                deleted++;
                releaseLeftoverObjects(photo);
            } else {
                log.warn("软删除缺失成品图的记录未命中（并发变化），保留现状：photoId={}", photo.id());
            }
        }
        synchronizeAlert(ALERT_CLEANED, deleted == 0 ? null
                : "启动清理已软删除 " + deleted + " 张成品图在对象存储中缺失的图片记录（共发现 "
                + missing.size() + " 张，跳过已采用 " + adopted.size()
                + " 张）。如属误判，可将对应记录改回 deleted=0 恢复。示例 photoId："
                + examples(deletable.stream().map(PhotoRecord::id).toList()));
        log.info("启动清理完成：核对 {} 张，缺失 {} 张，软删除 {} 张，跳过已采用 {} 张，HEAD 失败 {} 个",
                photos.size(), missing.size(), deleted, adopted.size(), headFailures.size());
        return new CleanupResult(photos.size(), missing.size(), deleted, adopted.size(),
                headFailures.size(), false);
    }

    /**
     * {@code UPLOADING} and {@code PROCESSING} photos legitimately have no
     * finished object yet, so they are never candidates.
     */
    private List<PhotoRecord> loadCandidates() {
        return jdbc.sql("""
                SELECT id, object_key, thumbnail_object_key, original_object_key,
                       status, version
                FROM photo
                WHERE deleted=0 AND status IN ('AVAILABLE', 'ARCHIVED')
                ORDER BY id
                """)
                .query((rs, rowNum) -> new PhotoRecord(
                        rs.getLong("id"),
                        rs.getString("object_key"),
                        rs.getString("thumbnail_object_key"),
                        rs.getString("original_object_key"),
                        rs.getString("status"),
                        rs.getInt("version")))
                .list();
    }

    private Optional<ObjectStorageService.ObjectInfo> exactObject(String objectKey) {
        if (!StringUtils.hasText(objectKey)) return Optional.empty();
        return storage.find(objectKey);
    }

    private boolean tripsCircuitBreaker(int total, int missing) {
        if (total <= 0) return false;
        if (missing >= total) return true;
        return missing >= minAbsolute && missing > total * maxRatio;
    }

    private boolean activelyAdopted(long photoId) {
        return jdbc.sql("SELECT COUNT(*) FROM adoption WHERE photo_id=:photoId AND deleted=0")
                .param("photoId", photoId)
                .query(Long.class)
                .single() > 0;
    }

    /**
     * Soft deletes under a CAS on the exact state that was observed, then records
     * the removal. {@code status} is left untouched so the row matches what the
     * manual gallery delete produces.
     */
    private boolean softDelete(PhotoRecord photo) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int updated = jdbc.sql("""
                    UPDATE photo
                    SET deleted=1, version=version+1, updated_at=CURRENT_TIMESTAMP
                    WHERE id=:id AND deleted=0 AND version=:version
                      AND object_key=:objectKey AND status=:status
                    """)
                    .param("id", photo.id())
                    .param("version", photo.version())
                    .param("objectKey", photo.objectKey())
                    .param("status", photo.status())
                    .update();
            if (updated != 1) return false;
            jdbc.sql("""
                    INSERT INTO audit_log
                        (operator_id, action, resource_type, resource_id, detail_json)
                    VALUES
                        (NULL, :action, 'PHOTO', :resourceId, :detail)
                    """)
                    .param("action", AUDIT_ACTION)
                    .param("resourceId", String.valueOf(photo.id()))
                    .param("detail", detailJson(photo))
                    .update();
            return true;
        }));
    }

    private String detailJson(PhotoRecord photo) {
        return "{\"reason\":\"OBJECT_MISSING\",\"objectKey\":\"" + jsonEscape(photo.objectKey())
                + "\",\"status\":\"" + jsonEscape(photo.status()) + "\"}";
    }

    private String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * The finished object is already gone, so only the preview and the retained
     * original can be left behind. Best effort: the record is already deleted and
     * a failure here just leaves an orphan object.
     */
    private void releaseLeftoverObjects(PhotoRecord photo) {
        deleteQuietly(photo.id(), "缩略图", photo.thumbnailObjectKey());
        deleteQuietly(photo.id(), "原图", photo.originalObjectKey());
    }

    private void deleteQuietly(long photoId, String objectType, String objectKey) {
        if (!StringUtils.hasText(objectKey)) return;
        try {
            storage.delete(objectKey);
        } catch (RuntimeException exception) {
            log.warn("数据库记录已软删除，但{}清理失败（photoId={}, objectKey={}），需要人工或清理任务重试",
                    objectType, photoId, objectKey, exception);
        }
    }

    private String examples(List<Long> photoIds) {
        return photoIds.stream().limit(20).map(String::valueOf).collect(Collectors.joining(", "));
    }

    /** Upserts an unresolved alert, or resolves the open one when {@code message} is null. */
    private void synchronizeAlert(String type, String message) {
        try {
            if (message == null) {
                jdbc.sql("""
                        UPDATE admin_alert
                        SET resolved=1, resolved_at=CURRENT_TIMESTAMP
                        WHERE type=:type AND resolved=0
                        """).param("type", type).update();
                return;
            }
            Long existing = jdbc.sql("""
                    SELECT id FROM admin_alert
                    WHERE type=:type AND resolved=0
                    ORDER BY id DESC LIMIT 1
                    """).param("type", type).query(Long.class).optional().orElse(null);
            if (existing == null) {
                jdbc.sql("""
                        INSERT INTO admin_alert
                            (type, message, resource_type, resolved, created_at)
                        VALUES
                            (:type, :message, 'STORAGE', false, CURRENT_TIMESTAMP)
                        """).param("type", type).param("message", message).update();
            } else {
                jdbc.sql("UPDATE admin_alert SET message=:message WHERE id=:id")
                        .param("message", message).param("id", existing).update();
            }
        } catch (RuntimeException exception) {
            log.warn("同步启动清理管理员告警失败：type={}", type, exception);
        }
    }

    private record PhotoRecord(long id, String objectKey, String thumbnailObjectKey,
                               String originalObjectKey, String status, int version) {
    }

    public record CleanupResult(int checked, int missing, int deleted, int skippedAdopted,
                                int headFailures, boolean aborted) {
    }
}
