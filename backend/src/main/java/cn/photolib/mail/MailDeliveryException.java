package cn.photolib.mail;

/**
 * 一封邮件没发出去。消息是可以直接给人看的原因。
 *
 * <p>{@link #fallbackAllowed()} 说明是否需要继续顺延尝试下一个发信邮箱：
 * 只要某个邮箱在投递过程中失败（含登录失败、连不上、超时、限频、发件人或收件人被该服务器拒收等），
 * 都允许切换到下一个邮箱继续投递；只有在本地连邮件正文/收件人格式都无法构造时才不顺延。</p>
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
