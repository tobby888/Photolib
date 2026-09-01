package cn.photolib.notification.wecom;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class WeComApiClientTests {
    private final WeComApi api = mock(WeComApi.class);
    private final AtomicInteger fetches = new AtomicInteger();
    private final WeComAccessTokenProvider tokens = new WeComAccessTokenProvider(
            () -> new WeComAccessTokenProvider.IssuedToken(
                    "token-" + fetches.incrementAndGet(), Duration.ofSeconds(7200)),
            Duration.ofMinutes(5), Clock.systemUTC());

    @Test
    void send_shouldPostATextMessageForTheConfiguredAgent() {
        client("https://photolib.example.cn").send("ZhangSan", "新的图片需求",
                "<p>毕业典礼&nbsp;拍摄</p>", "/requests");

        Map<String, Object> body = captureBody();
        assertThat(body).containsEntry("touser", "ZhangSan")
                .containsEntry("msgtype", "text")
                .containsEntry("agentid", 1000002L)
                .containsEntry("safe", 0);
        assertThat(content(body)).isEqualTo("""
                新的图片需求
                毕业典礼 拍摄

                查看详情：https://photolib.example.cn/#/requests""");
    }

    /** 前端是 Hash Router，链接漏了 # 会被后端当成未知路径直接 404。 */
    @Test
    void send_withoutActionPath_shouldLinkToTheInAppNotificationList() {
        client("https://photolib.example.cn").send("ZhangSan", "标题", "<p>正文</p>", null);

        assertThat(content(captureBody()))
                .endsWith("查看详情：https://photolib.example.cn/#/notifications");
    }

    @Test
    void send_withoutSiteBaseUrl_shouldStillDeliverTheBody() {
        client(null).send("ZhangSan", "标题", "<p>正文</p>", "/requests");

        assertThat(content(captureBody())).isEqualTo("标题\n正文").doesNotContain("查看详情");
    }

    @Test
    void send_whenTokenIsRejected_shouldRetryOnceWithAFreshToken() {
        doThrow(new WeComApiException(42001, "access_token expired"))
                .doNothing().when(api).sendMessage(eq("token-1"), any());

        client(null).send("ZhangSan", "标题", "<p>正文</p>", null);

        verify(api).sendMessage(eq("token-1"), any());
        verify(api).sendMessage(eq("token-2"), any());
    }

    /**
     * 非 token 类错误（收件人不存在、IP 不在白名单……）重试也不会好，直接抛给
     * NotificationService 记 RETRYING/FAILED，errcode 留在 last_error 里供排查。
     */
    @Test
    void send_whenTheFailureIsNotAboutTheToken_shouldNotRetry() {
        doThrow(new WeComApiException(60020, "not allow to access from your ip"))
                .when(api).sendMessage(any(), any());

        assertThatThrownBy(() -> client(null).send("ZhangSan", "标题", "<p>正文</p>", null))
                .isInstanceOfSatisfying(WeComApiException.class,
                        failure -> assertThat(failure.errcode()).isEqualTo(60020));
        verify(api, times(1)).sendMessage(any(), any());
    }

    @Test
    void send_asMarkdown_shouldUseTheMarkdownPayloadShape() {
        client("https://photowarehouse.cn", WeComMessageType.MARKDOWN)
                .send("ZhangSan", "新的图片需求", "<p>毕业典礼 <b>下午三点</b></p>", "/requests");

        Map<String, Object> body = captureBody();
        assertThat(body).containsEntry("msgtype", "markdown").doesNotContainKey("text")
                // safe 只对 text 有意义，markdown 消息不接受这个参数。
                .doesNotContainKey("safe");
        assertThat(markdown(body)).isEqualTo("""
                # 新的图片需求

                毕业典礼 **下午三点**

                [查看详情](https://photowarehouse.cn/#/requests)""");
    }

    /** 标题也是用户可控的（需求标题会原样进来），不能让它在企微里造出链接。 */
    @Test
    void send_asMarkdown_shouldEscapeTheSubject() {
        client(null, WeComMessageType.MARKDOWN)
                .send("ZhangSan", "[点这里](http://evil.cn)", "<p>正文</p>", null);

        // 方括号被转义就构不成链接语法了，后面那对圆括号只是普通文字。
        assertThat(markdown(captureBody())).startsWith("# \\[点这里\\]")
                .doesNotContain("[点这里](");
    }

    /**
     * 正文越长收件人越需要那个链接——图片和超长内容只在站内看得全。整条拼完再截会
     * 把末尾的链接切掉，正好切在最不该切的地方。
     */
    @Test
    void send_withOverlongBody_shouldKeepTheHeadingAndTheLink() {
        client("https://photowarehouse.cn", WeComMessageType.MARKDOWN)
                .send("ZhangSan", "长通知", "<p>" + "内容".repeat(2000) + "</p>", "/requests");

        String content = markdown(captureBody());
        assertThat(content.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2048);
        assertThat(content).startsWith("# 长通知")
                .endsWith("[查看详情](https://photowarehouse.cn/#/requests)");
    }

    @Test
    void truncateToBytes_shouldCutOnACharacterBoundary() {
        String overlong = "中".repeat(1200);

        String truncated = WeComApiClient.truncateToBytes(overlong, 2048);

        assertThat(truncated.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2048);
        assertThat(truncated).endsWith("…").startsWith("中中中");
        // 逐字符解码得回原样，说明没有把一个汉字切成半个。
        assertThat(new String(truncated.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .isEqualTo(truncated);
    }

    @Test
    void truncateToBytes_shouldLeaveShortContentUntouched() {
        assertThat(WeComApiClient.truncateToBytes("短通知", 2048)).isEqualTo("短通知");
    }

    private WeComApiClient client(String siteBaseUrl) {
        return client(siteBaseUrl, WeComMessageType.TEXT);
    }

    private WeComApiClient client(String siteBaseUrl, WeComMessageType messageType) {
        WeComProperties properties = new WeComProperties(null, "corp", 1000002L, "secret",
                siteBaseUrl, messageType, null, null, null);
        return new WeComApiClient(api, properties, tokens);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureBody() {
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(api).sendMessage(any(), body.capture());
        return body.getValue();
    }

    @SuppressWarnings("unchecked")
    private String content(Map<String, Object> body) {
        return (String) ((Map<String, Object>) body.get("text")).get("content");
    }

    @SuppressWarnings("unchecked")
    private String markdown(Map<String, Object> body) {
        return (String) ((Map<String, Object>) body.get("markdown")).get("content");
    }
}
