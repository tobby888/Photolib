package cn.photolib.photo;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.campus.model.CampusEntity;
import cn.photolib.common.error.BusinessException;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * 上传失败后重传同一个文件（issue #156）。
 *
 * <p>签了票据却没传完的行停在 {@code UPLOADING}、没有失败原因，要等直传地址过期再加一小时
 * 宽限才会被清理任务收走。在那之前，同一个人在同一处重传同一个文件应该拿到同一张图的新票据，
 * 而不是「已经上传过该图片」；换了人、换了选题、换了需求，仍然按重复处理。</p>
 */
@SpringBootTest
@Transactional
class PhotoTicketResumeTests {
    private static final long UPLOADER_ID = 15_601L;
    private static final long OTHER_ADMIN_ID = 15_602L;
    private static final long CONTACT_ID = 15_610L;

    @Autowired private PhotoService photoService;
    @Autowired private ProjectService projectService;
    @Autowired private CampusService campusService;
    @Autowired private ObjectStorageService storage;
    @Autowired private StorageProperties storageProperties;
    @Autowired private PhotoProcessingWorkspace workspace;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcClient jdbc;
    @Autowired private Clock clock;

    private AuthenticatedUser uploader;
    private AuthenticatedUser otherAdmin;
    private ProjectEntity project;
    private CampusEntity campus;

