package cn.photolib.featured;

import cn.photolib.featured.mapper.FeaturedCollectionMapper;
import cn.photolib.featured.model.FeaturedCloseReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 到达截止时间后关闭精选并生成 Word 文档。
 *
 * <p>每分钟扫一次，而不是给每份精选注册一个定时器：应用重启后不需要恢复任何调度状态，
 * 停机期间错过的截止时间也会在下一轮补上。</p>
 *
 * <p>关闭本身是带状态条件的更新（{@code closeIfPublished}），所以这里和部长手动截止
 * 并发时不会重复关闭，也不会重复触发文档生成。单份精选失败不影响同一轮里的其余精选。</p>
 */
@Slf4j
@Component
// 共享测试上下文里关掉自动截止，避免定时任务在用例之间改写已提交的精选状态
// （与每日数据库备份任务同样的处理）。
@ConditionalOnProperty(name = "photolib.featured.deadline-enabled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class FeaturedDeadlineJob {
    /** 一轮最多处理的份数，避免积压时单次占用过久。剩下的下一轮继续。 */
    private static final int BATCH = 50;

    private final FeaturedCollectionMapper mapper;
    private final FeaturedCollectionService service;

    @Scheduled(fixedDelayString = "${photolib.featured.deadline-check-delay-ms:60000}",
            initialDelayString = "${photolib.featured.deadline-check-initial-delay-ms:30000}")
    public void closeExpired() {
        List<Long> due;
        try {
            due = mapper.findDueForClose(LocalDateTime.now(), BATCH);
        } catch (RuntimeException failure) {
            log.warn("查询到期好图精选失败", failure);
            return;
        }
        for (Long id : due) {
            try {
                if (service.closeInternal(id, null, FeaturedCloseReason.DEADLINE)) {
                    log.info("好图精选到达截止时间，已自动截止 collectionId={}", id);
                }
            } catch (RuntimeException failure) {
                log.warn("好图精选自动截止失败 collectionId={}", id, failure);
            }
        }
    }
}
