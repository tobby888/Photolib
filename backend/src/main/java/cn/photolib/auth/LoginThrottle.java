package cn.photolib.auth;

import cn.photolib.auth.mapper.LoginAttemptMapper;
import cn.photolib.auth.model.LoginAttemptEntity;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Counts failed logins and refuses further attempts once a threshold is crossed.
 *
 * <p>Two scopes are tracked because they answer different questions. The
 * {@code IDENTIFIER} scope protects one account from being guessed at; the
 * {@code ADDRESS} scope stops a single client spraying one password across many
 * accounts, which never trips a per-account counter. A successful login clears
 * only the identifier — an address that has just produced a burst of failures
 * stays throttled even if one of the guesses happened to land.
 *
 * <p>Counters live in the database rather than in memory: an in-process map
 * resets on every deploy and is not shared between instances, and those are
 * precisely the windows a guessing run needs. Writes run in their own
 * transaction because the caller records failures while unwinding a failed
 * login, and a rolled-back counter would defeat the whole mechanism.
 *
 * <p>This bounds guessing; it is not a substitute for gateway rate limiting,
 * which sees traffic this application never reaches.
 */
@Service
@RequiredArgsConstructor
public class LoginThrottle {
    static final String IDENTIFIER_SCOPE = "IDENTIFIER";
    static final String ADDRESS_SCOPE = "ADDRESS";
    private static final int MAX_KEY_LENGTH = 190;

    private final LoginAttemptMapper mapper;
    private final AuthProperties properties;
    /** The application's single Asia/Shanghai clock bean; injected so tests can fix time. */
    private final Clock clock;

    /**
     * Refuses a login attempt while either counter is locked. Deliberately uses one
     * message for both scopes and never says how long is left or which counter
     * tripped: the response must not become an oracle for whether an account exists.
     */
    public void requireNotLocked(String identifier, String remoteAddress) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (locked(IDENTIFIER_SCOPE, identifierKey(identifier), now)
                || locked(ADDRESS_SCOPE, addressKey(remoteAddress), now)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "登录失败次数过多，请稍后再试，或联系管理员");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String identifier, String remoteAddress) {
        AuthProperties.LoginThrottleProperties settings = properties.loginThrottle();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime windowStart = now.minus(settings.failureWindow());
        LocalDateTime lockUntil = now.plus(settings.lockDuration());
        countFailure(IDENTIFIER_SCOPE, identifierKey(identifier), now, windowStart,
                settings.maxIdentifierFailures(), lockUntil);
        countFailure(ADDRESS_SCOPE, addressKey(remoteAddress), now, windowStart,
                settings.maxAddressFailures(), lockUntil);
    }

    /** Clears the account counter after a successful login. The address counter stands. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearIdentifier(String identifier) {
        String key = identifierKey(identifier);
        if (key != null) mapper.clear(IDENTIFIER_SCOPE, key);
    }

    private boolean locked(String scope, String key, LocalDateTime now) {
        if (key == null) return false;
        LoginAttemptEntity attempt = mapper.find(scope, key);
        return attempt != null && attempt.getLockedUntil() != null
                && attempt.getLockedUntil().isAfter(now);
    }

    private void countFailure(String scope, String key, LocalDateTime now,
                              LocalDateTime windowStart, int threshold, LocalDateTime lockUntil) {
        if (key == null) return;
        if (mapper.countFailure(scope, key, now, windowStart, threshold, lockUntil) > 0) return;
        LoginAttemptEntity attempt = new LoginAttemptEntity();
        attempt.setScope(scope);
        attempt.setAttemptKey(key);
        attempt.setFailureCount(1);
        attempt.setFirstFailedAt(now);
        attempt.setLastFailedAt(now);
        attempt.setLockedUntil(threshold <= 1 ? lockUntil : null);
        try {
            mapper.insert(attempt);
        } catch (DuplicateKeyException raced) {
            // Another attempt on the same key inserted first; count against that row.
            mapper.countFailure(scope, key, now, windowStart, threshold, lockUntil);
        }
    }

    /**
     * Normalizes the identifier the same way {@code AuthService} does before looking
     * an account up, so "Admin" and "admin " cannot be used as separate budgets.
     */
    private static String identifierKey(String identifier) {
        if (identifier == null) return null;
        String normalized = identifier.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;
        return normalized.length() > MAX_KEY_LENGTH
                ? normalized.substring(0, MAX_KEY_LENGTH) : normalized;
    }

    private static String addressKey(String remoteAddress) {
        if (remoteAddress == null) return null;
        String normalized = remoteAddress.trim();
        if (normalized.isEmpty()) return null;
        return normalized.length() > MAX_KEY_LENGTH
                ? normalized.substring(0, MAX_KEY_LENGTH) : normalized;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    @Transactional
    public void purgeSettledAttempts() {
        LocalDateTime now = LocalDateTime.now(clock);
        mapper.purgeSettled(now.minus(properties.loginThrottle().failureWindow()).minusDays(1), now);
    }
}
