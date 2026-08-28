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
@WithMockUser(authorities = "PROJECT_CREATE")
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
    void imageReferencedByAPublishedFeaturedCollection_shouldBeReadableByAnyCampusManager()
            throws Exception {
        // 好图精选的查看不设限，所以要求正文里的插图必须对所有人可读——
        // 否则负责人打开征集要求会看到一堆裂图。草稿仍然只有上传者本人可见。
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        String id = controller.upload(
                new MockMultipartFile("file", "requirement.png", "image/png", png), minister)
                .data().url().replaceFirst(".*/", "");
        AuthenticatedUser manager = new AuthenticatedUser(
                998L, "featured-reader", "校区负责人", UserRole.CAMPUS_MANAGER, 100L, false);

        try {
            insertFeaturedCollection("DRAFT", id);
            assertThatThrownBy(() -> controller.get(id, manager))
                    .as("草稿里的插图还不该外泄")
                    .isInstanceOf(BusinessException.class).hasMessageContaining("无权读取");

            jdbc.sql("UPDATE featured_collection SET status='PUBLISHED' WHERE created_by=814").update();
            assertThat(controller.get(id, manager).getBody().getInputStream().readAllBytes())
                    .isEqualTo(png);
        } finally {
            storage.delete(mapper.selectById(id).getObjectKey());
        }
    }

    private void insertFeaturedCollection(String status, String imageId) {
        jdbc.sql("""
                INSERT INTO featured_collection
                    (title, requirement_html, requirement_text, starts_at, ends_at, status,
                     assign_all, entry_limit, document_status, created_by)
                VALUES ('说明图片可见性', :html, '要求', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        :status, TRUE, 10, 'PENDING', 814)
                """)
                .param("html", "<p>要求</p><img src=\"/api/v1/description-images/" + imageId + "\">")
                .param("status", status).update();
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
