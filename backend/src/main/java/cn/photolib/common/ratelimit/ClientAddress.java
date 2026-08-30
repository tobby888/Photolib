package cn.photolib.common.ratelimit;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a servlet container's {@code remoteAddr} into a rate-limiting key.
 *
 * <p>The value deliberately comes from {@code remoteAddr} and never from
 * X-Forwarded-For: an application must not trust a client-spoofable forwarding
 * header without an explicit trusted-proxy policy.</p>
 *
 * <p>Behind a reverse proxy {@code remoteAddr} therefore identifies the proxy
 * rather than the browser. Non-public addresses (loopback, link-local, private
 * and carrier-grade-NAT ranges, multicast) return {@code null} so callers fail
 * open instead of throttling every visitor behind one shared key. The gateway
 * must provide its own durable, distributed rate limiting; the process-local
 * limiters in this codebase are defense in depth, not a replacement.</p>
 */
public final class ClientAddress {
    private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-f:]+(?:%[0-9a-z_.-]+)?");

    private ClientAddress() {
    }

    /** Returns a stable key for a public client address, or {@code null} when there is none. */
    public static String normalize(String value) {
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
}
