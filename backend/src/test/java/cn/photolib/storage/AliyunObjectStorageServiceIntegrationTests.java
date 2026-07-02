package cn.photolib.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "OSS_INTEGRATION_TEST", matches = "true")
class AliyunObjectStorageServiceIntegrationTests {

    @Test
    void configuredCredentialsCanReadWriteAndDeleteObjects() throws Exception {
        String endpoint = requiredEnvironmentVariable("OSS_ENDPOINT");
        String bucket = requiredEnvironmentVariable("OSS_BUCKET");
        String accessKeyId = requiredEnvironmentVariable("OSS_ACCESS_KEY_ID");
        String accessKeySecret = requiredEnvironmentVariable("OSS_ACCESS_KEY_SECRET");
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

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name + " is missing");
        assertFalse(value.isBlank(), name + " is empty");
        return value;
    }
}
