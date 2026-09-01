package cn.photolib.notification.wecom;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 缓存企业微信的 access_token，并在过期前提前续期。
 *
 * <p><b>为什么必须缓存。</b>access_token 是企业微信侧的受限资源：同一个应用的 token 全局
 * 唯一，{@code gettoken} 有调用频率限制，且新签发会让旧 token 失效。每次发消息都去换一次
 * token，轻则触发限频、重则让并发中的另一条消息拿着刚被作废的 token 投递失败。所以整个
 * 进程共用这一份缓存。
 *
 * <p><b>并发。</b>读走 volatile 快照，不加锁；只有需要续期时才抢锁，抢到锁后再检查一次
 * （双重检查），因此一次过期最多只会打出一次 {@code gettoken}。
 *
 * <p><b>提前量。</b>缓存的有效期是 {@code expires_in - tokenRefreshAhead}，默认提前 5 分钟，
 * 避免"本地还没过期、发出去的瞬间服务端已过期"这个窗口。如果企业微信返回的有效期本身就
 * 不比提前量长（异常情况），退化成有效期的一半——直接减会得到负数，那样每次调用都判定为
 * 过期，反而把 {@code gettoken} 打成了每消息一次。
 */
public class WeComAccessTokenProvider {

    /** 换取 token 的实际动作；生产实现打 HTTP，测试注入假实现以便控制过期与失败。 */
    @FunctionalInterface
    public interface Fetcher {
        IssuedToken fetch();
    }

    public record IssuedToken(String accessToken, Duration expiresIn) {
    }

    private final Fetcher fetcher;
    private final Duration refreshAhead;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile CachedToken cached;

    public WeComAccessTokenProvider(Fetcher fetcher, Duration refreshAhead, Clock clock) {
        this.fetcher = fetcher;
        this.refreshAhead = refreshAhead;
        this.clock = clock;
    }

    public String token() {
        CachedToken snapshot = cached;
        if (snapshot != null && snapshot.usableAt(clock.instant())) {
            return snapshot.token();
        }
        lock.lock();
        try {
            snapshot = cached;
            if (snapshot != null && snapshot.usableAt(clock.instant())) {
                return snapshot.token();
            }
            IssuedToken issued = fetcher.fetch();
            if (issued == null || issued.accessToken() == null || issued.accessToken().isBlank()) {
                throw new IllegalStateException("企业微信未返回 access_token");
            }
            cached = new CachedToken(issued.accessToken(),
                    clock.instant().plus(usableFor(issued.expiresIn())));
            return issued.accessToken();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 作废一个已被服务端拒绝的 token。
     *
     * <p>只在缓存里存的确实是这个 token 时才清空：并发调用中另一个线程可能已经换到新
     * token 了，无条件清空会把刚拿到的好 token 一起丢掉，退化成每次失败都多打一次
     * {@code gettoken}。
     */
    public void invalidate(String rejectedToken) {
        CachedToken snapshot = cached;
        if (snapshot == null || !snapshot.token().equals(rejectedToken)) return;
        lock.lock();
        try {
            if (cached == snapshot) cached = null;
        } finally {
            lock.unlock();
        }
    }

    private Duration usableFor(Duration expiresIn) {
        if (expiresIn == null || expiresIn.isZero() || expiresIn.isNegative()) {
            return Duration.ZERO;
        }
        return expiresIn.compareTo(refreshAhead) > 0
                ? expiresIn.minus(refreshAhead)
                : expiresIn.dividedBy(2);
    }

    private record CachedToken(String token, Instant usableUntil) {
        boolean usableAt(Instant now) {
            return now.isBefore(usableUntil);
        }
    }
}
