package cn.photolib.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureWindowTests {
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final Duration WINDOW = Duration.ofMinutes(5);

    /**
     * The whole point: two requests a few seconds apart must sign the same
     * expiry, otherwise the URL changes and the browser cache never hits.
     */
    @Test
    void quantisesEveryIssueInsideOneWindowToTheSameExpiry() {
        Instant windowStart = Instant.ofEpochSecond(1_700_000_100L); // a 300s boundary
        assertThat(windowStart.getEpochSecond() % WINDOW.getSeconds()).isZero();

        Instant first = SignatureWindow.expiryFor(windowStart, TTL, WINDOW);
        Instant middle = SignatureWindow.expiryFor(windowStart.plusSeconds(1), TTL, WINDOW);
        Instant last = SignatureWindow.expiryFor(windowStart.plusSeconds(299), TTL, WINDOW);

        assertThat(first).isEqualTo(middle).isEqualTo(last)
                .isEqualTo(windowStart.plus(TTL));
    }

    @Test
    void movesToANewExpiryOnceTheWindowRollsOver() {
        Instant windowStart = Instant.ofEpochSecond(1_700_000_100L);

        assertThat(SignatureWindow.expiryFor(windowStart.plusSeconds(300), TTL, WINDOW))
                .isEqualTo(windowStart.plus(WINDOW).plus(TTL));
    }

    /**
     * Flooring the issue time must never lend a signature more life than its
     * TTL allows, and must never spend more than one window of it.
     */
    @Test
    void keepsRemainingValidityInsideTheConfiguredTtl() {
        for (int second = 0; second < 300; second++) {
            Instant now = Instant.ofEpochSecond(1_700_000_100L + second);
            Duration remaining = Duration.between(now, SignatureWindow.expiryFor(now, TTL, WINDOW));

            assertThat(remaining).isLessThanOrEqualTo(TTL)
                    .isGreaterThan(TTL.minus(WINDOW).minusSeconds(1));
        }
    }

    /**
     * Callers pass their own TTL, so a TTL at or below the window would
     * otherwise be backdated into an already-expired signature. This is not
     * hypothetical: short-lived signatures exist for one-shot links.
     */
    @Test
    void neverBackdatesASignatureThatWouldAlreadyBeExpired() {
        Instant now = Instant.ofEpochSecond(1_700_000_399L); // 299s into a window

        assertThat(SignatureWindow.expiryFor(now, Duration.ofSeconds(30), WINDOW))
                .isEqualTo(now.plusSeconds(30));
        assertThat(SignatureWindow.expiryFor(now, WINDOW, WINDOW)).isEqualTo(now.plus(WINDOW));
    }

    @Test
    void disablingTheWindowRestoresPerRequestSigning() {
        Instant now = Instant.ofEpochSecond(1_700_000_399L);

        assertThat(SignatureWindow.expiryFor(now, TTL, Duration.ZERO)).isEqualTo(now.plus(TTL));
        assertThat(SignatureWindow.expiryFor(now, TTL, null)).isEqualTo(now.plus(TTL));
    }

    @Test
    void cachesForNoLongerThanTheWorstCaseRemainingValidity() {
        assertThat(SignatureWindow.maximumCacheAge(TTL, WINDOW)).isEqualTo(Duration.ofMinutes(10));
        assertThat(SignatureWindow.maximumCacheAge(TTL, TTL)).isEqualTo(Duration.ZERO);
        assertThat(SignatureWindow.maximumCacheAge(TTL, Duration.ZERO)).isEqualTo(TTL);
    }
}
