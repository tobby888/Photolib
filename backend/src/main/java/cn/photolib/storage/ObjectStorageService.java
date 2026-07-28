package cn.photolib.storage;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

    /**
     * Looks up one exact key without enumerating its whole prefix. Backends
     * should only return empty for a confirmed not-found response and propagate
     * permission or connectivity failures.
     */
    Optional<ObjectInfo> find(String objectKey);

    InputStream open(String objectKey);

    default void put(String objectKey, InputStream input, long size, String contentType) {
        put(objectKey, input, size, contentType, Map.of());
    }

    void put(String objectKey, InputStream input, long size, String contentType,
             Map<String, String> userMetadata);

    void delete(String objectKey);

    record SignedUrl(URL url, String method, java.time.Instant expiresAt) {
    }

    record ObjectInfo(long size, String contentType, Map<String, String> userMetadata) {
        public ObjectInfo {
            userMetadata = normalizeUserMetadata(userMetadata);
        }

        public ObjectInfo(long size, String contentType) {
            this(size, contentType, Map.of());
        }

        private static Map<String, String> normalizeUserMetadata(Map<String, String> metadata) {
            if (metadata == null || metadata.isEmpty()) {
                return Map.of();
            }
            Map<String, String> normalized = new LinkedHashMap<>();
            metadata.forEach((key, value) -> {
                String normalizedKey = Objects.requireNonNull(key, "user metadata key")
                        .toLowerCase(Locale.ROOT);
                String normalizedValue = Objects.requireNonNull(value,
                        "user metadata value for " + normalizedKey);
                if (normalized.putIfAbsent(normalizedKey, normalizedValue) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate user metadata key ignoring case: " + normalizedKey);
                }
            });
            return Map.copyOf(normalized);
        }
    }

    record StoredObject(String objectKey, long size) {
    }
}
