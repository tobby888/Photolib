package cn.photolib.mail;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 用<b>一个</b>邮箱发一封信。顺延到下一个邮箱的逻辑在 {@link SmtpMailSender}。
 *
 * <p>{@link JavaMailSenderImpl} 刻意不注册成 bean：Spring Boot 看见它就会挂一个连 SMTP 的健康检查，
 * 邮箱服务器一抖整个服务就报 DOWN。每次发信现建一个，没有连接池可言（每次 send 本来就各开一条连接），
 * 管理员改了邮箱配置下一封就生效。</p>
 *
 * <p>发件地址（From 头和 SMTP {@code MAIL FROM}）用 {@link SmtpServer#sender()}，不一定等于登录名：
 * 有些服务商的 SMTP 登录名和发件地址是分开的。</p>
 */
final class SmtpTransport {
    private SmtpTransport() {
    }

    static void send(SmtpServer server, SmtpProperties settings, OutgoingMail mail) {
        if (server.problem() != null) throw new MailDeliveryException(server.problem(), null, true);
        JavaMailSenderImpl delegate = build(server, settings);
        MimeMessage message = delegate.createMimeMessage();
        MimeMessageHelper helper;
        try {
            helper = new MimeMessageHelper(message,
                    MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());
            if (notBlank(mail.toName())) helper.setTo(
                    new InternetAddress(mail.to(), mail.toName(), StandardCharsets.UTF_8.name()));
            else helper.setTo(mail.to());
            helper.setSubject(mail.subject());
            helper.setText(mail.plainText() == null ? "" : mail.plainText(), mail.html());
            for (OutgoingMail.InlineImage image : mail.inlineImages()) {
                helper.addInline(image.contentId(), new ByteArrayResource(image.bytes()), image.contentType());
            }
        } catch (MessagingException | UnsupportedEncodingException ex) {
            // 收件地址或内容本身的问题，换哪个邮箱都一样。
            throw new MailDeliveryException("邮件内容无法生成：" + ex.getMessage(), ex, false);
        }
        try {
            String fromName = notBlank(server.fromName()) ? server.fromName().trim() : mail.fromName();
            if (notBlank(fromName)) helper.setFrom(server.sender(), fromName);
            else helper.setFrom(server.sender());
        } catch (MessagingException | UnsupportedEncodingException ex) {
            throw new MailDeliveryException("发件地址无效：" + server.sender(), ex, true);
        }
        try {
            delegate.send(message);
        } catch (MailException ex) {
            // 只要当前邮箱未能投递成功，一律允许顺延到下一个发信邮箱继续投递。
            throw new MailDeliveryException(describe(ex, server), ex, true);
        }
    }

    private static SendFailedException rejectedRecipient(MailException ex) {
        if (!(ex instanceof MailSendException send)) return null;
        for (Exception failure : send.getFailedMessages().values()) {
            SendFailedException rejected = findCause(failure, SendFailedException.class);
            if (rejected != null && !senderRejected(rejected)
                    && rejected.getInvalidAddresses() != null && rejected.getInvalidAddresses().length > 0) {
                return rejected;
            }
        }
        return null;
    }

    /** MAIL FROM 被拒（Angus 的 {@code SMTPSenderFailedException}）。按类名判断，免得直接依赖实现包。 */
    private static boolean senderRejected(Throwable ex) {
        return ex.getClass().getSimpleName().equals("SMTPSenderFailedException");
    }

    /** 把 JavaMail 的异常翻成管理员看得懂、知道该去改哪的原因。 */
    static String describe(MailException ex, SmtpServer server) {
        String address = server.host() + ":" + server.port();
        if (ex instanceof MailAuthenticationException || hasCause(ex, AuthenticationFailedException.class)) {
            AuthenticationFailedException auth = findCause(ex, AuthenticationFailedException.class);
            String reply = auth != null ? clip(serverReply(auth)) : clip(ex.getMessage());
            String detail = reply.isEmpty() ? "" : "（服务器返回：" + reply + "）";
            return "邮箱登录失败" + detail + "：请检查 SMTP 登录名、密码/授权码或服务商信任 IP 设置";
        }
        if (hasCause(ex, UnknownHostException.class)) return "找不到 SMTP 服务器 " + server.host() + "，请检查服务器地址";
        if (hasCause(ex, SocketTimeoutException.class)) return "连接 SMTP 服务器 " + address + " 超时，请检查地址、端口和网络";
        if (hasCause(ex, ConnectException.class)) {
            return "连不上 SMTP 服务器 " + address + "，请检查端口以及 SSL / STARTTLS 设置";
        }
        SendFailedException senderFailure = findSenderFailure(ex);
        if (senderFailure != null) {
            return "发件地址 " + server.sender() + " 被服务器拒绝：" + clip(serverReply(senderFailure))
                    + "（有的服务商要求发件地址与登录名一致，有的要先在服务商后台验证发件地址）";
        }
        SendFailedException rejected = rejectedRecipient(ex);
        if (rejected != null) {
            // 外层只有一句 "Invalid Addresses"，服务器的原话（如 "550 5.1.1 no such user"）挂在 next 链上。
            return "收件地址被邮件服务器拒收：" + clip(serverReply(rejected));
        }
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return "邮件发送失败：" + clip(root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage());
    }

    private static SendFailedException findSenderFailure(MailException ex) {
        if (!(ex instanceof MailSendException send)) return null;
        for (Exception failure : send.getFailedMessages().values()) {
            Throwable current = failure;
            int depth = 0;
            while (current != null && depth++ < 12) {
                if (current instanceof SendFailedException sendFailed && senderRejected(sendFailed)) return sendFailed;
                if (current instanceof MessagingException messaging && messaging.getNextException() != null
                        && messaging.getNextException() != current.getCause()) {
                    current = messaging.getNextException();
                } else {
                    current = current.getCause();
                }
            }
        }
        return null;
    }

    private static String serverReply(MessagingException ex) {
        String reply = ex.getMessage();
        Exception next = ex.getNextException();
        int depth = 0;
        while (next != null && depth++ < 5) {
            if (next.getMessage() != null && !next.getMessage().isBlank()) reply = next.getMessage();
            next = next instanceof MessagingException messaging ? messaging.getNextException() : null;
        }
        return reply;
    }

    static String clip(String value) {
        if (value == null) return "";
        String single = value.replaceAll("\\s+", " ").trim();
        return single.length() > 300 ? single.substring(0, 300) + "…" : single;
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        return findCause(ex, type) != null;
    }

    private static <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth++ < 12) {
            if (type.isInstance(current)) return type.cast(current);
            if (current instanceof MessagingException messaging && messaging.getNextException() != null
                    && messaging.getNextException() != current.getCause()) {
                T nested = findCause(messaging.getNextException(), type);
                if (nested != null) return nested;
            }
            current = current.getCause();
        }
        return null;
    }

    static JavaMailSenderImpl build(SmtpServer server, SmtpProperties settings) {
        boolean ssl = SmtpServer.SSL.equals(server.security());
        boolean starttls = SmtpServer.STARTTLS.equals(server.security());
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(server.host().trim());
        sender.setPort(server.port());
        sender.setUsername(server.username().trim());
        sender.setPassword(server.password());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        sender.setProtocol(ssl ? "smtps" : "smtp");
        Properties javaMail = new Properties();
        String prefix = ssl ? "mail.smtps." : "mail.smtp.";
        javaMail.put(prefix + "auth", "true");
        // 信封发件人（MAIL FROM）明确用发件地址，不让 JavaMail 去猜——登录名可能根本不是邮箱地址。
        javaMail.put(prefix + "from", server.sender());
        javaMail.put(prefix + "connectiontimeout", String.valueOf(settings.connectTimeout().toMillis()));
        javaMail.put(prefix + "timeout", String.valueOf(settings.readTimeout().toMillis()));
        javaMail.put(prefix + "writetimeout", String.valueOf(settings.readTimeout().toMillis()));
        if (ssl) {
            javaMail.put("mail.smtps.ssl.enable", "true");
            javaMail.put("mail.smtps.ssl.checkserveridentity", "true");
        } else if (starttls) {
            javaMail.put("mail.smtp.starttls.enable", "true");
            javaMail.put("mail.smtp.starttls.required", "true");
            javaMail.put("mail.smtp.ssl.checkserveridentity", "true");
        }
        sender.setJavaMailProperties(javaMail);
        return sender;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
