package cn.photolib.storage;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.performance.SnapshotRecorder;
import cn.photolib.performance.TimedObjectStorageService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.InputStream;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    /**
     * 实际的存储实现外面套一层计时（{@link TimedObjectStorageService}）：性能快照开着时记下
     * 每次存储操作的耗时，关着时直接转交。要拿具体实现时用 {@link TimedObjectStorageService#unwrap}。
     */
    @Bean
    ObjectStorageService objectStorageService(StorageProperties properties, SnapshotRecorder recorder) {
        return new TimedObjectStorageService(createStorage(properties), recorder);
    }

    private static ObjectStorageService createStorage(StorageProperties properties) {
        if (properties.local()) {
            return new LocalObjectStorageService(properties);
        }
        if (properties.ossConfigured()) {
            return new AliyunObjectStorageService(properties);
        }
        return new ObjectStorageService() {
            private BusinessException unavailable() {
                return new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "OSS 尚未配置");
            }

            public void initialize() { throw unavailable(); }
            public java.util.List<StoredObject> list(String prefix) { throw unavailable(); }
            public SignedUrl presignPut(String key, String type, Duration ttl) { throw unavailable(); }
            public SignedUrl presignGet(String key, String name, Duration ttl,
                                        String cacheControl) { throw unavailable(); }
            public ObjectInfo stat(String key) { throw unavailable(); }
            public java.util.Optional<ObjectInfo> find(String key) { throw unavailable(); }
            public InputStream open(String key) { throw unavailable(); }
            public void put(String key, InputStream input, long size, String type,
                            java.util.Map<String, String> userMetadata) { throw unavailable(); }
            public void delete(String key) { throw unavailable(); }
        };
    }
}
