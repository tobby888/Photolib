package cn.photolib.storage;

import cn.photolib.photo.model.PhotoStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Reconciles gallery records against object storage. Object storage is the
 * source of truth for file existence and file-level metadata.
 */
@Service
@RequiredArgsConstructor
public class PhotoStorageReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(PhotoStorageReconciliationService.class);

    private final ObjectStorageService storage;
    private final JdbcClient jdbc;

    @Scheduled(fixedDelay = 3600_000, initialDelay = 600_000)
    public void scheduledReconcile() {
        try {
            reconcile();
        } catch (Exception e) {
            log.error("定时对账任务执行失败", e);
        }
    }

    @Transactional
    public synchronized ReconciliationResult reconcile() {
        Map<String, ObjectStorageService.StoredObject> actual = new HashMap<>();
        storage.list("").forEach(object -> actual.put(object.objectKey(), object));

        var photos = jdbc.sql("""
                SELECT id, object_key, thumbnail_object_key, size, content_type
                FROM photo
                WHERE deleted=0 AND status IN ('AVAILABLE', 'ARCHIVED')
                """)
                .query((rs, rowNum) -> new PhotoObject(
                        rs.getLong("id"),
                        rs.getString("object_key"),
                        rs.getString("thumbnail_object_key"),
                        rs.getLong("size"),
                        rs.getString("content_type")))
                .list();

        int missing = 0;
        int updated = 0;
        for (PhotoObject photo : photos) {
            ObjectStorageService.StoredObject main = actual.get(photo.objectKey());
            if (photo.objectKey() == null || main == null) {
                jdbc.sql("""
                        UPDATE photo
                        SET status=:status, deleted=1, failure_reason=:reason,
                            version=version+1, updated_at=CURRENT_TIMESTAMP
                        WHERE id=:id AND deleted=0
                        """)
                        .param("status", PhotoStatus.DELETED.name())
                        .param("reason", "对象存储中的图片文件不存在")
                        .param("id", photo.id())
                        .update();
                missing++;
                continue;
            }

            String contentType = storage.stat(photo.objectKey()).contentType();
            boolean thumbnailMissing = photo.thumbnailObjectKey() != null
                    && !actual.containsKey(photo.thumbnailObjectKey());
            if (main.size() != photo.size()
                    || !java.util.Objects.equals(contentType, photo.contentType())
                    || thumbnailMissing) {
                jdbc.sql("""
                        UPDATE photo
                        SET size=:size, content_type=:contentType,
                            thumbnail_object_key=CASE WHEN :thumbnailMissing
                                THEN NULL ELSE thumbnail_object_key END,
                            version=version+1, updated_at=CURRENT_TIMESTAMP
                        WHERE id=:id AND deleted=0
                        """)
                        .param("size", main.size())
                        .param("contentType", contentType)
                        .param("thumbnailMissing", thumbnailMissing)
                        .param("id", photo.id())
                        .update();
                updated++;
            }
        }
        if (missing > 0 || updated > 0) {
            log.info("对象存储对账完成：标记缺失照片 {} 张，更新元数据 {} 张", missing, updated);
        }
        return new ReconciliationResult(photos.size(), missing, updated);
    }

    private record PhotoObject(Long id, String objectKey, String thumbnailObjectKey,
                               long size, String contentType) {
    }

    public record ReconciliationResult(int checked, int missing, int updated) {
    }
}
