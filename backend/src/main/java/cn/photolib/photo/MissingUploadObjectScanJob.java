package cn.photolib.photo;

import cn.photolib.storage.ObjectStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 定时扫描停在 {@code UPLOADING} 的照片，把"只剩数据库记录、对象存储里根本没有
 * 这张图"的那些收掉。
 *
 * <p>这一类记录是{@link AbandonedUploadCleanupJob 按时间回收临时对象}那个任务
 * 留下的缺口。那个任务只挑 {@code failure_reason IS NULL} 的行——带着失败原因的
 * 行停在同一个状态，但它的临时对象通常是那张图唯一的副本，按时间猜不得。可
 * **处理失败之后临时对象并不总是还在**：批量上传那条路的原图是在处理过程中才上传的
 * （{@code PhotoProcessingService.process} 里的 {@code batchSource != null} 分支），
 * 处理在那之前就失败的话，{@code original_object_key} 指着的对象从来没有被写出去过。
 * 这些行于是永久留在库里，而且不止是"图库里一直显示上传中"：</p>
 *
 * <ul>
 *   <li>查重是按 {@code sha256} + {@code deleted=0} 做的（{@code PhotoService.createTicket}），
 *       所以这样一行会让**同一张图再也传不上来**——上传者看到的是"已经上传过该图片"，
 *       可那张图从来没进过相册。这是这个任务真正要解决的问题。</li>
 *   <li>这些行还会一直占着 {@code object_key} 的唯一索引和它所属选题的相册计数。</li>
 * </ul>
 *
 * <p>凭据只有一条，而且必须是**确认**的：对 {@code original_object_key} 做一次精确
 * HEAD（{@code storage.find}），只有确认返回"不存在"才算数。HEAD 抛异常一律保留记录
 * 并写管理员告警——不知道的结果永远不能拿来删东西，这与
 * {@code MissingObjectPhotoCleanupJob}（§2.15）是同一条原则，也和那里一样**从不枚举桶**。</p>
 *
 * <p>另外三条护栏：</p>
 * <ol>
 *   <li><b>等直传地址过期之后再加宽限才看。</b> 还没过期的行正常地"对象不存在"——
 *       客户端还没 PUT 而已。宽限是给"最后一刻 PUT 成功、随后才调 complete"留的余地。</li>
 *   <li><b>删之前先确认存储本身可信</b>（{@link #storageLooksHealthy()}）。Bucket、
 *       Endpoint 或挂载点配错时每一次 HEAD 都会"确认不存在"，这一轮的结论就全不作数。
 *       这里不能照抄 §2.15 的比例熔断：那边的候选集是全库已发布的照片，
 *       {@code missing == total} 意味着事故；而这里的候选集本来就只有寥寥几条卡住的
 *       上传，全部缺失恰恰是正常情况。</li>
 *   <li><b>只软删，不碰别的行的字节。</b> 记录按观察到的 version/status 做 CAS 置
 *       {@code deleted=1}，误判可以改回 {@code deleted=0} 恢复；同一事务写一条
 *       {@code audit_log}。软删之后才 best-effort 收掉这一行可能留下的成品图/缩略图
 *       对象——它们的 key 是这张照片独有的 UUID，不会被别的行引用。</li>
 * </ol>
 *
 * <p>没有取 {@code PreviewMaintenanceLock}：预览生成、对账和修复的候选集都是
 * {@code AVAILABLE}/{@code ARCHIVED}，与这里的 {@code UPLOADING} 不相交。也没有查
 * {@code adoption}：采用的前提是照片已经发布，一张从没 complete 过的照片不可能被采用。</p>
 */
@Slf4j
@Component
public class MissingUploadObjectScanJob {
    /** 每轮最多 HEAD 多少行。够把积压慢慢啃完，又不会一轮打出几千个请求。 */
    static final int LIMIT = 200;
    /** 直传地址过期之后再等这么久才认定"对象不存在"，与 {@link AbandonedUploadCleanupJob} 一致。 */
    static final Duration GRACE = Duration.ofHours(1);

    private static final String AUDIT_ACTION = "PHOTO_AUTO_CLEANUP";
    private static final String ALERT_HEAD_FAILED = "PHOTO_UPLOAD_SCAN_HEAD_FAILED";
    private static final String ALERT_ABORTED = "PHOTO_UPLOAD_SCAN_ABORTED";

    private final ObjectStorageService storage;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean enabled;

    /**
     * 上一轮扫到哪一行了。对象**还在**的行（单张上传处理失败，临时副本仍是唯一副本）
     * 每轮都会被重新 HEAD 一遍，只按 id 正序取前 {@link #LIMIT} 条的话，它们会一直堵在
     * 队头，后面新产生的垃圾永远轮不到。游标每轮往后推，取不满一页就绕回开头。
     */
    private final AtomicLong cursor = new AtomicLong(0);
    /** 同一时刻只跑一轮：启动那一次和定时那一路可能撞在一起。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MissingUploadObjectScanJob(
            ObjectStorageService storage,
            JdbcClient jdbc,
            TransactionTemplate transactions,
            Clock clock,
            @Value("${photolib.photo.missing-upload-scan.enabled:true}") boolean enabled) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
        this.enabled = enabled;
    }

    /** 启动后收一次改动之前积下来的存量，部署完立刻见效，不用等第一次定时。 */
    @EventListener(ApplicationReadyEvent.class)
    public void scanOnStartup() {
        if (!enabled) {
            log.info("卡在上传中的图片记录扫描已被配置关闭");
            return;
        }
        scanQuietly();
    }

    @Scheduled(fixedDelayString = "${photolib.photo.missing-upload-scan.delay-ms:900000}",
            initialDelayString = "${photolib.photo.missing-upload-scan.initial-delay-ms:300000}")
    public void scheduledScan() {
        if (!enabled) return;
        scanQuietly();
    }

    private void scanQuietly() {
        try {
            ScanResult result = scan();
            String summary = "扫描卡在上传中的图片记录：核对 {} 张，原图缺失 {} 张，已软删 {} 张，HEAD 失败 {} 个{}";
            Object[] fields = {result.checked(), result.missing(), result.deleted(),
                    result.headFailures(), result.aborted() ? "（本轮已中止，未删除任何记录）" : ""};
            // 什么都没发生的那一轮只记 DEBUG：处理失败但原图还在的行会一直留在候选集里，
            // 每 15 分钟一条 INFO 只会把日志刷满。
            if (result.missing() > 0 || result.headFailures() > 0) {
                log.info(summary, fields);
            } else if (result.checked() > 0) {
                log.debug(summary, fields);
            }
        } catch (RuntimeException exception) {
            log.error("扫描卡在上传中的图片记录时出错，下一轮再试", exception);
        }
    }

    /**
     * 跑一轮。{@code enabled=false} 时启动和定时两路都不会调它，但测试和将来可能的
     * 手工触发仍然可以直接调用。
     */
    public ScanResult scan() {
        if (!running.compareAndSet(false, true)) return ScanResult.NONE;
        try {
            return scanLocked();
        } finally {
            running.set(false);
        }
    }

    private ScanResult scanLocked() {
        List<StuckUpload> candidates = loadCandidates();
        if (candidates.isEmpty()) {
            // 这一页空了说明已经扫到尾，下一轮从头再来。
            cursor.set(0);
            synchronizeAlert(ALERT_HEAD_FAILED, null);
            synchronizeAlert(ALERT_ABORTED, null);
            return ScanResult.NONE;
        }
        cursor.set(candidates.size() < LIMIT ? 0 : candidates.get(candidates.size() - 1).id());

        List<StuckUpload> missing = new ArrayList<>();
        List<Long> headFailures = new ArrayList<>();
        for (StuckUpload upload : candidates) {
            Optional<ObjectStorageService.ObjectInfo> object;
            try {
                object = storage.find(upload.originalObjectKey());
            } catch (RuntimeException exception) {
                // 不知道的 HEAD 结果永远不能用来删记录。
                headFailures.add(upload.id());
                log.error("HEAD 上传原图失败，保留数据库记录等待下一轮: photoId={}, objectKey={}",
                        upload.id(), upload.originalObjectKey(), exception);
                continue;
            }
            if (object.isEmpty()) missing.add(upload);
        }
        synchronizeAlert(ALERT_HEAD_FAILED, headFailures.isEmpty() ? null
                : "扫描卡在上传中的图片时有 " + headFailures.size() + " 个精确 HEAD 请求失败；"
                + "相关数据库记录均已保留，请检查网络、权限和对象存储状态。示例 photoId："
                + examples(headFailures));

        if (missing.isEmpty()) {
            synchronizeAlert(ALERT_ABORTED, null);
            return new ScanResult(candidates.size(), 0, 0, headFailures.size(), false);
        }

        if (!storageLooksHealthy()) {
            String message = "扫描卡在上传中的图片时发现 " + missing.size()
                    + " 张的原图在对象存储中缺失，但同一时刻连一张已发布照片的成品图都读不到，"
                    + "说明存储侧不可信，本轮已中止且未删除任何记录。"
                    + "请先核对存储挂载、Bucket、Endpoint 和对象前缀配置。示例 photoId："
                    + examples(missing.stream().map(StuckUpload::id).toList());
            synchronizeAlert(ALERT_ABORTED, message);
            log.error("存储侧不可信，本轮扫描已中止，未删除任何记录：疑似缺失 {} 张", missing.size());
            return new ScanResult(candidates.size(), missing.size(), 0, headFailures.size(), true);
        }
        synchronizeAlert(ALERT_ABORTED, null);

        int deleted = 0;
        for (StuckUpload upload : missing) {
            if (softDelete(upload)) {
                deleted++;
                releaseLeftoverObjects(upload);
            } else {
                log.warn("软删除缺失原图的上传记录未命中（并发变化），保留现状：photoId={}", upload.id());
            }
        }
        return new ScanResult(candidates.size(), missing.size(), deleted, headFailures.size(), false);
    }

    /**
     * 候选集：还没删、停在 {@code UPLOADING}、直传地址过期加宽限之后仍然没有走到下一步的行。
     *
     * <p>{@code upload_url_expires_at} 为空的老行（V50 之前建的、回填没覆盖到的那些）退回
     * 用 {@code updated_at} 判断。{@code original_object_key} 为空的行不进候选：没有 key
     * 就没有可以 HEAD 的东西，而"确认不存在"是这个任务唯一的删除凭据。</p>
     */
    private List<StuckUpload> loadCandidates() {
        return jdbc.sql("""
                        SELECT id, original_object_key, object_key, thumbnail_object_key, version
                        FROM photo
                        WHERE deleted = 0 AND status = 'UPLOADING'
                          AND original_object_key IS NOT NULL
                          AND id > :cursor
                          AND COALESCE(upload_url_expires_at, updated_at) <= :before
                        ORDER BY id
                        LIMIT :limit
                        """)
                .param("cursor", cursor.get())
                .param("before", LocalDateTime.now(clock).minus(GRACE))
                .param("limit", LIMIT)
                .query((rs, row) -> new StuckUpload(
                        rs.getLong("id"),
                        rs.getString("original_object_key"),
                        rs.getString("object_key"),
                        rs.getString("thumbnail_object_key"),
                        rs.getInt("version")))
                .list();
    }

    /**
     * 存储侧还认得出自己的东西吗：挑最近发布的一张照片，HEAD 它的成品图。
     *
     * <p>连它都"确认不存在"，那这一轮所有的"不存在"都只说明配置错了，不是图真的没传上来。
     * 库里一张已发布的照片都没有时返回 true——没有可比的基准，而一个还没有任何成品图的
     * 库也没有什么可被误删的。</p>
     */
    private boolean storageLooksHealthy() {
        String sentinel = jdbc.sql("""
                        SELECT object_key FROM photo
                        WHERE deleted = 0 AND status = 'AVAILABLE' AND object_key IS NOT NULL
                        ORDER BY id DESC LIMIT 1
                        """).query(String.class).optional().orElse(null);
        if (!StringUtils.hasText(sentinel)) return true;
        try {
            return storage.find(sentinel).isPresent();
        } catch (RuntimeException exception) {
            log.error("HEAD 基准成品图失败，本轮扫描按存储不可信处理: objectKey={}", sentinel, exception);
            return false;
        }
    }

    /**
     * 按观察到的 version/status 做 CAS 软删，并在同一事务里写审计行。
     *
     * <p>{@code status} 保持 {@code UPLOADING} 不动（与 {@code PhotoService.performDelete}
     * 和 §2.15 的既有语义一致），但 {@code failure_reason} 改写成人能看懂的原因——运维翻到
     * 这一行时要能看出它是被自动收走的，而不是谁手点了删除。</p>
     */
    private boolean softDelete(StuckUpload upload) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int updated = jdbc.sql("""
                    UPDATE photo
                    SET deleted = 1, failure_reason = :reason,
                        version = version + 1, updated_at = :now
                    WHERE id = :id AND deleted = 0 AND status = 'UPLOADING'
                      AND version = :version AND original_object_key = :objectKey
                    """)
                    .param("id", upload.id())
                    .param("version", upload.version())
                    .param("objectKey", upload.originalObjectKey())
                    .param("reason", "这张图片没有真正传到对象存储，记录已自动清理")
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
                    .param("detail", "{\"reason\":\"UPLOAD_OBJECT_MISSING\",\"originalObjectKey\":\""
                            + jsonEscape(upload.originalObjectKey()) + "\",\"status\":\"UPLOADING\"}")
                    .update();
            return true;
        }));
    }

    /**
     * 原图本来就不在了，可能留下的只有处理过程中途写出去的成品图和缩略图。两个 key 都是
     * 这张照片独有的 UUID，不会被别的行引用。best-effort：记录已经软删，这里失败只是多
     * 留一个孤儿对象。
     */
    private void releaseLeftoverObjects(StuckUpload upload) {
        deleteQuietly(upload.id(), "成品图", upload.objectKey());
        deleteQuietly(upload.id(), "缩略图", upload.thumbnailObjectKey());
    }

    private void deleteQuietly(long photoId, String what, String objectKey) {
        if (!StringUtils.hasText(objectKey)) return;
        try {
            storage.delete(objectKey);
        } catch (RuntimeException exception) {
            log.warn("数据库记录已软删除，但{}清理失败（photoId={}, objectKey={}），会留下一个孤儿对象",
                    what, photoId, objectKey, exception);
        }
    }

    private String examples(List<Long> photoIds) {
        return photoIds.stream().limit(20).map(String::valueOf).collect(Collectors.joining(", "));
    }

    private String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 写入一条未解决的告警，或在 {@code message} 为空时把现有的那条标成已解决。
     *
     * <p>告警类型不与 {@code MissingObjectPhotoCleanupJob} 和
     * {@code PhotoStorageReconciliationService} 共用：共用会让几个任务互相覆盖状态
     * （§2.15 最后一条）。清理成功本身不写告警——活动之后留下几十条没传完的记录是常态，
     * 每轮都弹一条管理员告警只会把真正的事故淹掉；被收走的记录在 {@code audit_log} 里。</p>
     */
    private void synchronizeAlert(String type, String message) {
        try {
            if (message == null) {
                jdbc.sql("""
                        UPDATE admin_alert
                        SET resolved = 1, resolved_at = CURRENT_TIMESTAMP
                        WHERE type = :type AND resolved = 0
                        """).param("type", type).update();
                return;
            }
            Long existing = jdbc.sql("""
                    SELECT id FROM admin_alert
                    WHERE type = :type AND resolved = 0
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
                jdbc.sql("UPDATE admin_alert SET message = :message WHERE id = :id")
                        .param("message", message).param("id", existing).update();
            }
        } catch (RuntimeException exception) {
            log.warn("同步卡住上传扫描的管理员告警失败：type={}", type, exception);
        }
    }

    private record StuckUpload(long id, String originalObjectKey, String objectKey,
                               String thumbnailObjectKey, int version) {
    }

    public record ScanResult(int checked, int missing, int deleted, int headFailures,
                             boolean aborted) {
        static final ScanResult NONE = new ScanResult(0, 0, 0, 0, false);
    }
}
