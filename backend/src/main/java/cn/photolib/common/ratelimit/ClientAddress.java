package cn.photolib.common.ratelimit;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a client address into a rate-limiting key.
 *
 * <p>The address comes from {@link RateLimitAddress}: {@code remoteAddr}, or the
 * {@code X-Real-IP} the local nginx overwrites when the request arrived through it.
 * Never from X-Forwarded-For, which a client can prefill.</p>
 *
 * <p>Non-public addresses (loopback, link-local, private and carrier-grade-NAT
 * ranges, multicast) return {@code null} so callers fail open instead of
 * throttling every visitor behind one shared key — that is what happens when
 * the proxy in front does not pass the real address. IPv6 addresses collapse to
 * their /64: one subscriber usually holds a whole /64 and can rotate through it
 * freely, so a per-address key would not limit anyone.</p>
 *
 * <p>A public IPv4 address is often a campus or dormitory NAT shared by hundreds
 * of people; limiters scale their per-address budgets with
 * {@link SharedAddressAllowance} for that reason. The process-local limiters are
 * defense in depth; the gateway should still provide durable rate limiting.</p>
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
        return ipv6Prefix(address);
    }

    /** The first four hextets of an IPv6 literal, or {@code null} if it does not parse. */
    static String ipv6Prefix(String address) {
        String[] head;
        String[] tail;
        int gap = address.indexOf("::");
        if (gap >= 0) {
            if (address.indexOf("::", gap + 1) >= 0) return null;
            head = split(address.substring(0, gap));
            tail = split(address.substring(gap + 2));
        } else {
            head = split(address);
            tail = new String[0];
        }
        if (head == null || tail == null) return null;
        int missing = 8 - head.length - tail.length;
        if (gap >= 0 ? missing < 1 : missing != 0) return null;
        String[] groups = new String[8];
        for (int i = 0; i < 8; i++) {
            String group = i < head.length ? head[i] : i < head.length + missing ? "0" : tail[i - head.length - missing];
            if (group.isEmpty() || group.length() > 4) return null;
            groups[i] = Integer.toHexString(Integer.parseInt(group, 16));
        }
        return String.join(":", groups[0], groups[1], groups[2], groups[3]) + "::/64";
    }

    private static String[] split(String part) {
        if (part.isEmpty()) return new String[0];
        if (part.contains(".")) return null;
        String[] groups = part.split(":", -1);
        for (String group : groups) {
            if (group.isEmpty()) return null;
        }
        return groups;
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
