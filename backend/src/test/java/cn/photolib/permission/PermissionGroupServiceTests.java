package cn.photolib.permission;

import cn.photolib.auth.AuthService;
import cn.photolib.campus.CampusService;
import cn.photolib.common.error.BusinessException;
import cn.photolib.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PermissionGroupServiceTests {
    @Autowired
    private PermissionGroupService groups;
    @Autowired
    private UserService users;
    @Autowired
    private CampusService campuses;
    @Autowired
    private AuthService auth;

    @Test
    void builtInGroupsPreserveExistingCapabilities() {
        var admin = groups.get(groups.requireByCode("ADMIN").getId());
        var minister = groups.get(groups.requireByCode("MINISTER").getId());
        var manager = groups.get(groups.requireByCode("CAMPUS_MANAGER").getId());
        var noAccess = groups.get(groups.requireByCode("NO_ACCESS").getId());

        assertThat(admin.dataScope()).isEqualTo(DataScope.GLOBAL);
        assertThat(admin.permissions()).containsExactlyInAnyOrder(PermissionCode.values());
        assertThat(minister.permissions()).containsExactlyInAnyOrder(
                PermissionCode.PROJECT_VIEW, PermissionCode.PROJECT_ADOPT,
                PermissionCode.PROJECT_CREATE, PermissionCode.PROJECT_COMPLETE,
                PermissionCode.PROJECT_DOWNLOAD, PermissionCode.PHOTO_VIEW,
                PermissionCode.PHOTO_DELETE, PermissionCode.PHOTO_UPLOAD,
                PermissionCode.PHOTO_DOWNLOAD, PermissionCode.REQUEST_VIEW,
                PermissionCode.REQUEST_CREATE, PermissionCode.REQUEST_CLOSE,
                PermissionCode.REQUEST_CONFIRM, PermissionCode.REQUEST_PHOTO_MANAGE,
                PermissionCode.WORKLOG_CONFIRM, PermissionCode.WORKLOG_EXPORT,
                PermissionCode.DIRECTORY_VIEW, PermissionCode.DIRECTORY_MANAGE,
                PermissionCode.MESSAGE_SEND, PermissionCode.RECRUITMENT_VIEW,
                PermissionCode.RECRUITMENT_PUBLISH, PermissionCode.FEATURED_MANAGE,
                // 文档中心由管理员和部长负责编写，所以 V37 把 DOC_MANAGE 也发给了部长。
                PermissionCode.DOC_MANAGE,
                PermissionCode.STATISTICS_DOWNLOAD, PermissionCode.MANAGER_CAMPUS_ASSIGN);
        assertThat(manager.dataScope()).isEqualTo(DataScope.CAMPUS);
        assertThat(manager.permissions()).containsExactlyInAnyOrder(
                PermissionCode.PROJECT_VIEW, PermissionCode.PROJECT_ADOPT,
                PermissionCode.PHOTO_VIEW, PermissionCode.PHOTO_UPLOAD,
                PermissionCode.PHOTO_DOWNLOAD, PermissionCode.REQUEST_VIEW,
                PermissionCode.REQUEST_PHOTO_MANAGE, PermissionCode.WORKLOG_SUBMIT,
                PermissionCode.DIRECTORY_VIEW, PermissionCode.DIRECTORY_MANAGE,
                PermissionCode.RECRUITMENT_VIEW);
        assertThat(noAccess.dataScope()).isEqualTo(DataScope.NONE);
        assertThat(noAccess.permissions()).isEmpty();
        assertThat(noAccess.lowest()).isTrue();
    }

    @Test
    void customGroupSupportsPartialPermissionsMultipleCampusesAndImmediateUpdates() {
        var north = campuses.create("PERM-N", "权限测试北校区");
        var south = campuses.create("PERM-S", "权限测试南校区");
        var group = groups.create(new PermissionGroupService.CreateCommand(
                "PHOTO_READER", "双校区图库查看", "只允许查看图库",
                DataScope.CAMPUS, PhotoVisibility.SELF, Set.of(PermissionCode.PHOTO_VIEW)));
        var created = users.create(new UserService.CreateUser(
                "permission-reader", "权限测试账号", null, null, null, null, null,
                group.id(), Set.of(north.getId(), south.getId())));

        var login = auth.login("permission-reader", created.initialPassword());
        assertThat(login.user().permissionGroupCode()).isEqualTo("PHOTO_READER");
        assertThat(login.user().permissions()).containsExactly(PermissionCode.PHOTO_VIEW);
        assertThat(login.user().campusIds()).containsExactlyInAnyOrder(north.getId(), south.getId());
        assertThat(login.user().canAccessCampus(north.getId())).isTrue();
        assertThat(login.user().canAccessCampus(south.getId())).isTrue();

        groups.update(group.id(), new PermissionGroupService.UpdateCommand(
                group.name(), group.description(), DataScope.CAMPUS, PhotoVisibility.SELF,
                Set.of(PermissionCode.PHOTO_VIEW, PermissionCode.PHOTO_DOWNLOAD), group.version()));

        assertThat(auth.authenticate(login.accessToken()).user().permissions())
                .containsExactlyInAnyOrder(PermissionCode.PHOTO_VIEW, PermissionCode.PHOTO_DOWNLOAD);
    }


    @Test
    void photoVisibilityIsStoredPerGroupAndReachesTheAuthenticatedPrincipal() {
        var campus = campuses.create("PERM-VIS", "可见范围测试校区");
        var group = groups.create(new PermissionGroupService.CreateCommand(
                "CAMPUS_GALLERY", "校区全量图库", "能看本校区全部图片",
                DataScope.CAMPUS, PhotoVisibility.CAMPUS, Set.of(PermissionCode.PHOTO_VIEW)));
        assertThat(group.photoVisibility()).isEqualTo(PhotoVisibility.CAMPUS);

        var created = users.create(new UserService.CreateUser(
                "permission-gallery", "图库范围账号", null, null, null, null, null,
                group.id(), Set.of(campus.getId())));
        var login = auth.login("permission-gallery", created.initialPassword());
        assertThat(login.user().photoVisibility()).isEqualTo(PhotoVisibility.CAMPUS);
        assertThat(login.user().seesOnlyOwnPhotos()).isFalse();
        assertThat(login.user().seesPhotosAcrossCampuses()).isFalse();

        // 改成全站可见后，已签发的会话下一次鉴权就应该拿到新范围（权限组解析每次都读库）。
        groups.update(group.id(), new PermissionGroupService.UpdateCommand(
                group.name(), group.description(), DataScope.CAMPUS, PhotoVisibility.GLOBAL,
                group.permissions(), group.version()));
        var refreshed = auth.authenticate(login.accessToken()).user();
        assertThat(refreshed.photoVisibility()).isEqualTo(PhotoVisibility.GLOBAL);
        assertThat(refreshed.seesPhotosAcrossCampuses()).isTrue();
    }

    @Test
    void photoVisibilityStaysEditableOnBuiltInGroupsButIsPinnedForAdministrators() {
        // 「校区负责人只能看自己上传的图片」正是内置组的默认值，锁住这个开关它就对最需要
        // 它的账号失效了——因此内置组的图库可见范围必须可改，尽管名称和数据范围不可改。
        var manager = groups.get(groups.requireByCode("CAMPUS_MANAGER").getId());
        assertThat(manager.photoVisibility()).isEqualTo(PhotoVisibility.SELF);

        var updated = groups.update(manager.id(), new PermissionGroupService.UpdateCommand(
                "改不掉的名字", "改不掉的说明", DataScope.GLOBAL, PhotoVisibility.CAMPUS,
                manager.permissions(), manager.version()));
        assertThat(updated.photoVisibility()).isEqualTo(PhotoVisibility.CAMPUS);
        assertThat(updated.name()).isEqualTo(manager.name());
        assertThat(updated.dataScope()).isEqualTo(DataScope.CAMPUS);

        // 系统管理员组固定全站可见：收窄之后没有别的角色能改回来。
        var admin = groups.get(groups.requireByCode("ADMIN").getId());
        assertThat(groups.update(admin.id(), new PermissionGroupService.UpdateCommand(
                admin.name(), admin.description(), DataScope.GLOBAL, PhotoVisibility.SELF,
                admin.permissions(), admin.version())).photoVisibility())
                .isEqualTo(PhotoVisibility.GLOBAL);
    }

    @Test
    void changingDataScopeRequiresAdministratorsToClearEveryMemberFirst() {
        var campus = campuses.create("PERM-SCOPE", "范围切换测试校区");
        var campusGroup = groups.create(new PermissionGroupService.CreateCommand(
                "SCOPE_SWITCH", "范围切换组", null, DataScope.CAMPUS, PhotoVisibility.SELF,
                Set.of(PermissionCode.PHOTO_VIEW)));
        var member = users.create(new UserService.CreateUser(
                "permission-scope-member", "范围切换成员", null, null, null, null, null,
                campusGroup.id(), Set.of(campus.getId())));

        assertThat(users.list(1, 20, null, null, campusGroup.id(), null, null).items())
                .extracting(UserService.UserView::id).containsExactly(member.user().id());
        assertThatThrownBy(() -> groups.update(campusGroup.id(),
                new PermissionGroupService.UpdateCommand(campusGroup.name(), campusGroup.description(),
                        DataScope.GLOBAL, campusGroup.photoVisibility(), campusGroup.permissions(),
                        campusGroup.version())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("先清空权限组成员");

        var noAccess = groups.requireByCode("NO_ACCESS");
        users.updateAuthorization(member.user().id(), noAccess.getId(), Set.of(),
                users.get(member.user().id()).version());
        var updated = groups.update(campusGroup.id(), new PermissionGroupService.UpdateCommand(
                campusGroup.name(), campusGroup.description(), DataScope.GLOBAL,
                campusGroup.photoVisibility(), campusGroup.permissions(), campusGroup.version()));

        assertThat(updated.dataScope()).isEqualTo(DataScope.GLOBAL);
        assertThat(updated.memberCount()).isZero();
    }

    @Test
    void changingGlobalGroupToCampusScopeAlsoRequiresAnEmptyGroup() {
        var globalGroup = groups.create(new PermissionGroupService.CreateCommand(
                "GLOBAL_SWITCH", "全局切换组", null, DataScope.GLOBAL, PhotoVisibility.GLOBAL,
                Set.of(PermissionCode.PHOTO_VIEW)));
        var member = users.create(new UserService.CreateUser(
                "global-scope-member", "全局范围成员", null, null, null, null, null,
                globalGroup.id(), Set.of()));

        assertThatThrownBy(() -> groups.update(globalGroup.id(),
                new PermissionGroupService.UpdateCommand(globalGroup.name(), globalGroup.description(),
                        DataScope.CAMPUS, globalGroup.photoVisibility(), globalGroup.permissions(),
                        globalGroup.version())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("先清空权限组成员");

        var noAccess = groups.requireByCode("NO_ACCESS");
        users.updateAuthorization(member.user().id(), noAccess.getId(), Set.of(),
                users.get(member.user().id()).version());
        assertThat(groups.update(globalGroup.id(), new PermissionGroupService.UpdateCommand(
                globalGroup.name(), globalGroup.description(), DataScope.CAMPUS,
                globalGroup.photoVisibility(), globalGroup.permissions(), globalGroup.version()))
                .dataScope()).isEqualTo(DataScope.CAMPUS);
    }

    @Test
    void deletingCustomGroupDemotesMembersAndKeepsLoginAvailable() {
        var campus = campuses.create("PERM-D", "权限删除测试校区");
        var group = groups.create(new PermissionGroupService.CreateCommand(
                "TEMP_UPLOAD", "临时上传组", null, DataScope.CAMPUS, PhotoVisibility.SELF,
                Set.of(PermissionCode.PHOTO_UPLOAD)));
        var created = users.create(new UserService.CreateUser(
                "permission-demoted", "待降级账号", null, null, null, null, null,
                group.id(), Set.of(campus.getId())));
        var archived = users.create(new UserService.CreateUser(
                "permission-archived", "已删除的历史账号", null, null, null, null, null,
                group.id(), Set.of(campus.getId())));
        users.delete(archived.user().id(), -1L);
        var activeSession = auth.login("permission-demoted", created.initialPassword());

        groups.delete(group.id());

        var demoted = users.get(created.user().id());
        assertThat(demoted.permissionGroupCode()).isEqualTo("NO_ACCESS");
        assertThat(demoted.dataScope()).isEqualTo(DataScope.NONE);
        assertThat(demoted.campusIds()).isEmpty();
        assertThat(auth.authenticate(activeSession.accessToken()).user().hasSystemAccess()).isFalse();
        assertThat(auth.login("permission-demoted", created.initialPassword()).user().hasSystemAccess()).isFalse();
        assertThatThrownBy(() -> groups.get(group.id()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("权限组不存在");
    }

    @Test
    void administratorGroupKeepsFullPermissionMatrixEvenWhenEditRequestTriesToTrimIt() {
        var admin = groups.get(groups.requireByCode("ADMIN").getId());

        // 系统管理员组是权限系统自身的管理入口，裁剪它会造出"能进管理页但业务权限被削掉"
        // 的不一致状态，因此写入请求里的权限明细必须被忽略。
        var updated = groups.update(admin.id(), new PermissionGroupService.UpdateCommand(
                admin.name(), admin.description(), DataScope.GLOBAL, PhotoVisibility.SELF,
                Set.of(PermissionCode.PHOTO_VIEW), admin.version()));

        assertThat(updated.permissions()).containsExactlyInAnyOrder(PermissionCode.values());
        assertThat(groups.get(admin.id()).permissions())
                .containsExactlyInAnyOrder(PermissionCode.values());
    }

    @Test
    void builtInAndLowestGroupsCannotBeDeletedOrMisconfigured() {
        var admin = groups.get(groups.requireByCode("ADMIN").getId());
        var noAccess = groups.get(groups.requireByCode("NO_ACCESS").getId());

        assertThatThrownBy(() -> groups.delete(admin.id()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("内置权限组不能删除");
        assertThatThrownBy(() -> groups.update(noAccess.id(), new PermissionGroupService.UpdateCommand(
                noAccess.name(), noAccess.description(), DataScope.CAMPUS, PhotoVisibility.SELF,
                Set.of(PermissionCode.PHOTO_VIEW), noAccess.version())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("最低权限组不能编辑");
    }
}