    @BeforeEach
    void setUp() {
        campus = campusService.create("RESUME", "续传测试校区");
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES
                    (:uploader, 'resume-uploader', 'hash', '续传上传者', 'ADMIN', null, true, false),
                    (:other, 'resume-other', 'hash', '另一位管理员', 'ADMIN', null, true, false)
                """).param("uploader", UPLOADER_ID).param("other", OTHER_ADMIN_ID).update();
        jdbc.sql("""
                INSERT INTO campus_member (id, campus_id, student_id, name, enabled, version, deleted)
                VALUES (:id, :campusId, '20231560', '续传拍摄者', true, 1, false)
                """).param("id", CONTACT_ID).param("campusId", campus.getId()).update();
        uploader = new AuthenticatedUser(UPLOADER_ID, "resume-uploader", "续传上传者", UserRole.ADMIN, null, false);
        otherAdmin = new AuthenticatedUser(OTHER_ADMIN_ID, "resume-other", "另一位管理员", UserRole.ADMIN, null, false);
        project = projectService.create("续传测试选题", "描述", ProjectStatus.ACTIVE, uploader);
    }

    private static String sha(char c) {
        return String.valueOf(c).repeat(64);
    }

    private PhotoService.UploadTicket ticket(Long requestId, Long projectId, String sha256,
                                            String contentType, AuthenticatedUser user) {
        return photoService.createTicket(new PhotoService.CreateTicket(
                requestId, projectId, contentType.equals("image/png") ? "retry.png" : "retry.jpg",
                contentType, 2048L, sha256,
                CONTACT_ID, LocalDateTime.now(clock)), user);
    }

    private PhotoService.UploadTicket ticket(Long projectId, String sha256, AuthenticatedUser user) {
        return ticket(null, projectId, sha256, "image/jpeg", user);
    }

    private Row row(long photoId) {
        return jdbc.sql("""
                        SELECT status, failure_reason, version, original_object_key, content_type,
                               upload_url_expires_at, deleted
                        FROM photo WHERE id = :id
                        """).param("id", photoId)
                .query((rs, n) -> new Row(rs.getString("status"), rs.getString("failure_reason"),
                        rs.getInt("version"), rs.getString("original_object_key"),
                        rs.getString("content_type"),
                        rs.getTimestamp("upload_url_expires_at").toLocalDateTime(),
                        rs.getBoolean("deleted")))
                .single();
    }

    private long count(String sql, String sha256) {
        return jdbc.sql(sql).param("sha", sha256).query(Long.class).single();
    }

    /** 清理任务的候选集是全库的：先把既有行藏起来，随测试事务一起回滚。 */
    private void hideExistingPhotos() {
        jdbc.sql("UPDATE photo SET deleted = 1 WHERE deleted = 0").update();
    }

    /** 把票据的到期时间往前拨，模拟「上一次签完已经过了很久」。 */
    private void expire(long photoId, LocalDateTime at) {
        jdbc.sql("UPDATE photo SET upload_url_expires_at = :at WHERE id = :id")
                .param("at", at).param("id", photoId).update();
    }

    @Test
    void theSameUploaderRetryingInTheSameProjectGetsANewUrlForTheSamePhoto() {
        String sha = sha('a');
        var first = ticket(project.getId(), sha, uploader);
        Row before = row(first.photoId());
        expire(first.photoId(), LocalDateTime.now(clock).minusMinutes(20));

        var second = ticket(project.getId(), sha, uploader);

        assertThat(second.photoId()).isEqualTo(first.photoId());
        assertThat(second.uploadUrl()).isNotBlank();
        Row after = row(first.photoId());
        // 只续到期时间：status、failure_reason、version、原图 key 都不动。
        assertThat(after.status()).isEqualTo("UPLOADING");
        assertThat(after.failureReason()).isNull();
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.originalObjectKey()).isEqualTo(before.originalObjectKey());
        assertThat(after.uploadUrlExpiresAt()).isAfter(LocalDateTime.now(clock));
        // 不插新行，也不重复插选题归属。
        assertThat(count("SELECT COUNT(*) FROM photo WHERE sha256 = :sha", sha)).isOne();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM photo_project WHERE photo_id = :id")
                .param("id", first.photoId()).query(Long.class).single()).isOne();
    }

    @Test
    void theResumedTicketKeepsTheContentTypeItWasFirstSignedWith() {
        String sha = sha('b');
        var first = ticket(null, project.getId(), sha, "image/png", uploader);

        // 同一个文件这次被声明成 JPEG：签名必须仍用行上的 PNG，否则 PUT 会因类型不一致被拒。
        var second = ticket(null, project.getId(), sha, "image/jpeg", uploader);

        assertThat(second.photoId()).isEqualTo(first.photoId());
        assertThat(second.contentType()).isEqualTo("image/png");
        assertThat(row(first.photoId()).contentType()).isEqualTo("image/png");
    }

    @Test
    void theResumedTicketCanStillBeCompleted() {
        String sha = sha('c');
        var first = ticket(project.getId(), sha, uploader);
        var second = ticket(project.getId(), sha, uploader);
        String key = row(second.photoId()).originalObjectKey();
        byte[] bytes = "original".getBytes(StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");

        // version 没被续期动过，complete 的乐观锁照常通过。
        var view = photoService.complete(first.photoId(),
                new PhotoService.CompleteUpload("标题", "描述", java.util.List.of()), uploader);

        assertThat(view.status()).isEqualTo(cn.photolib.photo.model.PhotoStatus.PROCESSING);
        storage.delete(key);
    }

    @Test
    void anotherUploaderIsStillTreatedAsADuplicate() {
        String sha = sha('d');
        ticket(project.getId(), sha, uploader);

        assertThatThrownBy(() -> ticket(project.getId(), sha, otherAdmin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已经上传过该图片");
        assertThat(count("SELECT COUNT(*) FROM photo WHERE sha256 = :sha", sha)).isOne();
    }

    @Test
    void anotherProjectIsStillTreatedAsADuplicate() {
        String sha = sha('e');
        ProjectEntity other = projectService.create("另一个选题", "描述", ProjectStatus.ACTIVE, uploader);
        ticket(project.getId(), sha, uploader);

        assertThatThrownBy(() -> ticket(other.getId(), sha, uploader))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已经上传过该图片");
        // 从选题换到总图库同样算换了地方。
        assertThatThrownBy(() -> ticket(null, sha, uploader))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已经上传过该图片");
    }

    @Test
    void anotherRequestIsStillTreatedAsADuplicate() {
        String sha = sha('f');
        jdbc.sql("""
                INSERT INTO photo_request
                    (id, project_id, title, campus_id, required_count, deadline, status, created_by)
                VALUES (15620, :projectId, '续传需求', :campusId, 1,
                        DATEADD('DAY', 1, CURRENT_TIMESTAMP), 'ACCEPTED', :uploader)
                """).param("projectId", project.getId()).param("campusId", campus.getId())
                .param("uploader", UPLOADER_ID).update();
        // 先在选题里直接传，再从同一选题下的需求传：选题相同，需求不同。
        ticket(project.getId(), sha, uploader);

        assertThatThrownBy(() -> ticket(15_620L, project.getId(), sha, "image/jpeg", uploader))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已经上传过该图片");
    }

    @Test
    void aPublishedPhotoIsStillTreatedAsADuplicate() {
        String sha = sha('1');
        var first = ticket(project.getId(), sha, uploader);
        jdbc.sql("UPDATE photo SET status = 'AVAILABLE', title = '已发布' WHERE id = :id")
                .param("id", first.photoId()).update();

        assertThatThrownBy(() -> ticket(project.getId(), sha, uploader))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已发布");
    }

    @Test
    void aFailedUploadLetsTheSameFileThroughAsANewPhoto() {
        String sha = sha('2');
        var first = ticket(project.getId(), sha, uploader);
        jdbc.sql("UPDATE photo SET failure_reason = '处理失败' WHERE id = :id")
                .param("id", first.photoId()).update();

        var second = ticket(project.getId(), sha, uploader);

        // 处理失败的行查重本来就不算，这里照旧签一张新图，不去续那一行。
        assertThat(second.photoId()).isNotEqualTo(first.photoId());
        assertThat(row(first.photoId()).failureReason()).isEqualTo("处理失败");
    }

    @Test
    void aGalleryUploadWithoutProjectIsResumedToo() {
        String sha = sha('3');
        var first = ticket(null, sha, uploader);

        var second = ticket(null, sha, uploader);

        assertThat(second.photoId()).isEqualTo(first.photoId());
        assertThat(count("SELECT COUNT(*) FROM photo WHERE sha256 = :sha", sha)).isOne();
    }

    @Test
    void aShareLinkUploadIsNeverResumedByItsCreator() {
        String sha = sha('4');
        var first = ticket(project.getId(), sha, uploader);
        // 上传链接的行 uploaded_by 记的是链接创建者，不能被创建者在站内续用。
        jdbc.sql("""
                INSERT INTO project_share_link (id, token, project_id, password_hash, created_by, purpose)
                VALUES (15630, 'RESUMELINK0000000000000000', :projectId, 'hash', :uploader, 'UPLOAD')
                """).param("projectId", project.getId()).param("uploader", UPLOADER_ID).update();
        jdbc.sql("UPDATE photo SET share_link_id = 15630 WHERE id = :id")
                .param("id", first.photoId()).update();

        assertThatThrownBy(() -> ticket(project.getId(), sha, uploader))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已经上传过该图片");
    }

    @Test
    void aResumedTicketIsNotCollectedByEitherCleanupJob() {
        hideExistingPhotos();
        String sha = sha('5');
        var first = ticket(project.getId(), sha, uploader);
        String key = row(first.photoId()).originalObjectKey();
        // 上一次签完已经过了一天，按理早该被收走；重传把它续上了。
        expire(first.photoId(), LocalDateTime.now(clock).minusDays(1));
        ticket(project.getId(), sha, uploader);

        new AbandonedUploadCleanupJob(storage, storageProperties, workspace, jdbc, clock).cleanup();
        new MissingUploadObjectScanJob(storage, jdbc, transactions, clock, true).scan();

        Row after = row(first.photoId());
        assertThat(after.deleted()).isFalse();
        assertThat(after.originalObjectKey()).isEqualTo(key);
    }

    @Test
    void theAbandonedCleanupDoesNotSoftDeleteATicketResumedWhileItWasDeleting() {
        String sha = sha('6');
        var first = ticket(project.getId(), sha, uploader);
        String key = row(first.photoId()).originalObjectKey();
        expire(first.photoId(), LocalDateTime.now(clock).minusDays(1));
        // 任务已经选中这一行、正在删对象的那一刻，上传者重传续上了票据。
        ObjectStorageService racing = spy(storage);
        doAnswer(invocation -> {
            if (key.equals(invocation.getArgument(0))) ticket(project.getId(), sha, uploader);
            return invocation.callRealMethod();
        }).when(racing).delete(anyString());

        new AbandonedUploadCleanupJob(racing, storageProperties, workspace, jdbc, clock).cleanup();

        // 对象删了也没关系：客户端会拿新地址重新 PUT。要紧的是行还在、key 还在。
        Row after = row(first.photoId());
        assertThat(after.deleted()).isFalse();
        assertThat(after.originalObjectKey()).isEqualTo(key);
    }

    @Test
    void theMissingObjectScanDoesNotSoftDeleteATicketResumedDuringItsHead() {
        hideExistingPhotos();
        String sha = sha('7');
        // 扫描要先 HEAD 到一张已发布照片的成品图，否则整轮按存储不可信中止。
        byte[] published = "published".getBytes(StandardCharsets.UTF_8);
        storage.put("photos/resume/sentinel.jpg", new ByteArrayInputStream(published), published.length,
                "image/jpeg");
        jdbc.sql("""
                INSERT INTO photo
                    (photographer_student_id, photographer_name, uploaded_by, taken_at, size, content_type,
                     object_key, sha256, status)
                VALUES ('20231560', '续传拍摄者', :uploader, :now, 9, 'image/jpeg',
                        'photos/resume/sentinel.jpg', :sha, 'AVAILABLE')
                """).param("uploader", UPLOADER_ID).param("now", LocalDateTime.now(clock))
                .param("sha", sha('8')).update();
        var first = ticket(project.getId(), sha, uploader);
        String key = row(first.photoId()).originalObjectKey();
        expire(first.photoId(), LocalDateTime.now(clock).minusDays(1));
        // HEAD 确认原图不存在的那一刻，上传者重传续上了票据。
        ObjectStorageService racing = spy(storage);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (key.equals(invocation.getArgument(0))) ticket(project.getId(), sha, uploader);
            return result;
        }).when(racing).find(anyString());

        new MissingUploadObjectScanJob(racing, jdbc, transactions, clock, true).scan();

        assertThat(row(first.photoId()).deleted()).isFalse();
        storage.delete("photos/resume/sentinel.jpg");
    }

    private record Row(String status, String failureReason, int version, String originalObjectKey,
                       String contentType, LocalDateTime uploadUrlExpiresAt, boolean deleted) {}
}
