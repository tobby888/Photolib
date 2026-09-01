package cn.photolib.notification.wecom;

/**
 * 向企业微信自建应用推送一条应用消息。
 *
 * <p>{@code actionPath} 是站内的路由路径（如 {@code /requests}），由实现拼成完整跳转链接；
 * 传 {@code null} 表示跳到站内信列表。投递失败一律抛异常，由
 * {@code NotificationService.deliver} 记 RETRYING/FAILED 并在连续失败后告警。
 */
public interface WeComGateway {
    void send(String toUser, String subject, String html, String actionPath);
}
