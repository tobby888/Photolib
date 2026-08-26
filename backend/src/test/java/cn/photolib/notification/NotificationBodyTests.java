package cn.photolib.notification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationBodyTests {
    @Test
    void markupInUserSuppliedTextIsEscapedRatherThanInterpreted() {
        // Call sites used to concatenate these straight into HTML. The safelist in
        // notifyUser would have stripped the tag, but only as a backstop — and it
        // also rewrote the reader's copy of the title in the process.
        assertThat(NotificationService.paragraphs("<script>alert(1)</script>"))
                .isEqualTo("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>");
        assertThat(NotificationService.paragraphs("毕业季 <预告> & 花絮"))
                .isEqualTo("<p>毕业季 &lt;预告&gt; &amp; 花絮</p>");
        assertThat(NotificationService.paragraphs("closing</p><p>injected"))
                .isEqualTo("<p>closing&lt;/p&gt;&lt;p&gt;injected</p>");
    }

    @Test
    void eachArgumentBecomesItsOwnParagraphAndBlanksAreDropped() {
        assertThat(NotificationService.paragraphs("需求已退回。", "退回原因：光线太暗"))
                .isEqualTo("<p>需求已退回。</p><p>退回原因：光线太暗</p>");
        assertThat(NotificationService.paragraphs("  留白  ")).isEqualTo("<p>留白</p>");
        assertThat(NotificationService.paragraphs(null, "", "   ")).isEmpty();
        assertThat(NotificationService.paragraphs()).isEmpty();
    }

    @Test
    void escapedBodySurvivesTheMailSafelistUnchanged() {
        // The two layers must agree: escaping here must not be undone downstream.
        String body = NotificationService.paragraphs("标题带 <b> 和 &");
        String cleaned = org.jsoup.Jsoup.clean(body, "",
                org.jsoup.safety.Safelist.none().addTags("p", "div", "br", "strong", "b", "em", "i"),
                new org.jsoup.nodes.Document.OutputSettings().prettyPrint(false));
        assertThat(org.jsoup.Jsoup.parse(cleaned).text()).isEqualTo("标题带 <b> 和 &");
    }
}
