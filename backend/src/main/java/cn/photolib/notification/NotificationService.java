package cn.photolib.notification;

import cn.photolib.admin.AdminAlertEntity;
import cn.photolib.admin.AdminAlertMapper;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.notification.wecom.WeComGateway;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import cn.photolib.user.model.UserRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.web.util.HtmlUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    static final String PAYLOAD_SEPARATOR = "\n\u0000MAIL_BODY\u0000\n";
    /** V38 起新通知只走这条通道；历史记录里的 {@code EMAIL} 见 {@link #deliver}。 */
    static final String CHANNEL_WECOM = "WECOM";
    private final NotificationLogMapper mapper;
    private final UserNotificationMapper userNotificationMapper;
    private final UserMapper userMapper;
    private final AdminAlertMapper alertMapper;
    private final MailGateway gateway;
    private final WeComGateway wecom;
    private final ApplicationEventPublisher events;

    private static final Safelist MESSAGE_HTML = Safelist.basic()
            .addTags("p", "div", "h1", "h2", "h3", "img")
            .addAttributes("img", "src", "alt", "title")
            .preserveRelativeLinks(true);
    private static final Safelist SYSTEM_MAIL_HTML = Safelist.none()
            .addTags("p", "div", "br", "strong", "b", "em", "i")
            .preserveRelativeLinks(false);

    /**
     * Builds a notification body out of plain text, one paragraph per argument.
     *
     * <p>Callers must not concatenate user-controlled text into HTML themselves.
     * {@link #notifyUser} does run every body through {@link #SYSTEM_MAIL_HTML},
     * but that safelist is a backstop, not the contract: anyone widening it would
     * silently turn every hand-rolled call site into an injection point, and
     * nothing in those call sites would say so. Escaping here also keeps the
     * notification faithful to the original text — a request title containing
     * {@code <} or {@code &} used to be rewritten on its way to the reader.</p>
     */
    public static String paragraphs(String... texts) {
        StringBuilder html = new StringBuilder();
        for (String text : texts) {
            if (text == null || text.isBlank()) continue;
            html.append("<p>").append(HtmlUtils.htmlEscape(text.trim())).append("</p>");
        }
        return html.toString();
    }

    @Transactional
    public void notifyUser(Long userId, String event, String subject, String html) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) return;
        String safeSubject = Jsoup.parse(subject == null ? "" : subject).text();
        String safeHtml = Jsoup.clean(html == null ? "" : html, "", SYSTEM_MAIL_HTML,
                new org.jsoup.nodes.Document.OutputSettings().prettyPrint(false));
        UserNotificationEntity notification = new UserNotificationEntity();
        notification.setUserId(userId);
        notification.setEventType(event);
        notification.setTitle(safeSubject);
        notification.setContent(toPlainText(safeHtml));
        notification.setActionUrl(actionUrl(event));
        notification.setCreatedAt(LocalDateTime.now());
        userNotificationMapper.insert(notification);

        queueDelivery(user, event, safeSubject, safeHtml, actionUrl(event));
    }

    /**
     * 为一条已经落库的站内信排一次外发。
     *
     * <p>系统通知和管理消息共用这一条路径，两边的收件规则、投递日志和重试因此不会走偏。
     * 外发只对绑定了企业微信的账号发生——和从前"没填邮箱就只有站内信"是同一条规则，
     * 换了收件标识而已。
     */
    private void queueDelivery(UserEntity user, String event, String subject,
                               String html, String actionPath) {
        if (user.getWecomUserid() == null || user.getWecomUserid().isBlank()) return;
        NotificationLogEntity log = new NotificationLogEntity();
        log.setUserId(user.getId());
        log.setChannel(CHANNEL_WECOM);
        log.setRecipient(user.getWecomUserid());
        log.setEventType(event);
        log.setActionPath(actionPath);
        log.setStatus("PENDING");
        log.setRetryCount(0);
        log.setPayloadJson(subject + PAYLOAD_SEPARATOR + html);
        log.setCreatedAt(LocalDateTime.now());
        log.setUpdatedAt(LocalDateTime.now());
        mapper.insert(log);
        events.publishEvent(new DeliveryRequested(log.getId()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeliveryRequested(DeliveryRequested event) {
        NotificationLogEntity log = mapper.selectById(event.notificationId());
        if (log != null) deliver(log);
    }

    public void deliver(NotificationLogEntity log) {
        try {
            MailPayload payload = parsePayload(log.getPayloadJson());
            // 判定按"是不是企微"而不是"是不是邮件"：channel 只有 notifyUser 会写成 WECOM，
            // 任何来路不明的值（含 null）都当作 V38 之前的邮件记录处理。反过来写的话，
            // 一条旧记录会被当成企微消息，发到一个"userid"其实是邮箱的收件人上。
            if (CHANNEL_WECOM.equals(log.getChannel())) {
                wecom.send(recipientOf(log), payload.subject(), payload.html(),
                        log.getActionPath());
            } else {
                gateway.send(recipientOf(log), payload.subject(), payload.html());
            }
            log.setStatus("SENT");
            log.setLastError(null);
        } catch (Exception ex) {
            log.setRetryCount(log.getRetryCount() + 1);
            log.setStatus(log.getRetryCount() >= 3 ? "FAILED" : "RETRYING");
            log.setLastError(ex.getMessage());
            if (log.getRetryCount() >= 3) createAlert(log);
        }
        log.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(log);
    }

    static MailPayload parsePayload(String value) {
        if (value == null) return new MailPayload("", "");
        int separator = value.indexOf(PAYLOAD_SEPARATOR);
        if (separator >= 0) {
            return new MailPayload(value.substring(0, separator),
                    value.substring(separator + PAYLOAD_SEPARATOR.length()));
        }
        int legacySeparator = value.indexOf('\n');
        if (legacySeparator >= 0) {
            return new MailPayload(value.substring(0, legacySeparator),
                    value.substring(legacySeparator + 1));
        }
        return new MailPayload(value, "");
    }

    record MailPayload(String subject, String html) {
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void retryFailed() {
        mapper.selectList(Wrappers.<NotificationLogEntity>lambdaQuery()
                .eq(NotificationLogEntity::getStatus, "RETRYING")
                .lt(NotificationLogEntity::getRetryCount, 3)
                .last("LIMIT 100")).forEach(this::deliver);
    }

    public List<NotificationLogEntity> list(String status, Long userId) {
        return mapper.selectList(Wrappers.<NotificationLogEntity>lambdaQuery()
                .eq(status != null, NotificationLogEntity::getStatus, status)
                .eq(userId != null, NotificationLogEntity::getUserId, userId)
                .orderByDesc(NotificationLogEntity::getCreatedAt).last("LIMIT 200"));
    }

    public void retry(Long id) {
        NotificationLogEntity log = mapper.selectById(id);
        if (log == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "通知记录不存在");
        log.setRetryCount(0);
        log.setStatus("RETRYING");
        mapper.updateById(log);
        deliver(log);
    }

    public List<UserNotificationEntity> listForUser(Long userId, boolean unreadOnly) {
        return userNotificationMapper.selectList(Wrappers.<UserNotificationEntity>lambdaQuery()
                .eq(UserNotificationEntity::getUserId, userId)
                .isNull(unreadOnly, UserNotificationEntity::getReadAt)
                .orderByDesc(UserNotificationEntity::getCreatedAt)
                .orderByDesc(UserNotificationEntity::getId)
                .last("LIMIT 50"));
    }

    public long unreadCount(Long userId) {
        return userNotificationMapper.selectCount(Wrappers.<UserNotificationEntity>lambdaQuery()
                .eq(UserNotificationEntity::getUserId, userId)
                .isNull(UserNotificationEntity::getReadAt));
    }

    public UserNotificationEntity getForUser(Long id, Long userId) {
        UserNotificationEntity notification = userNotificationMapper.selectOne(
                Wrappers.<UserNotificationEntity>lambdaQuery()
                        .eq(UserNotificationEntity::getId, id)
                        .eq(UserNotificationEntity::getUserId, userId));
        if (notification == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "通知不存在");
        }
        return notification;
    }

    @Transactional
    public void markRead(Long id, Long userId) {
        UserNotificationEntity notification = userNotificationMapper.selectOne(
                Wrappers.<UserNotificationEntity>lambdaQuery()
                        .eq(UserNotificationEntity::getId, id)
                        .eq(UserNotificationEntity::getUserId, userId));
        if (notification == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "通知不存在");
        }
        if (notification.getReadAt() == null) {
            notification.setReadAt(LocalDateTime.now());
            userNotificationMapper.updateById(notification);
        }
    }

    @Transactional
    public void markAllRead(Long userId) {
        UserNotificationEntity update = new UserNotificationEntity();
        update.setReadAt(LocalDateTime.now());
        userNotificationMapper.update(update, Wrappers.<UserNotificationEntity>lambdaUpdate()
                .eq(UserNotificationEntity::getUserId, userId)
                .isNull(UserNotificationEntity::getReadAt));
    }

    @Transactional
    public int sendMessage(Long senderId, Long targetUserId, boolean broadcast,
                           String title, String contentHtml) {
        String cleaned = Jsoup.clean(contentHtml, "", MESSAGE_HTML,
                new org.jsoup.nodes.Document.OutputSettings().prettyPrint(false));
        org.jsoup.nodes.Document document = Jsoup.parseBodyFragment(cleaned);
        document.select("img").removeIf(image -> !image.attr("src")
                .matches("^/api/v1/notifications/images/[0-9A-HJKMNP-TV-Z]{26}$"));
        String safeHtml = document.body().html();
        String plainText = Jsoup.parse(safeHtml).text();
        if (plainText.isBlank() && !safeHtml.contains("<img")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "消息内容不能为空");
        }
        List<UserEntity> recipients;
        if (broadcast) {
            recipients = userMapper.selectList(Wrappers.<UserEntity>lambdaQuery()
                    .eq(UserEntity::getEnabled, true));
        } else {
            UserEntity target = targetUserId == null ? null : userMapper.selectById(targetUserId);
            if (target == null || !Boolean.TRUE.equals(target.getEnabled())) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "接收成员不存在或已停用");
            }
            recipients = List.of(target);
        }
        LocalDateTime now = LocalDateTime.now();
        String event = broadcast ? "BROADCAST_MESSAGE" : "DIRECT_MESSAGE";
        String subject = title.trim();
        for (UserEntity recipient : recipients) {
            UserNotificationEntity notification = new UserNotificationEntity();
            notification.setUserId(recipient.getId());
            notification.setSenderId(senderId);
            notification.setEventType(event);
            notification.setTitle(subject);
            notification.setContent(plainText);
            notification.setContentHtml(safeHtml);
            notification.setActionUrl(null);
            notification.setCreatedAt(now);
            userNotificationMapper.insert(notification);
            // 跳这条消息本身而不是列表：正文里的消息图片没法带进企业微信，链接是收件人
            // 看到完整内容的唯一入口。每人一条投递记录，各自重试、各自记状态——广播因此
            // 是 N 次调用而不是一次群发，换来的是"谁没收到"在日志里查得出来。
            queueDelivery(recipient, event, subject, safeHtml,
                    "/notifications/" + notification.getId());
        }
        return recipients.size();
    }

    private String actionUrl(String event) {
        if (event == null) return null;
        if (event.startsWith("REQUEST_")) return "/requests";
        if (event.startsWith("WORKLOG_")) return "/worklogs";
        return null;
    }

    /** 历史记录的收件人可能只填了 {@code email}（V38 回填之前写入的行）。 */
    private String recipientOf(NotificationLogEntity log) {
        return log.getRecipient() != null ? log.getRecipient() : log.getEmail();
    }

    private String toPlainText(String html) {
        if (html == null) return null;
        return Jsoup.parse(html).text();
    }

    private void createAlert(NotificationLogEntity log) {
        AdminAlertEntity alert = new AdminAlertEntity();
        alert.setType("NOTIFICATION_DELIVERY_FAILED");
        alert.setMessage("通知连续投递失败：" + recipientOf(log));
        alert.setResourceType("NOTIFICATION");
        alert.setResourceId(log.getId().toString());
        alert.setResolved(false);
        alert.setCreatedAt(LocalDateTime.now());
        alertMapper.insert(alert);
        // 告警本身也走企业微信。这里不再落 notification_log：告警是投递失败的产物，
        // 给它建一条会失败的投递记录只会再触发一次告警。
        userMapper.selectList(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getRole, UserRole.ADMIN).eq(UserEntity::getEnabled, true)
                .isNotNull(UserEntity::getWecomUserid)).forEach(admin -> {
            try { wecom.send(admin.getWecomUserid(), "PhotoLib 通知投递告警", alert.getMessage(), null); }
            catch (Exception e) {
                NotificationService.log.warn("管理员告警消息发送失败: {}", admin.getWecomUserid(), e);
            }
        });
    }

    public record DeliveryRequested(Long notificationId) {}
}
