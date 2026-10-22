package cn.photolib.registration;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.ratelimit.ClientAddress;
import cn.photolib.common.ratelimit.FixedWindowRateLimiter;
import cn.photolib.common.ratelimit.SharedAddressAllowance;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * 匿名提交注册申请的进程内限流，按客户端地址计数。和其他匿名接口一样只是纵深防御：
 * 地址来源和内网地址放行的理由见 {@link ClientAddress}。{@link #LIMIT} 是一位访客的额度，
 * 按 IP 计数时由 {@link SharedAddressAllowance} 放大（开学季一整栋宿舍楼的人同时注册）。
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
    private final SharedAddressAllowance allowance;

    public RegistrationRateLimiter(Clock clock, SharedAddressAllowance allowance) {
        this.limiter = new FixedWindowRateLimiter(clock, MAX_TRACKED_KEYS);
        this.allowance = allowance;
    }

    public void requireAllowed(String remoteAddress) {
        String key = ClientAddress.normalize(remoteAddress);
        if (key == null) return;
        if (!limiter.tryAcquire(key, allowance.perAddress(LIMIT), WINDOW)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "提交过于频繁，请稍后再试");
        }
    }
}
