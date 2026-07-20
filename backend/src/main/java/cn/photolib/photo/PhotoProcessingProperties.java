package cn.photolib.photo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "photolib.photo-processing")
public record PhotoProcessingProperties(int threads, String temporaryDirectory) {
    public PhotoProcessingProperties {
        if (threads < 1 || threads > 32) {
            throw new IllegalArgumentException("PHOTO_PROCESSING_THREADS 必须在 1 到 32 之间");
        }
        if (temporaryDirectory == null || temporaryDirectory.isBlank()) {
            throw new IllegalArgumentException("PHOTO_PROCESSING_TEMPORARY_DIRECTORY 不能为空");
        }
    }

    public Path temporaryRoot() {
        return Path.of(temporaryDirectory).toAbsolutePath().normalize();
    }
}
