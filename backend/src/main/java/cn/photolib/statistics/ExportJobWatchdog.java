package cn.photolib.statistics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 把中断的导出任务判为失败。
 *
 * <p>导出跑在进程内的异步线程上，任务请求是一个内存里的事件：服务重启时，处理中的任务
 * 和还在队列里的任务都会就此消失，库里却永远停在 PROCESSING / PENDING。前端的进度弹窗
 * 不再设固定的等待上限（大批量打包本来就可能要好几分钟），就靠这里给这种任务一个结局。</p>
 *
 * <ul>
 *   <li>处理中超过 {@link #STALLED_PROCESSING} 没有更新：打包时每读一块都会刷新
 *       {@code updated_at}（见 {@link ExportProgress}），十分钟毫无动静只能是线程没了。</li>
 *   <li>排队超过 {@link #STALLED_PENDING} 还没开始：线程池满时会排队，所以给得宽很多。</li>
 * </ul>
 */
@Slf4j
@Component
public class ExportJobWatchdog {
    static final Duration STALLED_PROCESSING = Duration.ofMinutes(10);
    static final Duration STALLED_PENDING = Duration.ofMinutes(30);
    static final String INTERRUPTED_MESSAGE = "打包中断了（服务重启或长时间没有进展），请重新发起下载";

    private final JdbcClient jdbc;

    public ExportJobWatchdog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void failStalledJobs() {
        int failed = failStalled(LocalDateTime.now());
        if (failed > 0) log.warn("{} 个导出任务长时间没有进展，已判为失败", failed);
    }

    int failStalled(LocalDateTime now) {
        return jdbc.sql("""
                UPDATE export_job SET status='FAILED', error_message=:message, updated_at=:now
                WHERE (status='PROCESSING' AND updated_at < :processingBefore)
                   OR (status='PENDING' AND updated_at < :pendingBefore)
                """)
                .param("message", INTERRUPTED_MESSAGE)
                .param("now", now)
                .param("processingBefore", now.minus(STALLED_PROCESSING))
                .param("pendingBefore", now.minus(STALLED_PENDING))
                .update();
    }
}
