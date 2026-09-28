package cn.photolib.registration;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.ratelimit.ClientAddress;
import cn.photolib.common.ratelimit.FixedWindowRateLimiter;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * 匿名提交注册申请的进程内限流，按客户端地址计数。和其他匿名接口一样只是纵深防御：
 * 地址取自 {@code remoteAddr}、内网地址放行，理由见 {@link ClientAddress}。
 *
 * <p>注册码本身是 80 比特的随机串，猜不出来；这里挡的是拿着一枚真码批量灌申请、
 * 把名额和管理员的审核队列一起占满。</p>
 */
@Component
public class RegistrationRateLimiter {
    static final int MAX_TRACKED_KEYS = 4_096;
    static final int LIMIT = 10;
    static final Duration WINDOW = Duration.ofMinutes(10);

    private final FixedWindowRateLimiter limiter;

    public RegistrationRateLimiter(Clock clock) {
        this.limiter = new FixedWindowRateLimiter(clock, MAX_TRACKED_KEYS);
    }

    public void requireAllowed(String remoteAddress) {
        String key = ClientAddress.normalize(remoteAddress);
        if (key == null) return;
        if (!limiter.tryAcquire(key, LIMIT, WINDOW)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "提交过于频繁，请稍后再试");
        }
    }
}
