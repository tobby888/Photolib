package cn.photolib.mail;

/** 发一封邮件。失败抛 {@link MailDeliveryException}，消息是可以直接给人看的原因。 */
public interface MailSender {
    boolean configured();

    /** 发件邮箱地址，未配置时为 {@code null}。 */
    String fromAddress();

    void send(OutgoingMail mail);
}
