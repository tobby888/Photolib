package cn.photolib.photo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 接手停在 {@code PROCESSING}、却已经没有任务在处理的照片。
 *
 * <p>{@code complete} 把照片置为 {@code PROCESSING}，事务提交后才把处理任务交给
 * {@code photoProcessingExecutor}。之后只有 {@code PhotoProcessingService.process} 会把它
 * 推进到 {@code AVAILABLE} 或打回 {@code UPLOADING}。任务半路没了，这一行就永远停在
 * "处理中"，而且别的清理任务（§2.21.1、§2.27、§2.29）都只看 {@code UPLOADING}。
 * 任务半路没了的几种情况：JVM 重启（部署、崩溃，包括原生组件段错误）、执行器队列满了
 * 拒收，以及处理过程中出现 Error 并且连标失败都没做完。</p>
 *
 * <p>判定"卡住"要同时满足两条：</p>
 * <ul>
 *   <li><b>不在本进程的处理池里</b>（{@link PhotoProcessingService#isInFlight}）。处理池的
 *       队列能放 1000 个任务，活动当天排队超过任何固定阈值都很正常，只看时间会把排着队
 *       的照片再提交一遍。</li>
 *   <li><b>{@code updated_at} 早于 {@link #staleAfter}</b>（默认 15 分钟）。这是第二道保护：
 *       覆盖 complete 提交事务和交给执行器之间那一小段，以及万一有第二个实例在跑的情况。</li>
 * </ul>
 *
 * <p>处理方式：</p>
 * <ul>
 *   <li><b>重新提交</b>：重提交次数（{@code processing_recoveries}，Flyway V53）没到
 *       {@link #maxResubmits} 时，先按观察到的 version 做 CAS 认领（次数 +1、version +1、
 *       刷新 {@code updated_at}，同一事务写 audit_log），提交之后再交给处理池。认领落空
 *       说明这一行刚被别的路径推进过，那条路径赢。{@code process()} 自己会处理源文件已经
 *       不在的情况（批量那条路的本地文件、单张那条路的原图），所以无论哪条上传路径都可以
 *       安全地重提交。</li>
 *   <li><b>标为处理失败</b>：到了上限就不再重提交，通过
 *       {@link PhotoProcessingService#failStalled} 打回 {@code UPLOADING} 并写上原因，批次
 *       条目和批次计数同步更新；之后由 §2.29 的保留期接手。<b>次数必须落库</b>：把 JVM
 *       带走的往往就是这张图本身，计数放在内存里的话，每次重启都会再提交、再崩一次。</li>
 * </ul>
 */
@Slf4j
@Component
public class StalledProcessingRecoveryJob {
    /** 每轮最多处理多少行。 */
    static final int LIMIT = 200;
    static final String AUDIT_ACTION = "PHOTO_PROCESSING_RECOVERY";
    static final String FAILURE_REASON =
            "这张图片的处理被中断（服务重启或处理异常），自动重试后仍未完成。"
                    + "请重新上传；如果反复失败，请联系管理员。";

    private final PhotoProcessingService processing;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration staleAfter;
    private final int maxResubmits;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public StalledProcessingRecoveryJob(
            PhotoProcessingService processing,
            JdbcClient jdbc,
            TransactionTemplate transactions,
            Clock clock,
            @Value("${photolib.photo.stalled-processing.stale-after-minutes:15}") long staleAfterMinutes,
            @Value("${photolib.photo.stalled-processing.max-resubmits:1}") int maxResubmits,
            @Value("${photolib.photo.stalled-processing.enabled:true}") boolean enabled) {
        if (staleAfterMinutes < 1) {
            throw new IllegalArgumentException("photolib.photo.stalled-processing.stale-after-minutes 至少为 1");
        }
        if (maxResubmits < 0) {
            throw new IllegalArgumentException("photolib.photo.stalled-processing.max-resubmits 不能为负数");
        }
        this.processing = processing;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
        this.staleAfter = Duration.ofMinutes(staleAfterMinutes);
        this.maxResubmits = maxResubmits;
        this.enabled = enabled;
    }

    /** 启动后收一次：上一个进程留下的卡住照片，不用等第一次定时。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        if (!enabled) return;
        recoverQuietly();
    }

    @Scheduled(fixedDelayString = "${photolib.photo.stalled-processing.delay-ms:300000}",
            initialDelayString = "${photolib.photo.stalled-processing.initial-delay-ms:300000}")
    public void scheduledRecovery() {
        if (!enabled) return;
        recoverQuietly();
    }

    private void recoverQuietly() {
        try {
            RecoveryResult result = recover();
            if (result.resubmitted() > 0 || result.failed() > 0) {
                log.warn("接手卡在处理中的图片：重新提交 {} 张，标为处理失败 {} 张",
                        result.resubmitted(), result.failed());
            }
        } catch (RuntimeException exception) {
            log.error("接手卡在处理中的图片时出错，下一轮再试", exception);
        }
    }

    public RecoveryResult recover() {
        if (!running.compareAndSet(false, true)) return RecoveryResult.NONE;
        try {
            int resubmitted = 0;
            int failed = 0;
            for (StalledPhoto photo : loadCandidates()) {
                if (processing.isInFlight(photo.id())) continue;
                if (photo.recoveries() < maxResubmits) {
                    if (claimForResubmit(photo)) {
                        resubmitted++;
                        log.warn("图片停在处理中且没有任务在处理，重新提交（第 {} 次）: photoId={}",
                                photo.recoveries() + 1, photo.id());
                        processing.submit(photo.id()).exceptionally(exception -> {
                            log.error("重新提交的图片处理任务异常结束: photoId={}", photo.id(), exception);
                            return null;
                        });
                    }
                } else if (fail(photo)) {
                    failed++;
                    log.warn("图片停在处理中且已重试 {} 次，标为处理失败: photoId={}",
                            photo.recoveries(), photo.id());
                }
            }
            return new RecoveryResult(resubmitted, failed);
        } finally {
            running.set(false);
        }
    }

    private List<StalledPhoto> loadCandidates() {
        return jdbc.sql("""
                        SELECT id, version, processing_recoveries
                        FROM photo
                        WHERE deleted = 0 AND status = 'PROCESSING'
                          AND updated_at <= :before
                        ORDER BY id
                        LIMIT :limit
                        """)
                .param("before", LocalDateTime.now(clock).minus(staleAfter))
                .param("limit", LIMIT)
                .query((rs, row) -> new StalledPhoto(
                        rs.getLong("id"), rs.getInt("version"), rs.getInt("processing_recoveries")))
                .list();
    }

    /** CAS 认领并在同一事务里写审计；提交之后调用方才把任务交给处理池。 */
    private boolean claimForResubmit(StalledPhoto photo) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int updated = jdbc.sql("""
                    UPDATE photo
                    SET processing_recoveries = processing_recoveries + 1,
                        version = version + 1, updated_at = :now
                    WHERE id = :id AND deleted = 0 AND status = 'PROCESSING' AND version = :version
                    """)
                    .param("id", photo.id())
                    .param("version", photo.version())
                    .param("now", LocalDateTime.now(clock))
                    .update();
            if (updated != 1) return false;
            audit(photo, "RESUBMITTED");
            return true;
        }));
    }

    private boolean fail(StalledPhoto photo) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (!processing.failStalled(photo.id(), photo.version(), FAILURE_REASON)) return false;
            audit(photo, "MARKED_FAILED");
            return true;
        }));
    }

    private void audit(StalledPhoto photo, String outcome) {
        jdbc.sql("""
                INSERT INTO audit_log
                    (operator_id, action, resource_type, resource_id, detail_json)
                VALUES
                    (NULL, :action, 'PHOTO', :resourceId, :detail)
                """)
                .param("action", AUDIT_ACTION)
                .param("resourceId", String.valueOf(photo.id()))
                .param("detail", "{\"outcome\":\"" + outcome + "\",\"previousRecoveries\":"
                        + photo.recoveries() + ",\"maxResubmits\":" + maxResubmits
                        + ",\"staleAfterMinutes\":" + staleAfter.toMinutes() + "}")
                .update();
    }

    private record StalledPhoto(long id, int version, int recoveries) {
    }

    public record RecoveryResult(int resubmitted, int failed) {
        static final RecoveryResult NONE = new RecoveryResult(0, 0);
    }
}
