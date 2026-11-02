package cn.photolib.mail;

import java.util.List;

/**
 * 一封待发的 HTML 邮件。{@code inlineImages} 以 {@code cid:} 引用（例如品牌图标）：
 * 多数邮件客户端会拦截 data: URI 和外链图片，内嵌附件是唯一能稳定显示的方式。
 * {@code fromName} 是发件人显示名，{@code SMTP_FROM_NAME} 配了就以配置为准。
 */
public record OutgoingMail(String to, String toName, String fromName, String subject, String html,
                           String plainText, List<InlineImage> inlineImages) {
    public OutgoingMail {
        inlineImages = inlineImages == null ? List.of() : List.copyOf(inlineImages);
    }

    public record InlineImage(String contentId, byte[] bytes, String contentType) {}
}
