package cn.photolib.storage;

import java.time.Duration;
import java.time.Instant;

/**
 * Makes presigned URLs repeatable so browsers can actually cache what they
 * download.
 *
 * <p>Signing with {@code Instant.now().plus(ttl)} produces a different
 * {@code Expires}/{@code Signature} pair every second, so the gallery handed the
 * browser a brand-new URL for the same unchanged thumbnail on every render. The
 * HTTP cache is keyed by URL, so it never hit: every scroll, refresh and
 * back-navigation re-downloaded every visible preview from the object store.</p>
 *
 * <p>Quantising the <em>issue</em> time to a fixed wall-clock window makes every
 * request inside one window produce a byte-identical URL. Flooring rather than
 * rounding up keeps the guarantee callers already rely on: a signature is never
 * valid for longer than its configured TTL. The cost is that the remaining
 * validity of a freshly issued URL varies within
 * {@code (ttl - window, ttl]} instead of being exactly {@code ttl}.</p>
 */
public final class SignatureWindow {
    private SignatureWindow() {
    }

    /**
     * The expiry to sign for, quantised so that all callers inside one window
     * produce the same URL.
     *
     * @param window the quantisation window; a non-positive window disables
     *               quantisation and restores the exact {@code now + ttl}
     *               behaviour
     */
    public static Instant expiryFor(Instant now, Duration ttl, Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            return now.plus(ttl);
        }
        long windowSeconds = window.getSeconds();
        if (windowSeconds <= 0) return now.plus(ttl);
        // Backdating the issue time by up to a whole window would hand out an
        // already-expired signature whenever the TTL is not longer than the
        // window. Callers may pass any TTL, so this cannot be left to config
        // validation.
        if (ttl == null || ttl.compareTo(window) <= 0) return now.plus(ttl);
        long issuedAt = Math.floorDiv(now.getEpochSecond(), windowSeconds) * windowSeconds;
        return Instant.ofEpochSecond(issuedAt).plus(ttl);
    }

    /**
     * The longest a caller may tell a client to cache a response signed with
     * {@link #expiryFor}. Staying at or below the worst-case remaining validity
     * ({@code ttl - window}) means a cache entry can never outlive the URL that
     * produced it, so an already-rendered page cannot end up holding an expired
     * link with nothing cached behind it.
     */
    public static Duration maximumCacheAge(Duration ttl, Duration window) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) return Duration.ZERO;
        if (window == null || window.isNegative()) return ttl;
        Duration age = ttl.minus(window);
        return age.isNegative() || age.isZero() ? Duration.ZERO : age;
    }
}
