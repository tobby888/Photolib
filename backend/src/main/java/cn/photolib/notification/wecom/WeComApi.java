package cn.photolib.notification.wecom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * 企业微信服务端 API 的原始调用层：拼 URL、发请求、把 {@code errcode} 翻成异常。
 *
 * <p>刻意不认识 access_token 缓存——{@link WeComAccessTokenProvider} 换 token 时要调
 * {@link #fetchToken()}，而发消息又要先拿到 token，两者放同一个类里就会互相依赖。
 */
public class WeComApi {
    /**
     * 自带解析器，不注入容器里那个：全局 ObjectMapper 带着本站接口的序列化约定
     * （见 {@code common/config/JacksonConfig}），不该拿去解析第三方接口的报文。
     * 与 {@code AuditInterceptor}、{@code DatabaseDumpService} 的做法一致。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final WeComProperties properties;

    public WeComApi(RestClient http, WeComProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    /**
     * 用 corpid + corpsecret 换 access_token（{@code GET /cgi-bin/gettoken}）。
     *
     * <p>调用方只有 {@link WeComAccessTokenProvider}，别处不要直接调：这个接口有频率限制，
     * 且新签发会作废旧 token。
     */
    public WeComAccessTokenProvider.IssuedToken fetchToken() {
        ApiResult result = call(http.get().uri(uri -> uri.path("/cgi-bin/gettoken")
                .queryParam("corpid", properties.corpId())
                .queryParam("corpsecret", properties.secret())
                .build()));
        return new WeComAccessTokenProvider.IssuedToken(result.accessToken(),
                Duration.ofSeconds(result.expiresIn() == null ? 0 : result.expiresIn()));
    }

    /** 发送应用消息（{@code POST /cgi-bin/message/send}）。 */
    public void sendMessage(String accessToken, Map<String, Object> body) {
        String payload;
        try {
            payload = JSON.writeValueAsString(body);
        } catch (Exception exception) {
            throw new IllegalStateException("企业微信消息序列化失败", exception);
        }
        ApiResult result = call(http.post()
                .uri(uri -> uri.path("/cgi-bin/message/send")
                        .queryParam("access_token", accessToken).build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload));
        // errcode 为 0 但 invaliduser 非空时消息其实没送到（userid 不在通讯录、或不在应用可见范围）。
        // 不抛异常的话这条记录会以 SENT 落库，管理员再也看不出这个人从来没收到过通知。
        if (result.invaliduser() != null && !result.invaliduser().isBlank()) {
            throw new WeComApiException(0, "企业微信收件人无效：" + result.invaliduser()
                    + "（检查 userid 是否存在，以及该成员是否在应用的可见范围内）");
        }
    }

    /**
     * 企业微信部分接口以 {@code text/plain} 返回 JSON，所以先取字符串再自己解析，
     * 不依赖响应头去挑消息转换器。
     */
    private ApiResult call(RestClient.RequestHeadersSpec<?> request) {
        String payload;
        try {
            payload = request.retrieve().body(String.class);
        } catch (Exception exception) {
            throw new IllegalStateException("企业微信接口请求失败：" + exception.getMessage(), exception);
        }
        ApiResult result;
        try {
            result = JSON.readValue(payload == null ? "" : payload, ApiResult.class);
        } catch (Exception exception) {
            throw new IllegalStateException("企业微信接口返回无法解析：" + payload, exception);
        }
        if (result.errcode() != null && result.errcode() != 0) {
            throw new WeComApiException(result.errcode(),
                    "企业微信接口返回 errcode=" + result.errcode() + "，errmsg=" + result.errmsg());
        }
        return result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiResult(Integer errcode, String errmsg, String invaliduser,
                     @JsonProperty("access_token") String accessToken,
                     @JsonProperty("expires_in") Long expiresIn) {
    }
}
