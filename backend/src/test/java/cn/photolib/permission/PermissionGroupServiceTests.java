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
                PermissionCode.MESSAGE_SEND, PermissionCode.STATISTICS_DOWNLOAD,
                PermissionCode.MANAGER_CAMPUS_ASSIGN);
        assertThat(manager.dataScope()).isEqualTo(DataScope.CAMPUS);
        assertThat(manager.permissions()).containsExactlyInAnyOrder(
                PermissionCode.PROJECT_VIEW, PermissionCode.PROJECT_ADOPT,
                PermissionCode.PHOTO_VIEW, PermissionCode.PHOTO_UPLOAD,
                PermissionCode.PHOTO_DOWNLOAD, PermissionCode.REQUEST_VIEW,
                PermissionCode.REQUEST_PHOTO_MANAGE, PermissionCode.WORKLOG_SUBMIT,
                PermissionCode.DIRECTORY_VIEW, PermissionCode.DIRECTORY_MANAGE);
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
                DataScope.CAMPUS, Set.of(PermissionCode.PHOTO_VIEW)));
        var created = users.create(new UserService.CreateUser(
                "permission-reader", "权限测试账号", null, null, null, null,
                group.id(), Set.of(north.getId(), south.getId())));

        var login = auth.login("permission-reader", created.initialPassword());
        assertThat(login.user().permissionGroupCode()).isEqualTo("PHOTO_READER");
        assertThat(login.user().permissions()).containsExactly(PermissionCode.PHOTO_VIEW);
        assertThat(login.user().campusIds()).containsExactlyInAnyOrder(north.getId(), south.getId());
        assertThat(login.user().canAccessCampus(north.getId())).isTrue();
        assertThat(login.user().canAccessCampus(south.getId())).isTrue();

        groups.update(group.id(), new PermissionGroupService.UpdateCommand(
                group.name(), group.description(), DataScope.CAMPUS,
                Set.of(PermissionCode.PHOTO_VIEW, PermissionCode.PHOTO_DOWNLOAD), group.version()));

        assertThat(auth.authenticate(login.accessToken()).user().permissions())
                .containsExactlyInAnyOrder(PermissionCode.PHOTO_VIEW, PermissionCode.PHOTO_DOWNLOAD);
    }

    @Test
    void deletingCustomGroupDemotesMembersAndKeepsLoginAvailable() {
        var campus = campuses.create("PERM-D", "权限删除测试校区");
        var group = groups.create(new PermissionGroupService.CreateCommand(
                "TEMP_UPLOAD", "临时上传组", null, DataScope.CAMPUS,
                Set.of(PermissionCode.PHOTO_UPLOAD)));
        var created = users.create(new UserService.CreateUser(
                "permission-demoted", "待降级账号", null, null, null, null,
                group.id(), Set.of(campus.getId())));
        var archived = users.create(new UserService.CreateUser(
                "permission-archived", "已删除的历史账号", null, null, null, null,
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
                admin.name(), admin.description(), DataScope.GLOBAL,
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
                noAccess.name(), noAccess.description(), DataScope.CAMPUS,
                Set.of(PermissionCode.PHOTO_VIEW), noAccess.version())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("最低权限组不能编辑");
    }
}
