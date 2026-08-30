package cn.photolib.doc;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.ratelimit.ClientAddress;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 公开文档接口的限速。
 *
 * <p>限速在这里是有实际代价的防护：正文放在对象存储里，一次不受限的遍历会把
 * 每篇文档变成一次真实的 OSS 读取。用例覆盖的是"到额度就拒"、"过窗口就恢复"，
 * 以及"共享地址一律放行"这三条——最后一条最容易被误当成 bug 改掉。</p>
 */
class DocRateLimiterTests {
    private static final String CLIENT = "203.0.113.7";

    @Test
    void requestsAreRefusedOnceTheWindowQuotaIsUsedUp() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-30T10:00:00Z"));
        DocRateLimiter limiter = new DocRateLimiter(clock);

        for (int attempt = 0; attempt < DocRateLimiter.Action.PUBLIC_DOCUMENT.limit(); attempt++) {
            limiter.requireAllowed(DocRateLimiter.Action.PUBLIC_DOCUMENT, CLIENT);
        }
        assertThatThrownBy(() -> limiter.requireAllowed(DocRateLimiter.Action.PUBLIC_DOCUMENT, CLIENT))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RATE_LIMITED);

        // 额度按动作分开：正文用光了，插图和目录不受影响。
        limiter.requireAllowed(DocRateLimiter.Action.PUBLIC_ASSET, CLIENT);
        limiter.requireAllowed(DocRateLimiter.Action.PUBLIC_TREE, CLIENT);
    }

    @Test
    void aNewWindowStartsCleanAndTheKeyIsReleased() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-30T10:00:00Z"));
        DocRateLimiter limiter = new DocRateLimiter(clock);

        for (int attempt = 0; attempt < DocRateLimiter.Action.PUBLIC_TREE.limit(); attempt++) {
            limiter.requireAllowed(DocRateLimiter.Action.PUBLIC_TREE, CLIENT);
        }
        assertThat(limiter.trackedKeyCount()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(11));
        limiter.requireAllowed(DocRateLimiter.Action.PUBLIC_TREE, CLIENT);
        // 过期的键被清掉又重新计一次，键数不会随时间无限增长。
        assertThat(limiter.trackedKeyCount()).isEqualTo(1);
    }

    @Test
    void differentClientsDoNotShareAQuota() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-30T10:00:00Z"));
        DocRateLimiter limiter = new DocRateLimiter(clock);

        for (int attempt = 0; attempt < DocRateLimiter.Action.PUBLIC_TREE.limit(); attempt++) {
            limiter.requireAllowed(DocRateLimiter.Action.PUBLIC_TREE, CLIENT);
        }
        limiter.requireAllowed(DocRateLimiter.Action.PUBLIC_TREE, "198.51.100.4");
        assertThat(limiter.trackedKeyCount()).isEqualTo(2);
    }

    @Test
    void nonPublicAddressesFailOpenInsteadOfThrottlingEveryoneTogether() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-30T10:00:00Z"));
        DocRateLimiter limiter = new DocRateLimiter(clock);

        // 反向代理后面 remoteAddr 是代理地址，所有访客会共用一个键。
        // 那种情况下限速只会变成一次全站性的误伤，所以这里刻意放行，
        // 由网关的分布式限流负责——不要"修"掉这条。
        for (String shared : new String[]{"127.0.0.1", "10.0.0.8", "192.168.1.5", "::1", "不是地址"}) {
            assertThat(ClientAddress.normalize(shared)).isNull();
            for (int attempt = 0; attempt < DocRateLimiter.Action.PUBLIC_TREE.limit() + 5; attempt++) {
                limiter.requireAllowed(DocRateLimiter.Action.PUBLIC_TREE, shared);
            }
        }
        assertThat(limiter.trackedKeyCount()).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
