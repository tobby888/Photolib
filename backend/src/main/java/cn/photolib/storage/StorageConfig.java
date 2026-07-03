package cn.photolib.storage;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.InputStream;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    ObjectStorageService objectStorageService(StorageProperties properties) {
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
            public SignedUrl presignGet(String key, String name, Duration ttl) { throw unavailable(); }
            public ObjectInfo stat(String key) { throw unavailable(); }
            public InputStream open(String key) { throw unavailable(); }
            public void put(String key, InputStream input, long size, String type) { throw unavailable(); }
            public void delete(String key) { throw unavailable(); }
        };
    }
}
