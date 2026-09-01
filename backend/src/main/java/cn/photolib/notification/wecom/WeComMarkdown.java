package cn.photolib.notification.wecom;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * 把站内信的 HTML 正文转成企业微信 markdown 消息能显示的写法。
 *
 * <p><b>企业微信的 markdown 是个很小的子集</b>：标题、加粗、链接、行内代码、引用、
 * 字体颜色。<b>不支持图片</b>，也不支持斜体和表格。所以这里不是通用的 HTML→Markdown
 * 转换器，只认识站内信两张白名单（{@code SYSTEM_MAIL_HTML} 与 {@code MESSAGE_HTML}）
 * 放行的那些标签，其余一律降级成纯文本而不是原样输出——把不支持的语法发过去，
 * 企业微信不会报错，只会把标记当正文显示给收件人看。
 */
public final class WeComMarkdown {
    private WeComMarkdown() {
    }

    /**
     * @param siteBaseUrl 站点地址，用来把正文里的相对链接补成绝对地址；为空时相对链接
     *                    只保留文字，不生成点不开的链接
     */
    public static String fromHtml(String html, String siteBaseUrl) {
        StringBuilder out = new StringBuilder();
        renderChildren(Jsoup.parseBodyFragment(html == null ? "" : html).body(), out, siteBaseUrl);
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    private static void renderChildren(Element parent, StringBuilder out, String base) {
        for (Node child : parent.childNodes()) {
            render(child, out, base);
        }
    }

    private static void render(Node node, StringBuilder out, String base) {
        if (node instanceof TextNode text) {
            out.append(escape(text.text()));
            return;
        }
        if (!(node instanceof Element element)) return;
        switch (element.normalName()) {
            case "br" -> out.append('\n');
            // 企业微信 markdown 发不了图片。留个占位，正文末尾的链接才是收件人看到
            // 完整内容的入口。
            case "img" -> out.append("[图片]");
            case "h1", "h2", "h3", "h4", "h5", "h6" ->
                    block(out, "#".repeat(headingLevel(element)) + " ", element, base, 2);
            case "p", "div", "pre" -> block(out, "", element, base, 2);
            case "li" -> block(out, "- ", element, base, 1);
            case "blockquote" -> quote(out, element, base);
            case "strong", "b" -> wrap(out, "**", element, base);
            case "code" -> wrap(out, "`", element, base);
            case "a" -> link(out, element, base);
            // em/i 不做处理：企业微信 markdown 没有斜体，加标记只会让收件人看到裸的
            // 星号。ul/ol/span 等容器直接下钻。
            default -> renderChildren(element, out, base);
        }
    }

    private static void block(StringBuilder out, String prefix, Element element,
                              String base, int trailingNewlines) {
        StringBuilder inner = new StringBuilder();
        renderChildren(element, inner, base);
        String content = inner.toString().strip();
        if (content.isEmpty()) return;
        separate(out);
        out.append(prefix).append(content).append("\n".repeat(trailingNewlines));
    }

    private static void quote(StringBuilder out, Element element, String base) {
        StringBuilder inner = new StringBuilder();
        renderChildren(element, inner, base);
        String content = inner.toString().strip();
        if (content.isEmpty()) return;
        separate(out);
        // 引用要逐行加前缀：只在第一行加的话，后面几行会脱出引用块变成普通正文。
        content.lines().forEach(line -> out.append("> ").append(line).append('\n'));
        out.append('\n');
    }

    private static void wrap(StringBuilder out, String marker, Element element, String base) {
        StringBuilder inner = new StringBuilder();
        renderChildren(element, inner, base);
        String content = inner.toString().strip();
        if (content.isEmpty()) return;
        out.append(marker).append(content).append(marker);
    }

    private static void link(StringBuilder out, Element element, String base) {
        StringBuilder inner = new StringBuilder();
        renderChildren(element, inner, base);
        String text = inner.toString().strip();
        String href = absoluteHref(element.attr("href"), base);
        if (text.isEmpty()) return;
        // 拼不出绝对地址就只留文字：企业微信里的相对链接点开是死链，不如不给。
        if (href == null) {
            out.append(text);
            return;
        }
        out.append('[').append(text).append("](").append(href).append(')');
    }

    private static String absoluteHref(String href, String base) {
        if (href == null || href.isBlank()) return null;
        String trimmed = href.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed;
        if (base == null || !trimmed.startsWith("/")) return null;
        return base + trimmed;
    }

    private static int headingLevel(Element element) {
        return element.normalName().charAt(1) - '0';
    }

    private static void separate(StringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
    }

    /**
     * 转义正文里能构成 markdown 结构的字符。
     *
     * <p>不转义的话，一个标题叫 {@code [点这里](http://evil.cn)} 的需求，会让每个校区
     * 负责人在企业微信里收到一条带可点击链接、看起来还是系统发的消息——正文本来是
     * 用户可控的纯文本，不该有能力在消息里造出链接。
     *
     * <p>代价是：如果企业微信不认反斜杠转义，收件人会看到一个多余的反斜杠。这在中文
     * 通知里很少见（方括号一般写作【】），比放任构造链接划算。
     */
    public static String escapeText(String text) {
        return text == null ? "" : escape(text);
    }

    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (char character : text.toCharArray()) {
            if (character == '\\' || character == '*' || character == '`'
                    || character == '[' || character == ']') {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        return escaped.toString();
    }
}
