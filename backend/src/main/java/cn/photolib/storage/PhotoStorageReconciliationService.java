package cn.photolib.storage;

import cn.photolib.photo.PreviewRepairRequestedEvent;
import cn.photolib.photo.PreviewMaintenanceLock;
import cn.photolib.photo.PreviewProfile;
import cn.photolib.photo.PreviewProfilePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Observes gallery records against object storage without deleting business
 * records. Missing or stale preview references are invalidated with a
 * compare-and-set update and handed to the preview repair pipeline.
 *
 * <p>一次巡检分三段：
 * <ol>
 *   <li>持 {@link PreviewMaintenanceLock} 取运行期预览 profile、读出待查照片（很快）；</li>
 *   <li><b>不持锁</b>，以有界并发对每张照片做精确 HEAD——这一段只读对象存储、不写库，
 *       以前串行逐个 HEAD、全程持锁，7,899 个对象要 7 分钟，期间预览图定向修复只能干等；</li>
 *   <li>再持锁，按观察到的 key/size/version 做 CAS 清空、同步告警、发布修复事件。</li>
 * </ol>
 * HEAD 和 CAS 之间预览被别人换掉时，version 或 profile 对不上，CAS 不生效——和以前一样安全。
 *
 * <p>判定规则不变：不删除任何记录；成品图缺失只告警；HEAD 异常保留引用并告警；
 * 预览一律精确 HEAD（列举拿不到 MIME 和 profile 元数据）。
 *
 * <p>调度见 {@link PhotoStorageReconciliationJob}：每天一次全量，白天每小时只查有变化的照片。
 */
