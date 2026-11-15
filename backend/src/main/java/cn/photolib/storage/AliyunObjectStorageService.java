package cn.photolib.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.CreateBucketRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.ProcessObjectRequest;
import com.aliyun.oss.model.SetBucketCORSRequest;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AliyunObjectStorageService implements ObjectStorageService, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AliyunObjectStorageService.class);
    private static final List<String> DIRECT_UPLOAD_METHODS = List.of("PUT", "GET", "HEAD");

    private final StorageProperties properties;
    private final OSS client;
    private final OSS signingClient;

    public AliyunObjectStorageService(StorageProperties properties) {
        this(properties, buildClient(properties, properties.endpoint()),
                properties.endpoint().equals(properties.signingEndpoint()) ? null
                        : buildClient(properties, properties.signingEndpoint()));
    }

    AliyunObjectStorageService(StorageProperties properties, OSS client, OSS signingClient) {
        this.properties = properties;
        this.client = client;
        this.signingClient = signingClient == null ? client : signingClient;
    }

    private static OSS buildClient(StorageProperties properties, String endpoint) {
        return new OSSClientBuilder().build(endpoint, properties.accessKeyId(), properties.accessKeySecret());
    }

    @Override
    public void initialize() {
        if (!client.doesBucketExist(properties.bucket())) {
            CreateBucketRequest request = new CreateBucketRequest(properties.bucket());
            request.setCannedACL(CannedAccessControlList.Private);
            client.createBucket(request);
        }
        ensureDirectUploadCors();
    }

    private void ensureDirectUploadCors() {
        List<String> origins = properties.corsAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            origins = List.of("*");
        }
        origins = origins.stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (origins.isEmpty()) {
            origins = List.of("*");
        }
        List<String> requiredOrigins = origins;

        List<SetBucketCORSRequest.CORSRule> rules;
        try {
            rules = currentCorsRules();
        } catch (RuntimeException exception) {
            log.warn("读取 OSS Bucket {} 的 CORS 配置失败，跳过本次自动补全；对象存储本身继续可用",
                    properties.bucket(), exception);
            return;
        }
        if (rules.stream().anyMatch(rule -> supportsDirectUpload(rule, requiredOrigins))) {
            return;
        }

        SetBucketCORSRequest request = new SetBucketCORSRequest(properties.bucket());
        rules.forEach(request::addCorsRule);

        SetBucketCORSRequest.CORSRule uploadRule = new SetBucketCORSRequest.CORSRule();
        uploadRule.setAllowedOrigins(origins);
        uploadRule.setAllowedMethods(DIRECT_UPLOAD_METHODS);
        uploadRule.setAllowedHeaders(List.of("*"));
        uploadRule.setExposeHeaders(List.of("ETag", "x-oss-request-id"));
        uploadRule.setMaxAgeSeconds(3600);
        request.addCorsRule(uploadRule);

        try {
            client.setBucketCORS(request);
            log.info("OSS Bucket {} 已配置浏览器直传 CORS 规则: origins={}", properties.bucket(), origins);
        } catch (RuntimeException exception) {
            log.warn("OSS Bucket {} 配置浏览器直传 CORS 失败，前端直传可能被浏览器拦截", properties.bucket(), exception);
        }
    }

    private List<SetBucketCORSRequest.CORSRule> currentCorsRules() {
        try {
            return new ArrayList<>(client.getBucketCORSRules(properties.bucket()));
        } catch (OSSException exception) {
            if ("NoSuchCORSConfiguration".equals(exception.getErrorCode())) {
                return new ArrayList<>();
            }
            throw exception;
        }
    }

    private boolean supportsDirectUpload(SetBucketCORSRequest.CORSRule rule, List<String> requiredOrigins) {
        List<String> allowedOrigins = rule.getAllowedOrigins();
        List<String> allowedMethods = rule.getAllowedMethods();
        List<String> allowedHeaders = rule.getAllowedHeaders();
        if (allowedOrigins == null || allowedMethods == null || allowedHeaders == null) {
            return false;
        }
        boolean originsMatch = allowedOrigins.contains("*") || requiredOrigins.stream().allMatch(allowedOrigins::contains);
        boolean methodsMatch = allowedMethods.contains("PUT") && allowedMethods.contains("GET")
                && allowedMethods.contains("HEAD");
        boolean headersMatch = allowedHeaders.contains("*") || allowedHeaders.stream()
                .anyMatch(header -> "content-type".equalsIgnoreCase(header));
        return originsMatch && methodsMatch && headersMatch;
    }

    @Override
    public List<StoredObject> list(String prefix) {
        List<StoredObject> objects = new ArrayList<>();
        String marker = null;
        do {
            ListObjectsRequest request = new ListObjectsRequest(properties.bucket());
            request.setPrefix(prefix == null ? "" : prefix);
            request.setMarker(marker);
            request.setMaxKeys(1000);
            ObjectListing listing = client.listObjects(request);
            for (OSSObjectSummary item : listing.getObjectSummaries()) {
                objects.add(new StoredObject(item.getKey(), item.getSize()));
            }
            marker = listing.isTruncated() ? listing.getNextMarker() : null;
        } while (marker != null);
        return objects;
    }

    @Override
    public SignedUrl presignPut(String objectKey, String contentType, Duration ttl) {
        Instant expires = Instant.now().plus(ttl);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                properties.bucket(), objectKey, HttpMethod.PUT);
        request.setExpiration(Date.from(expires));
        request.setContentType(contentType);
        URL url = signingClient.generatePresignedUrl(request);
        return new SignedUrl(url, "PUT", expires);
    }

    @Override
    public SignedUrl presignGet(String objectKey, String downloadName, Duration ttl,
                                String cacheControl) {
        // Quantised so repeat views of the same object produce the same URL and
        // can be served from the browser cache; see SignatureWindow.
        Instant expires = SignatureWindow.expiryFor(
                Instant.now(), ttl, properties.signatureWindow());
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                properties.bucket(), objectKey, HttpMethod.GET);
        request.setExpiration(Date.from(expires));
        if (cacheControl != null && !cacheControl.isBlank()) {
            request.addQueryParameter("response-cache-control", cacheControl);
        }
        if (downloadName != null && !downloadName.isBlank()) {
            request.addQueryParameter("response-content-disposition",
                    "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(
                            downloadName, java.nio.charset.StandardCharsets.UTF_8));
        }
        return new SignedUrl(signingClient.generatePresignedUrl(request), "GET", expires);
    }

    @Override
    public ObjectInfo stat(String objectKey) {
        ObjectMetadata metadata = client.getObjectMetadata(properties.bucket(), objectKey);
        return new ObjectInfo(metadata.getContentLength(), metadata.getContentType(),
                metadata.getUserMetadata());
    }

    @Override
    public Optional<ObjectInfo> find(String objectKey) {
        try {
            return Optional.of(stat(objectKey));
        } catch (OSSException exception) {
            if ("NoSuchKey".equals(exception.getErrorCode())
                    || "NoSuchObject".equals(exception.getErrorCode())) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    @Override
    public InputStream open(String objectKey) {
        OSSObject object = client.getObject(properties.bucket(), objectKey);
        return object.getObjectContent();
    }

    @Override
    public void put(String objectKey, InputStream input, long size, String contentType,
                    Map<String, String> userMetadata) {
        ObjectInfo objectInfo = new ObjectInfo(size, contentType, userMetadata);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(size);
        metadata.setContentType(contentType);
        metadata.setUserMetadata(objectInfo.userMetadata());
        client.putObject(new PutObjectRequest(properties.bucket(), objectKey, input, metadata));
    }

    @Override
    public void delete(String objectKey) {
        client.deleteObject(properties.bucket(), objectKey);
    }

    @Override
    public void checkAccess() {
        if (!client.doesBucketExist(properties.bucket())) {
            throw new IllegalStateException("配置的 OSS Bucket 不存在");
        }
    }

    @Override
    public void saveAvifPreview(String sourceKey, String destinationKey) {
        if (sourceKey.equals(destinationKey)) {
            throw new IllegalArgumentException("预览转存不能覆盖来源对象");
        }
        String bucket = properties.bucket();
        // Bound both axes while preserving aspect ratio and orientation.
        String process = "image/resize,m_lfit,l_1600,s_1200,limit_1/format,avif"
                + "|sys/saveas,o_" + base64(destinationKey) + ",b_" + base64(bucket);
        ProcessObjectRequest request = new ProcessObjectRequest(bucket, sourceKey, process);
        var result = client.processObject(request);
        try (InputStream response = result.getResponse().getContent()) {
            if (response != null) response.transferTo(OutputStream.nullOutputStream());
        } catch (IOException exception) {
            throw new UncheckedIOException("读取 OSS 图片转存响应失败", exception);
        }
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 关掉 OSS 客户端的连接池和后台线程。Bean 外面套着 {@code TimedObjectStorageService}，
     * 这个类本身已经不是 Spring 管理的 Bean，{@code @PreDestroy} 不会被调到——由包装层的
     * {@code close()} 转交过来；直接 new 出来用的地方（集成测试）仍可靠 {@code @PreDestroy} 或手动调用。
     */
    @PreDestroy
    @Override
    public void close() {
        if (signingClient != client) {
            signingClient.shutdown();
        }
        client.shutdown();
    }
}
