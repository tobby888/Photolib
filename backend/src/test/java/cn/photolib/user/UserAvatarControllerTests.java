package cn.photolib.user;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.permission.PermissionGroupService;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@WithMockUser
class UserAvatarControllerTests {
    @Autowired
    private UserAvatarController controller;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private PermissionGroupService permissionGroups;
    @Autowired
    private ObjectStorageService storage;
    @Autowired
    private JdbcClient jdbc;

    private UserEntity user;
    private AuthenticatedUser principal;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        user.setUsername("avatar-user-" + System.nanoTime());
        user.setPasswordHash("unused");
        user.setDisplayName("头像测试用户");
        user.setRole(UserRole.MINISTER);
        user.setEnabled(true);
        user.setMustChangePassword(false);
        userMapper.insert(user);
        principal = new AuthenticatedUser(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(), null, false);
    }

    @AfterEach
    void tearDown() {
        if (user == null || user.getId() == null) {
            return;
        }
        for (ObjectStorageService.StoredObject object : storage.list("avatars/" + user.getId() + "/")) {
            storage.delete(object.objectKey());
        }
        jdbc.sql("DELETE FROM app_user WHERE id = :id")
                .param("id", user.getId())
                .update();
    }

    @Test
    void uploadReadReplaceAndDeleteUsesRevisionedPrivateUrl() throws Exception {
        String firstUrl = controller.replace(image("first.png", "image/png", "png", Color.BLUE), principal)
                .data().avatarUrl();
        UserEntity first = userMapper.selectById(user.getId());
        String firstKey = first.getAvatarObjectKey();

        assertThat(firstUrl).isEqualTo("/api/v1/users/" + user.getId() + "/avatar?v=2");
        assertThat(firstUrl).doesNotContain(firstKey).doesNotContain("avatars/");
        assertThat(userService.get(user.getId()).avatarUrl()).isEqualTo(firstUrl);
        assertThat(permissionGroups.toPrincipal(first).avatarUrl()).isEqualTo(firstUrl);

        var imageResponse = controller.current(principal);
        assertThat(imageResponse.getHeaders().getCacheControl()).contains("private").contains("no-cache");
        assertThat(imageResponse.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(imageResponse.getHeaders().getContentType()).hasToString("image/png");
        try (var input = imageResponse.getBody().getInputStream();
             var stored = storage.open(firstKey)) {
            assertThat(input.readAllBytes()).isEqualTo(stored.readAllBytes());
        }

        String secondUrl = controller.replace(image("second.jpg", "image/jpeg", "jpeg", Color.RED), principal)
                .data().avatarUrl();
        UserEntity second = userMapper.selectById(user.getId());

        assertThat(secondUrl).isEqualTo("/api/v1/users/" + user.getId() + "/avatar?v=3");
        assertThat(secondUrl).isNotEqualTo(firstUrl);
        assertThat(second.getAvatarObjectKey()).isNotEqualTo(firstKey);
        assertThatThrownBy(() -> storage.stat(firstKey)).isInstanceOf(RuntimeException.class);

        assertThat(controller.delete(principal).data().avatarUrl()).isNull();
        assertThat(userMapper.selectById(user.getId()).getAvatarObjectKey()).isNull();
        assertThatThrownBy(() -> storage.stat(second.getAvatarObjectKey()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> controller.current(principal))
                .isInstanceOf(BusinessException.class).hasMessageContaining("尚未设置头像");
    }

    @Test
    void deletingAccountCleansCommittedAvatarObject() throws Exception {
        controller.replace(image("account.png", "image/png", "png", Color.GREEN), principal);
        String objectKey = userMapper.selectById(user.getId()).getAvatarObjectKey();

        userService.delete(user.getId(), user.getId() + 1000);

        assertThat(userMapper.selectById(user.getId())).isNull();
        assertThatThrownBy(() -> storage.stat(objectKey)).isInstanceOf(RuntimeException.class);
    }

    private MockMultipartFile image(String name, String contentType,
                                    String format, Color color) throws Exception {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return new MockMultipartFile("file", name, contentType, output.toByteArray());
    }
}
