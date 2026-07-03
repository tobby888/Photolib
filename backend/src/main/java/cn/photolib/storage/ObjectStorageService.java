package cn.photolib.storage;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.List;

public interface ObjectStorageService {
    /**
     * Ensures that the backing store is ready for use. Implementations must make
     * this operation idempotent because it is invoked on every application start.
     */
    void initialize();

    /**
     * Returns the objects that really exist in the backing store.
     */
    List<StoredObject> list(String prefix);

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

    record StoredObject(String objectKey, long size) {
    }
}
