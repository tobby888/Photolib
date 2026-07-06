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
    private AuthenticatedUser ministerUser;
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
                    (201, 'test-manager', 'hash', '测试负责人', 'CAMPUS_MANAGER', :campusId, true, false),
                    (202, 'test-minister', 'hash', '测试部长', 'MINISTER', null, true, false)
                """).param("campusId", testCampus.getId()).update();

        adminUser = new AuthenticatedUser(
                200L, "test-admin", "测试管理员", UserRole.ADMIN, null, false);
        ministerUser = new AuthenticatedUser(
                202L, "test-minister", "测试部长", UserRole.MINISTER, null, false);
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
    void createTicket_forRequest_shouldAutomaticallyUseRequestsProject() {
        jdbc.sql("""
                INSERT INTO photo_request
                    (id, project_id, title, campus_id, required_count, deadline, status, created_by)
                VALUES
                    (2901, :projectId, '上传需求', :campusId, 1,
                     DATEADD('DAY', 1, CURRENT_TIMESTAMP), 'ACCEPTED', :adminId)
                """)
                .param("projectId", testProject.getId())
                .param("campusId", testCampus.getId())
                .param("adminId", adminUser.id())
                .update();

        var ticket = photoService.createTicket(new PhotoService.CreateTicket(
                2901L, null, "request-photo.jpg", "image/jpeg", 1024L,
                "d".repeat(64), "20230001", "张三", LocalDateTime.now()), adminUser);

        var stored = jdbc.sql("SELECT request_id, project_id FROM photo WHERE id=:id")
                .param("id", ticket.photoId())
                .query((rs, rowNum) -> new long[]{rs.getLong("request_id"), rs.getLong("project_id")})
                .single();
        assertThat(stored).containsExactly(2901L, testProject.getId());
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
                    (null, :projectId, '其他人的照片', '20230001', '张三', 200, :campusId,
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
                    (null, :projectId, '照片1', '20230001', '张三', 200, :campusId,
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
                    (1000, null, :projectId, '其他人的照片', '20230001', '张三', 200, :campusId,
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
    void updateCampus_shouldOnlyAllowAdminAndSupportClearing() {
        jdbc.sql("""
                INSERT INTO photo
                    (id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key, sha256, status, version)
                VALUES
                    (1010, :projectId, 'legacy photo', 'legacy-1010', 'legacy user',
                     :userId, null, NOW(), 1000, 'image/jpeg', 'photos/legacy.jpg', :sha256, 'AVAILABLE', 1)
                """)
                .param("projectId", testProject.getId())
                .param("userId", managerUser.id())
                .param("sha256", "j".repeat(64))
                .update();

        assertThatThrownBy(() -> photoService.updateCampus(
                1010L, testCampus.getId(), 1, managerUser))
                .isInstanceOf(BusinessException.class);

        var assigned = photoService.updateCampus(1010L, testCampus.getId(), 1, adminUser);
        assertThat(assigned.campusId()).isEqualTo(testCampus.getId());

        var cleared = photoService.updateCampus(1010L, null, assigned.version(), adminUser);
        assertThat(cleared.campusId()).isNull();
    }

    @Test
    void deleteAdoptedPhoto_shouldAllowAdmin() {
        jdbc.sql("""
                INSERT INTO photo
                    (id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, taken_at, size, content_type, object_key, sha256, status)
                VALUES
                    (1011, :projectId, 'adopted photo', '20230001', 'photographer',
                     :userId, NOW(), 1000, 'image/jpeg', 'photos/adopted.jpg', :sha256, 'AVAILABLE')
                """)
                .param("projectId", testProject.getId())
                .param("userId", managerUser.id())
                .param("sha256", "k".repeat(64))
                .update();
        jdbc.sql("""
                INSERT INTO adoption
                    (project_id, photo_id, photographer_student_id, photographer_name,
                     adopted_by, adopted_at, deleted)
                VALUES (:projectId, 1011, '20230001', 'photographer', :adminId, NOW(), false)
                """)
                .param("projectId", testProject.getId())
                .param("adminId", adminUser.id())
                .update();

        assertThatCode(() -> photoService.delete(1011L, adminUser)).doesNotThrowAnyException();
        assertThatThrownBy(() -> photoService.get(1011L, adminUser))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void listAndGet_shouldReturnActiveAdoptionCount() {
        jdbc.sql("""
                INSERT INTO photo
                    (id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key, sha256, status)
                VALUES
                    (1012, :projectId, 'adoption count photo', '20230001', 'photographer',
                     :userId, :campusId, NOW(), 1000, 'image/jpeg', 'photos/adoption-count.jpg',
                     :sha256, 'AVAILABLE')
                """)
                .param("projectId", testProject.getId())
                .param("userId", adminUser.id())
                .param("campusId", testCampus.getId())
                .param("sha256", "l".repeat(64))
                .update();
        jdbc.sql("""
                INSERT INTO adoption
                    (project_id, photo_id, photographer_student_id, photographer_name,
                     adopted_by, adopted_at, deleted)
                VALUES
                    (:projectId, 1012, '20230001', 'photographer', :adminId, NOW(), false)
                """)
                .param("projectId", testProject.getId())
                .param("adminId", adminUser.id())
                .update();

        var listed = photoService.list(
                1, 20, null, testProject.getId(), null, null, null,
                null, null, PhotoStatus.AVAILABLE, adminUser);

        assertThat(listed.items()).filteredOn(photo -> photo.id().equals(1012L))
                .singleElement()
                .extracting(PhotoService.PhotoView::adoptionCount)
                .isEqualTo(1L);
        assertThat(photoService.get(1012L, adminUser).adoptionCount()).isEqualTo(1L);
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
    void deletePhoto_asMinister_shouldMarkAsDeleted() {
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
        photoService.delete(1003L, ministerUser);

        // Then: 照片应该被逻辑删除
        assertThatThrownBy(() -> photoService.get(1003L, managerUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("图片不存在");
    }

    @Test
    void deletePhoto_asCampusManager_shouldBeForbidden() {
        jdbc.sql("""
                INSERT INTO photo
                    (id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, taken_at, size, content_type, object_key, sha256, status)
                VALUES
                    (1004, :projectId, 'manager photo', '20230001', 'photographer',
                     :userId, NOW(), 1000, 'image/jpeg', 'photos/manager.jpg', :sha256, 'AVAILABLE')
                """)
                .param("projectId", testProject.getId())
                .param("userId", managerUser.id())
                .param("sha256", "m".repeat(64))
                .update();

        assertThatThrownBy(() -> photoService.delete(1004L, managerUser))
                .isInstanceOf(BusinessException.class);
        assertThat(photoService.get(1004L, managerUser).id()).isEqualTo(1004L);
    }
}
