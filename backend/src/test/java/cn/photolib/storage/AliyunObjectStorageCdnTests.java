package cn.photolib.storage;

import cn.photolib.performance.SnapshotRecorder;
import cn.photolib.performance.TimedObjectStorageService;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.net.URI;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AliyunObjectStorageCdnTests {
    private static final Instant WINDOW_START = Instant.ofEpochSecond(1_700_000_100L); // divisible by 300s

    @Test
    void normalizesBareCdnDomainAndValidatesRequiredAuthKeyInOssMode() {
        StorageProperties props = ossProperties("cdn.photowarehouse.cn/", "myAuthKey", Duration.ofMinutes(30));
        assertThat(props.cdnEnabled()).isTrue();
        assertThat(props.cdnDomain()).isEqualTo("https://cdn.photowarehouse.cn");

        // Missing auth key in OSS mode must fail fast to avoid exposing the private bucket.
        assertThatThrownBy(() -> ossProperties("https://cdn.photowarehouse.cn", " ", Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OSS_CDN_AUTH_KEY");

        // Zero or negative CDN auth TTL is invalid on Alibaba Cloud CDN Type-A.
        assertThatThrownBy(() -> ossProperties("https://cdn.photowarehouse.cn", "key", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OSS_CDN_AUTH_TTL");

        // Domain must not contain paths or query strings.
        assertThatThrownBy(() -> ossProperties("https://cdn.photowarehouse.cn/prefix", "key", Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OSS_CDN_DOMAIN");

        // Local mode ignores CDN so local dev isn't blocked by leftover env values.
        StorageProperties localProps = new StorageProperties(
                "local", null, null, null, "cdn.photowarehouse.cn", null, null,
                null, null, "./data", "http://localhost:8080/objects", "secret", List.of("*"),
                Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofDays(30),
                10_485_760, 104_857_600, 0.6);
        assertThat(localProps.cdnEnabled()).isFalse();
    }

    @Test
    void routesRegularPresignGetThroughCdnWhileKeepingPutAndDirectGetOnOss() throws Exception {
        OSS client = mock(OSS.class);
        OSS signer = mock(OSS.class);
        URL ossPutUrl = URI.create("https://private-bucket.oss-cn-hangzhou.aliyuncs.com/photos/up.jpg?Signature=put").toURL();
        URL ossBackupUrl = URI.create("https://private-bucket.oss-cn-hangzhou.aliyuncs.com/backups/2026/08/b.jsonl.gz?Signature=bk").toURL();
        when(signer.generatePresignedUrl(any())).thenReturn(ossPutUrl, ossBackupUrl);

        StorageProperties props = ossProperties("https://cdn.photowarehouse.cn", "cdnSecretKey", Duration.ofMinutes(30));
        Clock clock = Clock.fixed(WINDOW_START.plusSeconds(42), ZoneOffset.UTC);
        AliyunObjectStorageService storage = new AliyunObjectStorageService(props, client, signer, clock);

        // Preview GET goes through CDN, drops response-cache-control=private, and respects SignatureWindow.
        ObjectStorageService.SignedUrl preview = storage.presignGet(
                "thumbnails/generations/oss-avif-v1/1.avif", null,
                props.downloadUrlTtl(), props.previewCacheControl());
        assertThat(preview.url().getHost()).isEqualTo("cdn.photowarehouse.cn");
        assertThat(preview.url().getPath()).isEqualTo("/thumbnails/generations/oss-avif-v1/1.avif");
        assertThat(preview.url().getQuery()).startsWith("auth_key=");
        assertThat(preview.url().getQuery()).doesNotContain("response-cache-control");
        assertThat(preview.expiresAt()).isEqualTo(WINDOW_START.plus(Duration.ofMinutes(15)));

        // Repeat call inside the same SignatureWindow yields a byte-identical URL (browser cache + previewRetry).
        Clock laterInWindow = Clock.fixed(WINDOW_START.plusSeconds(250), ZoneOffset.UTC);
        AliyunObjectStorageService storageLater = new AliyunObjectStorageService(props, client, signer, laterInWindow);
        ObjectStorageService.SignedUrl repeat = storageLater.presignGet(
                "thumbnails/generations/oss-avif-v1/1.avif", null,
                props.downloadUrlTtl(), props.previewCacheControl());
        assertThat(repeat.url().toString()).isEqualTo(preview.url().toString());

        // Regular download GET goes through CDN and includes response-content-disposition.
        ObjectStorageService.SignedUrl download = storage.presignGet(
                "photos/2026/08/1.jpg", "活动成片.jpg", props.downloadUrlTtl());
        assertThat(download.url().getHost()).isEqualTo("cdn.photowarehouse.cn");
        assertThat(download.url().getQuery()).contains("response-content-disposition=").contains("&auth_key=");
        verify(signer, never()).generatePresignedUrl(any());

        // Browser direct upload PUT must stay on OSS signingClient.
        ObjectStorageService.SignedUrl upload = storage.presignPut("photos/up.jpg", "image/jpeg", Duration.ofMinutes(15));
        assertThat(upload.url()).isEqualTo(ossPutUrl);

        // Sensitive downloads (database backup / performance snapshot) use presignDirectGet and bypass CDN.
        ObjectStorageService.SignedUrl backup = storage.presignDirectGet(
                "backups/2026/08/b.jsonl.gz", "photolib-backup.jsonl.gz", props.downloadUrlTtl());
        assertThat(backup.url()).isEqualTo(ossBackupUrl);

        ArgumentCaptor<GeneratePresignedUrlRequest> captor = ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
        verify(signer, org.mockito.Mockito.times(2)).generatePresignedUrl(captor.capture());
        assertThat(captor.getAllValues().get(0).getMethod().name()).isEqualTo("PUT");
        assertThat(captor.getAllValues().get(1).getMethod().name()).isEqualTo("GET");
        assertThat(captor.getAllValues().get(1).getKey()).isEqualTo("backups/2026/08/b.jsonl.gz");
        verifyNoInteractions(client);
    }

    @Test
    void keepsOriginalOssSigningBehaviorWhenCdnDomainIsNotConfigured() throws Exception {
        OSS client = mock(OSS.class);
        OSS signer = mock(OSS.class);
        URL ossGetUrl = URI.create("https://private-bucket.oss-cn-hangzhou.aliyuncs.com/thumbnails/1.avif?Signature=oss").toURL();
        when(signer.generatePresignedUrl(any())).thenReturn(ossGetUrl);

        StorageProperties props = ossProperties(null, null, null);
        assertThat(props.cdnEnabled()).isFalse();

        AliyunObjectStorageService storage = new AliyunObjectStorageService(
                props, client, signer, Clock.fixed(WINDOW_START, ZoneOffset.UTC));
        ObjectStorageService.SignedUrl preview = storage.presignGet(
                "thumbnails/1.avif", null, props.downloadUrlTtl(), props.previewCacheControl());

        assertThat(preview.url()).isEqualTo(ossGetUrl);
        ArgumentCaptor<GeneratePresignedUrlRequest> captor = ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
        verify(signer).generatePresignedUrl(captor.capture());
        assertThat(captor.getValue().getQueryParameter())
                .containsEntry("response-cache-control", "private, max-age=600");
        verifyNoInteractions(client);
    }

    @Test
    void timedWrapperDelegatesPresignDirectGetWithoutFallingBackToCdn() throws Exception {
        OSS client = mock(OSS.class);
        OSS signer = mock(OSS.class);
        URL ossDirectUrl = URI.create("https://private-bucket.oss-cn-hangzhou.aliyuncs.com/backups/1.jsonl.gz?Signature=s").toURL();
        when(signer.generatePresignedUrl(any())).thenReturn(ossDirectUrl);

        StorageProperties props = ossProperties("https://cdn.photowarehouse.cn", "cdnSecretKey", Duration.ofMinutes(30));
        AliyunObjectStorageService raw = new AliyunObjectStorageService(
                props, client, signer, Clock.fixed(WINDOW_START, ZoneOffset.UTC));
        TimedObjectStorageService timed = new TimedObjectStorageService(raw, mock(SnapshotRecorder.class));

        ObjectStorageService.SignedUrl direct = timed.presignDirectGet(
                "backups/1.jsonl.gz", "backup.jsonl.gz", props.downloadUrlTtl());
        assertThat(direct.url()).isEqualTo(ossDirectUrl);

        ObjectStorageService.SignedUrl cdn = timed.presignGet(
                "thumbnails/1.avif", null, props.downloadUrlTtl(), props.previewCacheControl());
        assertThat(cdn.url().getHost()).isEqualTo("cdn.photowarehouse.cn");
    }

    @Test
    void bindsCdnPropertiesFromConfigurationSource() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("photolib.storage.mode", "oss"),
                Map.entry("photolib.storage.bucket", "private-bucket"),
                Map.entry("photolib.storage.endpoint", "https://oss-cn-hangzhou-internal.aliyuncs.com"),
                Map.entry("photolib.storage.public-endpoint", "https://oss-cn-hangzhou.aliyuncs.com"),
                Map.entry("photolib.storage.cdn-domain", "cdn.photowarehouse.cn"),
                Map.entry("photolib.storage.cdn-auth-key", "secret123456"),
                Map.entry("photolib.storage.cdn-auth-ttl", "15m"),
                Map.entry("photolib.storage.access-key-id", "ak"),
                Map.entry("photolib.storage.access-key-secret", "sk"),
                Map.entry("photolib.storage.upload-url-ttl", "15m"),
                Map.entry("photolib.storage.download-url-ttl", "15m"),
                Map.entry("photolib.storage.signature-window", "5m"),
                Map.entry("photolib.storage.original-retention", "30d"),
                Map.entry("photolib.storage.image-target-bytes", "10485760"),
                Map.entry("photolib.storage.image-max-bytes", "104857600"),
                Map.entry("photolib.storage.preview-compression-ratio", "0.6")
        ));
        StorageProperties bound = new Binder(source)
                .bind("photolib.storage", StorageProperties.class)
                .get();

        assertThat(bound.cdnEnabled()).isTrue();
        assertThat(bound.cdnDomain()).isEqualTo("https://cdn.photowarehouse.cn");
        assertThat(bound.cdnAuthKey()).isEqualTo("secret123456");
        assertThat(bound.cdnAuthTtl()).isEqualTo(Duration.ofMinutes(15));
    }

    private static StorageProperties ossProperties(String cdnDomain, String cdnAuthKey, Duration cdnAuthTtl) {
        return new StorageProperties(
                "oss",
                "private-bucket",
                "https://oss-cn-hangzhou-internal.aliyuncs.com",
                "https://oss-cn-hangzhou.aliyuncs.com",
                cdnDomain,
                cdnAuthKey,
                cdnAuthTtl,
                "ak",
                "sk",
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
