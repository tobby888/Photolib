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
import com.aliyun.oss.model.SetBucketCORSRequest;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AliyunObjectStorageService implements ObjectStorageService {
    private static final Logger log = LoggerFactory.getLogger(AliyunObjectStorageService.class);
    private static final List<String> DIRECT_UPLOAD_METHODS = List.of("PUT", "GET", "HEAD");

    private final StorageProperties properties;
    private final OSS client;
    private final OSS signingClient;

    public AliyunObjectStorageService(StorageProperties properties) {
        this.properties = properties;
        this.client = new OSSClientBuilder().build(properties.endpoint(),
                properties.accessKeyId(), properties.accessKeySecret());
        this.signingClient = properties.endpoint().equals(properties.signingEndpoint())
                ? client
                : new OSSClientBuilder().build(properties.signingEndpoint(),
                        properties.accessKeyId(), properties.accessKeySecret());
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
    public SignedUrl presignGet(String objectKey, String downloadName, Duration ttl) {
        Instant expires = Instant.now().plus(ttl);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                properties.bucket(), objectKey, HttpMethod.GET);
        request.setExpiration(Date.from(expires));
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

    @PreDestroy
    void close() {
        if (signingClient != client) {
            signingClient.shutdown();
        }
        client.shutdown();
    }
}
