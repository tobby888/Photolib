package cn.photolib.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.CreateBucketRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.PutObjectRequest;
import jakarta.annotation.PreDestroy;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;

public class AliyunObjectStorageService implements ObjectStorageService {
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
        if (signingClient != client) {
            signingClient.shutdown();
        }
        client.shutdown();
    }
}
