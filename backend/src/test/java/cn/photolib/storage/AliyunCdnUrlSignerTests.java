package cn.photolib.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliyunCdnUrlSignerTests {

    @Test
    void signsTypeAUrlMatchingAlibabaCloudSpecificationWithPercentEncodedUnicodePath() throws Exception {
        Duration authTtl = Duration.ofSeconds(1800);
        AliyunCdnUrlSigner signer = new AliyunCdnUrlSigner("https://cdn.photowarehouse.cn", "PrivateKey", authTtl);
        long expectedTimestamp = 1_444_435_200L;
        Instant expiresAt = Instant.ofEpochSecond(expectedTimestamp + authTtl.getSeconds());

        ObjectStorageService.SignedUrl signed = signer.signGet("image/阿里云 照片.jpg", null, expiresAt);

        String expectedPath = "/image/%E9%98%BF%E9%87%8C%E4%BA%91%20%E7%85%A7%E7%89%87.jpg";
        String expectedMd5 = md5(expectedPath + "-" + expectedTimestamp + "-0-0-PrivateKey");

        assertThat(signed.method()).isEqualTo("GET");
        assertThat(signed.expiresAt()).isEqualTo(expiresAt);
        assertThat(signed.url().toString()).isEqualTo(
                "https://cdn.photowarehouse.cn" + expectedPath
                        + "?auth_key=" + expectedTimestamp + "-0-0-" + expectedMd5);
    }

    @Test
    void attachesResponseContentDispositionWithoutIncludingQueryStringInSignedUri() throws Exception {
        Duration authTtl = Duration.ofMinutes(30);
        AliyunCdnUrlSigner signer = new AliyunCdnUrlSigner("https://cdn.example.com", "secretKey123", authTtl);
        Instant expiresAt = Instant.ofEpochSecond(1_700_003_600L);
        long expectedTimestamp = expiresAt.getEpochSecond() - authTtl.getSeconds();

        ObjectStorageService.SignedUrl signed = signer.signGet(
                "photos/2026/sample.jpg", "毕业 照#1.jpg", expiresAt);

        String expectedPath = "/photos/2026/sample.jpg";
        String expectedMd5 = md5(expectedPath + "-" + expectedTimestamp + "-0-0-secretKey123");
        String expectedDisposition = "attachment; filename*=UTF-8''"
                + "%E6%AF%95%E4%B8%9A%20%E7%85%A7%231.jpg";
        String expectedQueryDisposition = AliyunCdnUrlSigner.percentEncode(expectedDisposition);

        assertThat(signed.url().toString()).isEqualTo(
                "https://cdn.example.com/photos/2026/sample.jpg"
                        + "?response-content-disposition=" + expectedQueryDisposition
                        + "&auth_key=" + expectedTimestamp + "-0-0-" + expectedMd5);
    }

    @Test
    void normalizesLeadingSlashesAndProducesDeterministicUrlsForSameExpiry() {
        AliyunCdnUrlSigner signer = new AliyunCdnUrlSigner(
                "https://cdn.example.com", "secretKey123", Duration.ofMinutes(30));
        Instant expiresAt = Instant.ofEpochSecond(1_700_003_600L);

        ObjectStorageService.SignedUrl withoutLeadingSlash = signer.signGet("thumbnails/a.avif", null, expiresAt);
        ObjectStorageService.SignedUrl withLeadingSlash = signer.signGet("/thumbnails/a.avif", null, expiresAt);

        assertThat(withLeadingSlash.url().toString()).isEqualTo(withoutLeadingSlash.url().toString());
        assertThat(withoutLeadingSlash.url().getPath()).isEqualTo("/thumbnails/a.avif");
    }

    @Test
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> new AliyunCdnUrlSigner("https://cdn.example.com", "", Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AliyunCdnUrlSigner("https://cdn.example.com", "key", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String md5(String input) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
