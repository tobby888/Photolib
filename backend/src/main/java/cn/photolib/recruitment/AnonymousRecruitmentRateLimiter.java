package cn.photolib.recruitment;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Small in-process defense-in-depth limiter for unauthenticated mutation
 * endpoints. Keys use the servlet container's {@code remoteAddr}; the component
 * deliberately ignores X-Forwarded-For because an application must not trust a
 * client-spoofable forwarding header without an explicit trusted-proxy policy.
 *
 * <p>When deployed behind a reverse proxy, remoteAddr may therefore identify
 * the proxy rather than the browser. Non-public addresses are treated as
 * shared/unsafe keys and fail open instead of throttling every user together.
 * The gateway must provide its own durable, distributed rate limiting. This
 * bounded process-local limiter is not a replacement for gateway protection.</p>
 */
@Component
public class AnonymousRecruitmentRateLimiter {
    static final int MAX_TRACKED_KEYS = 4_096;
    private static final Pattern PUBLIC_ID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");
    private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-f:]+(?:%[0-9a-z_.-]+)?");

    private final Clock clock;
    private final Map<Key, Window> windows = new HashMap<>();

    public AnonymousRecruitmentRateLimiter(Clock recruitmentClock) {
        this.clock = recruitmentClock;
    }

    public synchronized void requireAllowed(Action action, String publicId, String remoteAddress) {
        if (action == null) throw new IllegalArgumentException("rate-limit action is required");
        Instant now = clock.instant();
        pruneExpired(now);
        String taskKey = normalizePublicId(publicId);
        String remoteKey = normalizeRemoteAddress(remoteAddress);
        // Invalid task ids and non-client addresses (loopback/private proxy hops)
        // are deliberately not tracked. Controllers validate an active task
        // first, and a gateway is the correct place to limit shared proxy traffic.
        if (taskKey == null || remoteKey == null) return;
        Key key = new Key(action, taskKey, remoteKey);
        Window window = windows.get(key);
        if (window == null) {
            // Never turn bounded-state exhaustion into a global anonymous outage.
            // Existing keys remain limited; previously unseen keys fail open and
            // remain the responsibility of the distributed gateway limiter.
            if (windows.size() >= MAX_TRACKED_KEYS) return;
            windows.put(key, new Window(1, now.plus(action.window())));
            return;
        }
        if (!now.isBefore(window.expiresAt())) {
            windows.put(key, new Window(1, now.plus(action.window())));
            return;
        }
        if (window.count() >= action.limit()) throw limited();
        windows.put(key, new Window(window.count() + 1, window.expiresAt()));
    }

    synchronized int trackedKeyCount() {
        return windows.size();
    }

    private void pruneExpired(Instant now) {
        Iterator<Map.Entry<Key, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            if (!now.isBefore(iterator.next().getValue().expiresAt())) iterator.remove();
        }
    }

    private static String normalizePublicId(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return PUBLIC_ID.matcher(normalized).matches() ? normalized : null;
    }

    private static String normalizeRemoteAddress(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 64) return null;
        int mapped = normalized.lastIndexOf(':');
        if (mapped >= 0 && normalized.substring(mapped + 1).contains(".")) {
            normalized = normalized.substring(mapped + 1);
        }
        if (IPV4.matcher(normalized).matches()) return normalizePublicIpv4(normalized);
        if (!IPV6.matcher(normalized).matches()) return null;
        String address = normalized.contains("%")
                ? normalized.substring(0, normalized.indexOf('%')) : normalized;
        if (address.equals("::") || address.equals("::1") || address.startsWith("fe8")
                || address.startsWith("fe9") || address.startsWith("fea")
                || address.startsWith("feb") || address.startsWith("fc")
                || address.startsWith("fd") || address.startsWith("ff")) return null;
        return normalized;
    }

    private static String normalizePublicIpv4(String value) {
        String[] parts = value.split("\\.");
        int[] octets = new int[4];
        for (int index = 0; index < parts.length; index++) {
            try {
                octets[index] = Integer.parseInt(parts[index]);
            } catch (NumberFormatException invalid) {
                return null;
            }
            if (octets[index] > 255) return null;
        }
        int first = octets[0];
        int second = octets[1];
        boolean shared = first == 0 || first == 10 || first == 127 || first >= 224
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 100 && second >= 64 && second <= 127);
        if (shared) return null;
        return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
    }

    private static BusinessException limited() {
        return new BusinessException(ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试");
    }

    public enum Action {
        DRAFT_CREATE(8, Duration.ofMinutes(10)),
        SUBMIT(12, Duration.ofMinutes(10)),
        UPLOAD_CREATE(20, Duration.ofMinutes(10)),
        UPLOAD_COMPLETE(40, Duration.ofMinutes(10));

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

    private record Key(Action action, String publicId, String remoteAddress) {
    }

    private record Window(int count, Instant expiresAt) {
    }
}
