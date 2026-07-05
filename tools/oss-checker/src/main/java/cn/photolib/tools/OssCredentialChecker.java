package cn.photolib.tools;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;
import java.util.UUID;

public final class OssCredentialChecker {
    private OssCredentialChecker() {
    }

    public static void main(String[] args) {
        Properties config = loadConfiguration();
        String endpoint = required(config, "OSS_ENDPOINT");
        String publicEndpoint = value(config, "OSS_PUBLIC_ENDPOINT", endpoint);
        String bucket = required(config, "OSS_BUCKET");
        String accessKeyId = required(config, "OSS_ACCESS_KEY_ID");
        String accessKeySecret = required(config, "OSS_ACCESS_KEY_SECRET");
        String origin = value(config, "APP_ORIGIN", "https://photowarehouse.cn");
        String objectKey = "photolib-credential-check/" + UUID.randomUUID() + ".txt";
        byte[] expected = ("PhotoLib OSS check " + Instant.now()).getBytes(StandardCharsets.UTF_8);

        System.out.println("OSS Endpoint: " + endpoint);
        System.out.println("Browser Endpoint: " + publicEndpoint);
        System.out.println("Bucket: " + bucket);
        System.out.println("Test object: " + objectKey);

        OSS serverClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        OSS signingClient = endpoint.equals(publicEndpoint)
                ? serverClient
                : new OSSClientBuilder().build(publicEndpoint, accessKeyId, accessKeySecret);
        boolean uploaded = false;
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(expected.length);
            metadata.setContentType("text/plain; charset=utf-8");
            serverClient.putObject(new PutObjectRequest(
                    bucket, objectKey, new ByteArrayInputStream(expected), metadata));
            uploaded = true;
            System.out.println("[PASS] PutObject");

            ObjectMetadata stored = serverClient.getObjectMetadata(bucket, objectKey);
            if (stored.getContentLength() != expected.length) {
                throw new IllegalStateException("对象大小校验失败");
            }
            System.out.println("[PASS] GetObjectMetadata");

            try (OSSObject object = serverClient.getObject(bucket, objectKey);
                 InputStream input = object.getObjectContent()) {
                if (!java.util.Arrays.equals(expected, input.readAllBytes())) {
                    throw new IllegalStateException("对象内容校验失败");
                }
            }
            System.out.println("[PASS] GetObject");

            GeneratePresignedUrlRequest signRequest = new GeneratePresignedUrlRequest(
                    bucket, objectKey, HttpMethod.PUT);
            signRequest.setExpiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))));
            signRequest.setContentType("text/plain; charset=utf-8");
            URI signedPut = signingClient.generatePresignedUrl(signRequest).toURI();
            System.out.println("[PASS] Presigned URL host: " + signedPut.getHost());
            checkCors(signedPut, origin);
        } catch (Exception exception) {
            System.err.println("[FAIL] " + rootMessage(exception));
            System.err.println("如测试对象未能自动清理，请删除：" + objectKey);
            System.exit(1);
        } finally {
            if (uploaded) {
                try {
                    serverClient.deleteObject(bucket, objectKey);
                    System.out.println("[PASS] DeleteObject");
                } catch (Exception exception) {
                    System.err.println("[WARN] 测试对象清理失败：" + rootMessage(exception));
                }
            }
            if (signingClient != serverClient) {
                signingClient.shutdown();
            }
            serverClient.shutdown();
        }
        System.out.println("OSS 密钥、读写删除权限和签名地址检查通过。");
    }

    private static void checkCors(URI signedPut, String origin) {
        try {
            HttpRequest request = HttpRequest.newBuilder(signedPut)
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "PUT")
                    .header("Access-Control-Request-Headers", "content-type")
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<Void> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            String allowedOrigin = response.headers()
                    .firstValue("access-control-allow-origin").orElse("");
            if (response.statusCode() / 100 == 2
                    && ("*".equals(allowedOrigin) || origin.equals(allowedOrigin))) {
                System.out.println("[PASS] CORS PUT from " + origin);
            } else {
                System.out.println("[WARN] CORS 未匹配：HTTP " + response.statusCode()
                        + ", Access-Control-Allow-Origin=" + allowedOrigin);
            }
        } catch (Exception exception) {
            System.out.println("[WARN] CORS 网络检查失败：" + rootMessage(exception));
        }
    }

    private static Properties loadConfiguration() {
        Properties properties = new Properties();
        Path path = Path.of(".env");
        if (Files.isRegularFile(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            } catch (Exception exception) {
                throw new IllegalStateException("无法读取 " + path.toAbsolutePath(), exception);
            }
        }
        System.getenv().forEach(properties::setProperty);
        return properties;
    }

    private static String required(Properties properties, String name) {
        String result = properties.getProperty(name);
        if (result == null || result.isBlank()) {
            throw new IllegalArgumentException("缺少配置：" + name);
        }
        return result.trim();
    }

    private static String value(Properties properties, String name, String fallback) {
        String result = properties.getProperty(name);
        return result == null || result.isBlank() ? fallback : result.trim();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }
}
