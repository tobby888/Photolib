package cn.photolib.notification;

import cn.photolib.common.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class NotificationServiceTests {
    private static final long USER_A = 801L;
    private static final long USER_B = 802L;
    private static final long DISABLED_USER = 803L;

    @Autowired
    private NotificationService service;
    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES
                    (:userA, 'notify-user-a', 'hash', '通知用户A', 'MINISTER', true, false),
                    (:userB, 'notify-user-b', 'hash', '通知用户B', 'MINISTER', true, false),
                    (:disabled, 'notify-disabled', 'hash', '停用用户', 'MINISTER', false, false)
                """)
                .param("userA", USER_A)
                .param("userB", USER_B)
                .param("disabled", DISABLED_USER)
                .update();
    }

    @Test
    void notifyUser_withoutEmail_shouldStillCreateInAppNotification() {
        service.notifyUser(USER_A, "REQUEST_PUBLISHED", "新的图片需求", "<p>毕业典礼&nbsp;拍摄</p>");

        var notifications = service.listForUser(USER_A, false);
        assertThat(notifications).hasSize(1);
        assertThat(notifications.getFirst().getEventType()).isEqualTo("REQUEST_PUBLISHED");
        assertThat(notifications.getFirst().getTitle()).isEqualTo("新的图片需求");
        assertThat(notifications.getFirst().getContent()).isEqualTo("毕业典礼 拍摄");
        assertThat(notifications.getFirst().getActionUrl()).isEqualTo("/requests");
        assertThat(notifications.getFirst().getReadAt()).isNull();
        assertThat(service.unreadCount(USER_A)).isEqualTo(1);
    }

    @Test
    void notifyUser_withEmail_shouldCreateInAppNotificationAndMailLog() {
        jdbc.sql("UPDATE app_user SET email='user-a@example.com' WHERE id=:id")
                .param("id", USER_A).update();

        service.notifyUser(USER_A, "WORKLOG_CONFIRMED", "工时已确认", "<p>记录已确认</p>");

        assertThat(service.listForUser(USER_A, false)).singleElement()
                .satisfies(item -> assertThat(item.getActionUrl()).isEqualTo("/worklogs"));
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM notification_log
                WHERE user_id=:id AND event_type='WORKLOG_CONFIRMED' AND status='PENDING'
                """).param("id", USER_A).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void notifyUser_forDisabledUser_shouldNotCreateNotification() {
        service.notifyUser(DISABLED_USER, "REQUEST_PUBLISHED", "新的图片需求", "<p>内容</p>");

        assertThat(service.listForUser(DISABLED_USER, false)).isEmpty();
        assertThat(service.unreadCount(DISABLED_USER)).isZero();
    }

    @Test
    void listForUser_shouldIsolateUsersAndFilterUnreadNotifications() {
        service.notifyUser(USER_A, "REQUEST_PUBLISHED", "A1", "<p>A1</p>");
        service.notifyUser(USER_A, "WORKLOG_CONFIRMED", "A2", "<p>A2</p>");
        service.notifyUser(USER_B, "REQUEST_ACCEPTED", "B1", "<p>B1</p>");
        var first = service.listForUser(USER_A, false).getLast();
        service.markRead(first.getId(), USER_A);

        assertThat(service.listForUser(USER_A, false)).hasSize(2);
        assertThat(service.listForUser(USER_A, true))
                .extracting(UserNotificationEntity::getTitle)
                .containsExactly("A2");
        assertThat(service.listForUser(USER_B, false))
                .extracting(UserNotificationEntity::getTitle)
                .containsExactly("B1");
    }

    @Test
    void markRead_shouldBeIdempotentAndDecreaseUnreadCountOnce() {
        service.notifyUser(USER_A, "REQUEST_ACCEPTED", "需求已接受", "<p>内容</p>");
        var notification = service.listForUser(USER_A, false).getFirst();

        service.markRead(notification.getId(), USER_A);
        var firstReadAt = service.listForUser(USER_A, false).getFirst().getReadAt();
        service.markRead(notification.getId(), USER_A);

        assertThat(firstReadAt).isNotNull();
        assertThat(service.listForUser(USER_A, false).getFirst().getReadAt()).isEqualTo(firstReadAt);
        assertThat(service.unreadCount(USER_A)).isZero();
    }

    @Test
    void markRead_shouldNotAllowReadingAnotherUsersNotification() {
        service.notifyUser(USER_B, "REQUEST_ACCEPTED", "需求已接受", "<p>内容</p>");
        var notification = service.listForUser(USER_B, false).getFirst();

        assertThatThrownBy(() -> service.markRead(notification.getId(), USER_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("通知不存在");
        assertThat(service.unreadCount(USER_B)).isEqualTo(1);
    }

    @Test
    void markAllRead_shouldOnlyUpdateCurrentUsersNotifications() {
        service.notifyUser(USER_A, "REQUEST_PUBLISHED", "A1", "<p>A1</p>");
        service.notifyUser(USER_A, "WORKLOG_REJECTED", "A2", "<p>A2</p>");
        service.notifyUser(USER_B, "REQUEST_ACCEPTED", "B1", "<p>B1</p>");

        service.markAllRead(USER_A);
        service.markAllRead(USER_A);

        assertThat(service.unreadCount(USER_A)).isZero();
        assertThat(service.listForUser(USER_A, false))
                .allSatisfy(item -> assertThat(item.getReadAt()).isNotNull());
        assertThat(service.unreadCount(USER_B)).isEqualTo(1);
    }

    @Test
    void sendMessage_directMessage_shouldOnlyReachTargetAndSanitizeHtml() {
        int recipients = service.sendMessage(USER_A, USER_B, false, "  定向通知  ",
                "<h2>安排</h2><script>alert(1)</script><p><b>今晚</b>值班</p>"
                        + "<img src=\"/api/v1/notifications/images/0123456789ABCDEFGHJKMNPQRS\" onerror=\"alert(2)\">");

        assertThat(recipients).isEqualTo(1);
        assertThat(service.listForUser(USER_A, false)).isEmpty();
        assertThat(service.listForUser(USER_B, false)).singleElement().satisfies(item -> {
            assertThat(item.getSenderId()).isEqualTo(USER_A);
            assertThat(item.getEventType()).isEqualTo("DIRECT_MESSAGE");
            assertThat(item.getTitle()).isEqualTo("定向通知");
            assertThat(item.getContent()).isEqualTo("安排 今晚值班");
            assertThat(item.getContentHtml())
                    .contains("<h2>安排</h2>", "<b>今晚</b>",
                            "/api/v1/notifications/images/0123456789ABCDEFGHJKMNPQRS")
                    .doesNotContain("<script", "onerror");
        });
    }

    @Test
    void sendMessage_broadcast_shouldCreateIndependentNotificationForEveryEnabledUser() {
        int recipients = service.sendMessage(USER_A, null, true, "全员通知", "<p>请查收</p>");

        assertThat(recipients).isEqualTo(2);
        assertThat(service.unreadCount(USER_A)).isEqualTo(1);
        assertThat(service.unreadCount(USER_B)).isEqualTo(1);
        assertThat(service.unreadCount(DISABLED_USER)).isZero();

        service.markAllRead(USER_A);
        assertThat(service.unreadCount(USER_A)).isZero();
        assertThat(service.unreadCount(USER_B)).isEqualTo(1);
    }

    @Test
    void sendMessage_toDisabledUser_shouldFail() {
        assertThatThrownBy(() -> service.sendMessage(
                USER_A, DISABLED_USER, false, "通知", "<p>内容</p>"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("接收成员不存在或已停用");
    }

    @Test
    void sendMessage_withUnsafeEmptyContent_shouldFail() {
        assertThatThrownBy(() -> service.sendMessage(
                USER_A, USER_B, false, "通知", "<script>alert(1)</script>"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("消息内容不能为空");
    }

    @Test
    void getForUser_shouldRejectAnotherUsersMessage() {
        service.sendMessage(USER_A, USER_B, false, "通知", "<p>内容</p>");
        var notification = service.listForUser(USER_B, false).getFirst();

        assertThat(service.getForUser(notification.getId(), USER_B).getTitle()).isEqualTo("通知");
        assertThatThrownBy(() -> service.getForUser(notification.getId(), USER_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("通知不存在");
    }
}
