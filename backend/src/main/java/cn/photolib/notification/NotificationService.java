package cn.photolib.notification;

import cn.photolib.admin.AdminAlertEntity;
import cn.photolib.admin.AdminAlertMapper;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import cn.photolib.user.model.UserRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationLogMapper mapper;
    private final UserNotificationMapper userNotificationMapper;
    private final UserMapper userMapper;
    private final AdminAlertMapper alertMapper;
    private final MailGateway gateway;
    private final ApplicationEventPublisher events;

    private static final Safelist MESSAGE_HTML = Safelist.basic()
            .addTags("p", "div", "h1", "h2", "h3", "img")
            .addAttributes("img", "src", "alt", "title")
            .preserveRelativeLinks(true);

    @Transactional
    public void notifyUser(Long userId, String event, String subject, String html) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) return;
        UserNotificationEntity notification = new UserNotificationEntity();
        notification.setUserId(userId);
        notification.setEventType(event);
        notification.setTitle(subject);
        notification.setContent(toPlainText(html));
        notification.setActionUrl(actionUrl(event));
        notification.setCreatedAt(LocalDateTime.now());
        userNotificationMapper.insert(notification);

        if (user.getEmail() == null || user.getEmail().isBlank()) return;
        NotificationLogEntity log = new NotificationLogEntity();
        log.setUserId(userId);
        log.setEmail(user.getEmail());
        log.setEventType(event);
        log.setStatus("PENDING");
        log.setRetryCount(0);
        log.setPayloadJson(subject + "\n" + html);
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
            String[] payload = log.getPayloadJson().split("\\n", 2);
            gateway.send(log.getEmail(), payload[0], payload.length > 1 ? payload[1] : "");
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
        for (UserEntity recipient : recipients) {
            UserNotificationEntity notification = new UserNotificationEntity();
            notification.setUserId(recipient.getId());
            notification.setSenderId(senderId);
            notification.setEventType(broadcast ? "BROADCAST_MESSAGE" : "DIRECT_MESSAGE");
            notification.setTitle(title.trim());
            notification.setContent(plainText);
            notification.setContentHtml(safeHtml);
            notification.setActionUrl(null);
            notification.setCreatedAt(now);
            userNotificationMapper.insert(notification);
        }
        return recipients.size();
    }

    private String actionUrl(String event) {
        if (event.startsWith("REQUEST_")) return "/requests";
        if (event.startsWith("WORKLOG_")) return "/worklogs";
        return null;
    }

    private String toPlainText(String html) {
        if (html == null) return null;
        return html.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }

    private void createAlert(NotificationLogEntity log) {
        AdminAlertEntity alert = new AdminAlertEntity();
        alert.setType("MAIL_DELIVERY_FAILED");
        alert.setMessage("邮件连续投递失败：" + log.getEmail());
        alert.setResourceType("NOTIFICATION");
        alert.setResourceId(log.getId().toString());
        alert.setResolved(false);
        alert.setCreatedAt(LocalDateTime.now());
        alertMapper.insert(alert);
        userMapper.selectList(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getRole, UserRole.ADMIN).eq(UserEntity::getEnabled, true)
                .isNotNull(UserEntity::getEmail)).forEach(admin -> {
            try { gateway.send(admin.getEmail(), "PhotoLib 邮件投递告警", alert.getMessage()); }
            catch (Exception ignored) { }
        });
    }

    public record DeliveryRequested(Long notificationId) {}
}
