package cn.photolib.notification.wecom;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WeComPropertiesTests {

    /**
     * application.yml 把三个必填项写成 {@code ${WECOM_*:}}，没配环境变量时绑定到的是空字符串。
     * 如果空串不能转成 {@code Long}，没接企业微信的部署会直接起不来——这条用例守住这一点。
     */
    @Test
    void bind_withUnsetEnvironmentVariables_shouldYieldAnUnconfiguredInstance() {
        WeComProperties properties = bind(Map.of(
                "photolib.wecom.corp-id", "",
                "photolib.wecom.agent-id", "",
                "photolib.wecom.secret", "",
                "photolib.wecom.site-base-url", "",
                "photolib.wecom.base-url", "https://qyapi.weixin.qq.com"));

        assertThat(properties.configured()).isFalse();
        assertThat(properties.agentId()).isNull();
        assertThat(properties.siteBaseUrl()).isNull();
    }

    @Test
    void bind_withEveryValueSet_shouldBeConfigured() {
        WeComProperties properties = bind(Map.of(
                "photolib.wecom.corp-id", " wwabc ",
                "photolib.wecom.agent-id", "1000002",
                "photolib.wecom.secret", " s3cret ",
                "photolib.wecom.site-base-url", "https://photolib.example.cn/",
                "photolib.wecom.token-refresh-ahead", "10m"));

        assertThat(properties.configured()).isTrue();
        assertThat(properties.corpId()).isEqualTo("wwabc");
        assertThat(properties.secret()).isEqualTo("s3cret");
        // 站点地址的结尾斜杠必须去掉，否则链接会拼成 https://host//#/requests。
        assertThat(properties.siteBaseUrl()).isEqualTo("https://photolib.example.cn");
        assertThat(properties.tokenRefreshAhead()).isEqualTo(Duration.ofMinutes(10));
    }

    /**
     * {@code WECOM_SITE_BASE_URL=photowarehouse.cn} 是很自然的写法，但拼出来的
     * {@code photowarehouse.cn/#/notifications} 在企业微信里点不开——渲染成链接需要
     * 一个绝对 URL。
     */
    @Test
    void bind_withASiteBaseUrlMissingItsScheme_shouldAssumeHttps() {
        assertThat(bind(Map.of("photolib.wecom.site-base-url", "photowarehouse.cn")).siteBaseUrl())
                .isEqualTo("https://photowarehouse.cn");
        assertThat(bind(Map.of("photolib.wecom.site-base-url", "http://photowarehouse.cn/"))
                .siteBaseUrl()).isEqualTo("http://photowarehouse.cn");
    }

    @Test
    void bind_shouldDefaultToMarkdownAndAcceptATextOverride() {
        assertThat(bind(Map.of("photolib.wecom.corp-id", "wwabc")).messageType())
                .isEqualTo(WeComMessageType.MARKDOWN);
        assertThat(bind(Map.of("photolib.wecom.message-type", "text")).messageType())
                .isEqualTo(WeComMessageType.TEXT);
    }

    @Test
    void bind_withoutOptionalValues_shouldFallBackToDefaults() {
        WeComProperties properties = bind(Map.of("photolib.wecom.corp-id", "wwabc"));

        assertThat(properties.baseUrl()).isEqualTo("https://qyapi.weixin.qq.com");
        assertThat(properties.tokenRefreshAhead()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    private WeComProperties bind(Map<String, String> values) {
        ConfigurationPropertySource source =
                new MapConfigurationPropertySource(new LinkedHashMap<String, Object>(values));
        return new Binder(source).bind("photolib.wecom", WeComProperties.class).get();
    }
}
