package cn.photolib.notification.wecom;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WeComProperties.class)
public class WeComConfig {

    /**
     * 未配置时装一个直接抛错的网关，而不是一个静默丢弃的空实现：投递会以 FAILED 落库、
     * 连续失败后进管理员告警，配置漏了能被发现。这与 DirectMail 的处理保持一致。
     */
    @Bean
    WeComGateway weComGateway(WeComProperties properties) {
        if (!properties.configured()) {
            return (toUser, subject, html, actionPath) -> {
                throw new IllegalStateException("企业微信通知尚未配置（缺少 corpId / agentId / secret）");
            };
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        // 独立于容器里的 RestClient 配置：这条链路自带超时与 JSON 处理（见 WeComApi），
        // 不该被本站接口的拦截器、转换器或基地址影响。
        RestClient http = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
        WeComApi api = new WeComApi(http, properties);
        WeComAccessTokenProvider tokens = new WeComAccessTokenProvider(
                api::fetchToken, properties.tokenRefreshAhead(), Clock.systemUTC());
        return new WeComApiClient(api, properties, tokens);
    }
}
