package cn.photolib.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "photolib.storage")
public record StorageProperties(
        String mode,
        String bucket,
        String endpoint,
        String publicEndpoint,
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
    public StorageProperties {
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

    public String signingEndpoint() {
        return publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : publicEndpoint;
    }
}
