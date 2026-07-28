package cn.photolib.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIf("hasConfiguredDotEnv")
class AliyunObjectStorageServiceIntegrationTests {

    @Test
    void storageServiceRoundTripsUserMetadataWithoutHeaderPrefix() throws Exception {
        Properties dotEnv = loadDotEnv();
        String objectKey = "photolib-integration-test/" + UUID.randomUUID() + ".txt";
        byte[] expected = "PhotoLib OSS metadata test".getBytes(StandardCharsets.UTF_8);
        StorageProperties properties = new StorageProperties(
                "aliyun",
                dotEnv.getProperty("OSS_BUCKET"),
                dotEnv.getProperty("OSS_ENDPOINT"),
                null,
                dotEnv.getProperty("OSS_ACCESS_KEY_ID"),
                dotEnv.getProperty("OSS_ACCESS_KEY_SECRET"),
                null, null, "unused", java.util.List.of("*"),
                Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofDays(30),
                10_485_760, 104_857_600, 0.6);
        AliyunObjectStorageService storage = new AliyunObjectStorageService(properties);
        try {
            storage.put(objectKey, new ByteArrayInputStream(expected), expected.length,
                    "text/plain; charset=utf-8", Map.of("Preview-Ratio", "0.6"));

            ObjectStorageService.ObjectInfo info = storage.stat(objectKey);
            assertEquals(expected.length, info.size());
            assertEquals("0.6", info.userMetadata().get("preview-ratio"));
            assertFalse(info.userMetadata().containsKey("x-oss-meta-preview-ratio"));
            try (InputStream input = storage.open(objectKey)) {
                assertArrayEquals(expected, input.readAllBytes());
            }
        } finally {
            try {
                storage.delete(objectKey);
            } finally {
                storage.close();
            }
        }
    }

    @Test
    void configuredCredentialsCanReadWriteAndDeleteObjects() throws Exception {
        Properties dotEnv = loadDotEnv();
        String endpoint = dotEnv.getProperty("OSS_ENDPOINT");
        String bucket = dotEnv.getProperty("OSS_BUCKET");
        String accessKeyId = dotEnv.getProperty("OSS_ACCESS_KEY_ID");
        String accessKeySecret = dotEnv.getProperty("OSS_ACCESS_KEY_SECRET");
        String objectKey = "photolib-integration-test/" + UUID.randomUUID() + ".txt";
        byte[] expected = "PhotoLib OSS integration test".getBytes(StandardCharsets.UTF_8);

        OSS client = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            assertTrue(client.doesBucketExist(bucket),
                    "OSS credentials cannot access the configured bucket");

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(expected.length);
            metadata.setContentType("text/plain; charset=utf-8");
            client.putObject(bucket, objectKey, new ByteArrayInputStream(expected), metadata);

            assertTrue(client.doesObjectExist(bucket, objectKey));
            assertEquals(expected.length,
                    client.getObjectMetadata(bucket, objectKey).getContentLength());

            try (OSSObject object = client.getObject(bucket, objectKey);
                 var input = object.getObjectContent()) {
                assertArrayEquals(expected, input.readAllBytes());
            }
        } finally {
            try {
                client.deleteObject(bucket, objectKey);
                assertFalse(client.doesObjectExist(bucket, objectKey),
                        "OSS integration-test object was not cleaned up");
            } finally {
                client.shutdown();
            }
        }
    }

    static boolean hasConfiguredDotEnv() {
        Properties dotEnv = loadDotEnv();
        return "true".equalsIgnoreCase(dotEnv.getProperty("OSS_INTEGRATION_TEST"))
                && configured(dotEnv, "OSS_ENDPOINT")
                && configured(dotEnv, "OSS_BUCKET")
                && configured(dotEnv, "OSS_ACCESS_KEY_ID")
                && configured(dotEnv, "OSS_ACCESS_KEY_SECRET");
    }

    private static boolean configured(Properties properties, String name) {
        String value = properties.getProperty(name);
        return value != null && !value.isBlank();
    }

    private static Properties loadDotEnv() {
        Path dotEnvPath = Path.of(".env");
        if (!Files.isRegularFile(dotEnvPath)) {
            dotEnvPath = Path.of("backend", ".env");
        }

        Properties properties = new Properties();
        if (!Files.isRegularFile(dotEnvPath)) {
            return properties;
        }

        try (InputStream input = Files.newInputStream(dotEnvPath)) {
            properties.load(input);
            return properties;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + dotEnvPath, exception);
        }
    }
}
