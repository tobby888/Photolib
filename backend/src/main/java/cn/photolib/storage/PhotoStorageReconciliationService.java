package cn.photolib.storage;

import cn.photolib.photo.PreviewRepairRequestedEvent;
import cn.photolib.photo.PreviewMaintenanceLock;
import cn.photolib.photo.PreviewProfile;
import cn.photolib.photo.PreviewProfilePolicy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Observes gallery records against object storage without deleting business
 * records. Missing or stale preview references are invalidated with a
 * compare-and-set update and handed to the preview repair pipeline.
 */
@Service
@RequiredArgsConstructor
public class PhotoStorageReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(PhotoStorageReconciliationService.class);
    private static final long MAX_PREVIEW_BYTES = 20L * 1024 * 1024;

    private final ObjectStorageService storage;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final PreviewProfilePolicy previewProfiles;
    private final PreviewMaintenanceLock maintenanceLock;

    @Scheduled(fixedDelay = 3600_000, initialDelay = 600_000)
    public void scheduledReconcile() {
        try {
            reconcile();
        } catch (Exception e) {
            log.error("定时对账任务执行失败", e);
        }
    }

    public ReconciliationResult reconcile() {
        return maintenanceLock.exclusively(this::reconcileLocked);
    }

    private ReconciliationResult reconcileLocked() {
        PreviewProfile expected;
        try {
            expected = previewProfiles.requireRunningProfile();
            synchronizePreviewProfileAlert(null);
        } catch (RuntimeException exception) {
            synchronizePreviewProfileAlert(exception.getMessage());
            throw new IllegalStateException(
                    "运行期对象存储巡检无法取得有效数据库预览图 profile；未修改任何引用",
                    exception);
        }

        var photos = jdbc.sql("""
                SELECT id, object_key, content_type, thumbnail_object_key, thumbnail_size, version
                FROM photo
                WHERE deleted=0 AND status IN ('AVAILABLE', 'ARCHIVED')
                """)
                .query((rs, rowNum) -> new PhotoObject(
                        rs.getLong("id"),
                        rs.getString("object_key"),
                        rs.getString("content_type"),
                        rs.getString("thumbnail_object_key"),
                        rs.getObject("thumbnail_size", Long.class),
                        rs.getInt("version")))
                .list();

        int missing = 0;
        int updated = 0;
        boolean mainHeadComplete = true;
        List<Long> missingPhotoIds = new ArrayList<>();
        List<Long> repairPhotoIds = new ArrayList<>();
        List<String> headFailures = new ArrayList<>();
        for (PhotoObject photo : photos) {
            Optional<ObjectStorageService.ObjectInfo> main;
            try {
                main = exactObject(photo.objectKey());
            } catch (RuntimeException exception) {
                mainHeadComplete = false;
                headFailures.add(photo.id() + "(成品)");
                log.error("HEAD 成品图失败，保留数据库记录并跳过本照片的预览修复: photoId={}, objectKey={}",
                        photo.id(), photo.objectKey(), exception);
                continue;
            }
            if (main.isEmpty()) {
                log.warn("对象存储对账发现成品图缺失，保留数据库记录等待人工核对: photoId={}, objectKey={}",
                        photo.id(), photo.objectKey());
                missing++;
                missingPhotoIds.add(photo.id());
                continue;
            }

            if (photo.thumbnailObjectKey() == null) {
                if (photo.thumbnailSize() == null) {
                    repairPhotoIds.add(photo.id());
                } else if (clearInvalidThumbnail(photo, expected)) {
                    repairPhotoIds.add(photo.id());
                    updated++;
                }
                continue;
            }

            Optional<ObjectStorageService.ObjectInfo> thumbnail;
            try {
                // Every preview is checked by an exact HEAD. A bucket listing
                // never proves MIME type or the persisted generation profile.
                thumbnail = exactObject(photo.thumbnailObjectKey());
            } catch (RuntimeException exception) {
                headFailures.add(photo.id() + "(预览)");
                log.error("HEAD 预览图失败，保留原引用等待下次巡检: photoId={}, objectKey={}",
                        photo.id(), photo.thumbnailObjectKey(), exception);
                continue;
            }

            boolean healthy = thumbnail.isPresent()
                    && photo.thumbnailSize() != null
                    && thumbnail.get().size() == photo.thumbnailSize()
                    && thumbnail.get().size() <= MAX_PREVIEW_BYTES
                    && supportedPreviewContentType(thumbnail.get().contentType())
                    && expected.matches(thumbnail.get(), thumbnail.get().contentType());
            if (healthy) {
                continue;
            }

            if (clearInvalidThumbnail(photo, expected)) {
                log.warn("对象存储对账确认预览图缺失、大小、MIME 或 profile 元数据不匹配，"
                                + "已按数据库 profile CAS 清空引用并请求修复: photoId={}, objectKey={}",
                        photo.id(), photo.thumbnailObjectKey());
                repairPhotoIds.add(photo.id());
                updated++;
            }
        }
        synchronizeMissingObjectAlert(missingPhotoIds, mainHeadComplete);
        synchronizeHeadFailureAlert(headFailures);
        if (!repairPhotoIds.isEmpty()) {
            try {
                events.publishEvent(new PreviewRepairRequestedEvent(repairPhotoIds, expected));
            } catch (RuntimeException exception) {
                // The null metadata is durable, so the next reconciliation can
                // retry even if the async preview executor is temporarily full.
                log.error("提交预览图修复任务失败，将由后续对账重试", exception);
            }
        }
        if (missing > 0 || updated > 0 || !repairPhotoIds.isEmpty()) {
            log.info("对象存储对账完成：发现缺失成品图 {} 张，清空无效预览引用 {} 张，请求修复预览 {} 张",
                    missing, updated, repairPhotoIds.size());
        }
        return new ReconciliationResult(photos.size(), missing, updated);
    }

    private void synchronizeMissingObjectAlert(List<Long> missingPhotoIds, boolean mainHeadComplete) {
        try {
            if (missingPhotoIds.isEmpty()) {
                if (!mainHeadComplete) {
                    // An unknown HEAD result cannot prove that a previously
                    // reported missing object has recovered.
                    return;
                }
                jdbc.sql("""
                        UPDATE admin_alert
                        SET resolved=1, resolved_at=CURRENT_TIMESTAMP
                        WHERE type='PHOTO_STORAGE_OBJECT_MISSING' AND resolved=0
                        """).update();
                return;
            }

            String examples = missingPhotoIds.stream().limit(20)
                    .map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
            String message = "对象存储对账发现 " + missingPhotoIds.size()
                    + " 张成品图缺失；数据库记录已保留，请先核对存储挂载、Bucket 和对象路径。"
                    + " 示例 photoId：" + examples;
            Long existing = jdbc.sql("""
                    SELECT id FROM admin_alert
                    WHERE type='PHOTO_STORAGE_OBJECT_MISSING' AND resolved=0
                    ORDER BY id DESC LIMIT 1
                    """).query(Long.class).optional().orElse(null);
            if (existing == null) {
                jdbc.sql("""
                        INSERT INTO admin_alert
                            (type, message, resource_type, resolved, created_at)
                        VALUES
                            ('PHOTO_STORAGE_OBJECT_MISSING', :message, 'STORAGE', false,
                             CURRENT_TIMESTAMP)
                        """).param("message", message).update();
            } else {
                jdbc.sql("UPDATE admin_alert SET message=:message WHERE id=:id")
                        .param("message", message).param("id", existing).update();
            }
        } catch (RuntimeException exception) {
            log.warn("写入对象存储对账管理员告警失败", exception);
        }
    }

    private Optional<ObjectStorageService.ObjectInfo> exactObject(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return Optional.empty();
        }
        return storage.find(objectKey);
    }

    private boolean clearInvalidThumbnail(PhotoObject photo, PreviewProfile expected) {
        String sql;
        if (photo.thumbnailObjectKey() == null) {
            sql = """
                    UPDATE photo
                    SET thumbnail_object_key=NULL, thumbnail_size=NULL,
                        version=version+1, updated_at=CURRENT_TIMESTAMP
                    WHERE id=:id AND deleted=0 AND version=:version
                      AND thumbnail_object_key IS NULL AND thumbnail_size=:thumbnailSize
                      AND EXISTS (
                          SELECT 1 FROM preview_setting ps
                          WHERE ps.id=1 AND ps.compression_ratio=:profileRatio
                            AND ps.generator_fingerprint=:profileGenerator
                      )
                    """;
        } else if (photo.thumbnailSize() == null) {
            sql = """
                    UPDATE photo
                    SET thumbnail_object_key=NULL, thumbnail_size=NULL,
                        version=version+1, updated_at=CURRENT_TIMESTAMP
                    WHERE id=:id AND deleted=0 AND version=:version
                      AND thumbnail_object_key=:thumbnailObjectKey AND thumbnail_size IS NULL
                      AND EXISTS (
                          SELECT 1 FROM preview_setting ps
                          WHERE ps.id=1 AND ps.compression_ratio=:profileRatio
                            AND ps.generator_fingerprint=:profileGenerator
                      )
                    """;
        } else {
            sql = """
                    UPDATE photo
                    SET thumbnail_object_key=NULL, thumbnail_size=NULL,
                        version=version+1, updated_at=CURRENT_TIMESTAMP
                    WHERE id=:id AND deleted=0 AND version=:version
                      AND thumbnail_object_key=:thumbnailObjectKey AND thumbnail_size=:thumbnailSize
                      AND EXISTS (
                          SELECT 1 FROM preview_setting ps
                          WHERE ps.id=1 AND ps.compression_ratio=:profileRatio
                            AND ps.generator_fingerprint=:profileGenerator
                      )
                    """;
        }
        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("id", photo.id())
                .param("version", photo.version())
                .param("profileRatio", expected.compressionRatio())
                .param("profileGenerator", expected.generatorFingerprint());
        if (photo.thumbnailObjectKey() != null) {
            statement = statement.param("thumbnailObjectKey", photo.thumbnailObjectKey());
        }
        if (photo.thumbnailSize() != null) {
            statement = statement.param("thumbnailSize", photo.thumbnailSize());
        }
        return statement.update() == 1;
    }

    /**
     * The preview format follows the finished object's real bytes, so a preview is
     * judged by its own MIME rather than by {@code photo.content_type}. That column
     * describes the source and is not trustworthy — legacy migration copies the old
     * system's {@code mime_type} verbatim and falls back to
     * {@code application/octet-stream} — so deriving the expectation from it would
     * clear a perfectly good preview reference on every hourly pass.
     */
    private boolean supportedPreviewContentType(String contentType) {
        return "image/jpeg".equals(contentType) || "image/png".equals(contentType);
    }

    private void synchronizePreviewProfileAlert(String error) {
        try {
            if (error == null) {
                jdbc.sql("""
                        UPDATE admin_alert
                        SET resolved=1, resolved_at=CURRENT_TIMESTAMP
                        WHERE type='PREVIEW_PROFILE_INVALID' AND resolved=0
                        """).update();
                return;
            }
            String message = "运行期预览图 profile 无效，OSS 巡检已停止且没有清空任何预览引用："
                    + error;
            Long existing = jdbc.sql("""
                    SELECT id FROM admin_alert
                    WHERE type='PREVIEW_PROFILE_INVALID' AND resolved=0
                    ORDER BY id DESC LIMIT 1
                    """).query(Long.class).optional().orElse(null);
            if (existing == null) {
                jdbc.sql("""
                        INSERT INTO admin_alert
                            (type, message, resource_type, resolved, created_at)
                        VALUES
                            ('PREVIEW_PROFILE_INVALID', :message, 'STORAGE', false,
                             CURRENT_TIMESTAMP)
                        """).param("message", message).update();
            } else {
                jdbc.sql("UPDATE admin_alert SET message=:message WHERE id=:id")
                        .param("message", message).param("id", existing).update();
            }
        } catch (RuntimeException exception) {
            log.warn("同步预览图 profile 管理员告警失败", exception);
        }
    }

    private void synchronizeHeadFailureAlert(List<String> failures) {
        try {
            if (failures.isEmpty()) {
                jdbc.sql("""
                        UPDATE admin_alert
                        SET resolved=1, resolved_at=CURRENT_TIMESTAMP
                        WHERE type='PHOTO_STORAGE_HEAD_FAILED' AND resolved=0
                        """).update();
                return;
            }

            String examples = failures.stream().limit(20)
                    .collect(java.util.stream.Collectors.joining(", "));
            String message = "对象存储巡检有 " + failures.size()
                    + " 个精确 HEAD 请求失败；数据库引用均已保留，请检查网络、权限和 OSS 状态。"
                    + " 示例 photoId（对象类型）：" + examples;
            Long existing = jdbc.sql("""
                    SELECT id FROM admin_alert
                    WHERE type='PHOTO_STORAGE_HEAD_FAILED' AND resolved=0
                    ORDER BY id DESC LIMIT 1
                    """).query(Long.class).optional().orElse(null);
            if (existing == null) {
                jdbc.sql("""
                        INSERT INTO admin_alert
                            (type, message, resource_type, resolved, created_at)
                        VALUES
                            ('PHOTO_STORAGE_HEAD_FAILED', :message, 'STORAGE', false,
                             CURRENT_TIMESTAMP)
                        """).param("message", message).update();
            } else {
                jdbc.sql("UPDATE admin_alert SET message=:message WHERE id=:id")
                        .param("message", message).param("id", existing).update();
            }
        } catch (RuntimeException exception) {
            log.warn("同步对象存储 HEAD 失败管理员告警失败", exception);
        }
    }

    private record PhotoObject(Long id, String objectKey, String contentType,
                               String thumbnailObjectKey, Long thumbnailSize,
                               int version) {
    }

    public record ReconciliationResult(int checked, int missing, int updated) {
    }
}
