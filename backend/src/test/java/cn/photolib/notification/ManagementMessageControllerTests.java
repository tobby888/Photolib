package cn.photolib.notification;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ManagementMessageControllerTests {
    private static final long ADMIN_ID = 811L;
    private static final long MINISTER_ID = 812L;
    private static final long MEMBER_ID = 813L;

    @Autowired
    private UserNotificationController controller;
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
                    (811, 'message-admin', 'hash', '管理员', 'ADMIN', true, false),
                    (812, 'message-minister', 'hash', '部长', 'MINISTER', true, false),
                    (813, 'message-member', 'hash', '成员', 'CAMPUS_MANAGER', true, false)
                """).update();
    }

    @Test
    @WithMockUser(authorities = "MESSAGE_SEND")
    void admin_shouldSendDirectMessageToAnyEnabledMember() {
        var sender = principal(ADMIN_ID, UserRole.ADMIN);
        var request = new UserNotificationController.SendMessageRequest(
                false, MEMBER_ID, "定点通知", "<p>请于今晚提交材料</p>");

        var result = controller.send(request, sender).data();

        assertThat(result.recipientCount()).isEqualTo(1);
        assertThat(service.listForUser(MEMBER_ID, false)).singleElement().satisfies(message -> {
            assertThat(message.getEventType()).isEqualTo("DIRECT_MESSAGE");
            assertThat(message.getSenderId()).isEqualTo(ADMIN_ID);
            assertThat(message.getContent()).isEqualTo("请于今晚提交材料");
        });
        assertThat(service.listForUser(MINISTER_ID, false)).isEmpty();
    }

    @Test
    @WithMockUser(authorities = "MESSAGE_SEND")
    void minister_shouldBroadcastToAllEnabledMembers() {
        var sender = principal(MINISTER_ID, UserRole.MINISTER);
        var request = new UserNotificationController.SendMessageRequest(
                true, null, "全员通知", "<p><b>明天</b>九点集合</p>");

        var result = controller.send(request, sender).data();

        assertThat(result.recipientCount()).isEqualTo(3);
        assertThat(service.listForUser(ADMIN_ID, false)).hasSize(1);
        assertThat(service.listForUser(MINISTER_ID, false)).hasSize(1);
        assertThat(service.listForUser(MEMBER_ID, false)).hasSize(1);
    }

    @Test
    @WithMockUser(roles = "CAMPUS_MANAGER")
    void campusManager_shouldNotSendManagementMessage() {
        var sender = principal(MEMBER_ID, UserRole.CAMPUS_MANAGER);
        var request = new UserNotificationController.SendMessageRequest(
                false, ADMIN_ID, "越权消息", "<p>不应发送</p>");

        assertThatThrownBy(() -> controller.send(request, sender))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(service.listForUser(ADMIN_ID, false)).isEmpty();
    }

    private AuthenticatedUser principal(long id, UserRole role) {
        return new AuthenticatedUser(id, "sender", "发送人", role, null, false);
    }
}
