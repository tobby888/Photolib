package cn.photolib.auth.mfa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * RFC 6238 基于时间的一次性密码。
 *
 * <p>参数固定为 SHA1 / 6 位 / 30 秒，这是所有验证器 App（Microsoft、Google、1Password、
 * 各种小程序）都支持的组合；有些 App 会无视 URI 里的其他参数，改了反而算不对。
 */
final class Totp {
    static final int PERIOD_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int SECRET_BYTES = 20;
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    static String newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return base32(secret);
    }

    static long step(long epochSecond) {
        return Math.floorDiv(epochSecond, PERIOD_SECONDS);
    }

    /**
     * 在当前时间步前后各一步里找与 {@code code} 吻合的那一步。前后各一步用来容忍手机和
     * 服务器之间几十秒的时钟偏差。
     *
     * @param lastUsedStep 这台设备上一次用掉的时间步；不大于它的一律不认，同一个码不能用两次
     * @return 吻合的时间步；对不上时为 {@code null}
     */
    static Long match(String base32Secret, String code, long currentStep, Long lastUsedStep) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) return null;
        byte[] key = decodeBase32(base32Secret);
        byte[] expected = code.getBytes(StandardCharsets.US_ASCII);
        for (long step = currentStep - 1; step <= currentStep + 1; step++) {
            if (lastUsedStep != null && step <= lastUsedStep) continue;
            byte[] actual = code(key, step).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, actual)) return step;
        }
        return null;
    }

    static String code(String base32Secret, long step) {
        return code(decodeBase32(base32Secret), step);
    }

    static String uri(String issuer, String account, String base32Secret) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    private static String code(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA1 is unavailable", ex);
        }
    }

    /** 标签里的空格要写成 %20：部分验证器 App 不认 {@code +}。 */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) out.append(BASE32[(buffer << (5 - bits)) & 31]);
        return out.toString();
    }

    static byte[] decodeBase32(String value) {
        String normalized = value.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        byte[] out = new byte[normalized.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char c : normalized.toCharArray()) {
            int digit = c >= 'A' && c <= 'Z' ? c - 'A' : c >= '2' && c <= '7' ? c - '2' + 26 : -1;
            if (digit < 0) throw new IllegalArgumentException("非法的 Base32 字符");
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) ((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out;
    }
}
