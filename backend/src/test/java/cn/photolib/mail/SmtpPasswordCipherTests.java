package cn.photolib.mail;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpPasswordCipherTests {

    private static final String KEY_32 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String OTHER_KEY_32 = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    @Test
    void sealsWithAesGcmWhenKeyIsConfigured() {
        SmtpPasswordCipher cipher = new SmtpPasswordCipher(KEY_32);
        assertThat(cipher.encrypting()).isTrue();

        String sealed1 = cipher.seal("auth-code-123");
        String sealed2 = cipher.seal("auth-code-123");
        assertThat(sealed1).startsWith("v1:").isNotEqualTo(sealed2);
        assertThat(SmtpPasswordCipher.isEncrypted(sealed1)).isTrue();
        assertThat(cipher.open(sealed1)).isEqualTo("auth-code-123");
        assertThat(cipher.open(sealed2)).isEqualTo("auth-code-123");
    }

    @Test
    void fallsBackToPlainPrefixWithoutKeyAndStillOpensLegacyPlainAfterKeyAdded() {
        SmtpPasswordCipher plainCipher = new SmtpPasswordCipher("");
        assertThat(plainCipher.encrypting()).isFalse();

        String stored = plainCipher.seal("my-secret");
        assertThat(stored).isEqualTo("plain:my-secret");
        assertThat(SmtpPasswordCipher.isEncrypted(stored)).isFalse();
        assertThat(plainCipher.open(stored)).isEqualTo("my-secret");

        // 后续补上加密密钥后，原先明文保存的记录仍可读取。
        SmtpPasswordCipher encryptedCipher = new SmtpPasswordCipher(KEY_32);
        assertThat(encryptedCipher.open(stored)).isEqualTo("my-secret");
    }

    @Test
    void reportsClearErrorsWhenKeyMissingOrRotatedOrInvalid() {
        String sealed = new SmtpPasswordCipher(KEY_32).seal("top-secret");

        assertThatThrownBy(() -> new SmtpPasswordCipher(null).open(sealed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP_ENCRYPTION_KEY");

        assertThatThrownBy(() -> new SmtpPasswordCipher(OTHER_KEY_32).open(sealed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法解密");

        assertThatThrownBy(() -> new SmtpPasswordCipher("not-base64!!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");

        assertThatThrownBy(() -> new SmtpPasswordCipher(Base64.getEncoder().encodeToString("short".getBytes())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16、24 或 32");
    }
}
