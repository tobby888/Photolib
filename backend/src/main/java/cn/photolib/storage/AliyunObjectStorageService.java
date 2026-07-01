package cn.photolib.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PutObjectRequest;
import jakarta.annotation.PreDestroy;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

public class AliyunObjectStorageService implements ObjectStorageService {
    private final StorageProperties properties;
    private final OSS client;

    public AliyunObjectStorageService(StorageProperties properties) {
        this.properties = properties;
        this.client = new OSSClientBuilder().build(properties.endpoint(),
                properties.accessKeyId(), properties.accessKeySecret());
    }

    @Override
    public SignedUrl presignPut(String objectKey, String contentType, Duration ttl) {
        Instant expires = Instant.now().plus(ttl);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                properties.bucket(), objectKey, HttpMethod.PUT);
        request.setExpiration(Date.from(expires));
        request.setContentType(contentType);
        URL url = client.generatePresignedUrl(request);
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
        return new SignedUrl(client.generatePresignedUrl(request), "GET", expires);
    }

    @Override
    public ObjectInfo stat(String objectKey) {
        ObjectMetadata metadata = client.getObjectMetadata(properties.bucket(), objectKey);
        return new ObjectInfo(metadata.getContentLength(), metadata.getContentType());
    }

    @Override
    public InputStream open(String objectKey) {
        OSSObject object = client.getObject(properties.bucket(), objectKey);
        return object.getObjectContent();
    }

    @Override
    public void put(String objectKey, InputStream input, long size, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(size);
        metadata.setContentType(contentType);
        client.putObject(new PutObjectRequest(properties.bucket(), objectKey, input, metadata));
    }

    @Override
    public void delete(String objectKey) {
        client.deleteObject(properties.bucket(), objectKey);
    }

    @PreDestroy
    void close() {
        client.shutdown();
    }
}
