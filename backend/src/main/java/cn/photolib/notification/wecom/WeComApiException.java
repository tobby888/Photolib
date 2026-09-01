package cn.photolib.notification.wecom;

/**
 * 企业微信接口返回了非 0 的 {@code errcode}，或者返回 0 但收件人被判为无效。
 *
 * <p>消息带上 errcode 是有意的：投递日志的 {@code last_error} 是管理员排查的唯一线索，
 * 而企业微信的错误码手册按码查最快（例如 60020 是 IP 不在白名单、81013 是收件人不存在）。
 */
public class WeComApiException extends RuntimeException {
    /** access_token 失效相关的错误码：拿到这些码要作废缓存并用新 token 重试一次。 */
    private static final int INVALID_CREDENTIAL = 40014;
    private static final int EXPIRED_ACCESS_TOKEN = 42001;
    private static final int MISSING_ACCESS_TOKEN = 41001;

    private final int errcode;

    public WeComApiException(int errcode, String message) {
        super(message);
        this.errcode = errcode;
    }

    public int errcode() {
        return errcode;
    }

    public boolean tokenRejected() {
        return errcode == INVALID_CREDENTIAL || errcode == EXPIRED_ACCESS_TOKEN
                || errcode == MISSING_ACCESS_TOKEN;
    }
}
