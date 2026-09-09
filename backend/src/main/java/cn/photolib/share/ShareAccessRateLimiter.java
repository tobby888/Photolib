package cn.photolib.share;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.ratelimit.ClientAddress;
import cn.photolib.common.ratelimit.FixedWindowRateLimiter;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 分享链接匿名入口的进程内限速，和招募、文档那两处同源（{@link FixedWindowRateLimiter}）。
 *
 * <p>和它们不同的是这里**每个动作都记两个计数器**：一个带客户端地址，一个只带
 * 链接 token。原因是 {@link ClientAddress} 对回环/内网地址一律失败开放（反向代理
 * 后面 {@code remoteAddr} 是代理地址），只按地址记的话密码校验在生产部署里等于
 * 没有限速——而密码正是这条链路上唯一一道可猜的门。只按 token 记的那一路把一条
 * 链接的猜测总量压住，代价是同一条链接上的正常访客会互相影响，所以只对写和密码
 * 这类低频动作用紧的额度，读列表给的额度足够一个班的人同时看图。</p>
 *
 * <p>这仍然只是纵深防御：进程内状态重启即失，分布式限流由网关负责。</p>
 */
@Component
public class ShareAccessRateLimiter {
    static final int MAX_TRACKED_KEYS = 4_096;
    private static final Pattern TOKEN = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

    private final FixedWindowRateLimiter limiter;

    public ShareAccessRateLimiter(Clock clock) {
        this.limiter = new FixedWindowRateLimiter(clock, MAX_TRACKED_KEYS);
    }

    public void requireAllowed(Action action, String token, String remoteAddress) {
        if (action == null) throw new IllegalArgumentException("rate-limit action is required");
        String linkKey = normalizeToken(token);
        if (linkKey == null) return;
        String remoteKey = ClientAddress.normalize(remoteAddress);
        boolean allowed = limiter.tryAcquire(
                action.name() + "|LINK|" + linkKey, action.linkLimit(), action.window());
        if (remoteKey != null) {
            allowed &= limiter.tryAcquire(
                    action.name() + '|' + linkKey + '|' + remoteKey, action.addressLimit(), action.window());
        }
        if (!allowed) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "操作过于频繁，请稍后重试");
        }
    }

    int trackedKeyCount() {
        return limiter.trackedKeyCount();
    }

    private static String normalizeToken(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return TOKEN.matcher(normalized).matches() ? normalized : null;
    }

    public enum Action {
        /** 密码校验。额度最紧：这是唯一可以被猜的东西。 */
        PASSWORD(30, 10, Duration.ofMinutes(10)),
        /** 浏览图片列表。一条链接可能同时有一整个编辑部在看，额度给得宽。 */
        BROWSE(1_200, 240, Duration.ofMinutes(10)),
        /** 单张下载地址签发。每次都是一次真实的对象存储读取和账单。 */
        DOWNLOAD(600, 120, Duration.ofMinutes(10)),
        /** 打包下载：一次最多 200 张，成本远高于单张。 */
        BATCH_DOWNLOAD(60, 12, Duration.ofMinutes(10)),
        /** 标记/取消被引，会写进项目的采用记录。 */
        ADOPTION(300, 60, Duration.ofMinutes(10));

        private final int linkLimit;
        private final int addressLimit;
        private final Duration window;

        Action(int linkLimit, int addressLimit, Duration window) {
            this.linkLimit = linkLimit;
            this.addressLimit = addressLimit;
            this.window = window;
        }

        int linkLimit() {
            return linkLimit;
        }

        int addressLimit() {
            return addressLimit;
        }

        Duration window() {
            return window;
        }
    }
}
