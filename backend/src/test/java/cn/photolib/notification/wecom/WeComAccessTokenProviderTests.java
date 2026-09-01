package cn.photolib.notification.wecom;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeComAccessTokenProviderTests {
    private static final Duration REFRESH_AHEAD = Duration.ofMinutes(5);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
    private final AtomicInteger fetches = new AtomicInteger();

    @Test
    void token_shouldReuseCachedTokenInsteadOfCallingGettokenEveryTime() {
        WeComAccessTokenProvider provider = provider(Duration.ofSeconds(7200));

        assertThat(provider.token()).isEqualTo("token-1");
        assertThat(provider.token()).isEqualTo("token-1");
        clock.advance(Duration.ofMinutes(60));
        assertThat(provider.token()).isEqualTo("token-1");

        assertThat(fetches).hasValue(1);
    }

    @Test
    void token_shouldRefreshBeforeTheServerSideExpiry() {
        WeComAccessTokenProvider provider = provider(Duration.ofSeconds(7200));
        provider.token();

        // 距服务端过期还有 4 分钟：本地已判定过期，避免消息在飞行途中撞上过期。
        clock.advance(Duration.ofSeconds(7200).minus(Duration.ofMinutes(4)));

        assertThat(provider.token()).isEqualTo("token-2");
        assertThat(fetches).hasValue(2);
    }

    /**
     * 企业微信返回的有效期不比提前量长时，直接相减会得到负数，缓存永远判定为过期，
     * 于是每发一条消息就打一次 gettoken——正好撞上这个接口的频率限制。
     */
    @Test
    void token_whenExpiryIsShorterThanRefreshAhead_shouldStillCache() {
        WeComAccessTokenProvider provider = provider(Duration.ofMinutes(4));

        provider.token();
        clock.advance(Duration.ofMinutes(1));
        provider.token();

        assertThat(fetches).hasValue(1);
    }

    @Test
    void invalidate_shouldForceTheNextCallToFetchAFreshToken() {
        WeComAccessTokenProvider provider = provider(Duration.ofSeconds(7200));
        String rejected = provider.token();

        provider.invalidate(rejected);

        assertThat(provider.token()).isEqualTo("token-2");
        assertThat(fetches).hasValue(2);
    }

    /**
     * 并发投递时，别的线程可能已经换到新 token；这时再拿旧 token 去作废，
     * 会把刚拿到的好 token 一起丢掉，退化成"每次失败多打一次 gettoken"。
     */
    @Test
    void invalidate_withAnAlreadyReplacedToken_shouldKeepTheCurrentToken() {
        WeComAccessTokenProvider provider = provider(Duration.ofSeconds(7200));
        String first = provider.token();
        provider.invalidate(first);
        String second = provider.token();

        provider.invalidate(first);

        assertThat(provider.token()).isEqualTo(second);
        assertThat(fetches).hasValue(2);
    }

    @Test
    void token_underConcurrency_shouldCallGettokenOnlyOnce() throws Exception {
        int threads = 8;
        WeComAccessTokenProvider provider = provider(Duration.ofSeconds(7200));
        CyclicBarrier start = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    provider.token();
                } catch (Exception ignored) {
                    // 断言看 fetches 计数即可，线程内异常会让计数对不上。
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(fetches).hasValue(1);
    }

    @Test
    void token_whenWeComReturnsNoToken_shouldFail() {
        WeComAccessTokenProvider provider = new WeComAccessTokenProvider(
                () -> new WeComAccessTokenProvider.IssuedToken(null, Duration.ofSeconds(7200)),
                REFRESH_AHEAD, clock);

        assertThatThrownBy(provider::token)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access_token");
    }

    private WeComAccessTokenProvider provider(Duration expiresIn) {
        return new WeComAccessTokenProvider(
                () -> new WeComAccessTokenProvider.IssuedToken(
                        "token-" + fetches.incrementAndGet(), expiresIn),
                REFRESH_AHEAD, clock);
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
