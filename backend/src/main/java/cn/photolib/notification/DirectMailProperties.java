package cn.photolib.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "photolib.direct-mail")
public record DirectMailProperties(String regionId, String accountName,
                                   String accessKeyId, String accessKeySecret) {
    boolean configured() {
        return accountName != null && !accountName.isBlank()
                && accessKeyId != null && !accessKeyId.isBlank()
                && accessKeySecret != null && !accessKeySecret.isBlank();
    }
}
