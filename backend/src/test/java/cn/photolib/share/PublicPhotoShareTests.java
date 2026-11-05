package cn.photolib.share;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.photo.PublicPhoto;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.project.model.ProjectType;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上传链接里的公共照片：访客可以勾选「公共照片」代替自报姓名学号，选了就不能申报工时；
 * 选题仅接收公共照片时，不论报了什么，会话身份都是公共照片。
 */
@SpringBootTest
@Transactional
class PublicPhotoShareTests {
    private static final long MINISTER_ID = 9_751L;
    private static final String PASSWORD = "public-pass";

    @Autowired private ProjectShareService shareService;
    @Autowired private ProjectShareUploadService uploadService;
    @Autowired private ProjectService projectService;
    @Autowired private ObjectStorageService storage;
    @Autowired private JdbcClient jdbc;

    private AuthenticatedUser minister;
    private ProjectEntity normal;
    private ProjectEntity publicOnly;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES (:id, 'public-minister', 'hash', '公共部长', 'MINISTER', null, TRUE, FALSE)
                """).param("id", MINISTER_ID).update();
        minister = new AuthenticatedUser(MINISTER_ID, "public-minister", "公共部长",
                UserRole.MINISTER, null, false);
        normal = projectService.create("迎新晚会", "活动选题", ProjectStatus.ACTIVE,
                List.of(), ProjectType.EVENT, minister);
        publicOnly = projectService.create("校园素材", "创作选题", ProjectStatus.ACTIVE,
                List.of(), ProjectType.CREATION, true, minister);
    }

    @Test
    void choosingPublicPhotosRecordsThePublicPhotographerAndBlocksWorklogs() {
        var created = uploadLink(normal.getId());
        assertThat(shareService.greet(created.link().token()).publicPhotosOnly()).isFalse();
        var session = shareService.openSession(created.link().token(), PASSWORD,
                new ProjectShareService.GuestIdentity(null, null, true));
        var context = context(created, session.sessionToken());

        assertThat(session.access().uploaderPublic()).isTrue();
        assertThat(session.access().uploaderName()).isEqualTo(PublicPhoto.PHOTOGRAPHER_NAME);
        assertThat(session.access().allowUpload()).isTrue();
        assertThat(session.access().allowWorklog()).isFalse();

        Long photoId = handIn(context, "a");
        assertThat(column(photoId, "photographer_name")).isEqualTo(PublicPhoto.PHOTOGRAPHER_NAME);
        assertThat(column(photoId, "photographer_student_id")).isEmpty();

        assertThatThrownBy(() -> uploadService.declareWorklog(context, declaration()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("公共照片")
                // 不能是 403/404：访客页把那两种当成会话失效。
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
        assertThat(uploadService.worklogs(context)).isEmpty();
    }

    @Test
    void switchingFromPublicPhotosToAPersonAllowsThatPersonToDeclare() {
        var created = uploadLink(normal.getId());
        var session = shareService.openSession(created.link().token(), PASSWORD,
                new ProjectShareService.GuestIdentity(null, null, true));
        handIn(context(created, session.sessionToken()), "b");

        var access = shareService.switchUploader(context(created, session.sessionToken()),
                new ProjectShareService.GuestIdentity("张小拍", "20230001"));
        assertThat(access.uploaderPublic()).isFalse();
        assertThat(access.allowWorklog()).isTrue();
        // 公共照片交过的图不能拿来给本人报工时：门槛按学号现查。
        assertThatThrownBy(() -> uploadService.declareWorklog(context(created, session.sessionToken()),
                declaration())).isInstanceOf(BusinessException.class).hasMessageContaining("先通过这条链接上传");

        handIn(context(created, session.sessionToken()), "c");
        assertThat(uploadService.declareWorklog(context(created, session.sessionToken()), declaration()))
                .isNotNull();
    }

    @Test
    void thePublicPhotographerNameIsReservedForGuests() {
        var created = uploadLink(normal.getId());
        assertThatThrownBy(() -> shareService.openSession(created.link().token(), PASSWORD,
                new ProjectShareService.GuestIdentity(" 公共照片 ", "20230001")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("勾选");
    }

    @Test
    void aPublicOnlyProjectMakesEveryGuestUploadPublic() {
        var created = uploadLink(publicOnly.getId());
        assertThat(shareService.greet(created.link().token()).publicPhotosOnly()).isTrue();
        // 不报姓名学号也能进门。
        var session = shareService.openSession(created.link().token(), PASSWORD, null);
        assertThat(session.access().publicPhotosOnly()).isTrue();
        assertThat(session.access().uploaderPublic()).isTrue();

        // 切换成具体的人也没用：仍然是公共照片。
        var access = shareService.switchUploader(context(created, session.sessionToken()),
                new ProjectShareService.GuestIdentity("张小拍", "20230001"));
        assertThat(access.uploaderPublic()).isTrue();
        Long photoId = handIn(context(created, session.sessionToken()), "d");
        assertThat(column(photoId, "photographer_name")).isEqualTo(PublicPhoto.PHOTOGRAPHER_NAME);
    }

    @Test
    void turningOnPublicOnlyStopsAnExistingPersonalSessionFromUploading() {
        var created = uploadLink(normal.getId());
        var session = shareService.openSession(created.link().token(), PASSWORD,
                new ProjectShareService.GuestIdentity("张小拍", "20230001"));
        ProjectEntity current = projectService.get(normal.getId());
        projectService.update(current.getId(), current.getTitle(), current.getDescription(), null, true,
                current.getVersion(), minister);

        assertThatThrownBy(() -> handIn(context(created, session.sessionToken()), "e"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅接收公共照片");
    }

    // ------------------------------------------------------------------ 夹具

    private ProjectShareService.CreatedShareLink uploadLink(Long projectId) {
        return shareService.create(projectId, new ProjectShareService.CreateCommand(
                ShareLinkPurpose.UPLOAD, "摄影组", PASSWORD, false, false, null), minister);
    }

    private ProjectShareService.GuestContext context(ProjectShareService.CreatedShareLink created,
                                                     String sessionToken) {
        return shareService.resolveGuestContext(created.link().token(), sessionToken);
    }

    private Long handIn(ProjectShareService.GuestContext context, String sha) {
        var ticket = uploadService.createTicket(context, new ProjectShareUploadService.TicketCommand(
                "field.jpg", "image/jpeg", 2_048L, sha.repeat(64), LocalDateTime.now()));
        String originalKey = column(ticket.photoId(), "original_object_key");
        byte[] bytes = "non-empty-original".getBytes(StandardCharsets.UTF_8);
        storage.put(originalKey, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
        uploadService.complete(context, ticket.photoId(),
                new ProjectShareUploadService.CompleteCommand(null, null, List.of()));
        return ticket.photoId();
    }

    private String column(Long photoId, String column) {
        return jdbc.sql("SELECT " + column + " FROM photo WHERE id=:id")
                .param("id", photoId).query(String.class).single();
    }

    private ProjectShareUploadService.WorklogDeclaration declaration() {
        return new ProjectShareUploadService.WorklogDeclaration(LocalDate.now(), 60, 30, null);
    }
}