@Service
public class PhotoStorageReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(PhotoStorageReconciliationService.class);
    private static final long MAX_PREVIEW_BYTES = 20L * 1024 * 1024;

    private final ObjectStorageService storage;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final PreviewProfilePolicy previewProfiles;
    private final PreviewMaintenanceLock maintenanceLock;
    private final int headConcurrency;

    /** 串行 HEAD、就在调用线程上执行。测试用它让 HEAD 的副作用落在测试事务里。 */
    public PhotoStorageReconciliationService(ObjectStorageService storage, JdbcClient jdbc,
                                             ApplicationEventPublisher events,
                                             PreviewProfilePolicy previewProfiles,
                                             PreviewMaintenanceLock maintenanceLock) {
        this(storage, jdbc, events, previewProfiles, maintenanceLock, 1);
    }

    @Autowired
    public PhotoStorageReconciliationService(ObjectStorageService storage, JdbcClient jdbc,
                                             ApplicationEventPublisher events,
                                             PreviewProfilePolicy previewProfiles,
                                             PreviewMaintenanceLock maintenanceLock,
                                             @Value("${photolib.storage-reconciliation.concurrency:8}")
                                             int headConcurrency) {
        if (headConcurrency < 1 || headConcurrency > 64) {
            throw new IllegalArgumentException("STORAGE_RECONCILIATION_CONCURRENCY 必须在 1 到 64 之间");
        }
        this.storage = storage;
        this.jdbc = jdbc;
        this.events = events;
        this.previewProfiles = previewProfiles;
        this.maintenanceLock = maintenanceLock;
        this.headConcurrency = headConcurrency;
    }

    /** 全量巡检：所有可见照片。 */
    public ReconciliationResult reconcile() {
        return run(null);
    }

    /**
     * 增量巡检：只查 {@code updated_at} 不早于 {@code since} 的照片（新上传、换过预览的）。
     * 没被查到的照片状态未知，所以增量巡检<b>只会新开告警、不会把已有告警标为已解决</b>，
     * 也不会改写已有告警的内容——那是全量巡检的事。
     */
    public ReconciliationResult reconcileChangedSince(LocalDateTime since) {
        if (since == null) throw new IllegalArgumentException("since 不能为空");
        return run(since);
    }

    /** 数据库当前时间。增量巡检的水位线用库里的时钟，和 {@code updated_at} 同源。 */
    public LocalDateTime databaseNow() {
        return jdbc.sql("SELECT CURRENT_TIMESTAMP").query(LocalDateTime.class).single();
    }

    private ReconciliationResult run(LocalDateTime changedSince) {
        boolean full = changedSince == null;
        Snapshot snapshot = maintenanceLock.exclusively(() -> snapshot(changedSince));
        List<Observation> observations = observe(snapshot.photos(), snapshot.expected());
        return maintenanceLock.exclusively(() -> apply(snapshot, observations, full));
    }

    private Snapshot snapshot(LocalDateTime changedSince) {
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

        String sql = """
                SELECT id, object_key, content_type, thumbnail_object_key, thumbnail_size, version
                FROM photo
                WHERE deleted=0 AND status IN ('AVAILABLE', 'ARCHIVED')
                """ + (changedSince == null ? "" : " AND updated_at >= :since");
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        if (changedSince != null) statement = statement.param("since", changedSince);
        List<PhotoObject> photos = statement
                .query((rs, rowNum) -> new PhotoObject(
                        rs.getLong("id"),
                        rs.getString("object_key"),
                        rs.getString("content_type"),
                        rs.getString("thumbnail_object_key"),
                        rs.getObject("thumbnail_size", Long.class),
                        rs.getInt("version")))
                .list();
        return new Snapshot(expected, photos);
    }

    /** HEAD 阶段：只读对象存储，不碰数据库，可以并发。结果顺序与 {@code photos} 一致。 */
    private List<Observation> observe(List<PhotoObject> photos, PreviewProfile expected) {
        if (headConcurrency == 1 || photos.size() <= 1) {
            return photos.stream().map(photo -> observe(photo, expected)).toList();
        }
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(headConcurrency, photos.size()), runnable -> {
            Thread thread = new Thread(runnable, "storage-reconcile-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<CompletableFuture<Observation>> futures = photos.stream()
                    .map(photo -> CompletableFuture.supplyAsync(() -> observe(photo, expected), pool))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            pool.shutdownNow();
        }
    }

    private Observation observe(PhotoObject photo, PreviewProfile expected) {
        Optional<ObjectStorageService.ObjectInfo> main;
        try {
            main = exactObject(photo.objectKey());
        } catch (RuntimeException exception) {
            log.error("HEAD 成品图失败，保留数据库记录并跳过本照片的预览修复: photoId={}, objectKey={}",
                    photo.id(), photo.objectKey(), exception);
            return new Observation(photo, Verdict.MAIN_HEAD_FAILED);
        }
        if (main.isEmpty()) {
            log.warn("对象存储对账发现成品图缺失，保留数据库记录等待人工核对: photoId={}, objectKey={}",
                    photo.id(), photo.objectKey());
            return new Observation(photo, Verdict.MAIN_MISSING);
        }

        if (photo.thumbnailObjectKey() == null) {
            return new Observation(photo, photo.thumbnailSize() == null
                    ? Verdict.PREVIEW_ABSENT : Verdict.PREVIEW_INVALID);
        }

        Optional<ObjectStorageService.ObjectInfo> thumbnail;
        try {
            // Every preview is checked by an exact HEAD. A bucket listing
            // never proves MIME type or the persisted generation profile.
            thumbnail = exactObject(photo.thumbnailObjectKey());
        } catch (RuntimeException exception) {
            log.error("HEAD 预览图失败，保留原引用等待下次巡检: photoId={}, objectKey={}",
                    photo.id(), photo.thumbnailObjectKey(), exception);
            return new Observation(photo, Verdict.PREVIEW_HEAD_FAILED);
        }

        boolean healthy = thumbnail.isPresent()
                && photo.thumbnailSize() != null
                && thumbnail.get().size() == photo.thumbnailSize()
                && thumbnail.get().size() <= MAX_PREVIEW_BYTES
                && supportedPreviewContentType(thumbnail.get().contentType())
                && expected.matches(thumbnail.get(), thumbnail.get().contentType());
        return new Observation(photo, healthy ? Verdict.HEALTHY : Verdict.PREVIEW_INVALID);
    }

    /** 写库阶段：持锁，逐张 CAS，最后统一同步告警和发布修复事件。 */
    private ReconciliationResult apply(Snapshot snapshot, List<Observation> observations, boolean full) {
        PreviewProfile expected = snapshot.expected();
        int missing = 0;
        int updated = 0;
        boolean mainHeadComplete = true;
        List<Long> missingPhotoIds = new ArrayList<>();
        List<Long> repairPhotoIds = new ArrayList<>();
        List<String> headFailures = new ArrayList<>();
        for (Observation observation : observations) {
            PhotoObject photo = observation.photo();
            switch (observation.verdict()) {
                case HEALTHY -> {
                }
                case MAIN_HEAD_FAILED -> {
                    mainHeadComplete = false;
                    headFailures.add(photo.id() + "(成品)");
                }
                case MAIN_MISSING -> {
                    missing++;
                    missingPhotoIds.add(photo.id());
                }
                case PREVIEW_HEAD_FAILED -> headFailures.add(photo.id() + "(预览)");
                case PREVIEW_ABSENT -> repairPhotoIds.add(photo.id());
                case PREVIEW_INVALID -> {
                    if (clearInvalidThumbnail(photo, expected)) {
                        if (photo.thumbnailObjectKey() != null) {
                            log.warn("对象存储对账确认预览图缺失、大小、MIME 或 profile 元数据不匹配，"
                                            + "已按数据库 profile CAS 清空引用并请求修复: photoId={}, objectKey={}",
                                    photo.id(), photo.thumbnailObjectKey());
                        }
                        repairPhotoIds.add(photo.id());
                        updated++;
                    }
                }
            }
        }
        synchronizeMissingObjectAlert(missingPhotoIds, mainHeadComplete, full);
        synchronizeHeadFailureAlert(headFailures, full);
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
            log.info("对象存储{}对账完成：发现缺失成品图 {} 张，清空无效预览引用 {} 张，请求修复预览 {} 张",
                    full ? "" : "增量", missing, updated, repairPhotoIds.size());
        }
        return new ReconciliationResult(observations.size(), missing, updated);
    }

    private void synchronizeMissingObjectAlert(List<Long> missingPhotoIds, boolean mainHeadComplete,
                                               boolean full) {
        try {
            if (missingPhotoIds.isEmpty()) {
                // 增量巡检没查的照片状态未知，不能据此判定"缺失已恢复"。
                if (!full || !mainHeadComplete) {
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
            } else if (full) {
                // 增量巡检只看到一部分照片，不覆盖全量巡检写下的完整统计。
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
     * A preview is judged by its own MIME rather than by {@code photo.content_type}.
     * That column describes the source and is not trustworthy — legacy migration
     * copies the old system's {@code mime_type} verbatim and falls back to
     * {@code application/octet-stream} — so deriving the expectation from it would
     * clear a perfectly good preview reference on every hourly pass. Previews are
     * re-encoded into one container regardless of the source, so the only MIME a
     * current preview can have is {@link PreviewProfile#PREVIEW_CONTENT_TYPE}.
     */
    private boolean supportedPreviewContentType(String contentType) {
        return PreviewProfile.PREVIEW_CONTENT_TYPE.equals(contentType);
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

    private void synchronizeHeadFailureAlert(List<String> failures, boolean full) {
        try {
            if (failures.isEmpty()) {
                if (!full) return;
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
            } else if (full) {
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

    private record Snapshot(PreviewProfile expected, List<PhotoObject> photos) {
    }

    private enum Verdict {
        HEALTHY,
        MAIN_HEAD_FAILED,
        MAIN_MISSING,
        /** 没有预览引用、也没有记录大小：只请求修复，不清空。 */
        PREVIEW_ABSENT,
        PREVIEW_HEAD_FAILED,
        /** 预览缺失或元数据不符：CAS 清空引用后请求修复。 */
        PREVIEW_INVALID
    }

    private record Observation(PhotoObject photo, Verdict verdict) {
    }

    public record ReconciliationResult(int checked, int missing, int updated) {
    }
}
