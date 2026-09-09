package cn.photolib.share;

import cn.photolib.adoption.AdoptionEntity;
import cn.photolib.adoption.AdoptionService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.campus.model.CampusEntity;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.share.mapper.ProjectShareLinkMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 选题分享链接。
 *
 * <p>用例围着三条不变量转（见 {@link ProjectShareService} 类注释）：图片和被引不复制、
 * 会话不缓存权限、校区范围账号不能发链接。</p>
 */
@SpringBootTest
@Transactional
class ProjectShareServiceTests {
    private static final long MINISTER_ID = 7_701L;
    private static final long MANAGER_ID = 7_702L;
    private static final long PHOTO_A = 77_001L;
    private static final long PHOTO_B = 77_002L;
    /** 加进了图库但没有加进项目相册的图片：分享链接绝不能碰到它。 */
    private static final long PHOTO_OUTSIDE = 77_003L;

    @Autowired private ProjectShareService service;
    @Autowired private ProjectShareLinkMapper linkMapper;
    @Autowired private ProjectService projectService;
    @Autowired private AdoptionService adoptionService;
    @Autowired private CampusService campusService;
    @Autowired private JdbcClient jdbc;

    private AuthenticatedUser minister;
    private AuthenticatedUser campusManager;
    private ProjectEntity project;

