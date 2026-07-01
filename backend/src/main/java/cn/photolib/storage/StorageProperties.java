package cn.photolib.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "photolib.storage")
public record StorageProperties(
        String mode,
        String bucket,
        String endpoint,
        String accessKeyId,
        String accessKeySecret,
        String localDirectory,
        String publicBaseUrl,
        String signingSecret,
        Duration uploadUrlTtl,
        Duration downloadUrlTtl,
        Duration originalRetention,
        long imageTargetBytes,
        long imageMaxBytes
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
}
