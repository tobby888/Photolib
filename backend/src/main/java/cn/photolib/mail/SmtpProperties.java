package cn.photolib.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 发信设置，读自 {@code .env} 的 {@code SMTP_*}（见 application.yml 的 {@code photolib.smtp}）。
 *
 * <p>发信邮箱主要在后台「邮件模板 → 发信邮箱」里配置（{@code smtp_account} 表，可配多个、按顺序顺延）；
 * 这里的 host / username / password 是可选的兜底邮箱，三项齐了才算配置，排在后台配置的所有邮箱之后。
 * 并发、间隔、超时、站点地址对所有邮箱通用。</p>
 *
 * <p>{@code fromAddress} 是发件地址。多数邮箱的 SMTP 登录名就是邮箱地址，留空即可；但有些服务商的
 * 登录名和发件地址是分开的（SendGrid 登录名是 {@code apikey}、Amazon SES 是 IAM 凭据、
 * 部分企业中继用工号登录），这时必须单独填。</p>
 */
@ConfigurationProperties(prefix = "photolib.smtp")
public record SmtpProperties(String host, Integer port, String username, String password,
                             String fromAddress, String fromName, Boolean ssl, Boolean starttls,
                             Duration connectTimeout, Duration readTimeout,
                             Integer concurrency, Duration sendInterval, String siteBaseUrl,
                             String encryptionKey) {
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

    /** {@code .env} 里的兜底邮箱；没配齐时为 {@code null}。 */
    SmtpServer envServer() {
        if (!configured()) return null;
        String security = ssl ? SmtpServer.SSL : starttls ? SmtpServer.STARTTLS : SmtpServer.NONE;
        return new SmtpServer(null, SmtpServer.ENV_NAME, host.trim(), port, username.trim(), password,
                notBlank(fromAddress) ? fromAddress.trim() : null,
                notBlank(fromName) ? fromName.trim() : null, security, null);
    }

    public String siteUrl() {
        if (!notBlank(siteBaseUrl)) return null;
        String url = siteBaseUrl.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url + "/#/";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
