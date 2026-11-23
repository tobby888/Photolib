package cn.photolib.storage;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Generates Alibaba Cloud CDN Type-A authenticated GET URLs for a private OSS
 * bucket fronted by CDN ("OSS 私有 Bucket 回源" + "URL 鉴权 A 方式").
 *
 * <p>Format:
 * {@code <cdnBaseUrl><encodedPath>?[response-content-disposition=...&]auth_key=<timestamp>-0-0-<md5hash>}
 * where {@code sstring = encodedPath + "-" + timestamp + "-0-0-" + authKey}.</p>
 *
 * <p>Alibaba Cloud CDN expires Type-A URLs when {@code timestamp + consoleAuthTtl < now}.
 * Subtracting {@code authTtl} from the desired {@code expiresAt} makes the edge
 * node enforce the exact expiry calculated by {@link SignatureWindow}, regardless
 * of whether the console TTL is the default 1800s or a custom duration.</p>
 *
 * <p>{@code rand} is fixed to {@code 0} (explicitly allowed by Alibaba Cloud CDN)
 * so repeated calls inside one {@link SignatureWindow} produce byte-identical
 * URLs. This preserves browser HTTP caching and the frontend's one-shot retry
 * guard ({@code src/previewRetry.ts}), while CDN edge nodes strip {@code auth_key}
 * after verification so edge cache hits persist across windows and users.</p>
 */
final class AliyunCdnUrlSigner {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final String baseUrl;
    private final String authKey;
    private final Duration authTtl;

    AliyunCdnUrlSigner(String normalizedBaseUrl, String authKey, Duration authTtl) {
        this.baseUrl = Objects.requireNonNull(normalizedBaseUrl, "normalizedBaseUrl");
        this.authKey = Objects.requireNonNull(authKey, "authKey");
        this.authTtl = Objects.requireNonNull(authTtl, "authTtl");
        if (this.baseUrl.isBlank()) {
            throw new IllegalArgumentException("CDN 域名不能为空");
        }
        if (this.authKey.isBlank()) {
            throw new IllegalArgumentException("CDN 鉴权密钥不能为空");
        }
        if (this.authTtl.getSeconds() < 1) {
            throw new IllegalArgumentException("CDN 鉴权有效时长必须至少为 1 秒");
        }
    }

    ObjectStorageService.SignedUrl signGet(String objectKey, String downloadName, Instant expiresAt) {
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(expiresAt, "expiresAt");

        String encodedPath = encodePath(objectKey);
        long timestamp = expiresAt.getEpochSecond() - authTtl.getSeconds();
        if (timestamp < 0) {
            throw new IllegalArgumentException("CDN 签名时间戳无效：expiresAt 早于 authTtl");
        }

        // Alibaba Cloud Type-A signs only the encoded URI path (no query string).
        String sstring = encodedPath + "-" + timestamp + "-0-0-" + authKey;
        String authParam = timestamp + "-0-0-" + md5HexLower(sstring);

        StringBuilder urlBuilder = new StringBuilder(baseUrl.length() + encodedPath.length() + 96);
        urlBuilder.append(baseUrl).append(encodedPath).append('?');
        if (downloadName != null && !downloadName.isBlank()) {
            String disposition = "attachment; filename*=UTF-8''" + percentEncode(downloadName);
            urlBuilder.append("response-content-disposition=")
                    .append(percentEncode(disposition))
                    .append('&');
        }
        urlBuilder.append("auth_key=").append(authParam);

        try {
            URL url = URI.create(urlBuilder.toString()).toURL();
            return new ObjectStorageService.SignedUrl(url, "GET", expiresAt);
        } catch (MalformedURLException exception) {
            throw new IllegalStateException("构造 CDN 鉴权 URL 失败", exception);
        }
    }

    static String encodePath(String objectKey) {
        String trimmed = objectKey;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isEmpty()) {
            return "/";
        }
        String[] segments = trimmed.split("/", -1);
        StringBuilder encoded = new StringBuilder(trimmed.length() + 16);
        for (String segment : segments) {
            encoded.append('/').append(percentEncode(segment));
        }
        return encoded.toString();
    }

    static String percentEncode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length + 16);
        for (byte raw : bytes) {
            int b = raw & 0xFF;
            if (isUnreserved(b)) {
                out.append((char) b);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(HEX[(b >>> 4) & 0x0F]))
                        .append(Character.toUpperCase(HEX[b & 0x0F]));
            }
        }
        return out.toString();
    }

    private static boolean isUnreserved(int b) {
        return (b >= 'A' && b <= 'Z')
                || (b >= 'a' && b <= 'z')
                || (b >= '0' && b <= '9')
                || b == '-' || b == '_' || b == '.' || b == '~';
    }

    private static String md5HexLower(String input) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(input.getBytes(StandardCharsets.UTF_8));
            char[] chars = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int v = digest[i] & 0xFF;
                chars[i * 2] = HEX[v >>> 4];
                chars[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(chars);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 MD5 算法实现", exception);
        }
    }
}
