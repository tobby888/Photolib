package cn.photolib.photo;

import cn.photolib.adoption.AdoptionService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.photo.batch.BatchUploadService;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.project.model.ProjectType;
import cn.photolib.request.RequestService;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 公共照片（{@link PublicPhoto}）在站内的几条规则：它是一个拍摄者、图库里能按它筛，
 * 不计被引，选题可以设成仅接收公共照片。上传链接那一侧见 {@code PublicPhotoShareTests}。
 */
@SpringBootTest
@Transactional
class PublicPhotoTests {
    private static final long CAMPUS_ID = 9_710L;
    private static final long ADMIN_ID = 9_720L;
    private static final long MANAGER_ID = 9_721L;
    private static final long CONTACT_ID = 9_730L;
    private static final long PUBLIC_ONLY_REQUEST = 9_740L;
    private static final long NORMAL_REQUEST = 9_741L;

    @Autowired private PhotoService photoService;
    @Autowired private ProjectService projectService;
    @Autowired private RequestService requestService;
    @Autowired private BatchUploadService batchUploadService;
    @Autowired private AdoptionService adoptionService;
    @Autowired private JdbcClient jdbc;

    private AuthenticatedUser admin;
    private AuthenticatedUser manager;
    private ProjectEntity publicOnly;
    private ProjectEntity normal;
    private int shaSeed;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO campus (id, code, name, enabled, version, deleted)
                VALUES (:id, 'PUBP', '公共照片校区', true, 1, false)
                """).param("id", CAMPUS_ID).update();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES
                    (:admin, 'pub-admin', 'hash', '公共管理员', 'ADMIN', null, true, false),
                    (:manager, 'pub-manager', 'hash', '公共负责人', 'CAMPUS_MANAGER', :campus, true, false)
                """).param("admin", ADMIN_ID).param("manager", MANAGER_ID).param("campus", CAMPUS_ID).update();
        jdbc.sql("""
                INSERT INTO campus_member (id, campus_id, student_id, name, enabled, version, deleted)
                VALUES (:id, :campus, '20269730', '真实拍摄者', true, 1, false)
                """).param("id", CONTACT_ID).param("campus", CAMPUS_ID).update();
        admin = new AuthenticatedUser(ADMIN_ID, "pub-admin", "公共管理员", UserRole.ADMIN, null, false);
        manager = new AuthenticatedUser(MANAGER_ID, "pub-manager", "公共负责人", UserRole.CAMPUS_MANAGER,
                CAMPUS_ID, false);

        publicOnly = projectService.create("校园风光素材", "说明", ProjectStatus.ACTIVE, List.of(),
                ProjectType.CREATION, true, admin);
        normal = projectService.create("日常", "说明", ProjectStatus.ACTIVE, admin);
        insertAcceptedRequest(PUBLIC_ONLY_REQUEST, publicOnly.getId());
        insertAcceptedRequest(NORMAL_REQUEST, normal.getId());
    }

    // ------------------------------------------------------------------ 拍摄者

    @Test
    void aPublicPhotoIsRecordedAsThePublicPhotographerAndCanBeFoundByIt() {
        var ticket = photoService.createTicket(ticketCommand(null, null, null, true), admin);

        assertThat(photographerName(ticket.photoId())).isEqualTo(PublicPhoto.PHOTOGRAPHER_NAME);
        assertThat(photographerStudentId(ticket.photoId())).isEmpty();

        // 图库「按拍摄者筛选」：它就是一个普通的作者。
        long otherPhoto = insertPhoto(null, "20269730", "真实拍摄者");
        jdbc.sql("UPDATE photo SET status='AVAILABLE' WHERE id=:id").param("id", ticket.photoId()).update();
        assertThat(photoService.photographers(false, admin)).contains(PublicPhoto.PHOTOGRAPHER_NAME, "真实拍摄者");
        var found = photoService.list(1, 50, null, null, null, null, null, null, null, null,
                false, false, false, List.of(), List.of(PublicPhoto.PHOTOGRAPHER_NAME), admin).items();
        assertThat(found).extracting(PhotoService.PhotoView::id)
                .contains(ticket.photoId()).doesNotContain(otherPhoto);
    }

    @Test
    void withoutThePublicOptionTheDirectoryIsStillRequired() {
        assertThatThrownBy(() -> photoService.createTicket(ticketCommand(null, null, null, false), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("通讯录");
    }

    @Test
    void requestUploadsCanChooseThePublicPhotographerToo() {
        var ticket = photoService.createTicket(ticketCommand(NORMAL_REQUEST, normal.getId(), null, true), manager);
        assertThat(photographerName(ticket.photoId())).isEqualTo(PublicPhoto.PHOTOGRAPHER_NAME);
    }

    // ------------------------------------------------------------------ 仅接收公共照片

    @Test
    void aPublicOnlyProjectExposesTheFlagAndRejectsMemberPhotos() {
        assertThat(projectService.get(publicOnly.getId()).getPublicPhotosOnly()).isTrue();
        assertThat(projectService.getDetail(publicOnly.getId(), admin).publicPhotosOnly()).isTrue();
        assertThat(projectService.getDetail(normal.getId(), admin).publicPhotosOnly()).isFalse();
        // 上传者不一定能看选题，表单从需求这边读到这个开关。
        assertThat(requestService.tagOptions(PUBLIC_ONLY_REQUEST, manager).publicPhotosOnly()).isTrue();
        assertThat(requestService.tagOptions(NORMAL_REQUEST, manager).publicPhotosOnly()).isFalse();

        assertThatThrownBy(() -> photoService.createTicket(
                ticketCommand(PUBLIC_ONLY_REQUEST, publicOnly.getId(), CONTACT_ID, false), manager))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅接收公共照片");
        var accepted = photoService.createTicket(
                ticketCommand(PUBLIC_ONLY_REQUEST, publicOnly.getId(), null, true), manager);
        assertThat(photographerName(accepted.photoId())).isEqualTo(PublicPhoto.PHOTOGRAPHER_NAME);

        // 选题相册直传同一条规则。
        assertThatThrownBy(() -> photoService.createTicket(
                ticketCommand(null, publicOnly.getId(), CONTACT_ID, false), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅接收公共照片");
    }

    @Test
    void updatingTheProjectKeepsOrChangesTheFlag() {
        ProjectEntity kept = projectService.update(publicOnly.getId(), "改名", "说明", null,
                projectService.get(publicOnly.getId()).getVersion(), admin);
        assertThat(kept.getPublicPhotosOnly()).isTrue();
        ProjectEntity off = projectService.update(publicOnly.getId(), "改名", "说明", null, false,
                kept.getVersion(), admin);
        assertThat(off.getPublicPhotosOnly()).isFalse();
    }

    @Test
    void zipMetadataForAPublicOnlyProjectMustBePublic() {
        jdbc.sql("""
                INSERT INTO photo_upload_batch
                    (id, mode, request_id, project_id, created_by, status, total_count, success_count, failure_count)
                VALUES ('batch-public-only', 'ZIP', :requestId, :projectId, :manager, 'WAITING_METADATA', 1, 0, 0)
                """).param("requestId", PUBLIC_ONLY_REQUEST).param("projectId", publicOnly.getId())
                .param("manager", MANAGER_ID).update();
        jdbc.sql("""
                INSERT INTO photo_upload_item
                    (batch_id, original_file_name, temp_object_key, content_type, size, status)
                VALUES ('batch-public-only', '风光.jpg', 'temporary/public.jpg', 'image/jpeg', 100, 'WAITING_METADATA')
                """).update();

        assertThatThrownBy(() -> batchUploadService.setMetadataForAll("batch-public-only",
                new BatchUploadService.BatchMetadata("说明", CONTACT_ID, LocalDateTime.now(), List.of()), manager))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅接收公共照片");

        batchUploadService.setMetadataForAll("batch-public-only",
                new BatchUploadService.BatchMetadata("说明", null, LocalDateTime.now(), List.of(), true), manager);
        assertThat(jdbc.sql("SELECT photographer_name FROM photo WHERE request_id=:requestId")
                .param("requestId", PUBLIC_ONLY_REQUEST).query(String.class).single())
                .isEqualTo(PublicPhoto.PHOTOGRAPHER_NAME);
    }

    @Test
    void onlyPublicPhotosCanBeAddedOrCopiedIntoAPublicOnlyProject() {
        long member = insertPhoto(null, "20269730", "真实拍摄者");
        long common = insertPhoto(null, "", PublicPhoto.PHOTOGRAPHER_NAME);

        assertThatThrownBy(() -> projectService.addPhotos(publicOnly.getId(), List.of(common, member), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅接收公共照片");
        assertThat(inAlbum(common, publicOnly.getId())).isFalse();
        projectService.addPhotos(publicOnly.getId(), List.of(common), admin);
        assertThat(inAlbum(common, publicOnly.getId())).isTrue();

        projectService.addPhotos(normal.getId(), List.of(member), admin);
        assertThatThrownBy(() -> projectService.transferPhotos(normal.getId(), publicOnly.getId(),
                List.of(member), ProjectService.TransferMode.COPY, admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅接收公共照片");
    }

    // ------------------------------------------------------------------ 不计被引

    @Test
    void publicPhotosCannotBeMarkedAsCited() {
        long common = insertPhoto(normal.getId(), "", PublicPhoto.PHOTOGRAPHER_NAME);
        long member = insertPhoto(normal.getId(), "20269730", "真实拍摄者");

        assertThatThrownBy(() -> adoptionService.adopt(normal.getId(), List.of(common), null, admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不计入被引");
        assertThat(adoptionCount(common)).isZero();

        adoptionService.adopt(normal.getId(), List.of(member), null, admin);
        assertThat(adoptionCount(member)).isEqualTo(1);
    }

    @Test
    void aCitedPhotoCannotBecomePublicAndTheAdminFormDropsTheStudentId() {
        long cited = insertPhoto(normal.getId(), "20269730", "真实拍摄者");
        adoptionService.adopt(normal.getId(), List.of(cited), null, admin);
        assertThatThrownBy(() -> photoService.adminUpdate(cited, adminMetadata(" 公共照片 ", "123", 1), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("被引");
        assertThatThrownBy(() -> photoService.adminBatchUpdate(new PhotoService.BatchMetadata(List.of(cited),
                null, null, new PhotoService.PhotographerChange("公共照片", ""), null, true), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("被引");

        long uncited = insertPhoto(null, "20269730", "真实拍摄者");
        var edit = photoService.adminUpdate(uncited, adminMetadata(" 公共照片 ", "123", 1), admin);
        assertThat(edit.photo().photographerName()).isEqualTo(PublicPhoto.PHOTOGRAPHER_NAME);
        assertThat(photographerStudentId(uncited)).isEmpty();
    }

    // ------------------------------------------------------------------ 夹具

    private PhotoService.CreateTicket ticketCommand(Long requestId, Long projectId, Long contactId,
                                                    boolean publicPhoto) {
        return new PhotoService.CreateTicket(requestId, projectId, "p.jpg", "image/jpeg", 1024L, nextSha(),
                contactId, LocalDateTime.now().minusMinutes(1), publicPhoto);
    }

    private PhotoService.AdminMetadata adminMetadata(String name, String studentId, int version) {
        return new PhotoService.AdminMetadata("标题", null, name, studentId, LocalDateTime.now().minusDays(1),
                CAMPUS_ID, List.of(), version, true);
    }

    private String nextSha() {
        String sha = Integer.toHexString(++shaSeed);
        return "e".repeat(64 - sha.length()) + sha;
    }

    private void insertAcceptedRequest(long id, Long projectId) {
        jdbc.sql("""
                INSERT INTO photo_request (id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:id, :projectId, '公共需求', :campus, :deadline, 'ACCEPTED', :admin)
                """).param("id", id).param("projectId", projectId).param("campus", CAMPUS_ID)
                .param("admin", ADMIN_ID).param("deadline", LocalDateTime.now().plusDays(3)).update();
        jdbc.sql("""
                INSERT INTO request_participant (request_id, user_id, accepted_at)
                VALUES (:id, :manager, :acceptedAt)
                """).param("id", id).param("manager", MANAGER_ID).param("acceptedAt", LocalDateTime.now()).update();
    }

    private long insertPhoto(Long projectId, String studentId, String name) {
        String sha = nextSha();
        jdbc.sql("""
                INSERT INTO photo
                    (project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key, sha256, status)
                VALUES
                    (:projectId, '图片', :studentId, :name, :admin, :campus,
                     :takenAt, 1000, 'image/jpeg', :objectKey, :sha, 'AVAILABLE')
                """)
                .param("projectId", projectId).param("studentId", studentId).param("name", name)
                .param("admin", ADMIN_ID).param("campus", CAMPUS_ID)
                .param("takenAt", LocalDateTime.now().minusDays(1))
                .param("objectKey", "photos/2026/public-" + sha + ".jpg").param("sha", sha)
                .update();
        long id = jdbc.sql("SELECT id FROM photo WHERE sha256=:sha").param("sha", sha).query(Long.class).single();
        if (projectId != null) {
            jdbc.sql("INSERT INTO photo_project (photo_id, project_id) VALUES (:photoId, :projectId)")
                    .param("photoId", id).param("projectId", projectId).update();
        }
        return id;
    }

    private String photographerName(Long photoId) {
        return jdbc.sql("SELECT photographer_name FROM photo WHERE id=:id").param("id", photoId)
                .query(String.class).single();
    }

    private String photographerStudentId(Long photoId) {
        return jdbc.sql("SELECT photographer_student_id FROM photo WHERE id=:id").param("id", photoId)
                .query(String.class).single();
    }

    private boolean inAlbum(long photoId, long projectId) {
        return jdbc.sql("SELECT COUNT(*) FROM photo_project WHERE photo_id=:p AND project_id=:pr")
                .param("p", photoId).param("pr", projectId).query(Long.class).single() > 0;
    }

    private long adoptionCount(long photoId) {
        return jdbc.sql("SELECT COUNT(*) FROM adoption WHERE photo_id=:id AND deleted=FALSE")
                .param("id", photoId).query(Long.class).single();
    }
}
