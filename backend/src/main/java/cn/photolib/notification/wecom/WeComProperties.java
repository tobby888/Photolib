package cn.photolib.notification.wecom;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 企业微信自建应用的接入参数。
 *
 * <p>{@code corpId} 是企业 ID，{@code secret} 是这个自建应用自己的 Secret，两者换出的
 * access_token 只能操作该应用；{@code agentId} 是应用 ID，发消息时必须带上。三者缺一
 * 不可，缺失时 {@link WeComConfig} 装配一个直接抛错的网关，让投递以 FAILED 落库并触发
 * 管理员告警，而不是静默把消息丢掉。
 *
 * <p>{@code siteBaseUrl} 只影响消息末尾的跳转链接（站点是 Hash Router，链接形如
 * {@code https://host/#/requests}）。没配就只发正文，不影响投递本身。
 */
@ConfigurationProperties(prefix = "photolib.wecom")
public record WeComProperties(String baseUrl, String corpId, Long agentId, String secret,
                              String siteBaseUrl, Duration tokenRefreshAhead,
                              Duration connectTimeout, Duration readTimeout) {

    public WeComProperties {
        baseUrl = normalizeUrl(baseUrl, "https://qyapi.weixin.qq.com");
        siteBaseUrl = normalizeUrl(siteBaseUrl, null);
        corpId = trimToNull(corpId);
        secret = trimToNull(secret);
        tokenRefreshAhead = tokenRefreshAhead == null ? Duration.ofMinutes(5) : tokenRefreshAhead;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }

    public boolean configured() {
        return corpId != null && secret != null && agentId != null;
    }

    private static String normalizeUrl(String value, String fallback) {
        String trimmed = trimToNull(value);
        if (trimmed == null) return fallback;
        int end = trimmed.length();
        while (end > 0 && trimmed.charAt(end - 1) == '/') end--;
        return end == 0 ? fallback : trimmed.substring(0, end);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
