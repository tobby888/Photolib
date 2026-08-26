package cn.photolib.auth;

import cn.photolib.auth.mapper.LoginAttemptMapper;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LoginThrottleTests {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant START = Instant.parse("2026-08-26T02:00:00Z");

    @Autowired private LoginAttemptMapper mapper;
    @Autowired private JdbcClient jdbc;

    private MutableClock clock;
    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM login_attempt").update();
        clock = new MutableClock(START);
        throttle = new LoginThrottle(mapper, properties(3, 5,
                Duration.ofMinutes(15), Duration.ofMinutes(15)), clock);
    }

    @Test
    void identifierLocksOnlyAfterTheConfiguredNumberOfFailures() {
        throttle.recordFailure("alice", "203.0.113.10");
        throttle.recordFailure("alice", "203.0.113.10");
        throttle.requireNotLocked("alice", "203.0.113.10");

        throttle.recordFailure("alice", "203.0.113.10");

        assertThatThrownBy(() -> throttle.requireNotLocked("alice", "203.0.113.10"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    void lockIsScopedToTheAccountAndReleasesWhenItExpires() {
        for (int attempt = 0; attempt < 3; attempt++) {
            throttle.recordFailure("alice", "203.0.113.10");
        }
        // A different account from a different address must not inherit the lock.
        throttle.requireNotLocked("bob", "203.0.113.99");

        clock.advance(Duration.ofMinutes(16));
        throttle.requireNotLocked("alice", "203.0.113.10");
    }

    @Test
    void identifierIsNormalizedSoCasingCannotBuyExtraAttempts() {
        throttle.recordFailure("Alice", "203.0.113.10");
        throttle.recordFailure(" alice ", "203.0.113.10");
        throttle.recordFailure("ALICE", "203.0.113.10");

        assertThatThrownBy(() -> throttle.requireNotLocked("alice", "203.0.113.10"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void addressLockCatchesSprayingAcrossManyAccounts() {
        // Never three failures on one account, so the identifier scope never trips.
        for (int attempt = 0; attempt < 5; attempt++) {
            throttle.recordFailure("victim-" + attempt, "203.0.113.10");
        }

        assertThatThrownBy(() -> throttle.requireNotLocked("someone-else", "203.0.113.10"))
                .isInstanceOf(BusinessException.class);
        // Another client is unaffected.
        throttle.requireNotLocked("someone-else", "198.51.100.7");
    }

    @Test
    void successClearsTheAccountButLeavesTheAddressCounterStanding() {
        for (int attempt = 0; attempt < 4; attempt++) {
            throttle.recordFailure("victim-" + attempt, "203.0.113.10");
        }
        throttle.recordFailure("alice", "203.0.113.10");
        // The address is now locked; a guess that happens to land must not lift it.
        throttle.clearIdentifier("alice");

        assertThat(mapper.find(LoginThrottle.IDENTIFIER_SCOPE, "alice")).isNull();
        assertThatThrownBy(() -> throttle.requireNotLocked("alice", "203.0.113.10"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void countingRestartsOnceTheFailureWindowHasElapsed() {
        throttle.recordFailure("alice", "203.0.113.10");
        throttle.recordFailure("alice", "203.0.113.10");

        clock.advance(Duration.ofMinutes(16));
        throttle.recordFailure("alice", "203.0.113.10");

        assertThat(mapper.find(LoginThrottle.IDENTIFIER_SCOPE, "alice").getFailureCount()).isEqualTo(1);
        throttle.requireNotLocked("alice", "203.0.113.10");
    }

    @Test
    void purgeKeepsLiveLocksAndDropsSettledRows() {
        for (int attempt = 0; attempt < 3; attempt++) {
            throttle.recordFailure("alice", "203.0.113.10");
        }
        clock.advance(Duration.ofDays(2));
        throttle.recordFailure("carol", "198.51.100.7");

        throttle.purgeSettledAttempts();

        assertThat(mapper.find(LoginThrottle.IDENTIFIER_SCOPE, "alice")).isNull();
        assertThat(mapper.find(LoginThrottle.IDENTIFIER_SCOPE, "carol")).isNotNull();
    }

    @Test
    void blankAndMissingKeysAreIgnoredRatherThanTrackedAsOneSharedBudget() {
        throttle.recordFailure(null, null);
        throttle.recordFailure("   ", "");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM login_attempt").query(Long.class).single()).isZero();
        throttle.requireNotLocked(null, null);
    }

    private static AuthProperties properties(int identifierFailures, int addressFailures,
                                             Duration window, Duration lock) {
        return new AuthProperties(Duration.ofMinutes(15), Duration.ofMinutes(30), true,
                new AuthProperties.LoginThrottleProperties(
                        identifierFailures, addressFailures, window, lock));
    }

    /** A clock the test can move, so lock expiry is asserted without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override public ZoneId getZone() { return ZONE; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
