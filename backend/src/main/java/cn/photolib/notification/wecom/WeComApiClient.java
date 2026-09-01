package cn.photolib.notification.wecom;

import org.jsoup.Jsoup;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把一条站内通知投递成企业微信应用消息。
 *
 * <p>只发 {@code text} 类型。正文是站内信的纯文本（HTML 由 Jsoup 摘掉）加一行跳转链接——
 * 比 {@code textcard} 少一层长度与必填 url 的约束，而通知要传达的就是这段文字。
 */
public class WeComApiClient implements WeComGateway {
    /** 企业微信对 text 消息正文的上限是 2048 字节，超出会整条拒收而不是截断。 */
    private static final int MAX_CONTENT_BYTES = 2048;
    private static final String ELLIPSIS = "…";

    private final WeComApi api;
    private final WeComProperties properties;
    private final WeComAccessTokenProvider tokens;

    public WeComApiClient(WeComApi api, WeComProperties properties,
                          WeComAccessTokenProvider tokens) {
        this.api = api;
        this.properties = properties;
        this.tokens = tokens;
    }

    @Override
    public void send(String toUser, String subject, String html, String actionPath) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", toUser);
        body.put("msgtype", "text");
        body.put("agentid", properties.agentId());
        body.put("text", Map.of("content", content(subject, html, actionPath)));
        // 不走保密消息：保密消息只能在企业微信客户端内查看且不可转发，对通知没有意义。
        body.put("safe", 0);

        String token = tokens.token();
        try {
            api.sendMessage(token, body);
        } catch (WeComApiException exception) {
            if (!exception.tokenRejected()) throw exception;
            // token 可能被别处重新签发而作废。作废本地缓存后用新 token 再试一次，
            // 只重试一次：新 token 仍被拒说明问题不在 token 上，继续重试只会放大限频。
            tokens.invalidate(token);
            api.sendMessage(tokens.token(), body);
        }
    }

    String content(String subject, String html, String actionPath) {
        StringBuilder text = new StringBuilder();
        if (subject != null && !subject.isBlank()) text.append(subject.trim()).append('\n');
        String plain = html == null ? "" : Jsoup.parse(html).text();
        if (!plain.isBlank()) text.append(plain.trim());
        String link = link(actionPath);
        if (link != null) {
            if (!text.isEmpty()) text.append("\n\n");
            text.append("查看详情：").append(link);
        }
        return truncateToBytes(text.toString(), MAX_CONTENT_BYTES);
    }

    private String link(String actionPath) {
        if (properties.siteBaseUrl() == null) return null;
        String path = actionPath == null || actionPath.isBlank() ? "/notifications" : actionPath.trim();
        if (!path.startsWith("/")) path = "/" + path;
        // 前端是 Hash Router，链接必须带 #，否则后端 JAR 直接返回 404。
        return properties.siteBaseUrl() + "/#" + path;
    }

    /**
     * 按 UTF-8 字节数截断，且不切开一个字符。
     *
     * <p>按 {@code String.length()} 截会算错（一个汉字三字节），按字节数组硬切会切出半个
     * 汉字——企业微信收到非法 UTF-8 整条拒收，于是一条超长通知永远重试、永远失败。
     */
    static String truncateToBytes(String value, int maxBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return value;
        int budget = maxBytes - ELLIPSIS.getBytes(StandardCharsets.UTF_8).length;
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        CharBuffer in = CharBuffer.wrap(value);
        encoder.encode(in, ByteBuffer.allocate(budget), true);
        return value.substring(0, in.position()) + ELLIPSIS;
    }
}
