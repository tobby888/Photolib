package cn.photolib.photo;

import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 收走"传了一半就走"留下的临时对象。
 *
 * <p>在这个任务之前，这些东西没有任何回收：{@link OriginalCleanupJob} 只删
 * {@code original_delete_after} 到期的原图，而那一列**只在处理成功时才写**；
 * {@code PhotoStorageReconciliationService} 只对 AVAILABLE/ARCHIVED 的照片做对账。
 * 于是签了票据却没 complete 的单张、传完却没 complete 的压缩包、解包出来却没被
 * 整理的条目，会在对象存储和本地盘上无限期堆着——上传链接让这条路对站外的人也
 * 敞开了，一个 1.5 GB 的包能在本地摊开 10 GiB。</p>
 *
 * <p>四条规矩：</p>
 * <ol>
 *   <li><b>只按库里记着的精确键删，从不枚举桶。</b> 枚举一遍 OSS 既贵又慢，而且
 *       分不清"正在传"和"已放弃"。代价是库里没记过的键（编辑裁切的来源对象，
 *       {@code PhotoImageEditService} 刻意不落库）不在这个任务的覆盖范围内。</li>
 *   <li><b>等直传地址过期之后再动手，还要再宽限一段。</b> 对象删早了没用——客户端
 *       手里那条还没过期的 PUT 可以把它原样传回来，而那时库里已经没有任何一行指着
 *       它了（招募那条链路在 Flyway V28 踩过这个坑）。宽限是给"最后一刻 PUT 成功、
 *       随后才调 complete"的那些请求留的余地。</li>
 *   <li><b>处理失败的照片不碰。</b> 它同样停在 {@code UPLOADING}，但带着
 *       {@code failure_reason}，页面上正显示着让人重试，而那个临时对象是这张图
 *       唯一的副本。只挑 {@code failure_reason IS NULL} 的，也就是"从没 complete 过"。</li>
 *   <li><b>每轮有上限，删完就把键从库里抹掉。</b> 先删对象再清列，清列用条件更新：
 *       中途有人把这一行推进到下一个状态，就让那一次更新落空，不去覆盖它。</li>
 * </ol>
 *
 * <p>触发有三处：启动后一次（把历史遗留收走）、定时一轮，以及每次签发上传票据时
 * 捅一下（{@link #nudge()}）。上传触发是有意的——活动当天量最大、产生的半成品也
 * 最多；但它**节流且异步**，不能让访客的上传等一次存储删除，也不能让几百次上传
 * 变成几百轮扫描。只靠上传触发则不行：活动结束没人再传的时候，恰恰是残留最多的
 * 时刻，所以定时那一路必须留着。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AbandonedUploadCleanupJob {
    /** 每轮每一类最多处理多少行。够把积压慢慢啃完，又不会一轮占住存储太久。 */
    static final int LIMIT = 200;
    /** 直传地址过期之后再等这么久才删，给"刚传完、还没来得及 complete"留余地。 */
    static final Duration GRACE = Duration.ofHours(1);
    /**
     * 解包出来却一直没人整理的批次留多久。这一类没有直传地址可依据，而站内成员
     * 填元数据可能隔一晚上再回来，所以给得比上面宽得多。
     */
    static final Duration EXTRACTED_RETENTION = Duration.ofHours(24);
    /** 上传触发的最小间隔。活动当天上传是连发的，没有节流就是几百轮重复扫描。 */
    static final Duration NUDGE_INTERVAL = Duration.ofMinutes(5);

    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final PhotoProcessingWorkspace workspace;
    private final JdbcClient jdbc;
    private final Clock clock;

    private final AtomicReference<LocalDateTime> lastNudge = new AtomicReference<>();
    /** 同一时刻只跑一轮：定时、启动和上传触发三路都可能撞在一起。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOnStartup() {
        runQuietly();
    }

    @Scheduled(fixedDelayString = "${photolib.photo.abandoned-cleanup-delay-ms:1800000}",
            initialDelayString = "${photolib.photo.abandoned-cleanup-initial-delay-ms:120000}")
    public void scheduledCleanup() {
        runQuietly();
    }

    /**
     * 每次签发上传票据时调一下：检查有没有还没清掉的失效文件。
     *
     * <p>节流到 {@link #NUDGE_INTERVAL} 一次，并且异步跑——上传请求不该为别人留下的
     * 垃圾买单。异常一律吞掉：清理失败绝不能让正在进行的上传失败。</p>
     */
    @Async
    public void nudge() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime previous = lastNudge.get();
        if (previous != null && previous.plus(NUDGE_INTERVAL).isAfter(now)) return;
        if (!lastNudge.compareAndSet(previous, now)) return;
        runQuietly();
    }

    private void runQuietly() {
        try {
            CleanupResult result = cleanup();
            if (result.total() > 0) {
                log.info("清理未完成上传留下的临时文件：单张 {}、压缩包 {}、批次条目 {}、解包文件 {}",
                        result.photos(), result.archives(), result.items(), result.extracted());
            }
        } catch (RuntimeException exception) {
            log.error("清理未完成上传的临时文件时出错，下一轮再试", exception);
        }
    }

    public CleanupResult cleanup() {
        if (!running.compareAndSet(false, true)) return CleanupResult.NONE;
        try {
            LocalDateTime deletableBefore = LocalDateTime.now(clock).minus(GRACE);
            return new CleanupResult(
                    cleanPhotos(deletableBefore),
                    cleanArchives(deletableBefore),
                    cleanItems(deletableBefore),
                    cleanExtracted(LocalDateTime.now(clock).minus(EXTRACTED_RETENTION)));
        } finally {
            running.set(false);
        }
    }

    /**
     * 签了票据却从没 complete 的单张。行本身也一并软删：对象没了之后它永远走不到
     * 下一步，留着只会让"上传中"的列表越积越长。
     */
    private int cleanPhotos(LocalDateTime deletableBefore) {
        List<KeyedRow<Long>> rows = jdbc.sql("""
                        SELECT id, original_object_key FROM photo
                        WHERE deleted = 0 AND status = 'UPLOADING' AND failure_reason IS NULL
                          AND original_object_key IS NOT NULL
                          AND upload_url_expires_at IS NOT NULL AND upload_url_expires_at <= :before
                        ORDER BY upload_url_expires_at
                        LIMIT :limit
                        """)
                .param("before", deletableBefore).param("limit", LIMIT)
                .query((rs, row) -> new KeyedRow<>(rs.getLong("id"), rs.getString("original_object_key")))
                .list();
        int cleaned = 0;
        for (KeyedRow<Long> row : rows) {
            if (!deleteObject(row.objectKey(), "未完成上传的原图", String.valueOf(row.id()))) continue;
            cleaned += jdbc.sql("""
                    UPDATE photo SET original_object_key = NULL, deleted = 1,
                        failure_reason = '上传未完成，临时文件已清理', updated_at = :now
                    WHERE id = :id AND status = 'UPLOADING' AND failure_reason IS NULL
                      AND original_object_key = :objectKey
                    """).param("id", row.id()).param("objectKey", row.objectKey())
                    .param("now", LocalDateTime.now(clock)).update();
        }
        return cleaned;
    }

    /** 传完却没 complete 的压缩包。批次标成 FAILED，界面上才看得出它没成。 */
    private int cleanArchives(LocalDateTime deletableBefore) {
        List<KeyedRow<String>> rows = jdbc.sql("""
                        SELECT id, archive_object_key FROM photo_upload_batch
                        WHERE status = 'UPLOADING' AND archive_object_key IS NOT NULL
                          AND upload_url_expires_at IS NOT NULL AND upload_url_expires_at <= :before
                        ORDER BY upload_url_expires_at
                        LIMIT :limit
                        """)
                .param("before", deletableBefore).param("limit", LIMIT)
                .query((rs, row) -> new KeyedRow<>(rs.getString("id"), rs.getString("archive_object_key")))
                .list();
        int cleaned = 0;
        for (KeyedRow<String> row : rows) {
            if (!deleteObject(row.objectKey(), "未完成上传的压缩包", row.id())) continue;
            cleaned += jdbc.sql("""
                    UPDATE photo_upload_batch SET archive_object_key = NULL, status = 'FAILED',
                        failure_reason = '压缩包没有传完，临时文件已清理', updated_at = :now
                    WHERE id = :id AND status = 'UPLOADING' AND archive_object_key = :objectKey
                    """).param("id", row.id()).param("objectKey", row.objectKey())
                    .param("now", LocalDateTime.now(clock)).update();
        }
        return cleaned;
    }

    /** FILES 模式里签了地址却没传上来的条目。 */
    private int cleanItems(LocalDateTime deletableBefore) {
        List<KeyedRow<Long>> rows = jdbc.sql("""
                        SELECT id, temp_object_key FROM photo_upload_item
                        WHERE status = 'UPLOADING' AND temp_object_key IS NOT NULL
                          AND upload_url_expires_at IS NOT NULL AND upload_url_expires_at <= :before
                        ORDER BY upload_url_expires_at
                        LIMIT :limit
                        """)
                .param("before", deletableBefore).param("limit", LIMIT)
                .query((rs, row) -> new KeyedRow<>(rs.getLong("id"), rs.getString("temp_object_key")))
                .list();
        int cleaned = 0;
        for (KeyedRow<Long> row : rows) {
            if (!deleteObject(row.objectKey(), "未完成上传的批次条目", String.valueOf(row.id()))) continue;
            cleaned += jdbc.sql("""
                    UPDATE photo_upload_item SET status = 'FAILED',
                        failure_reason = '这一张没有传完，临时文件已清理', updated_at = :now
                    WHERE id = :id AND status = 'UPLOADING' AND temp_object_key = :objectKey
                    """).param("id", row.id()).param("objectKey", row.objectKey())
                    .param("now", LocalDateTime.now(clock)).update();
        }
        return cleaned;
    }

    /**
     * 解包出来、却一直没人整理成照片的条目：本地盘上的那些文件。
     *
     * <p>删文件的同时必须把条目标成 FAILED——否则之后真有人来整理，落出来的照片会
     * 指着一个已经不存在的本地路径，处理时才失败。</p>
     */
    private int cleanExtracted(LocalDateTime abandonedBefore) {
        List<ExtractedRow> rows = jdbc.sql("""
                        SELECT i.id, i.temp_local_path FROM photo_upload_item i
                        JOIN photo_upload_batch b ON b.id = i.batch_id
                        WHERE i.status = 'WAITING_METADATA' AND i.temp_local_path IS NOT NULL
                          AND b.updated_at <= :before
                        ORDER BY i.id
                        LIMIT :limit
                        """)
                .param("before", abandonedBefore).param("limit", LIMIT)
                .query((rs, row) -> new ExtractedRow(rs.getLong("id"), rs.getString("temp_local_path")))
                .list();
        int cleaned = 0;
        for (ExtractedRow row : rows) {
            try {
                Path path = workspace.resolveStoredPath(row.localPath());
                if (Files.exists(path)) workspace.deleteBatchFile(path);
            } catch (RuntimeException exception) {
                log.warn("删除解包临时文件失败，留到下一轮: itemId={}, path={}",
                        row.id(), row.localPath(), exception);
                continue;
            }
            cleaned += jdbc.sql("""
                    UPDATE photo_upload_item SET temp_local_path = NULL, status = 'FAILED',
                        failure_reason = '解包后一直没有整理，临时文件已清理', updated_at = :now
                    WHERE id = :id AND status = 'WAITING_METADATA' AND temp_local_path = :path
                    """).param("id", row.id()).param("path", row.localPath())
                    .param("now", LocalDateTime.now(clock)).update();
        }
        return cleaned;
    }

    /** 删不掉就原样留着：键还在库里，下一轮会再试，绝不能"删失败也把键抹掉"。 */
    private boolean deleteObject(String objectKey, String what, String owner) {
        try {
            storage.delete(objectKey);
            return true;
        } catch (RuntimeException exception) {
            log.warn("删除{}失败，保留记录待下一轮重试: owner={}, objectKey={}",
                    what, owner, objectKey, exception);
            return false;
        }
    }

    /**
     * 签票据时写进 {@code upload_url_expires_at} 的值。必须和上面比较用的是同一只时钟：
     * 写的一方要是用 JVM 默认时区的 {@code LocalDateTime.now()}，宿主不在 Asia/Shanghai 时
     * （CI 的 UTC runner）刚签出的票据就已经"过期"八小时，下一次 {@link #nudge()} 会把
     * 还没传完的压缩包删掉。
     */
    public LocalDateTime uploadUrlExpiresAt() {
        return LocalDateTime.now(clock).plus(storageProperties.uploadUrlTtl());
    }

    private record KeyedRow<T>(T id, String objectKey) {}

    private record ExtractedRow(Long id, String localPath) {}

    public record CleanupResult(int photos, int archives, int items, int extracted) {
        static final CleanupResult NONE = new CleanupResult(0, 0, 0, 0);

        public int total() {
            return photos + archives + items + extracted;
        }
    }
}
