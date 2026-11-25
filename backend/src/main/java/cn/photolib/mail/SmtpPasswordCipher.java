package cn.photolib.mail;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 后台配置的发信邮箱密码（授权码）的存储格式。
 *
 * <p>配了密钥（{@code SMTP_ENCRYPTION_KEY}，留空沿用 {@code MFA_ENCRYPTION_KEY}）就用 AES-GCM 加密，
 * 存成 {@code v1:<base64(iv|密文)>}；没配密钥时存成 {@code plain:<原文>}，界面会提示管理员。
 * 两种前缀解密时都认：之后补上密钥，旧记录照样能用，管理员重新保存一次密码就会加密。</p>
 *
 * <p>只用 JDK 自带的 {@code javax.crypto}，没有反射，原生镜像里同样可用。</p>
 */
final class SmtpPasswordCipher {
    private static final String ENCRYPTED = "v1:";
    private static final String PLAIN = "plain:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    SmtpPasswordCipher(String base64Key) {
        this.key = parse(base64Key);
    }

    boolean encrypting() {
        return key != null;
    }

    String seal(String plain) {
        if (key == null) return PLAIN + plain;
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ENCRYPTED + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("无法加密发信邮箱密码", ex);
        }
    }

    String open(String stored) {
        if (stored == null) throw new IllegalStateException("发信邮箱没有保存密码，请重新填写");
        if (stored.startsWith(PLAIN)) return stored.substring(PLAIN.length());
        if (!stored.startsWith(ENCRYPTED)) throw new IllegalStateException("无法识别发信邮箱密码的存储格式，请重新填写密码");
        if (key == null) {
            throw new IllegalStateException("发信邮箱密码是加密保存的，但服务器现在没有配置 SMTP_ENCRYPTION_KEY / MFA_ENCRYPTION_KEY");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(ENCRYPTED.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_LENGTH));
            return new String(cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("无法解密发信邮箱密码（加密密钥可能被更换过），请重新填写密码", ex);
        }
    }

    static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(ENCRYPTED);
    }

    private static SecretKeySpec parse(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) return null;
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("SMTP_ENCRYPTION_KEY（或沿用的 MFA_ENCRYPTION_KEY）必须是 Base64 编码", ex);
        }
        if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
            throw new IllegalStateException("SMTP_ENCRYPTION_KEY（或沿用的 MFA_ENCRYPTION_KEY）解码后必须是 16、24 或 32 字节");
        }
        return new SecretKeySpec(raw, "AES");
    }
}
