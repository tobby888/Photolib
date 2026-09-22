package cn.photolib.photo;

import cn.photolib.storage.ObjectStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 给"处理失败"的上传一个保留期：到期之后软删记录，并收掉它留在对象存储里的原图。
 *
 * <p>另外两个清理任务都刻意不碰这类行，各有道理：
 * {@link AbandonedUploadCleanupJob} 只挑 {@code failure_reason IS NULL}——失败行的临时
 * 对象通常是那张图唯一的副本，页面上正提示上传者重试；
 * {@link MissingUploadObjectScanJob} 只删"原图确认不存在"的——而处理失败时原图多半
 * 好好地在桶里。结果是<b>原图还在、处理又失败</b>的行没有任何任务收走：它们永远挂在
 * 图库里（以前还显示成"上传中"），占着相册计数。超大渐进式 JPEG 就是这样一批批积下来的。</p>
 *
 * <p>这里换的是时间凭据：失败之后 {@link #retention} 这么久（默认 7 天）都没人重试，
 * 就当上传者已经放弃。</p>
 * <ul>
 *   <li><b>按 {@code updated_at} 计时。</b> 重试（{@code complete-upload}）会把这一行推回
 *       {@code PROCESSING} 并清空 {@code failure_reason}，再失败就是新的一次，重新计时。</li>
 *   <li><b>软删 + CAS + audit_log。</b> 按观察到的 version 置 {@code deleted=1}，与重试并发时
 *       让这次更新落空；原来的失败原因写进审计明细，行上的 {@code failure_reason} 换成
 *       人能看懂的"自动清理"说明。</li>
 *   <li><b>对象删在软删之后，best-effort。</b> 原图、成品图和缩略图的 key 都是这张照片
 *       独有的 UUID，不会被别的行引用；一张从没发布过的照片也不可能被采用。删不掉只多
 *       一个孤儿对象，记录已经收走。这是"原图不能删"那条规矩唯一的例外：这张图从没
 *       进过图库，保留期就是它的全部宽限。</li>
 * </ul>
 */
@Slf4j
@Component
public class FailedUploadRetentionJob {
    /** 每轮最多收多少行。 */
    static final int LIMIT = 200;
    static final String AUDIT_ACTION = "PHOTO_AUTO_CLEANUP";
    static final String CLEANED_REASON = "这张图片处理失败后长时间没有重新上传，记录已自动清理";

    private final ObjectStorageService storage;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration retention;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public FailedUploadRetentionJob(
            ObjectStorageService storage,
            JdbcClient jdbc,
            TransactionTemplate transactions,
            Clock clock,
            @Value("${photolib.photo.failed-upload-retention.retention-days:7}") long retentionDays,
            @Value("${photolib.photo.failed-upload-retention.enabled:true}") boolean enabled) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("photolib.photo.failed-upload-retention.retention-days 至少为 1");
        }
        this.storage = storage;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
        this.retention = Duration.ofDays(retentionDays);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${photolib.photo.failed-upload-retention.delay-ms:3600000}",
            initialDelayString = "${photolib.photo.failed-upload-retention.initial-delay-ms:600000}")
    public void scheduledCleanup() {
        if (!enabled) return;
        try {
            int cleaned = cleanup();
            if (cleaned > 0) log.info("收走处理失败且超过保留期的上传记录 {} 条", cleaned);
        } catch (RuntimeException exception) {
            log.error("清理处理失败的上传记录时出错，下一轮再试", exception);
        }
    }

    public int cleanup() {
        if (!running.compareAndSet(false, true)) return 0;
        try {
            int cleaned = 0;
            for (FailedUpload upload : loadCandidates()) {
                if (softDelete(upload)) {
                    cleaned++;
                    deleteQuietly(upload.id(), "原图", upload.originalObjectKey());
                    deleteQuietly(upload.id(), "成品图", upload.objectKey());
                    deleteQuietly(upload.id(), "缩略图", upload.thumbnailObjectKey());
                } else {
                    log.warn("软删除过期的失败上传未命中（并发变化），保留现状：photoId={}", upload.id());
                }
            }
            return cleaned;
        } finally {
            running.set(false);
        }
    }

    private List<FailedUpload> loadCandidates() {
        return jdbc.sql("""
                        SELECT id, original_object_key, object_key, thumbnail_object_key,
                               failure_reason, version
                        FROM photo
                        WHERE deleted = 0 AND status = 'UPLOADING'
                          AND failure_reason IS NOT NULL
                          AND updated_at <= :before
                        ORDER BY id
                        LIMIT :limit
                        """)
                .param("before", LocalDateTime.now(clock).minus(retention))
                .param("limit", LIMIT)
                .query((rs, row) -> new FailedUpload(
                        rs.getLong("id"),
                        rs.getString("original_object_key"),
                        rs.getString("object_key"),
                        rs.getString("thumbnail_object_key"),
                        rs.getString("failure_reason"),
                        rs.getInt("version")))
                .list();
    }

    private boolean softDelete(FailedUpload upload) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int updated = jdbc.sql("""
                    UPDATE photo
                    SET deleted = 1, failure_reason = :reason,
                        version = version + 1, updated_at = :now
                    WHERE id = :id AND deleted = 0 AND status = 'UPLOADING'
                      AND failure_reason IS NOT NULL AND version = :version
                    """)
                    .param("id", upload.id())
                    .param("version", upload.version())
                    .param("reason", CLEANED_REASON)
                    .param("now", LocalDateTime.now(clock))
                    .update();
            if (updated != 1) return false;
            jdbc.sql("""
                    INSERT INTO audit_log
                        (operator_id, action, resource_type, resource_id, detail_json)
                    VALUES
                        (NULL, :action, 'PHOTO', :resourceId, :detail)
                    """)
                    .param("action", AUDIT_ACTION)
                    .param("resourceId", String.valueOf(upload.id()))
                    .param("detail", "{\"reason\":\"FAILED_UPLOAD_EXPIRED\",\"retentionDays\":"
                            + retention.toDays() + ",\"failureReason\":\""
                            + jsonEscape(upload.failureReason()) + "\",\"originalObjectKey\":\""
                            + jsonEscape(upload.originalObjectKey()) + "\"}")
                    .update();
            return true;
        }));
    }

    private void deleteQuietly(long photoId, String what, String objectKey) {
        if (!StringUtils.hasText(objectKey)) return;
        try {
            storage.delete(objectKey);
        } catch (RuntimeException exception) {
            log.warn("失败上传的记录已软删除，但{}清理失败（photoId={}, objectKey={}），会留下一个孤儿对象",
                    what, photoId, objectKey, exception);
        }
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (char character : value.toCharArray()) {
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.toString();
    }

    private record FailedUpload(long id, String originalObjectKey, String objectKey,
                                String thumbnailObjectKey, String failureReason, int version) {
    }
}
