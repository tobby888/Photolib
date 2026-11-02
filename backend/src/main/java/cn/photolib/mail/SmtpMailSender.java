package cn.photolib.mail;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 用 {@code .env} 里的 SMTP 邮箱发信。
 *
 * <p>{@link JavaMailSenderImpl} 刻意不注册成 bean：Spring Boot 看见它就会挂一个连 SMTP 的健康检查，
 * 邮箱服务器一抖整个服务就报 DOWN。它本身线程安全（每次 send 各开一条连接），多个发信线程共用一个。</p>
 */
@Slf4j
@Component
@EnableConfigurationProperties(SmtpProperties.class)
public class SmtpMailSender implements MailSender {
    private final SmtpProperties properties;
    private final JavaMailSenderImpl delegate;

    public SmtpMailSender(SmtpProperties properties) {
        this.properties = properties;
        this.delegate = properties.configured() ? build(properties) : null;
    }

    @Override
    public boolean configured() {
        return delegate != null;
    }

    @Override
    public String fromAddress() {
        return configured() ? properties.username().trim() : null;
    }

    @Override
    public void send(OutgoingMail mail) {
        if (!configured()) throw new MailDeliveryException("邮件服务未配置：请在 .env 中填写 SMTP_HOST、SMTP_USERNAME 和 SMTP_PASSWORD");
        try {
            MimeMessage message = delegate.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message,
                    MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());
            String fromName = properties.fromName() != null && !properties.fromName().isBlank()
                    ? properties.fromName().trim() : mail.fromName();
            if (fromName != null && !fromName.isBlank()) helper.setFrom(fromAddress(), fromName);
            else helper.setFrom(fromAddress());
            if (mail.toName() != null && !mail.toName().isBlank()) helper.setTo(
                    new jakarta.mail.internet.InternetAddress(mail.to(), mail.toName(), StandardCharsets.UTF_8.name()));
            else helper.setTo(mail.to());
            helper.setSubject(mail.subject());
            helper.setText(mail.plainText() == null ? "" : mail.plainText(), mail.html());
            for (OutgoingMail.InlineImage image : mail.inlineImages()) {
                helper.addInline(image.contentId(), new ByteArrayResource(image.bytes()), image.contentType());
            }
            delegate.send(message);
        } catch (MessagingException | UnsupportedEncodingException ex) {
            throw new MailDeliveryException("邮件内容无法生成：" + ex.getMessage(), ex);
        } catch (MailException ex) {
            throw new MailDeliveryException(describe(ex), ex);
        }
    }

    /** 把 JavaMail 的异常翻成管理员看得懂、知道该去改哪的原因。 */
    static String describe(MailException ex) {
        if (ex instanceof MailAuthenticationException || hasCause(ex, AuthenticationFailedException.class)) {
            return "发信邮箱登录失败：请检查 SMTP_USERNAME / SMTP_PASSWORD（QQ、163 等邮箱要填授权码而不是登录密码）";
        }
        if (hasCause(ex, UnknownHostException.class)) return "找不到 SMTP 服务器，请检查 SMTP_HOST";
        if (hasCause(ex, SocketTimeoutException.class)) return "连接 SMTP 服务器超时，请检查 SMTP_HOST / SMTP_PORT 和网络";
        if (hasCause(ex, ConnectException.class)) return "连不上 SMTP 服务器，请检查 SMTP_HOST / SMTP_PORT，以及 SSL / STARTTLS 设置";
        if (ex instanceof MailSendException send) {
            for (Exception failure : send.getFailedMessages().values()) {
                SendFailedException rejected = findCause(failure, SendFailedException.class);
                if (rejected != null && rejected.getInvalidAddresses() != null && rejected.getInvalidAddresses().length > 0) {
                    // 外层只有一句 "Invalid Addresses"，服务器的原话（如 "550 5.1.1 no such user"）挂在 next 链上。
                    return "收件地址被邮件服务器拒收：" + clip(serverReply(rejected));
                }
            }
        }
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return "邮件发送失败：" + clip(root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage());
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

    private static String clip(String value) {
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

    private static JavaMailSenderImpl build(SmtpProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.host().trim());
        sender.setPort(properties.port());
        sender.setUsername(properties.username().trim());
        sender.setPassword(properties.password());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        sender.setProtocol(properties.ssl() ? "smtps" : "smtp");
        Properties javaMail = new Properties();
        String prefix = properties.ssl() ? "mail.smtps." : "mail.smtp.";
        javaMail.put(prefix + "auth", "true");
        javaMail.put(prefix + "connectiontimeout", String.valueOf(properties.connectTimeout().toMillis()));
        javaMail.put(prefix + "timeout", String.valueOf(properties.readTimeout().toMillis()));
        javaMail.put(prefix + "writetimeout", String.valueOf(properties.readTimeout().toMillis()));
        if (properties.ssl()) {
            javaMail.put("mail.smtps.ssl.enable", "true");
            javaMail.put("mail.smtps.ssl.checkserveridentity", "true");
        } else if (properties.starttls()) {
            javaMail.put("mail.smtp.starttls.enable", "true");
            javaMail.put("mail.smtp.starttls.required", "true");
            javaMail.put("mail.smtp.ssl.checkserveridentity", "true");
        }
        sender.setJavaMailProperties(javaMail);
        log.info("SMTP 发信已配置：{}:{}（{}），发件人 {}", properties.host(), properties.port(),
                properties.ssl() ? "SSL" : properties.starttls() ? "STARTTLS" : "明文", properties.username());
        return sender;
    }
}
