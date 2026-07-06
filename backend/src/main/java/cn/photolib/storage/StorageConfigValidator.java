package cn.photolib.storage;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
@ConditionalOnProperty(prefix = "photolib.storage", name = "mode", havingValue = "local")
@RequiredArgsConstructor
public class StorageConfigValidator {
    private static final Logger log = LoggerFactory.getLogger(StorageConfigValidator.class);
    private static final Set<String> WEAK_SECRETS = Set.of(
        "photolib-test-signing-secret",
        "test-secret",
        "secret",
        "changeme",
        "default"
    );
    private static final int MIN_SECRET_LENGTH = 32;

    private final StorageProperties properties;

    @PostConstruct
    public void validateConfig() {
        if (!properties.local()) {
            return;
        }

        String secret = properties.signingSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "本地存储模式要求配置 photolib.storage.signing-secret"
            );
        }

        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                "签名密钥过短，至少需要 " + MIN_SECRET_LENGTH + " 个字符"
            );
        }

        if (WEAK_SECRETS.contains(secret.toLowerCase())) {
            throw new IllegalStateException(
                "不能使用默认或弱签名密钥，请配置强随机密钥"
            );
        }

        log.info("本地存储签名密钥验证通过");
    }
}
