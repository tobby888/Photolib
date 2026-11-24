package cn.photolib.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AliyunObjectStorageCnameTests {

    @Test
    void presignsWithoutBucketPrefixWhenPublicEndpointIsCustomCnameDomain() {
        StorageProperties props = ossProperties(
                "https://oss-cn-hangzhou-internal.aliyuncs.com",
                "https://oss.photowarehouse.cn");
        try (AliyunObjectStorageService storage = new AliyunObjectStorageService(props)) {
            ObjectStorageService.SignedUrl upload = storage.presignPut(
                    "temporary/photos/sample.jpg", "image/jpeg", Duration.ofMinutes(15));
            assertThat(upload.url().getProtocol()).isEqualTo("https");
            assertThat(upload.url().getHost()).isEqualTo("oss.photowarehouse.cn");
            assertThat(upload.url().getPath()).isEqualTo("/temporary/photos/sample.jpg");
            assertThat(upload.url().getQuery()).contains("Signature=");

            ObjectStorageService.SignedUrl download = storage.presignGet(
                    "photos/2026/sample.jpg", null, Duration.ofMinutes(15), null);
            assertThat(download.url().getHost()).isEqualTo("oss.photowarehouse.cn");
            assertThat(download.url().getPath()).isEqualTo("/photos/2026/sample.jpg");

            ObjectStorageService.SignedUrl direct = storage.presignDirectGet(
                    "backups/2026/backup.jsonl.gz", "backup.jsonl.gz", Duration.ofMinutes(15));
            assertThat(direct.url().getHost()).isEqualTo("oss.photowarehouse.cn");
        }
    }

    @Test
    void keepsBucketSubdomainWhenPublicEndpointIsStandardAliyunEndpoint() {
        StorageProperties props = ossProperties(
                "https://oss-cn-hangzhou-internal.aliyuncs.com",
                "https://oss-cn-hangzhou.aliyuncs.com");
        try (AliyunObjectStorageService storage = new AliyunObjectStorageService(props)) {
            ObjectStorageService.SignedUrl upload = storage.presignPut(
                    "temporary/photos/sample.jpg", "image/jpeg", Duration.ofMinutes(15));
            assertThat(upload.url().getProtocol()).isEqualTo("https");
            assertThat(upload.url().getHost()).isEqualTo("private-bucket.oss-cn-hangzhou.aliyuncs.com");
            assertThat(upload.url().getPath()).isEqualTo("/temporary/photos/sample.jpg");

            ObjectStorageService.SignedUrl download = storage.presignGet(
                    "photos/2026/sample.jpg", null, Duration.ofMinutes(15), null);
            assertThat(download.url().getHost()).isEqualTo("private-bucket.oss-cn-hangzhou.aliyuncs.com");
        }
    }

    private static StorageProperties ossProperties(String endpoint, String publicEndpoint) {
        return new StorageProperties(
                "oss",
                "private-bucket",
                endpoint,
                publicEndpoint,
                null,
                null,
                null,
                "test-ak",
                "test-sk",
                null,
                null,
                null,
                List.of("https://photowarehouse.cn"),
                Duration.ofMinutes(15),
                Duration.ofMinutes(15),
                Duration.ofMinutes(5),
                Duration.ofDays(30),
                10_485_760,
                104_857_600,
                0.6);
    }
}
