package cn.photolib.storage;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;

public interface ObjectStorageService {
    SignedUrl presignPut(String objectKey, String contentType, Duration ttl);

    SignedUrl presignGet(String objectKey, String downloadName, Duration ttl);

    ObjectInfo stat(String objectKey);

    InputStream open(String objectKey);

    void put(String objectKey, InputStream input, long size, String contentType);

    void delete(String objectKey);

    record SignedUrl(URL url, String method, java.time.Instant expiresAt) {
    }

    record ObjectInfo(long size, String contentType) {
    }
}
