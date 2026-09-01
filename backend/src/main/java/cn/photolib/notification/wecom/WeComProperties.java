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
                              String siteBaseUrl, WeComMessageType messageType,
                              Duration tokenRefreshAhead,
                              Duration connectTimeout, Duration readTimeout) {

    public WeComProperties {
        baseUrl = normalizeUrl(baseUrl, "https://qyapi.weixin.qq.com");
        siteBaseUrl = normalizeUrl(siteBaseUrl, null);
        corpId = trimToNull(corpId);
        secret = trimToNull(secret);
        messageType = messageType == null ? WeComMessageType.MARKDOWN : messageType;
        tokenRefreshAhead = tokenRefreshAhead == null ? Duration.ofMinutes(5) : tokenRefreshAhead;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }

    public boolean configured() {
        return corpId != null && secret != null && agentId != null;
    }

    /**
     * 去掉结尾斜杠，并在缺少协议头时补上 {@code https://}。
     *
     * <p>补协议是必要的：{@code WECOM_SITE_BASE_URL=photowarehouse.cn} 是很自然的写法，
     * 但拼出来的 {@code photowarehouse.cn/#/notifications} 在企业微信里点不开——它需要
     * 一个绝对 URL 才会渲染成链接。
     */
    private static String normalizeUrl(String value, String fallback) {
        String trimmed = trimToNull(value);
        if (trimmed == null) return fallback;
        int end = trimmed.length();
        while (end > 0 && trimmed.charAt(end - 1) == '/') end--;
        if (end == 0) return fallback;
        String withoutTrailingSlash = trimmed.substring(0, end);
        return withoutTrailingSlash.contains("://")
                ? withoutTrailingSlash
                : "https://" + withoutTrailingSlash;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
