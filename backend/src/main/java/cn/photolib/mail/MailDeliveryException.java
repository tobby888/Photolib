package cn.photolib.mail;

/**
 * 一封邮件没发出去。消息是可以直接给人看的原因。
 *
 * <p>{@link #fallbackAllowed()} 说明换一个发信邮箱有没有意义：邮箱登录失败、连不上服务器、被服务商限频
 * 是这个邮箱自己的问题，换下一个可能就成了；收件地址被拒收、邮件内容生成不了，换哪个邮箱都一样，
 * 再试只会让每个邮箱都去碰一次壁（收件地址被反复拒收还会拉低发信信誉）。</p>
 */
public class MailDeliveryException extends RuntimeException {
    private final boolean fallbackAllowed;

    public MailDeliveryException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public MailDeliveryException(String message) {
        this(message, null, false);
    }

    public MailDeliveryException(String message, Throwable cause, boolean fallbackAllowed) {
        super(message, cause);
        this.fallbackAllowed = fallbackAllowed;
    }

    public boolean fallbackAllowed() {
        return fallbackAllowed;
    }
}
