package cn.photolib.content;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@WithMockUser(roles = "MINISTER")
class DescriptionImageControllerTests {
    @Autowired
    private DescriptionImageController controller;
    @Autowired
    private DescriptionImageMapper mapper;
    @Autowired
    private ObjectStorageService storage;
    @Autowired
    private JdbcClient jdbc;

    private final AuthenticatedUser minister = new AuthenticatedUser(
            814L, "description-image-minister", "部长", UserRole.MINISTER, null, false);

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (814, 'description-image-minister', 'hash', '部长', 'MINISTER', true, false)
                """).update();
    }

    @Test
    void upload_shouldStoreDescriptionImageAndReturnStableUrl() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        var result = controller.upload(
                new MockMultipartFile("file", "guide.png", "image/png", png), minister).data();
        String id = result.url().substring(result.url().lastIndexOf('/') + 1);
        DescriptionImageEntity image = mapper.selectById(id);

        try {
            assertThat(result.url()).isEqualTo("/api/v1/description-images/" + id);
            assertThat(image.getObjectKey()).startsWith("descriptions/").endsWith(".png");
            assertThat(storage.open(image.getObjectKey()).readAllBytes()).isEqualTo(png);
            assertThat(controller.get(id, minister).getBody().getInputStream().readAllBytes()).isEqualTo(png);
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
    void unreferencedImage_shouldNotBeReadableByCampusManager() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        String id = controller.upload(
                new MockMultipartFile("file", "private.png", "image/png", png), minister)
                .data().url().replaceFirst(".*/", "");
        AuthenticatedUser manager = new AuthenticatedUser(
                999L, "outsider", "无关负责人", UserRole.CAMPUS_MANAGER, 100L, false);

        try {
            assertThatThrownBy(() -> controller.get(id, manager))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("无权读取");
        } finally {
            storage.delete(mapper.selectById(id).getObjectKey());
        }
    }
}
