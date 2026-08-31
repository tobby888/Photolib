package cn.photolib.doc;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.ratelimit.ClientAddress;
import cn.photolib.common.ratelimit.FixedWindowRateLimiter;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * 公开文档接口的速率限制。
 *
 * <p>文档正文对未登录访客开放，等于把一份可被脚本遍历的内容放在了公网上：
 * 树接口给出全部链接，正文接口逐篇取全文。没有限制的话，一次爬取就会把
 * 每篇文档的对象存储读取放大成一轮真实的 OSS 流量与账单。所以三个公开
 * 接口各自按客户端地址计数，正文那一条最紧。</p>
 *
 * <p>已登录的编辑接口不走这里：它们要 {@code DOC_MANAGE}，被授权的人本来就
 * 屈指可数，再加一层限制只会妨碍正常写作。</p>
 *
 * <p>键的来源和失败开放的语义见 {@link ClientAddress}——反向代理后面
 * {@code remoteAddr} 是代理地址，此时本限制器让行，由网关限流负责。</p>
 */
@Component
public class DocRateLimiter {
    static final int MAX_TRACKED_KEYS = 4_096;

    private final FixedWindowRateLimiter limiter;

    public DocRateLimiter(Clock clock) {
        this.limiter = new FixedWindowRateLimiter(clock, MAX_TRACKED_KEYS);
    }

    public void requireAllowed(Action action, String remoteAddress) {
        if (action == null) throw new IllegalArgumentException("rate-limit action is required");
        String remoteKey = ClientAddress.normalize(remoteAddress);
        if (remoteKey == null) return;
        if (!limiter.tryAcquire(action.name() + '|' + remoteKey, action.limit(), action.window())) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "文档访问过于频繁，请稍后重试");
        }
    }

    int trackedKeyCount() {
        return limiter.trackedKeyCount();
    }

    public enum Action {
        /** 目录树。一次访问拉一遍就够，正常浏览不会反复请求。 */
        PUBLIC_TREE(60, Duration.ofMinutes(10)),
        /** 正文。人正常读文档十分钟内点不满 120 篇，脚本遍历会立刻撞上。 */
        PUBLIC_DOCUMENT(120, Duration.ofMinutes(10)),
        /** 图片。一篇图多的文档就可能有几十张，所以额度必须比正文宽得多。 */
        PUBLIC_ASSET(600, Duration.ofMinutes(10)),
        /** PDF 文件。单个响应可能有几十 MiB，所以额度比正文还紧。 */
        PUBLIC_FILE(60, Duration.ofMinutes(10));

        private final int limit;
        private final Duration window;

        Action(int limit, Duration window) {
            this.limit = limit;
            this.window = window;
        }

        int limit() {
            return limit;
        }

        Duration window() {
            return window;
        }
    }
}
