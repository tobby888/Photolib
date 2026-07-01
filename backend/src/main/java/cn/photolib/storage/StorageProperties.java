package cn.photolib.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "photolib.storage")
public record StorageProperties(
        String bucket,
        String endpoint,
        String accessKeyId,
        String accessKeySecret,
        Duration uploadUrlTtl,
        Duration downloadUrlTtl,
        Duration originalRetention,
        long imageTargetBytes,
        long imageMaxBytes
) {
    public boolean configured() {
        return bucket != null && !bucket.isBlank()
                && endpoint != null && !endpoint.isBlank()
                && accessKeyId != null && !accessKeyId.isBlank()
                && accessKeySecret != null && !accessKeySecret.isBlank();
    }
}
