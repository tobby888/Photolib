package cn.photolib.photo;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.campus.model.CampusEntity;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「签了票但没传完」的上传票据复用（方案 A）。
 *
 * <p>以前这类行（{@code UPLOADING} 且 {@code failure_reason} 为空）会被查重当成
 * "已经上传过"，同一个文件在 {@link AbandonedUploadCleanupJob} 收走它之前一直报
 * {@code DUPLICATE_RESOURCE}——直传地址有效期 + 1 小时宽限，最长约 105 分钟。现在的
 * 规则是：同一个上传者、同一个需求/选题、同一份文件，就把那行接着用，只换一张新票据；
 * 其余任何组合都还是重复。</p>
 */
@SpringBootTest
@Transactional
class PhotoTicketResumeTests {
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
    @Autowired
    private AbandonedUploadCleanupJob abandonedUploads;
    @Autowired
    private Clock clock;

    private AuthenticatedUser adminUser;
    private AuthenticatedUser secondAdminUser;
    private ProjectEntity testProject;
    private CampusEntity testCampus;
    private Long photographerContactId;

    @BeforeEach
    void setUp() {
        testCampus = campusService.create("RESUME", "续传测试校区");

        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES
                    (200, 'resume-admin', 'hash', '续传管理员', 'ADMIN', null, true, false),
                    (203, 'resume-admin-2', 'hash', '续传管理员二', 'ADMIN', null, true, false)
                """).update();

        // 拍摄者必须来自通讯录：无校区的管理员上传接受任意启用成员。
        jdbc.sql("""
                INSERT INTO campus_member (id, campus_id, student_id, name, enabled, version, deleted)
                VALUES (300, :campusId, '20230001', '张三', true, 1, false)
                """).param("campusId", testCampus.getId()).update();
        photographerContactId = 300L;

        adminUser = new AuthenticatedUser(
                200L, "resume-admin", "续传管理员", UserRole.ADMIN, null, false);
        secondAdminUser = new AuthenticatedUser(
                203L, "resume-admin-2", "续传管理员二", UserRole.ADMIN, null, false);

        testProject = projectService.create(
                "续传测试项目", "项目描述", ProjectStatus.ACTIVE, adminUser);
    }

    @Test
    void theSameUploaderResumesTheAbandonedRowInsteadOfBeingBlocked() {
        String sha = sha(1);
        var first = photoService.createTicket(ticket(sha, testProject.getId()), adminUser);
        Integer versionBefore = versionOf(first.photoId());
        boolean failureReasonBefore = hasFailureReason(first.photoId());
        // 模拟「票早过期、行还没被清理」：第二次签票前把过期时刻推到过去。
        expireTicket(first.photoId());

        var second = photoService.createTicket(ticket(sha, testProject.getId()), adminUser);

        // 同一行：photoId 不变、不重插，且 expiry 真的被刷新了（严格晚于现在）。
        assertThat(second.photoId()).isEqualTo(first.photoId());
        assertThat(photoCount(sha)).isOne();
        assertThat(uploadUrlExpiresAt(second.photoId())).isAfter(LocalDateTime.now(clock));
        // 复用不改状态、不动乐观锁版本、不清 failure_reason，也不重复插 photo_project。
        assertThat(statusOf(first.photoId())).isEqualTo("UPLOADING");
        assertThat(versionOf(first.photoId())).isEqualTo(versionBefore);
        assertThat(hasFailureReason(first.photoId())).isEqualTo(failureReasonBefore);
        assertThat(photoProjectCount(first.photoId())).isOne();
        assertThat(second.uploadUrl()).isNotEmpty();
        assertThat(second.method()).isEqualTo("PUT");
    }

    @Test
    void anotherUploaderStillGetsDuplicateResource() {
        String sha = sha(2);
        photoService.createTicket(ticket(sha, testProject.getId()), adminUser);

        assertThatThrownBy(() -> photoService.createTicket(ticket(sha, testProject.getId()), secondAdminUser))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
                    assertThat(exception.getMessage()).contains("已经上传过该图片");
                });
        assertThat(photoCount(sha)).isOne();
    }

    @Test
    void theSameUploaderStillGetsDuplicateResourceForAnotherProject() {
        String sha = sha(3);
        photoService.createTicket(ticket(sha, testProject.getId()), adminUser);
        ProjectEntity otherProject = projectService.create(
                "续传测试另一个项目", "项目描述", ProjectStatus.ACTIVE, adminUser);

        assertThatThrownBy(() -> photoService.createTicket(
                ticket(sha, otherProject.getId()), adminUser))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
                    assertThat(exception.getMessage()).contains("已经上传过该图片");
                });
        assertThat(photoCount(sha)).isOne();
    }

    @Test
    void theSameUploaderStillGetsDuplicateResourceForAnotherRequest() {
        String sha = sha(7);
        insertRequest(2901L, "续传测试需求一");
        insertRequest(2902L, "续传测试需求二");
        photoService.createTicket(requestTicket(sha, 2901L), adminUser);

        // requestId 判等是复用条件里唯一容易写错的一条：同项目、同人、同文件，换个需求就不行。
        assertThatThrownBy(() -> photoService.createTicket(requestTicket(sha, 2902L), adminUser))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
                    assertThat(exception.getMessage()).contains("已经上传过该图片");
                });
        assertThat(photoCount(sha)).isOne();
    }

    @Test
    void aPublishedPhotoWithTheSameShaIsStillADuplicate() {
        String sha = sha(4);
        insertPhoto(887_201L, sha, "AVAILABLE", null);

        assertThatThrownBy(() -> photoService.createTicket(ticket(sha, testProject.getId()), adminUser))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
                    assertThat(exception.getMessage()).contains("已经上传过该图片");
                });
        assertThat(photoCount(sha)).isOne();
    }

    @Test
    void aFailedUploadRowDoesNotBlockANewTicket() {
        String sha = sha(5);
        insertPhoto(887_202L, sha, "UPLOADING", "处理失败");

        // 失败行本来就不算重复（见 findDuplicate），这里拿到的是全新的一行。
        var ticket = photoService.createTicket(ticket(sha, testProject.getId()), adminUser);

        assertThat(ticket.photoId()).isNotEqualTo(887_202L);
        assertThat(photoCount(sha)).isEqualTo(2);
    }

    @Test
    void reusedTicketKeepsTheStoredContentTypeInsteadOfTheNewRequests() {
        String sha = sha(6);
        var first = photoService.createTicket(
                ticket(sha, testProject.getId(), "resume.jpg", "image/jpeg"), adminUser);

        // 同一份字节换个扩展名/声明类型再传：仍复用原行，但 Content-Type 必须跟库里一致，
        // 不能让签名头、photo.content_type、对象实际字节三者对不上。
        var second = photoService.createTicket(
                ticket(sha, testProject.getId(), "resume.png", "image/png"), adminUser);

        assertThat(second.photoId()).isEqualTo(first.photoId());
        assertThat(second.contentType()).isEqualTo("image/jpeg");
        assertThat(photoCount(sha)).isOne();
    }

    @Test
    void resumingAGalleryUploadKeepsTheSamePhoto() {
        String sha = sha(8);
        var first = photoService.createTicket(galleryTicket(sha), adminUser);

        var second = photoService.createTicket(galleryTicket(sha), adminUser);

        // 总图库场景 requestId / projectId 都是 null，判等两边一致，同样复用。
        assertThat(second.photoId()).isEqualTo(first.photoId());
        assertThat(photoCount(sha)).isOne();
        assertThat(photoProjectCount(first.photoId())).isZero();
    }

    @Test
    void resumingRefreshesTheExpirySoTheRowIsNoLongerACleanupCandidate() {
        String sha = sha(9);
        var first = photoService.createTicket(ticket(sha, testProject.getId()), adminUser);
        String originalKey = originalObjectKeyOf(first.photoId());
        putObject(originalKey);
        expireTicket(first.photoId());

        // 续上之后 expiry 被刷新：这一行不再是清理任务的候选。这里只验证候选资格，
        // 不覆盖"SELECT 之后、删对象之前被续期"的那个清理竞态。
        var second = photoService.createTicket(ticket(sha, testProject.getId()), adminUser);
        abandonedUploads.cleanup();

        assertThat(second.photoId()).isEqualTo(first.photoId());
        assertThat(deletedOf(first.photoId())).isFalse();
        assertThat(storage.find(originalKey)).isPresent();
        storage.delete(originalKey);
    }

    private PhotoService.CreateTicket ticket(String sha, Long projectId) {
        return ticket(sha, projectId, "resume.jpg", "image/jpeg");
    }

    private PhotoService.CreateTicket ticket(String sha, Long projectId,
                                             String fileName, String contentType) {
        return new PhotoService.CreateTicket(null, projectId, fileName, contentType, 1024L,
                sha, photographerContactId, LocalDateTime.now());
    }

    private PhotoService.CreateTicket galleryTicket(String sha) {
        return new PhotoService.CreateTicket(null, null, "gallery.jpg", "image/jpeg", 1024L,
                sha, photographerContactId, LocalDateTime.now());
    }

    private PhotoService.CreateTicket requestTicket(String sha, long requestId) {
        return new PhotoService.CreateTicket(requestId, null, "resume.jpg", "image/jpeg", 1024L,
                sha, photographerContactId, LocalDateTime.now());
    }

    /** 把这张票的过期时刻推到过去：模拟「签了票但没传完、清理任务还没跑」。 */
    private void expireTicket(long photoId) {
        jdbc.sql("""
                UPDATE photo SET upload_url_expires_at = DATEADD('HOUR', -2, CURRENT_TIMESTAMP)
                WHERE id = :id
                """).param("id", photoId).update();
    }

    private LocalDateTime uploadUrlExpiresAt(long photoId) {
        return jdbc.sql("SELECT upload_url_expires_at FROM photo WHERE id = :id")
                .param("id", photoId)
                .query((rs, row) -> rs.getTimestamp("upload_url_expires_at").toLocalDateTime())
                .single();
    }

    private String statusOf(long photoId) {
        return jdbc.sql("SELECT status FROM photo WHERE id = :id")
                .param("id", photoId).query(String.class).single();
    }

    private Integer versionOf(long photoId) {
        return jdbc.sql("SELECT version FROM photo WHERE id = :id")
                .param("id", photoId).query(Integer.class).single();
    }

    private boolean hasFailureReason(long photoId) {
        return jdbc.sql("SELECT COUNT(*) FROM photo WHERE id = :id AND failure_reason IS NOT NULL")
                .param("id", photoId).query(Long.class).single() > 0;
    }

    private boolean deletedOf(long photoId) {
        return jdbc.sql("SELECT deleted FROM photo WHERE id = :id")
                .param("id", photoId).query(Boolean.class).single();
    }

    private String originalObjectKeyOf(long photoId) {
        return jdbc.sql("SELECT original_object_key FROM photo WHERE id = :id")
                .param("id", photoId).query(String.class).single();
    }

    /** 该 sha 在 photo 表里的行数：复用路径不得插新行。 */
    private long photoCount(String sha256) {
        return jdbc.sql("SELECT COUNT(*) FROM photo WHERE sha256 = :sha")
                .param("sha", sha256).query(Long.class).single();
    }

    private long photoProjectCount(long photoId) {
        return jdbc.sql("SELECT COUNT(*) FROM photo_project WHERE photo_id = :id")
                .param("id", photoId).query(Long.class).single();
    }

    private void insertPhoto(long id, String sha256, String status, String failureReason) {
        jdbc.sql("""
                INSERT INTO photo
                    (id, project_id, photographer_student_id, photographer_name, uploaded_by,
                     campus_id, taken_at, size, content_type, object_key, original_object_key,
                     sha256, status, failure_reason, title, version, deleted)
                VALUES (:id, :projectId, '20230001', '张三', :uploader, :campusId, NOW(), 1024,
                        'image/jpeg', :objectKey, :originalKey, :sha, :status, :failureReason,
                        '续传测试图片', 1, FALSE)
                """)
                .param("id", id)
                .param("projectId", testProject.getId())
                .param("uploader", adminUser.id())
                .param("campusId", testCampus.getId())
                .param("objectKey", "photos/resume-test/" + id + ".jpg")
                .param("originalKey", "temporary/photos/resume-test/" + id + ".jpg")
                .param("sha", sha256).param("status", status)
                .param("failureReason", failureReason)
                .update();
    }

    private void insertRequest(long requestId, String title) {
        jdbc.sql("""
                INSERT INTO photo_request
                    (id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:id, :projectId, :title, :campusId,
                        DATEADD('DAY', 1, CURRENT_TIMESTAMP), 'ACCEPTED', :adminId)
                """)
                .param("id", requestId)
                .param("projectId", testProject.getId())
                .param("title", title)
                .param("campusId", testCampus.getId())
                .param("adminId", adminUser.id())
                .update();
    }

    private void putObject(String key) {
        byte[] bytes = "original".getBytes(StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
    }

    /** 64 位十六进制的哈希占位；每个用例一份，免得撞上查重。 */
    private String sha(int seed) {
        return String.format("%064x", 0x7E5_0000L + seed);
    }
}
