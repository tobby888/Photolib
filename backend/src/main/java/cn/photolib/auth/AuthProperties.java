package cn.photolib.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "photolib.auth")
public record AuthProperties(
        Duration accessTtl,
        Duration idleTtl,
        boolean secureCookie
) {
}
