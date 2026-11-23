package cn.photolib.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@ConfigurationProperties(prefix = "photolib.storage")
public record StorageProperties(
        String mode,
        String bucket,
        String endpoint,
        String publicEndpoint,
        String cdnDomain,
        String cdnAuthKey,
        Duration cdnAuthTtl,
        String accessKeyId,
        String accessKeySecret,
        String localDirectory,
        String publicBaseUrl,
        String signingSecret,
        List<String> corsAllowedOrigins,
        Duration uploadUrlTtl,
        Duration downloadUrlTtl,
        Duration signatureWindow,
        Duration originalRetention,
        long imageTargetBytes,
        long imageMaxBytes,
        double previewCompressionRatio
) {
    /** Default Alibaba Cloud CDN Type-A auth validity in console (1800 seconds). */
    public static final Duration DEFAULT_CDN_AUTH_TTL = Duration.ofMinutes(30);

    public StorageProperties {
        cdnDomain = normalizeCdnDomain(cdnDomain);
        cdnAuthKey = cdnAuthKey == null || cdnAuthKey.isBlank() ? null : cdnAuthKey.trim();
        if (cdnAuthTtl == null) {
            cdnAuthTtl = DEFAULT_CDN_AUTH_TTL;
        }
        if (cdnDomain != null && !"local".equalsIgnoreCase(mode)) {
            if (cdnAuthKey == null) {
                throw new IllegalArgumentException(
                        "启用 OSS_CDN_DOMAIN 时必须配置 OSS_CDN_AUTH_KEY（阿里云 CDN URL 鉴权 A 方式主密钥），避免私有 Bucket 暴露");
            }
            if (cdnAuthTtl.getSeconds() < 1) {
                throw new IllegalArgumentException(
                        "OSS_CDN_AUTH_TTL 必须至少为 1 秒（需与阿里云 CDN 控制台 A 方式有效时长一致）");
            }
        }
        // A window at or above the TTL would leave freshly issued URLs with no
        // usable validity left; disabling it (0) restores per-request signing.
        if (signatureWindow == null || signatureWindow.isNegative()) {
            signatureWindow = Duration.ZERO;
        }
        if (downloadUrlTtl != null && signatureWindow.compareTo(downloadUrlTtl) >= 0) {
            throw new IllegalArgumentException(
                    "OSS_SIGNATURE_WINDOW 必须小于 OSS_DOWNLOAD_URL_TTL");
        }
    }

    private static String normalizeCdnDomain(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String candidate = raw.trim();
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            candidate = "https://" + candidate;
        }
        while (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("OSS_CDN_DOMAIN 格式无效：" + raw, exception);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean hasPath = uri.getRawPath() != null && !uri.getRawPath().isEmpty();
        if ((!"http".equals(scheme) && !"https".equals(scheme))
                || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || hasPath
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "OSS_CDN_DOMAIN 必须是有效的域名或来源地址（不含路径和查询参数）：" + raw);
        }
        return candidate;
    }

    /**
     * How long a client may cache a preview response. Previews are immutable —
     * their object key carries the generation id — so the only bound that
     * matters is the signed URL's own worst-case remaining validity.
     */
    public String previewCacheControl() {
        long seconds = SignatureWindow.maximumCacheAge(downloadUrlTtl, signatureWindow).getSeconds();
        if (seconds <= 0) return null;
        // "private" keeps the signature out of shared caches. Put a CDN in front
        // of the bucket rather than making a signed URL publicly cacheable.
        return "private, max-age=" + seconds;
    }

    public boolean local() {
        return "local".equalsIgnoreCase(mode);
    }

    public boolean ossConfigured() {
        return bucket != null && !bucket.isBlank()
                && endpoint != null && !endpoint.isBlank()
                && accessKeyId != null && !accessKeyId.isBlank()
                && accessKeySecret != null && !accessKeySecret.isBlank();
    }

    public boolean usesOss() {
        return !local() && ossConfigured();
    }

    public String signingEndpoint() {
        return publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : publicEndpoint;
    }

    public boolean cdnEnabled() {
        return !local() && cdnDomain != null && !cdnDomain.isBlank();
    }
}
