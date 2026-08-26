package cn.photolib.notification;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.access.prepost.PreAuthorize;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@WithMockUser(authorities = "MESSAGE_SEND")
class MessageImageControllerTests {
    @Autowired
    private MessageImageController controller;
    @Autowired
    private MessageImageMapper mapper;
    @Autowired
    private ObjectStorageService storage;
    @Autowired
    private JdbcClient jdbc;

    private final AuthenticatedUser minister = new AuthenticatedUser(
            804L, "image-minister", "部长", UserRole.MINISTER, null, false);
    private final AuthenticatedUser other = new AuthenticatedUser(
            805L, "image-manager", "校区负责人", UserRole.CAMPUS_MANAGER, null, false);

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (804, 'image-minister', 'hash', '部长', 'MINISTER', true, false),
                       (805, 'image-manager', 'hash', '校区负责人', 'CAMPUS_MANAGER', true, false)
                """).update();
    }

    @Test
    void upload_shouldStoreImageAndReturnStableAuthenticatedUrl() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        var result = controller.upload(
                new MockMultipartFile("file", "notice.png", "image/png", png), minister).data();
        String id = result.url().substring(result.url().lastIndexOf('/') + 1);
        MessageImageEntity image = mapper.selectById(id);

        try {
            assertThat(result.url()).isEqualTo("/api/v1/notifications/images/" + id);
            assertThat(image.getObjectKey()).startsWith("messages/").endsWith(".png");
            assertThat(storage.open(image.getObjectKey()).readAllBytes()).isEqualTo(png);
            assertThat(controller.get(id, minister).getBody().getInputStream().readAllBytes())
                    .isEqualTo(png);
        } finally {
            storage.delete(image.getObjectKey());
        }
    }

    @Test
    void upload_shouldRejectSpoofedImageContent() {
        var file = new MockMultipartFile("file", "fake.png", "image/png",
                "not an image".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> controller.upload(file, minister))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("图片内容与文件类型不匹配");
    }

    @Test
    void imageReadRequiresAuthentication() throws Exception {
        PreAuthorize authorization = MessageImageController.class
                .getDeclaredMethod("get", String.class, AuthenticatedUser.class)
                .getAnnotation(PreAuthorize.class);
        assertThat(authorization.value()).isEqualTo("isAuthenticated()");
    }

    @Test
    void signedInStrangersCannotReadSomeoneElsesMessageImage() throws Exception {
        String id = uploadImage();
        MessageImageEntity image = mapper.selectById(id);
        try {
            // Being signed in is not the same question as being on the message.
            assertThatThrownBy(() -> controller.get(id, other))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("无权读取该消息图片");
        } finally {
            storage.delete(image.getObjectKey());
        }
    }

    @Test
    void recipientOfTheMessageCarryingTheImageMayReadIt() throws Exception {
        String id = uploadImage();
        MessageImageEntity image = mapper.selectById(id);
        jdbc.sql("""
                INSERT INTO user_notification
                    (user_id, event_type, title, content, content_html, sender_id, created_at)
                VALUES (805, 'DIRECT_MESSAGE', '通知', '正文', :html, 804, CURRENT_TIMESTAMP)
                """).param("html", "<p>看图</p><img src=\"/api/v1/notifications/images/" + id + "\">")
                .update();
        try {
            assertThat(controller.get(id, other).getStatusCode().value()).isEqualTo(200);
        } finally {
            storage.delete(image.getObjectKey());
        }
    }

    @Test
    void underscoreInAnIdCannotWildcardIntoSomeoneElsesDelivery() throws Exception {
        String id = uploadImage();
        MessageImageEntity image = mapper.selectById(id);
        // A delivery for a *different* image must not match through a LIKE wildcard.
        String neighbour = id.substring(0, id.length() - 1) + "Z";
        jdbc.sql("""
                INSERT INTO user_notification
                    (user_id, event_type, title, content, content_html, sender_id, created_at)
                VALUES (805, 'DIRECT_MESSAGE', '通知', '正文', :html, 804, CURRENT_TIMESTAMP)
                """).param("html", "<img src=\"/api/v1/notifications/images/" + neighbour + "\">")
                .update();
        try {
            assertThatThrownBy(() -> controller.get(id, other))
                    .isInstanceOf(BusinessException.class);
        } finally {
            storage.delete(image.getObjectKey());
        }
    }

    private String uploadImage() throws java.io.IOException {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        var result = controller.upload(
                new MockMultipartFile("file", "notice.png", "image/png", png), minister);
        String url = result.data().url();
        return url.substring(url.lastIndexOf('/') + 1);
    }
}
