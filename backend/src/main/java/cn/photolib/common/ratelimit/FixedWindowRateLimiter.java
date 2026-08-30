package cn.photolib.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Bounded in-process fixed-window counter shared by the anonymous endpoints.
 *
 * <p>State is process-local and lost on restart, so this is defense in depth
 * behind a gateway limiter, never the only protection. The key table is capped:
 * once {@link #maxTrackedKeys} distinct keys are live, previously unseen keys
 * fail open rather than turning bounded-state exhaustion into a global outage
 * for anonymous visitors. Keys already being tracked stay limited.</p>
 */
public final class FixedWindowRateLimiter {
    private final Clock clock;
    private final int maxTrackedKeys;
    private final Map<String, Window> windows = new HashMap<>();

    public FixedWindowRateLimiter(Clock clock, int maxTrackedKeys) {
        this.clock = clock;
        this.maxTrackedKeys = maxTrackedKeys;
    }

    /**
     * Counts one request against {@code key}.
     *
     * @return {@code false} only when the key has already used up {@code limit}
     *         requests inside the current window.
     */
    public synchronized boolean tryAcquire(String key, int limit, Duration window) {
        Instant now = clock.instant();
        pruneExpired(now);
        Window current = windows.get(key);
        if (current == null) {
            if (windows.size() >= maxTrackedKeys) return true;
            windows.put(key, new Window(1, now.plus(window)));
            return true;
        }
        if (!now.isBefore(current.expiresAt())) {
            windows.put(key, new Window(1, now.plus(window)));
            return true;
        }
        if (current.count() >= limit) return false;
        windows.put(key, new Window(current.count() + 1, current.expiresAt()));
        return true;
    }

    public synchronized int trackedKeyCount() {
        return windows.size();
    }

    private void pruneExpired(Instant now) {
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            if (!now.isBefore(iterator.next().getValue().expiresAt())) iterator.remove();
        }
    }

    private record Window(int count, Instant expiresAt) {
    }
}
