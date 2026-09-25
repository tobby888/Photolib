package cn.photolib.notification;

import cn.photolib.storage.ObjectStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 收走没人用的消息图片。
 *
 * <p>消息图片先上传、后引用：编辑器贴图时就传上来了，用户放弃发送、或者删掉那张图再发，
 * 图片就永远留在桶里。上传只对 {@code MESSAGE_SEND} 开放时量很小，反馈开放给所有成员之后
 * 就得有人打扫。</p>
 *
 * <ul>
 *   <li><b>引用只可能在三处</b>：{@code user_notification.content_html}（管理消息）、
 *       {@code feedback.content_html} 与 {@code feedback_reply.content_html}（反馈）。
 *       别的富文本（精选征集要求等）用的是 description-images，不走这张表。</li>
 *   <li><b>按上传时间给宽限期</b>（默认 7 天）：还在编辑框里、没发出去的图不能被当成孤儿。
 *       只扫刚过宽限期的 {@link #SCAN_WINDOW} 这一段，见那里的说明。</li>
 *   <li><b>删行时再核一遍引用</b>：选出候选到真正删除之间若有人发了引用它的消息，
 *       条件删除落空，这张图保留。行删成功才删对象；对象删不掉只多一个孤儿对象，记录已收走。</li>
 * </ul>
 */
@Slf4j
@Component
public class OrphanMessageImageCleanupJob {
    /** 每轮最多收多少张。 */
    static final int LIMIT = 200;
    /**
     * 只看「刚过宽限期」这一段时间里上传的图。被引用过的图永远有引用（通知和反馈都不删），
     * 不设下界的话它们每晚都要拿 LIKE 把三张表扫一遍，开销随历史无限增长。
     * 每天跑一次，30 天的窗口足够让偶尔失败的一轮在后面补上。
     */
    static final Duration SCAN_WINDOW = Duration.ofDays(30);

    private static final String UNREFERENCED = """
            NOT EXISTS (SELECT 1 FROM user_notification n
                        WHERE n.content_html LIKE CONCAT('%/api/v1/notifications/images/', {id}, '%'))
            AND NOT EXISTS (SELECT 1 FROM feedback f
                            WHERE f.content_html LIKE CONCAT('%/api/v1/notifications/images/', {id}, '%'))
            AND NOT EXISTS (SELECT 1 FROM feedback_reply r
                            WHERE r.content_html LIKE CONCAT('%/api/v1/notifications/images/', {id}, '%'))
            """;

    private final ObjectStorageService storage;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration grace;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public OrphanMessageImageCleanupJob(
            ObjectStorageService storage,
            JdbcClient jdbc,
            Clock clock,
            @Value("${photolib.message-image-cleanup.grace-days:7}") long graceDays,
            @Value("${photolib.message-image-cleanup.enabled:true}") boolean enabled) {
        if (graceDays < 1) {
            throw new IllegalArgumentException("photolib.message-image-cleanup.grace-days 至少为 1");
        }
        this.storage = storage;
        this.jdbc = jdbc;
        this.clock = clock;
        this.grace = Duration.ofDays(graceDays);
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 45 3 * * *", zone = "Asia/Shanghai")
    public void scheduledCleanup() {
        if (!enabled) return;
        try {
            int removed = cleanup();
            if (removed > 0) log.info("清理无人引用的消息图片 {} 张", removed);
        } catch (RuntimeException exception) {
            log.error("清理无人引用的消息图片时出错，下一轮再试", exception);
        }
    }

    public int cleanup() {
        if (!running.compareAndSet(false, true)) return 0;
        try {
            int removed = 0;
            for (Candidate candidate : loadCandidates()) {
                int deleted = jdbc.sql("DELETE FROM message_image WHERE id = :id AND "
                                + UNREFERENCED.replace("{id}", ":id"))
                        .param("id", candidate.id())
                        .update();
                if (deleted != 1) continue;
                removed++;
                try {
                    storage.delete(candidate.objectKey());
                } catch (RuntimeException exception) {
                    log.warn("删除消息图片对象失败，记录已收走：id={} key={}",
                            candidate.id(), candidate.objectKey(), exception);
                }
            }
            return removed;
        } finally {
            running.set(false);
        }
    }

    private List<Candidate> loadCandidates() {
        LocalDateTime before = LocalDateTime.now(clock).minus(grace);
        return jdbc.sql("SELECT m.id, m.object_key FROM message_image m"
                        + " WHERE m.created_at <= :before AND m.created_at > :after AND "
                        + UNREFERENCED.replace("{id}", "m.id")
                        + " ORDER BY m.created_at LIMIT :limit")
                .param("before", before)
                .param("after", before.minus(SCAN_WINDOW))
                .param("limit", LIMIT)
                .query((rs, row) -> new Candidate(rs.getString("id"), rs.getString("object_key")))
                .list();
    }

    private record Candidate(String id, String objectKey) {
    }
}
