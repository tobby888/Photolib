package cn.photolib.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 发信邮箱，读自 {@code .env} 的 {@code SMTP_*}（见 application.yml 的 {@code photolib.smtp}）。
 * host / username / password 三项齐了才算配置好，缺一项发送接口直接拒绝，不会排队后再全部失败。
 */
@ConfigurationProperties(prefix = "photolib.smtp")
public record SmtpProperties(String host, Integer port, String username, String password,
                             String fromName, Boolean ssl, Boolean starttls,
                             Duration connectTimeout, Duration readTimeout,
                             Integer concurrency, Duration sendInterval, String siteBaseUrl) {
    public SmtpProperties {
        port = port == null ? 465 : port;
        ssl = ssl == null || ssl;
        starttls = starttls != null && starttls;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
        concurrency = concurrency == null ? 2 : Math.max(1, Math.min(8, concurrency));
        sendInterval = sendInterval == null || sendInterval.isNegative() ? Duration.ofSeconds(1) : sendInterval;
    }

    public boolean configured() {
        return notBlank(host) && notBlank(username) && notBlank(password);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
