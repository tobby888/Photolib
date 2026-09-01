package cn.photolib.notification.wecom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeComMarkdownTests {
    private static final String SITE = "https://photowarehouse.cn";

    @Test
    void fromHtml_shouldConvertHeadingsBoldAndParagraphs() {
        String markdown = WeComMarkdown.fromHtml(
                "<h2>拍摄安排</h2><p>今晚 <b>七点</b> 集合</p><p>带好器材</p>", SITE);

        assertThat(markdown).isEqualTo("""
                ## 拍摄安排

                今晚 **七点** 集合

                带好器材""");
    }

    @Test
    void fromHtml_shouldTurnRelativeLinksIntoAbsoluteOnes() {
        String markdown = WeComMarkdown.fromHtml(
                "<p>去 <a href=\"/requests\">需求列表</a> 接单</p>", SITE);

        assertThat(markdown).isEqualTo("去 [需求列表](https://photowarehouse.cn/requests) 接单");
    }

    /** 企业微信里的相对链接点开是死链，不如只留文字。 */
    @Test
    void fromHtml_withoutSiteBaseUrl_shouldKeepRelativeLinkTextOnly() {
        String markdown = WeComMarkdown.fromHtml(
                "<p>去 <a href=\"/requests\">需求列表</a> 接单</p>", null);

        assertThat(markdown).isEqualTo("去 需求列表 接单").doesNotContain("](");
    }

    @Test
    void fromHtml_shouldKeepAbsoluteLinks() {
        assertThat(WeComMarkdown.fromHtml("<p><a href=\"https://example.cn/a\">文档</a></p>", SITE))
                .isEqualTo("[文档](https://example.cn/a)");
    }

    /** 企业微信 markdown 发不了图片，正文只留占位，靠消息末尾的链接看全内容。 */
    @Test
    void fromHtml_shouldReplaceImagesWithAPlaceholder() {
        String markdown = WeComMarkdown.fromHtml(
                "<p>场地示意</p><p><img src=\"/api/v1/notifications/images/0123456789ABCDEFGHJKMNPQRS\"></p>",
                SITE);

        assertThat(markdown).isEqualTo("场地示意\n\n[图片]");
    }

    @Test
    void fromHtml_shouldPrefixEveryLineOfAQuote() {
        String markdown = WeComMarkdown.fromHtml(
                "<blockquote><p>第一行</p><p>第二行</p></blockquote>", SITE);

        // 只给首行加前缀的话，第二行会脱出引用块。
        assertThat(markdown).isEqualTo("> 第一行\n> \n> 第二行");
    }

    @Test
    void fromHtml_shouldRenderListItemsAsDashes() {
        assertThat(WeComMarkdown.fromHtml("<ul><li>相机</li><li>三脚架</li></ul>", SITE))
                .isEqualTo("- 相机\n- 三脚架");
    }

    /** 企业微信没有斜体，加标记只会让收件人看到裸的星号。 */
    @Test
    void fromHtml_shouldDropItalicsRatherThanEmitUnsupportedMarkers() {
        assertThat(WeComMarkdown.fromHtml("<p>请<em>务必</em>准时</p>", SITE))
                .isEqualTo("请务必准时");
    }

    @Test
    void fromHtml_shouldConvertLineBreaksAndInlineCode() {
        assertThat(WeComMarkdown.fromHtml("<p>第一行<br>第二行 <code>ISO100</code></p>", SITE))
                .isEqualTo("第一行\n第二行 `ISO100`");
    }

    /**
     * 正文是用户可控的纯文本，不该有能力在企业微信消息里造出一个可点击的链接——
     * 一个标题叫 [点这里](http://evil.cn) 的需求会推给全部校区负责人。
     */
    @Test
    void fromHtml_shouldEscapeMarkdownSyntaxComingFromUserText() {
        String markdown = WeComMarkdown.fromHtml(
                "<p>[点这里](http://evil.cn) 和 **加粗**</p>", SITE);

        assertThat(markdown).doesNotContain("[点这里](http://evil.cn)")
                .contains("\\[点这里\\]").contains("\\*\\*加粗\\*\\*");
    }

    @Test
    void escapeText_shouldEscapeStructuralCharactersOnly() {
        assertThat(WeComMarkdown.escapeText("毕业典礼 [B区] *重要*"))
                .isEqualTo("毕业典礼 \\[B区\\] \\*重要\\*");
        assertThat(WeComMarkdown.escapeText("普通标题")).isEqualTo("普通标题");
    }

    @Test
    void fromHtml_withEmptyOrNullInput_shouldReturnEmptyString() {
        assertThat(WeComMarkdown.fromHtml(null, SITE)).isEmpty();
        assertThat(WeComMarkdown.fromHtml("<p></p><div>  </div>", SITE)).isEmpty();
    }
}
