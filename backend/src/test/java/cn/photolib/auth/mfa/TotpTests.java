package cn.photolib.auth.mfa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TotpTests {
    /** RFC 6238 附录 B 的 SHA1 测试密钥。 */
    private static final String RFC_SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesRfc6238TestVectors() {
        assertThat(RFC_SECRET).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        // RFC 给的是 8 位码，6 位取末 6 位。
        assertThat(Totp.code(RFC_SECRET, Totp.step(59))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Totp.step(1111111109))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.step(1234567890))).isEqualTo("005924");
        assertThat(Totp.code(RFC_SECRET, Totp.step(2000000000))).isEqualTo("279037");
    }

    @Test
    void toleratesOneStepOfClockDriftEitherWay() {
        String secret = Totp.newSecret();
        assertThat(Totp.match(secret, Totp.code(secret, 99), 100, null)).isEqualTo(99L);
        assertThat(Totp.match(secret, Totp.code(secret, 100), 100, null)).isEqualTo(100L);
        assertThat(Totp.match(secret, Totp.code(secret, 101), 100, null)).isEqualTo(101L);
        assertThat(Totp.match(secret, Totp.code(secret, 98), 100, null)).isNull();
        assertThat(Totp.match(secret, Totp.code(secret, 102), 100, null)).isNull();
    }

    @Test
    void aCodeCannotBeUsedTwice() {
        String secret = Totp.newSecret();
        String code = Totp.code(secret, 100);
        assertThat(Totp.match(secret, code, 100, 99L)).isEqualTo(100L);
        assertThat(Totp.match(secret, code, 100, 100L)).isNull();
        // 用过更晚的码之后，更早的码也不再认。
        assertThat(Totp.match(secret, Totp.code(secret, 99), 100, 100L)).isNull();
    }

    @Test
    void rejectsMalformedCodes() {
        String secret = Totp.newSecret();
        assertThat(Totp.match(secret, null, 100, null)).isNull();
        assertThat(Totp.match(secret, "12345", 100, null)).isNull();
        assertThat(Totp.match(secret, "abcdef", 100, null)).isNull();
        assertThat(Totp.match(secret, "1234567", 100, null)).isNull();
    }

    @Test
    void base32RoundTripsAndIsCaseInsensitive() {
        byte[] data = {0, 1, 2, (byte) 0xfe, (byte) 0xff, 42, 7, 9, 11, 13};
        String encoded = Totp.base32(data);
        assertThat(Totp.decodeBase32(encoded)).isEqualTo(data);
        assertThat(Totp.decodeBase32(encoded.toLowerCase())).isEqualTo(data);
        assertThat(Totp.newSecret()).hasSize(32).matches("[A-Z2-7]+");
    }

    @Test
    void uriUsesPercentEncodedSpaces() {
        String uri = Totp.uri("Photo Lib", "张 三", "ABCDEF");
        assertThat(uri).startsWith("otpauth://totp/Photo%20Lib:%E5%BC%A0%20%E4%B8%89?secret=ABCDEF")
                .contains("issuer=Photo%20Lib").contains("digits=6").contains("period=30")
                .doesNotContain("+");
    }

    @Test
    void secretCipherRoundTripsAndRefusesAnotherKey() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        byte[] other = new byte[32];
        other[0] = 1;
        SecretCipher cipher = new SecretCipher(key);
        String stored = cipher.encrypt("JBSWY3DPEHPK3PXP");
        assertThat(stored).startsWith("v1:").doesNotContain("JBSWY3DPEHPK3PXP");
        assertThat(cipher.decrypt(stored)).isEqualTo("JBSWY3DPEHPK3PXP");
        // 同一明文两次加密结果不同（随机 IV）。
        assertThat(cipher.encrypt("JBSWY3DPEHPK3PXP")).isNotEqualTo(stored);
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(other)).decrypt(stored))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MFA_ENCRYPTION_KEY");
    }

    @Test
    void secretCipherValidatesItsKey() {
        assertThat(new SecretCipher("").available()).isFalse();
        assertThat(new SecretCipher(null).available()).isFalse();
        assertThatThrownBy(() -> new SecretCipher("not base64 !!"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[10])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("字节");
        assertThatThrownBy(() -> new SecretCipher("").encrypt("x"))
                .isInstanceOf(IllegalStateException.class);
    }
}
