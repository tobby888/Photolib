package cn.photolib.photo;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.campus.model.CampusEntity;
import cn.photolib.common.error.BusinessException;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * 照片服务测试
 * 测试照片上传、查询、下载等核心功能
 */
@SpringBootTest
@Transactional
class PhotoServiceTests {
    @Autowired
    private PhotoService photoService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private CampusService campusService;
    @Autowired
    private JdbcClient jdbc;

    private AuthenticatedUser adminUser;
    private AuthenticatedUser managerUser;
    private ProjectEntity testProject;
    private CampusEntity testCampus;

    @BeforeEach
    void setUp() {
        // 创建测试用户
        testCampus = campusService.create("TEST", "测试校区");

        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES
                    (200, 'test-admin', 'hash', '测试管理员', 'ADMIN', null, true, false),
                    (201, 'test-manager', 'hash', '测试负责人', 'CAMPUS_MANAGER', :campusId, true, false)
                """).param("campusId", testCampus.getId()).update();

        adminUser = new AuthenticatedUser(
                200L, "test-admin", "测试管理员", UserRole.ADMIN, null, false);
        managerUser = new AuthenticatedUser(
                201L, "test-manager", "测试负责人", UserRole.CAMPUS_MANAGER, testCampus.getId(), false);

        testProject = projectService.create(
                "测试项目", "项目描述", ProjectStatus.ACTIVE, adminUser);
    }

    @Test
    void createTicket_shouldGenerateUploadUrl() {
        // When: 创建上传票据
        PhotoService.CreateTicket command = new PhotoService.CreateTicket(
                null, testProject.getId(),
                "test.jpg", "image/jpeg", 1024000L,
                "a".repeat(64), // SHA256
                "20230001", "张三",
                LocalDateTime.now());

        PhotoService.UploadTicket ticket = photoService.createTicket(command, adminUser);

        // Then: 应该返回预签名上传 URL
        assertThat(ticket.photoId()).isNotNull();
        assertThat(ticket.uploadUrl()).isNotEmpty();
        assertThat(ticket.method()).isEqualTo("PUT");
        assertThat(ticket.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void createTicket_withInvalidFileType_shouldThrowException() {
        // When & Then: 不支持的文件类型应该失败
        PhotoService.CreateTicket command = new PhotoService.CreateTicket(
                null, testProject.getId(),
                "test.pdf", "application/pdf", 1024000L,
                "a".repeat(64),
                "20230001", "张三",
                LocalDateTime.now());

        assertThatThrownBy(() -> photoService.createTicket(command, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持");
    }

    @Test
    void createTicket_withOversizedFile_shouldThrowException() {
        // When & Then: 超大文件应该失败（>100MB）
        PhotoService.CreateTicket command = new PhotoService.CreateTicket(
                null, testProject.getId(),
                "test.jpg", "image/jpeg", 150_000_000L, // 150MB
                "a".repeat(64),
                "20230001", "张三",
                LocalDateTime.now());

        assertThatThrownBy(() -> photoService.createTicket(command, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过");
    }

    @Test
    void listPhotos_asCampusManager_shouldOnlyShowOwnPhotos() {
        // Given: 其他用户上传的照片
        jdbc.sql("""
                INSERT INTO photo
                    (request_id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key, sha256, status)
                VALUES
                    (null, :projectId, '其他人的照片', '20230001', '张三', 999, :campusId,
                     NOW(), 1000, 'image/jpeg', 'photos/2026/other.jpg', :sha256, 'AVAILABLE')
                """)
                .param("projectId", testProject.getId())
                .param("campusId", testCampus.getId())
                .param("sha256", "b".repeat(64))
                .update();

        // 当前用户上传的照片
        jdbc.sql("""
                INSERT INTO photo
                    (request_id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key, sha256, status)
                VALUES
                    (null, :projectId, '我的照片', '20230002', '李四', :userId, :campusId,
                     NOW(), 1000, 'image/jpeg', 'photos/2026/mine.jpg', :sha256, 'AVAILABLE')
                """)
                .param("projectId", testProject.getId())
                .param("userId", managerUser.id())
                .param("campusId", testCampus.getId())
                .param("sha256", "c".repeat(64))
                .update();

        // When: 校区负责人查询照片
        var result = photoService.list(
                1, 20, null, testProject.getId(), null, null, null,
                null, null, PhotoStatus.AVAILABLE, managerUser);

        // Then: 应该只看到自己上传的照片
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).title()).isEqualTo("我的照片");
    }

    @Test
    void listPhotos_asAdmin_shouldShowAllPhotos() {
        // Given: 多个用户上传的照片
        jdbc.sql("""
                INSERT INTO photo
                    (request_id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key, sha256, status)
                VALUES
                    (null, :projectId, '照片1', '20230001', '张三', 999, :campusId,
                     NOW(), 1000, 'image/jpeg', 'photos/2026/photo1.jpg', :sha256_1, 'AVAILABLE'),
                    (null, :projectId, '照片2', '20230002', '李四', :userId, :campusId,
                     NOW(), 1000, 'image/jpeg', 'photos/2026/photo2.jpg', :sha256_2, 'AVAILABLE')
                """)
                .param("projectId", testProject.getId())
                .param("userId", adminUser.id())
                .param("campusId", testCampus.getId())
                .param("sha256_1", "d".repeat(64))
                .param("sha256_2", "e".repeat(64))
                .update();

        // When: 管理员查询照片
        var result = photoService.list(
                1, 20, null, testProject.getId(), null, null, null,
                null, null, PhotoStatus.AVAILABLE, adminUser);

        // Then: 应该看到所有照片
        assertThat(result.items()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void getPhoto_asCampusManager_ofOthersPhoto_shouldThrowException() {
        // Given: 其他用户上传的照片
        jdbc.sql("""
                INSERT INTO photo
                    (id, request_id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key, sha256, status)
                VALUES
                    (1000, null, :projectId, '其他人的照片', '20230001', '张三', 999, :campusId,
                     NOW(), 1000, 'image/jpeg', 'photos/2026/other.jpg', :sha256, 'AVAILABLE')
                """)
                .param("projectId", testProject.getId())
                .param("campusId", testCampus.getId())
                .param("sha256", "f".repeat(64))
                .update();

        // When & Then: 校区负责人查看他人照片应该失败
        assertThatThrownBy(() -> photoService.get(1000L, managerUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权查看");
    }

    @Test
    void updatePhoto_shouldModifyMetadata() {
        // Given: 已存在的照片
        jdbc.sql("""
                INSERT INTO photo
                    (id, request_id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key, sha256, status, version)
                VALUES
                    (1001, null, :projectId, '原始标题', '20230001', '张三', :userId, :campusId,
                     NOW(), 1000, 'image/jpeg', 'photos/2026/test.jpg', :sha256, 'AVAILABLE', 1)
                """)
                .param("projectId", testProject.getId())
                .param("userId", managerUser.id())
                .param("campusId", testCampus.getId())
                .param("sha256", "g".repeat(64))
                .update();

        // When: 更新照片元数据
        PhotoService.Metadata metadata = new PhotoService.Metadata(
                "新标题", "新描述", "20230002", "李四",
                LocalDateTime.now(), null, 1);
        var updated = photoService.update(1001L, metadata, managerUser);

        // Then: 元数据应该被更新
        assertThat(updated.title()).isEqualTo("新标题");
        assertThat(updated.description()).isEqualTo("新描述");
        assertThat(updated.photographerStudentId()).isEqualTo("20230002");
        assertThat(updated.photographerName()).isEqualTo("李四");
    }

    @Test
    void archivePhoto_asMinister_shouldChangeStatus() {
        // Given: 可用的照片
        jdbc.sql("""
                INSERT INTO photo
                    (id, request_id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key, sha256, status)
                VALUES
                    (1002, null, :projectId, '测试照片', '20230001', '张三', :userId, :campusId,
                     NOW(), 1000, 'image/jpeg', 'photos/2026/test.jpg', :sha256, 'AVAILABLE')
                """)
                .param("projectId", testProject.getId())
                .param("userId", adminUser.id())
                .param("campusId", testCampus.getId())
                .param("sha256", "h".repeat(64))
                .update();

        // When: 归档照片
        var archived = photoService.changeArchive(1002L, true, adminUser);

        // Then: 状态应该变为已归档
        assertThat(archived.status()).isEqualTo(PhotoStatus.ARCHIVED);
    }

    @Test
    void deletePhoto_shouldMarkAsDeleted() {
        // Given: 已存在的照片
        jdbc.sql("""
                INSERT INTO photo
                    (id, request_id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key, sha256, status)
                VALUES
                    (1003, null, :projectId, '测试照片', '20230001', '张三', :userId, :campusId,
                     NOW(), 1000, 'image/jpeg', 'photos/2026/test.jpg', :sha256, 'AVAILABLE')
                """)
                .param("projectId", testProject.getId())
                .param("userId", managerUser.id())
                .param("campusId", testCampus.getId())
                .param("sha256", "i".repeat(64))
                .update();

        // When: 删除照片
        photoService.delete(1003L, managerUser);

        // Then: 照片应该被逻辑删除
        assertThatThrownBy(() -> photoService.get(1003L, managerUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("照片不存在");
    }
}
