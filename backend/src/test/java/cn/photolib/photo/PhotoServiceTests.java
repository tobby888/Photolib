package cn.photolib.photo;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.campus.model.CampusEntity;
import cn.photolib.common.error.BusinessException;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
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
import java.util.List;
import java.util.Set;

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
    @Autowired
    private ObjectStorageService storage;

    private AuthenticatedUser adminUser;
    private AuthenticatedUser ministerUser;
    private AuthenticatedUser managerUser;
    private ProjectEntity testProject;
    private CampusEntity testCampus;
    private Long photographerContactId;

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

        // 通讯录成员：上传拍摄者必须来自通讯录（快照姓名/学号写入照片）。
        jdbc.sql("""
                INSERT INTO campus_member (id, campus_id, student_id, name, enabled, version, deleted)
                VALUES
                    (300, :campusId, '20230001', '张三', true, 1, false),
                    (301, :campusId, '20230002', '李四', true, 1, false)
                """).param("campusId", testCampus.getId()).update();
        photographerContactId = 300L;

        adminUser = new AuthenticatedUser(
                200L, "test-admin", "测试管理员", UserRole.ADMIN, null, false);
        ministerUser = new AuthenticatedUser(
                202L, "test-minister", "测试部长", UserRole.MINISTER, null, false);
        managerUser = new AuthenticatedUser(
                201L, "test-manager", "测试负责人", UserRole.CAMPUS_MANAGER, testCampus.getId(), false);

        testProject = projectService.create(
                "测试项目", "项目描述", ProjectStatus.ACTIVE, adminUser);
    }

    /**
     * 把每张照片按其 project_id 链接进 photo_project（幂等）。项目相册/计数现以该多对多表为准，
     * 用裸 SQL 直插照片的测试需要补这一步（等价于 V12 迁移的回填）。
     */
    private void linkPhotosToProjects() {
        jdbc.sql("""
                INSERT INTO photo_project (photo_id, project_id)
                SELECT p.id, p.project_id FROM photo p
                WHERE p.project_id IS NOT NULL AND p.deleted = 0
                  AND NOT EXISTS (SELECT 1 FROM photo_project pp
                                  WHERE pp.photo_id = p.id AND pp.project_id = p.project_id)
                """).update();
    }

    @Test
    void createTicket_shouldGenerateUploadUrl() {
        // When: 创建上传票据
        PhotoService.CreateTicket command = new PhotoService.CreateTicket(
                null, testProject.getId(),
                "test.jpg", "image/jpeg", 1024000L,
                "a".repeat(64), // SHA256
                photographerContactId,
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
        ProjectEntity untrustedProject = projectService.create(
                "客户端伪造项目", "不应被采用", ProjectStatus.ACTIVE, adminUser);
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
                2901L, untrustedProject.getId(), "request-photo.jpg", "image/jpeg", 1024L,
                "d".repeat(64), photographerContactId, LocalDateTime.now()), adminUser);

        var stored = jdbc.sql("SELECT request_id, project_id FROM photo WHERE id=:id")
                .param("id", ticket.photoId())
                .query((rs, rowNum) -> new long[]{rs.getLong("request_id"), rs.getLong("project_id")})
                .single();
        assertThat(stored).containsExactly(2901L, testProject.getId());
    }

    @Test
    void galleryUploadCannotInjectProjectMembershipWithPhotoUploadPermissionAlone() {
        var uploadOnly = new AuthenticatedUser(ministerUser.id(), "upload-only", "仅图库上传",
                UserRole.CAMPUS_MANAGER, null, false, -20L, "UPLOAD_ONLY", "仅图库上传",
                DataScope.GLOBAL, Set.of(PermissionCode.PHOTO_UPLOAD), Set.of());

        assertThatThrownBy(() -> photoService.createTicket(new PhotoService.CreateTicket(
                null, testProject.getId(), "injected.jpg", "image/jpeg", 1024L,
                "2".repeat(64), photographerContactId, LocalDateTime.now()), uploadOnly))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权将上传图片加入项目相册");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM photo WHERE sha256=:sha")
                .param("sha", "2".repeat(64)).query(Long.class).single()).isZero();
    }

    @Test
    void galleryUploadCannotTargetACompletedProject() {
        ProjectEntity completed = projectService.create(
                "已完成上传目标", "描述", ProjectStatus.ACTIVE, adminUser);
        projectService.changeStatus(completed.getId(), ProjectStatus.COMPLETED,
                projectService.get(completed.getId()).getVersion(), adminUser);

        assertThatThrownBy(() -> photoService.createTicket(new PhotoService.CreateTicket(
                null, completed.getId(), "completed.jpg", "image/jpeg", 1024L,
                "3".repeat(64), photographerContactId, LocalDateTime.now()), adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅进行中项目");
    }

    @Test
    void requestUploadIsRejectedAfterParticipantLosesTheRequestsCampus() {
        CampusEntity otherCampus = campusService.create("MOVED-UPLOAD", "迁移后校区");
        jdbc.sql("""
                INSERT INTO photo_request
                    (id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (2903, :projectId, '撤销校区上传需求', :campusId,
                        DATEADD('DAY', 1, CURRENT_TIMESTAMP), 'ACCEPTED', :adminId)
                """).param("projectId", testProject.getId()).param("campusId", testCampus.getId())
                .param("adminId", adminUser.id()).update();
        jdbc.sql("""
                INSERT INTO request_participant(request_id, user_id, accepted_at)
                VALUES (2903, :userId, CURRENT_TIMESTAMP)
                """).param("userId", managerUser.id()).update();
        var movedManager = new AuthenticatedUser(managerUser.id(), "test-manager", "测试负责人",
                UserRole.CAMPUS_MANAGER, otherCampus.getId(), false, -21L,
                "MOVED_UPLOAD", "已迁移负责人", DataScope.CAMPUS,
                Set.of(PermissionCode.REQUEST_PHOTO_MANAGE), Set.of(otherCampus.getId()));

        assertThatThrownBy(() -> photoService.createTicket(new PhotoService.CreateTicket(
                2903L, testProject.getId(), "revoked.jpg", "image/jpeg", 1024L,
                "4".repeat(64), photographerContactId, LocalDateTime.now()), movedManager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权操作该校区");
    }

    @Test
    void campusScopedGalleryUploadRequiresAnAssignedCampus() {
        var emptyCampus = new AuthenticatedUser(managerUser.id(), "empty-campus", "未分配校区",
                UserRole.CAMPUS_MANAGER, null, false, -22L, "EMPTY_CAMPUS", "未分配校区",
                DataScope.CAMPUS, Set.of(PermissionCode.PHOTO_UPLOAD), Set.of());

        assertThatThrownBy(() -> photoService.createTicket(new PhotoService.CreateTicket(
                null, null, "no-campus.jpg", "image/jpeg", 1024L,
                "5".repeat(64), photographerContactId, LocalDateTime.now()), emptyCampus))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未分配可用校区");
    }

    @Test
    void complete_shouldTransitionToProcessing() {
        // Regression for H-3 optimistic-lock double-application: complete-upload was
        // 409-ing on every call because manual version handling conflicted with the
        // @Version OptimisticLockerInnerInterceptor, so processing never started and
        // OSS kept only the original. This exercises the real interceptor via H2.
        var ticket = photoService.createTicket(new PhotoService.CreateTicket(
                null, testProject.getId(), "done.jpg", "image/jpeg", 1024L,
                "e".repeat(64), photographerContactId, LocalDateTime.now()), adminUser);

        // Simulate the browser having PUT the original object to storage.
        String originalKey = jdbc.sql("SELECT original_object_key FROM photo WHERE id=:id")
                .param("id", ticket.photoId())
                .query(String.class).single();
        byte[] bytes = "non-empty-original".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        storage.put(originalKey, new java.io.ByteArrayInputStream(bytes), bytes.length, "image/jpeg");

        var view = photoService.complete(ticket.photoId(),
                new PhotoService.CompleteUpload("标题", "描述", java.util.List.of("tag")), adminUser);

        assertThat(view.status()).isEqualTo(PhotoStatus.PROCESSING);
        String status = jdbc.sql("SELECT status FROM photo WHERE id=:id")
                .param("id", ticket.photoId()).query(String.class).single();
        assertThat(status).isEqualTo("PROCESSING");
    }

    @Test
    void galleryTicketCannotBeCompletedWithRequestUploadPermissionAfterPermissionChanges() {
        var galleryUploader = new AuthenticatedUser(ministerUser.id(), "gallery-uploader", "图库上传者",
                UserRole.CAMPUS_MANAGER, null, false, -23L, "GALLERY_UPLOADER", "图库上传者",
                DataScope.GLOBAL, Set.of(PermissionCode.PHOTO_UPLOAD, PermissionCode.PROJECT_ADOPT), Set.of());
        var ticket = photoService.createTicket(new PhotoService.CreateTicket(
                null, testProject.getId(), "permission-changed.jpg", "image/jpeg", 1024L,
                "6".repeat(64), photographerContactId, LocalDateTime.now()), galleryUploader);
        String originalKey = jdbc.sql("SELECT original_object_key FROM photo WHERE id=:id")
                .param("id", ticket.photoId()).query(String.class).single();
        byte[] bytes = "uploaded".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        storage.put(originalKey, new java.io.ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
        var wrongPermission = new AuthenticatedUser(galleryUploader.id(), galleryUploader.username(),
                galleryUploader.displayName(), UserRole.CAMPUS_MANAGER, null, false, -23L,
                "GALLERY_UPLOADER", "图库上传者", DataScope.GLOBAL,
                Set.of(PermissionCode.REQUEST_PHOTO_MANAGE), Set.of());

        assertThatThrownBy(() -> photoService.complete(ticket.photoId(),
                new PhotoService.CompleteUpload("标题", "描述", List.of()), wrongPermission))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权上传图片");
        assertThat(jdbc.sql("SELECT status FROM photo WHERE id=:id")
                .param("id", ticket.photoId()).query(String.class).single()).isEqualTo("UPLOADING");
    }

    @Test
    void createTicket_withInvalidFileType_shouldThrowException() {
        // When & Then: 不支持的文件类型应该失败
        PhotoService.CreateTicket command = new PhotoService.CreateTicket(
                null, testProject.getId(),
                "test.pdf", "application/pdf", 1024000L,
                "a".repeat(64),
                photographerContactId,
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
                photographerContactId,
                LocalDateTime.now());

        assertThatThrownBy(() -> photoService.createTicket(command, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过");
    }

    @Test
    void listPhotos_asCampusManager_shouldOnlyShowOwnPhotos() {
        jdbc.sql("""
                INSERT INTO photo_request
                    (id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (2899, :projectId, '项目可见性需求', :campusId,
                        DATEADD('DAY', 1, CURRENT_TIMESTAMP), 'ACCEPTED', :adminId)
                """).param("projectId", testProject.getId()).param("campusId", testCampus.getId())
                .param("adminId", adminUser.id()).update();
        jdbc.sql("""
                INSERT INTO request_participant(request_id, user_id, accepted_at)
                VALUES (2899, :userId, CURRENT_TIMESTAMP)
                """).param("userId", managerUser.id()).update();
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
        linkPhotosToProjects();
        var result = photoService.list(
                1, 20, null, testProject.getId(), null, null, null,
                null, null, PhotoStatus.AVAILABLE, false, managerUser);

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
        linkPhotosToProjects();
        var result = photoService.list(
                1, 20, null, testProject.getId(), null, null, null,
                null, null, PhotoStatus.AVAILABLE, false, adminUser);

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

        // When: 更新照片元数据（拍摄者改选通讯录中的另一位成员 301=李四/20230002）
        PhotoService.Metadata metadata = new PhotoService.Metadata(
                "新标题", "新描述", 301L,
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

        linkPhotosToProjects();
        var listed = photoService.list(
                1, 20, null, testProject.getId(), null, null, null,
                null, null, PhotoStatus.AVAILABLE, false, adminUser);

        assertThat(listed.items()).filteredOn(photo -> photo.id().equals(1012L))
                .singleElement()
                .extracting(PhotoService.PhotoView::adoptionCount)
                .isEqualTo(1L);
        assertThat(photoService.get(1012L, adminUser).adoptionCount()).isEqualTo(1L);
    }

    @Test
    void thumbnailUrl_shouldFallBackToTheFinishedObjectWhenNoPreviewExists() {
        jdbc.sql("""
                INSERT INTO photo
                    (id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key,
                     thumbnail_object_key, thumbnail_size, sha256, status)
                VALUES
                    (1013, :projectId, 'no preview', '20230001', 'photographer',
                     :userId, :campusId, NOW(), 1000, 'image/jpeg', 'photos/no-preview.jpg',
                     NULL, NULL, :sha256, 'AVAILABLE'),
                    (1014, :projectId, 'with preview', '20230001', 'photographer',
                     :userId, :campusId, NOW(), 1000, 'image/jpeg', 'photos/with-preview.jpg',
                     'thumbnails/generations/uploads/1014.jpg', 200, :otherSha256, 'AVAILABLE')
                """)
                .param("projectId", testProject.getId())
                .param("userId", adminUser.id())
                .param("campusId", testCampus.getId())
                .param("sha256", "m".repeat(64))
                .param("otherSha256", "n".repeat(64))
                .update();

        // A missing preview must not leave the gallery with a blank tile.
        assertThat(signedObjectKey(photoService.get(1013L, adminUser).thumbnailUrl()))
                .isEqualTo("photos/no-preview.jpg");
        assertThat(signedObjectKey(photoService.get(1014L, adminUser).thumbnailUrl()))
                .isEqualTo("thumbnails/generations/uploads/1014.jpg");
    }

    /** Reads the object key back out of a local-profile signed URL token. */
    private String signedObjectKey(String signedUrl) {
        assertThat(signedUrl).isNotNull();
        String token = signedUrl.substring(signedUrl.lastIndexOf('/') + 1);
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token),
                java.nio.charset.StandardCharsets.UTF_8);
        return payload.split("\n")[2];
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
                     uploaded_by, campus_id, taken_at, size, content_type, object_key, sha256, status)
                VALUES
                    (1004, :projectId, 'manager photo', '20230001', 'photographer',
                     :userId, :campusId, NOW(), 1000, 'image/jpeg', 'photos/manager.jpg', :sha256, 'AVAILABLE')
                """)
                .param("projectId", testProject.getId())
                .param("userId", managerUser.id())
                .param("campusId", testCampus.getId())
                .param("sha256", "m".repeat(64))
                .update();

        assertThatThrownBy(() -> photoService.delete(1004L, managerUser))
                .isInstanceOf(BusinessException.class);
        assertThat(photoService.get(1004L, managerUser).id()).isEqualTo(1004L);
    }

    @Test
    void campusScopedCustomPermissionsCannotBypassGalleryOwnershipOrProjectParticipation() {
        jdbc.sql("""
                INSERT INTO photo
                    (id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key, sha256, status)
                VALUES
                    (1005, :projectId, 'other member photo', '20230001', 'photographer',
                     :adminId, :campusId, NOW(), 1000, 'image/jpeg', 'photos/other-member.jpg',
                     :sha256, 'AVAILABLE')
                """)
                .param("projectId", testProject.getId())
                .param("adminId", adminUser.id())
                .param("campusId", testCampus.getId())
                .param("sha256", "o".repeat(64))
                .update();
        jdbc.sql("INSERT INTO photo_project(photo_id, project_id) VALUES (1005, :projectId)")
                .param("projectId", testProject.getId()).update();
        var custom = new AuthenticatedUser(managerUser.id(), "custom", "自定义校区账号",
                UserRole.CAMPUS_MANAGER, testCampus.getId(), false, 99L, "CUSTOM_SCOPED",
                "自定义校区组", DataScope.CAMPUS,
                Set.of(PermissionCode.PHOTO_DELETE, PermissionCode.PROJECT_DOWNLOAD),
                Set.of(testCampus.getId()));

        assertThatThrownBy(() -> photoService.delete(1005L, custom))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权删除");
        assertThatThrownBy(() -> photoService.download(1005L, custom))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权下载");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM photo WHERE id=1005 AND deleted=FALSE")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void requestParticipantCanBatchDeleteMultipleRequestPhotosAtomically() {
        jdbc.sql("""
                INSERT INTO photo_request(id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (2902, :projectId, '批量图片需求', :campusId,
                        DATEADD('DAY', 1, CURRENT_TIMESTAMP), 'ACCEPTED', :adminId)
                """).param("projectId", testProject.getId()).param("campusId", testCampus.getId())
                .param("adminId", adminUser.id()).update();
        jdbc.sql("""
                INSERT INTO request_participant(request_id, user_id, accepted_at)
                VALUES (2902, :userId, CURRENT_TIMESTAMP)
                """).param("userId", managerUser.id()).update();
        jdbc.sql("""
                INSERT INTO photo(id, request_id, project_id, title, photographer_student_id,
                                  photographer_name, uploaded_by, campus_id, taken_at, size,
                                  content_type, object_key, sha256, status)
                VALUES
                    (1006, 2902, :projectId, '需求图一', '20230001', '张三', :adminId,
                     :campusId, NOW(), 100, 'image/jpeg', 'photos/request-one.jpg', :sha1, 'AVAILABLE'),
                    (1007, 2902, :projectId, '需求图二', '20230002', '李四', :userId,
                     :campusId, NOW(), 100, 'image/jpeg', 'photos/request-two.jpg', :sha2, 'AVAILABLE')
                """).param("projectId", testProject.getId()).param("adminId", adminUser.id())
                .param("userId", managerUser.id()).param("campusId", testCampus.getId())
                .param("sha1", "p".repeat(64)).param("sha2", "q".repeat(64)).update();

        photoService.batchDelete(List.of(1006L, 1007L), managerUser);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM photo WHERE id IN (1006,1007) AND deleted=FALSE")
                .query(Long.class).single()).isZero();
    }

    @Test
    void listPhotos_shouldUseMembershipTable_soOnePhotoAppearsInMultipleProjects() {
        // Given: 第二个项目 B，和一张“主项目=testProject”的照片
        ProjectEntity projectB = projectService.create(
                "项目B", "另一个项目", ProjectStatus.ACTIVE, adminUser);
        jdbc.sql("""
                INSERT INTO photo
                    (id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key, sha256, status)
                VALUES
                    (1500, :projectId, '多归属照片', '20230001', '张三', 200, :campusId,
                     NOW(), 1000, 'image/jpeg', 'photos/2026/multi.jpg', :sha256, 'AVAILABLE')
                """)
                .param("projectId", testProject.getId())
                .param("campusId", testCampus.getId())
                .param("sha256", "n".repeat(64))
                .update();
        // 该照片同时归属 testProject 与 项目B（一图多项目）
        jdbc.sql("INSERT INTO photo_project (photo_id, project_id) VALUES (1500, :a), (1500, :b)")
                .param("a", testProject.getId()).param("b", projectB.getId())
                .update();

        // When & Then: 两个项目的相册都能查到这张照片
        var inA = photoService.list(1, 20, null, testProject.getId(), null, null, null,
                null, null, PhotoStatus.AVAILABLE, false, adminUser);
        var inB = photoService.list(1, 20, null, projectB.getId(), null, null, null,
                null, null, PhotoStatus.AVAILABLE, false, adminUser);
        assertThat(inA.items()).extracting(PhotoService.PhotoView::id).contains(1500L);
        assertThat(inB.items()).extracting(PhotoService.PhotoView::id).contains(1500L);
    }

    @Test
    void createTicket_shouldCreateMembershipLink() {
        var ticket = photoService.createTicket(new PhotoService.CreateTicket(
                null, testProject.getId(), "linked.jpg", "image/jpeg", 1024L,
                "o".repeat(64), photographerContactId, LocalDateTime.now()), adminUser);

        Long links = jdbc.sql(
                "SELECT COUNT(*) FROM photo_project WHERE photo_id=:pid AND project_id=:prj")
                .param("pid", ticket.photoId())
                .param("prj", testProject.getId())
                .query(Long.class).single();
        assertThat(links).isEqualTo(1L);
    }

    @Test
    void createTicket_withPhotographerFromAnotherCampus_shouldThrow() {
        // 另一个校区及其通讯录成员
        CampusEntity otherCampus = campusService.create("OTHER", "其他校区");
        jdbc.sql("""
                INSERT INTO campus_member (id, campus_id, student_id, name, enabled, version, deleted)
                VALUES (400, :campusId, '99990001', '王五', true, 1, false)
                """).param("campusId", otherCampus.getId()).update();

        // 校区负责人图库上传的校区为其本校区，选用其他校区的拍摄者应被拒绝
        PhotoService.CreateTicket command = new PhotoService.CreateTicket(
                null, null, "cross.jpg", "image/jpeg", 1024L,
                "1".repeat(64), 400L, LocalDateTime.now());

        assertThatThrownBy(() -> photoService.createTicket(command, managerUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("校区通讯录");
    }
}