    @BeforeEach
    void setUp() {
        CampusEntity campus = campusService.create("SHARE", "分享测试校区");
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES
                    (:minister, 'share-minister', 'hash', '分享部长', 'MINISTER', null, TRUE, FALSE),
                    (:manager, 'share-manager', 'hash', '分享负责人', 'CAMPUS_MANAGER', :campus, TRUE, FALSE)
                """).param("minister", MINISTER_ID).param("manager", MANAGER_ID)
                .param("campus", campus.getId()).update();

        minister = new AuthenticatedUser(MINISTER_ID, "share-minister", "分享部长",
                UserRole.MINISTER, null, false);
        campusManager = new AuthenticatedUser(MANAGER_ID, "share-manager", "分享负责人",
                UserRole.CAMPUS_MANAGER, campus.getId(), false, 5L, "CAMPUS_MANAGER", "校区负责人",
                DataScope.CAMPUS, Set.of(PermissionCode.PROJECT_SHARE, PermissionCode.PROJECT_VIEW),
                Set.of(campus.getId()));

        project = projectService.create("分享测试项目", "说明", ProjectStatus.ACTIVE, minister);

        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by, campus_id,
                     taken_at, size, content_type, object_key, stored_file_name, sha256, status)
                VALUES
                    (:a, '开幕式', '20230001', '张三', :uploader, :campus, NOW(), 1000, 'image/jpeg',
                     'photos/a.jpg', 'a.jpg', :sha_a, 'AVAILABLE'),
                    (:b, '闭幕式', '20230002', '李四', :uploader, :campus, NOW(), 1000, 'image/jpeg',
                     'photos/b.jpg', 'b.jpg', :sha_b, 'AVAILABLE'),
                    (:outside, '别的项目的图', '20230003', '王五', :uploader, :campus, NOW(), 1000,
                     'image/jpeg', 'photos/c.jpg', 'c.jpg', :sha_c, 'AVAILABLE')
                """)
                .param("a", PHOTO_A).param("b", PHOTO_B).param("outside", PHOTO_OUTSIDE)
                .param("uploader", MINISTER_ID).param("campus", campus.getId())
                .param("sha_a", "a".repeat(64)).param("sha_b", "b".repeat(64))
                .param("sha_c", "c".repeat(64)).update();
        projectService.addPhotos(project.getId(), List.of(PHOTO_A, PHOTO_B), minister);
    }

    private ProjectShareService.CreateCommand command(boolean download, boolean adoption) {
        return new ProjectShareService.CreateCommand("校报编辑部", "share-pass", download, adoption, null);
    }

    private ProjectShareLinkEntity guest(ProjectShareService.CreatedShareLink created) {
        ProjectShareService.GuestSession session =
                service.openSession(created.link().token(), "share-pass");
        return service.resolveGuest(created.link().token(), session.sessionToken());
    }

    @Test
    void aGuestWithTheLinkAndPasswordSeesExactlyTheProjectAlbum() {
        ProjectShareService.CreatedShareLink created = service.create(project.getId(),
                command(true, true), minister);

        PageResponse<ProjectShareService.SharePhotoView> photos =
                service.photos(guest(created), 1, 30, null);

        assertThat(photos.items()).extracting(ProjectShareService.SharePhotoView::id)
                .containsExactlyInAnyOrder(PHOTO_A, PHOTO_B)
                .doesNotContain(PHOTO_OUTSIDE);
        // 学号是个人信息，站外的人拿不到；视图里根本没有这个字段，这里顺带钉住
        // "访客看到的是摄影者姓名"这条。
        assertThat(photos.items()).allSatisfy(photo ->
                assertThat(photo.photographerName()).isNotBlank());
    }

    @Test
    void theGeneratedPasswordIsReturnedOnceAndOnlyItsHashIsStored() {
        ProjectShareService.CreatedShareLink created = service.create(project.getId(),
                new ProjectShareService.CreateCommand(null, null, false, false, null), minister);

        assertThat(created.password()).hasSize(10);
        String stored = linkMapper.selectById(created.link().id()).getPasswordHash();
        assertThat(stored).doesNotContain(created.password()).startsWith("$2");
        // 列表视图里没有任何能还原密码的东西。
        assertThat(service.list(project.getId(), minister))
                .extracting(ProjectShareService.ShareLinkView::token)
                .containsExactly(created.link().token());
    }

    @Test
    void awrongPasswordNeverOpensASession() {
        ProjectShareService.CreatedShareLink created = service.create(project.getId(),
                command(true, true), minister);

        assertThatThrownBy(() -> service.openSession(created.link().token(), "not-the-password"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("密码不正确");
    }

    /** 会话不缓存权限：开关一改，正在浏览的访客下一次请求就受新规则约束。 */
    @Test
    void revokingDownloadTakesEffectOnAnAlreadyOpenSession() {
        ProjectShareService.CreatedShareLink created = service.create(project.getId(),
                command(true, false), minister);
        ProjectShareService.GuestSession session =
                service.openSession(created.link().token(), "share-pass");
        String token = created.link().token();
        assertThat(service.download(service.resolveGuest(token, session.sessionToken()), PHOTO_A)
                .downloadUrl()).isNotBlank();

        service.update(project.getId(), created.link().id(),
                new ProjectShareService.UpdateCommand("校报编辑部", false, false, null,
                        created.link().version()), minister);

        assertThatThrownBy(() -> service.download(
                service.resolveGuest(token, session.sessionToken()), PHOTO_A))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有开放下载");
    }

    @Test
    void deletingTheLinkLocksOutEveryAlreadyOpenSession() {
        ProjectShareService.CreatedShareLink created = service.create(project.getId(),
                command(true, true), minister);
        ProjectShareService.GuestSession session =
                service.openSession(created.link().token(), "share-pass");
        String token = created.link().token();

        service.delete(project.getId(), created.link().id(), minister);

        assertThatThrownBy(() -> service.resolveGuest(token, session.sessionToken()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不存在或已被撤销");
    }

    @Test
    void resettingThePasswordInvalidatesTheSessionsIssuedUnderTheOldOne() {
        ProjectShareService.CreatedShareLink created = service.create(project.getId(),
                command(true, true), minister);
        ProjectShareService.GuestSession session =
                service.openSession(created.link().token(), "share-pass");
        String token = created.link().token();

        ProjectShareService.ResetPassword reset =
                service.resetPassword(project.getId(), created.link().id(), null, minister);

        assertThatThrownBy(() -> service.resolveGuest(token, session.sessionToken()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("重新输入密码");
        assertThat(service.openSession(token, reset.password()).sessionToken()).isNotBlank();
    }

    @Test
    void anExpiredLinkIsIndistinguishableFromOneThatNeverExisted() {
        ProjectShareService.CreatedShareLink created = service.create(project.getId(),
                command(true, true), minister);
        jdbc.sql("UPDATE project_share_link SET expires_at = :past WHERE id = :id")
                .param("past", LocalDateTime.now().minusMinutes(1))
                .param("id", created.link().id()).update();

        assertThatThrownBy(() -> service.greet(created.link().token()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不存在或已被撤销");
    }

    /**
     * 需求里最要紧的一条：访客标的被引就是项目的被引，站内立刻看得到，
     * 同一个项目的另一条分享链接也立刻看得到。
     */
    @Test
    void aGuestAdoptionIsTheProjectAdoption() {
        ProjectShareService.CreatedShareLink writable = service.create(project.getId(),
                command(false, true), minister);
        ProjectShareService.CreatedShareLink other = service.create(project.getId(),
                new ProjectShareService.CreateCommand("另一条链接", "share-pass", false, false, null),
                minister);

        service.adopt(guest(writable), PHOTO_A);

        // 站内：项目的采用记录里就是它，操作人记在发链接的部长名下。
        List<AdoptionEntity> adoptions = adoptionService
                .list(project.getId(), 1, 50, null, minister).items();
        assertThat(adoptions).hasSize(1);
        assertThat(adoptions.getFirst().getPhotoId()).isEqualTo(PHOTO_A);
        assertThat(adoptions.getFirst().getAdoptedBy()).isEqualTo(MINISTER_ID);
        // 另一条链接：同一张图立刻显示已被引。
        assertThat(service.photos(guest(other), 1, 30, null).items())
                .filteredOn(photo -> photo.id().equals(PHOTO_A))
                .allMatch(ProjectShareService.SharePhotoView::adopted);

        service.cancelAdoption(guest(writable), PHOTO_A);
        assertThat(adoptionService.list(project.getId(), 1, 50, null, minister).items()).isEmpty();
        assertThat(service.photos(guest(other), 1, 30, null).items())
                .noneMatch(ProjectShareService.SharePhotoView::adopted);
    }

    /** 站内标的被引，访客那边也一样看得见——同步是双向的。 */
    @Test
    void anAdoptionMadeInsideTheAppShowsUpForGuests() {
        adoptionService.adopt(project.getId(), List.of(PHOTO_B), "站内标记", minister);
        ProjectShareService.CreatedShareLink created = service.create(project.getId(),
                command(false, false), minister);

        assertThat(service.photos(guest(created), 1, 30, null).items())
                .filteredOn(photo -> photo.id().equals(PHOTO_B))
                .allMatch(ProjectShareService.SharePhotoView::adopted);
    }

    @Test
    void aLinkWithoutTheSwitchRefusesTheCorrespondingAction() {
        ProjectShareService.CreatedShareLink readOnly = service.create(project.getId(),
                command(false, false), minister);
        ProjectShareLinkEntity link = guest(readOnly);

        assertThatThrownBy(() -> service.download(link, PHOTO_A))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有开放下载");
        assertThatThrownBy(() -> service.batchDownload(link, List.of(PHOTO_A, PHOTO_B)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有开放下载");
        assertThatThrownBy(() -> service.adopt(link, PHOTO_A))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有开放标记被引");
        assertThatThrownBy(() -> service.cancelAdoption(link, PHOTO_A))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有开放标记被引");
    }

    /** 打包下载不能成为把项目外的图片弄出去的路径。 */
    @Test
    void photosOutsideTheProjectAlbumAreOutOfReachThroughEveryGuestEndpoint() {
        ProjectShareService.CreatedShareLink created = service.create(project.getId(),
                command(true, true), minister);
        ProjectShareLinkEntity link = guest(created);

        assertThatThrownBy(() -> service.download(link, PHOTO_OUTSIDE))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不属于这条分享链接");
        assertThatThrownBy(() -> service.batchDownload(link, List.of(PHOTO_A, PHOTO_OUTSIDE)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不属于这条分享链接");
        assertThatThrownBy(() -> service.adopt(link, PHOTO_OUTSIDE))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不属于这条分享链接");
    }

    @Test
    void aBatchDownloadJobIsOnlyVisibleThroughTheLinkThatCreatedIt() {
        ProjectShareService.CreatedShareLink created = service.create(project.getId(),
                command(true, false), minister);
        ProjectShareService.CreatedShareLink other = service.create(project.getId(),
                new ProjectShareService.CreateCommand("另一条链接", "share-pass", true, false, null),
                minister);
        String jobId = service.batchDownload(guest(created), List.of(PHOTO_A, PHOTO_B)).getId();

        assertThat(service.batchDownloadStatus(guest(created), jobId).job().getId()).isEqualTo(jobId);
        assertThatThrownBy(() -> service.batchDownloadStatus(guest(other), jobId))
                .isInstanceOf(BusinessException.class).hasMessageContaining("导出任务不存在");
    }

    @Test
    void aCampusScopedAccountCannotHandOutTheWholeProjectAlbum() {
        assertThatThrownBy(() -> service.create(project.getId(), command(true, true), campusManager))
                .isInstanceOf(BusinessException.class).hasMessageContaining("校区范围账号");
    }

    @Test
    void anAccountWithoutTheShareePermissionIsRefused() {
        AuthenticatedUser withoutShare = new AuthenticatedUser(MINISTER_ID, "share-minister",
                "分享部长", UserRole.MINISTER, null, false, 6L, "CUSTOM", "自定义组",
                DataScope.GLOBAL, Set.of(PermissionCode.PROJECT_VIEW), Set.of());

        assertThatThrownBy(() -> service.create(project.getId(), command(true, true), withoutShare))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权管理");
    }

    @Test
    void anEmptyOrUnknownSessionIsRejectedWithTheRetryPasswordMessage() {
        ProjectShareService.CreatedShareLink created = service.create(project.getId(),
                command(true, true), minister);

        assertThatThrownBy(() -> service.resolveGuest(created.link().token(), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("重新输入密码");
        assertThatThrownBy(() -> service.resolveGuest(created.link().token(), "made-up"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("重新输入密码");
    }

    @Test
    void aSessionIsRefusedOnceItsLifetimeIsOver() {
        ProjectShareService.CreatedShareLink created = service.create(project.getId(),
                command(true, true), minister);
        ProjectShareService.GuestSession session =
                service.openSession(created.link().token(), "share-pass");
        jdbc.sql("UPDATE project_share_session SET expires_at = :past")
                .param("past", LocalDateTime.now().minusMinutes(1)).update();

        assertThatThrownBy(() -> service.resolveGuest(created.link().token(), session.sessionToken()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("重新输入密码");
    }

    @Test
    void aShortPasswordIsRefusedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> service.create(project.getId(),
                new ProjectShareService.CreateCommand(null, "123", false, false, null), minister))
                .isInstanceOf(BusinessException.class).hasMessageContaining("密码长度");
        assertThat(service.list(project.getId(), minister)).isEmpty();
    }

    @Test
    void anExpiryInThePastIsRefused() {
        assertThatThrownBy(() -> service.create(project.getId(),
                new ProjectShareService.CreateCommand(null, "share-pass", false, false,
                        LocalDateTime.now().minusHours(1)), minister))
                .isInstanceOf(BusinessException.class).hasMessageContaining("失效时间");
    }

    /** 只回答"链接还能不能用"，不带项目标题——密码页不该泄露项目信息。 */
    @Test
    void theGreetingCarriesNoProjectInformation() {
        ProjectShareService.CreatedShareLink created = service.create(project.getId(),
                command(true, true), minister);

        assertThat(service.greet(created.link().token()).requiresPassword()).isTrue();
    }
}
