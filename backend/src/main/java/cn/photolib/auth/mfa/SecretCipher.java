package cn.photolib.auth.mfa;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 用 AES-GCM 加解密 TOTP 密钥。
 *
 * <p>密钥丢了，所有人的验证器 App 都得重新绑定，所以部署文档要求把它和数据库备份
 * 分开保管、但一样要备份。密文带 {@code v1:} 前缀，将来换算法或轮换密钥时能分辨旧数据。
 */
final class SecretCipher {
    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    SecretCipher(String base64Key) {
        this.key = parse(base64Key);
    }

    boolean available() {
        return key != null;
    }

    String encrypt(String plain) {
        requireKey();
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("无法加密两步验证密钥", ex);
        }
    }

    String decrypt(String stored) {
        requireKey();
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("无法识别的两步验证密钥格式");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_LENGTH));
            return new String(cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            // 多半是加密密钥换过了。不要把它当成"验证码错误"吞掉：那样管理员只会看到
            // 所有人都登不上，却找不到原因。
            throw new IllegalStateException("无法解密两步验证密钥，请检查 MFA_ENCRYPTION_KEY 是否被更换", ex);
        }
    }

    private void requireKey() {
        if (key == null) throw new IllegalStateException("未配置 MFA_ENCRYPTION_KEY");
    }

    private static SecretKeySpec parse(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) return null;
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("MFA_ENCRYPTION_KEY 必须是 Base64 编码", ex);
        }
        if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
            throw new IllegalStateException("MFA_ENCRYPTION_KEY 解码后必须是 16、24 或 32 字节");
        }
        return new SecretKeySpec(raw, "AES");
    }
}
