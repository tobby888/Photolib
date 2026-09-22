package cn.photolib.auth.mfa;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 两步验证的部署参数。
 *
 * @param encryptionKey    加密 TOTP 密钥用的 AES 密钥（Base64，16/24/32 字节）。没配就不能绑定
 *                         验证器 App，也不能打开全站开关；安全密钥不依赖它。
 * @param rpId             WebAuthn 的 RP ID，即站点域名（如 photowarehouse.cn）。留空时从请求的
 *                         Origin 头推断，只适合本地开发。
 * @param rpName           验证器里显示的站点名
 * @param origins          允许的页面来源（如 https://photowarehouse.cn），留空时同上
 * @param trustedDeviceTtl 信任浏览器的有效期，每次凭它登录都顺延
 * @param stepUpTtl        敏感操作验证一次后的信任期
 * @param challengeTtl     登录第二步、绑定、再验证这些一次性票据的有效期
 */
@ConfigurationProperties(prefix = "photolib.auth.mfa")
public record MfaProperties(
        String encryptionKey,
        String rpId,
        String rpName,
        List<String> origins,
        Duration trustedDeviceTtl,
        Duration stepUpTtl,
        Duration challengeTtl
) {
    public MfaProperties {
        encryptionKey = encryptionKey == null ? "" : encryptionKey.trim();
        rpId = rpId == null ? "" : rpId.trim();
        rpName = rpName == null || rpName.isBlank() ? "PhotoLib" : rpName.trim();
        origins = origins == null ? List.of()
                : origins.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
        trustedDeviceTtl = positiveOr(trustedDeviceTtl, Duration.ofDays(30));
        stepUpTtl = positiveOr(stepUpTtl, Duration.ofMinutes(15));
        challengeTtl = positiveOr(challengeTtl, Duration.ofMinutes(5));
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
