package cn.photolib.share;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 清掉过期的分享访问会话。
 *
 * <p>纯粹是打扫：过期会话在 {@link ProjectShareService#resolveGuest} 那里本来就被拒，
 * 留着只是让表一直长。删链接时会话已经跟着删了，这里处理的是"没人再来、自然过期"的那些。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredShareSessionCleanupJob {
    private final JdbcClient jdbc;
    private final Clock clock;

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Shanghai")
    public void purge() {
        int removed = jdbc.sql("DELETE FROM project_share_session WHERE expires_at <= :now")
                .param("now", LocalDateTime.now(clock))
                .update();
        if (removed > 0) log.info("清理过期分享访问会话 {} 条", removed);
    }
}
