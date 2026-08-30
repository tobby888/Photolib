package cn.photolib.recruitment;

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
 * Small in-process defense-in-depth limiter for the unauthenticated recruitment
 * mutation endpoints. Keys combine the action, the recruitment task and the
 * client address from {@link ClientAddress} — see that class for why the key
 * never comes from a forwarding header, and why non-public addresses fail open.
 *
 * <p>Invalid task ids are deliberately not tracked either: controllers validate
 * an active task before calling in, so an unparseable id can only come from a
 * request that is about to be rejected anyway.</p>
 */
@Component
public class AnonymousRecruitmentRateLimiter {
    static final int MAX_TRACKED_KEYS = 4_096;
    private static final Pattern PUBLIC_ID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

    private final FixedWindowRateLimiter limiter;

    public AnonymousRecruitmentRateLimiter(Clock recruitmentClock) {
        this.limiter = new FixedWindowRateLimiter(recruitmentClock, MAX_TRACKED_KEYS);
    }

    public void requireAllowed(Action action, String publicId, String remoteAddress) {
        if (action == null) throw new IllegalArgumentException("rate-limit action is required");
        String taskKey = normalizePublicId(publicId);
        String remoteKey = ClientAddress.normalize(remoteAddress);
        if (taskKey == null || remoteKey == null) return;
        String key = action.name() + '|' + taskKey + '|' + remoteKey;
        if (!limiter.tryAcquire(key, action.limit(), action.window())) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试");
        }
    }

    int trackedKeyCount() {
        return limiter.trackedKeyCount();
    }

    private static String normalizePublicId(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return PUBLIC_ID.matcher(normalized).matches() ? normalized : null;
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
}
