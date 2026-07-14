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
        Duration originalRetention,
        long imageTargetBytes,
        long imageMaxBytes,
        double previewCompressionRatio
) {
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
