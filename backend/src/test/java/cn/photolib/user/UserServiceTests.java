package cn.photolib.user;

import cn.photolib.auth.AuthService;
import cn.photolib.campus.CampusService;
import cn.photolib.campus.model.CampusEntity;
import cn.photolib.common.error.BusinessException;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

/**
 * 用户服务测试
 * 测试用户创建、更新、密码重置等功能
 */
@SpringBootTest
@Transactional
class UserServiceTests {
    @Autowired
    private UserService userService;
    @Autowired
    private CampusService campusService;
    @Autowired
    private AuthService authService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private CampusEntity testCampus;

    @BeforeEach
    void setUp() {
        testCampus = campusService.create("TEST", "测试校区");
    }

    @Test
    void createUser_shouldGenerateRandomPassword() {
        // When: 创建新用户
        UserService.CreateUser command = new UserService.CreateUser(
                "newuser", "新用户", UserRole.CAMPUS_MANAGER,
                testCampus.getId(), "13800138000", "test@example.com");
        UserService.CreatedUser result = userService.create(command);

        // Then: 应该返回随机生成的初始密码
        assertThat(result.user().username()).isEqualTo("newuser");
        assertThat(result.user().displayName()).isEqualTo("新用户");
        assertThat(result.user().mustChangePassword()).isTrue();
        assertThat(result.initialPassword()).isNotEmpty();
        assertThat(result.initialPassword().length()).isBetween(8, 16);

        // 验证密码可以登录
        assertThatNoException().isThrownBy(() ->
                authService.login("newuser", result.initialPassword()));
    }

    @Test
    void createUser_withDuplicateUsername_shouldThrowException() {
        // Given: 已存在的用户
        UserService.CreateUser first = new UserService.CreateUser(
                "duplicate", "用户1", UserRole.MINISTER, null, null, null);
        userService.create(first);

        // When & Then: 创建重复用户名应该失败
        UserService.CreateUser duplicate = new UserService.CreateUser(
                "duplicate", "用户2", UserRole.MINISTER, null, null, null);
        assertThatThrownBy(() -> userService.create(duplicate))
                .hasMessageContaining("资源已存在");
    }

    @Test
    void createCampusManager_withoutCampus_shouldThrowException() {
        // When & Then: 校区负责人必须指定校区
        UserService.CreateUser command = new UserService.CreateUser(
                "manager", "负责人", UserRole.CAMPUS_MANAGER,
                null, null, null);
        assertThatThrownBy(() -> userService.create(command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("校区负责人必须指定校区");
    }

    @Test
    void updateUser_shouldModifyUserInfo() {
        // Given: 已存在的用户
        UserService.CreateUser create = new UserService.CreateUser(
                "updatetest", "原始名称", UserRole.MINISTER,
                null, "13800138000", "old@example.com");
        UserService.CreatedUser created = userService.create(create);

        // When: 更新用户信息
        UserService.UpdateUser update = new UserService.UpdateUser(
                "新名称", UserRole.CAMPUS_MANAGER, testCampus.getId(),
                "13900139000", "new@example.com", true, 1);
        UserService.UserView updated = userService.update(created.user().id(), update);

        // Then: 信息应该被更新
        assertThat(updated.displayName()).isEqualTo("新名称");
        assertThat(updated.role()).isEqualTo(UserRole.CAMPUS_MANAGER);
        assertThat(updated.campusId()).isEqualTo(testCampus.getId());
        assertThat(updated.phone()).isEqualTo("13900139000");
        assertThat(updated.email()).isEqualTo("new@example.com");
    }

    @Test
    void updateUser_withWrongVersion_shouldThrowException() {
        // Given: 已存在的用户
        UserService.CreateUser create = new UserService.CreateUser(
                "versiontest", "测试", UserRole.MINISTER, null, null, null);
        UserService.CreatedUser created = userService.create(create);

        // When & Then: 使用错误的版本号应该失败（乐观锁）
        UserService.UpdateUser update = new UserService.UpdateUser(
                "新名称", UserRole.MINISTER, null, null, null, true, 999);
        assertThatThrownBy(() -> userService.update(created.user().id(), update))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被其他操作修改");
    }

    @Test
    void disableUser_shouldRevokeAllSessions() {
        // Given: 用户已登录
        UserService.CreateUser create = new UserService.CreateUser(
                "disabletest", "测试", UserRole.MINISTER, null, null, null);
        UserService.CreatedUser created = userService.create(create);
        authService.login("disabletest", created.initialPassword());

        // When: 禁用用户
        UserService.UpdateUser update = new UserService.UpdateUser(
                "测试", UserRole.MINISTER, null, null, null, false, 1);
        userService.update(created.user().id(), update);

        // Then: 用户应该无法登录
        assertThatThrownBy(() ->
                authService.login("disabletest", created.initialPassword()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void resetPassword_shouldGenerateNewPasswordAndRevokeSessions() {
        // Given: 用户已登录
        UserService.CreateUser create = new UserService.CreateUser(
                "resettest", "测试", UserRole.MINISTER, null, null, null);
        UserService.CreatedUser created = userService.create(create);
        String oldPassword = created.initialPassword();
        authService.login("resettest", oldPassword);

        // When: 重置密码
        String newPassword = userService.resetPassword(created.user().id());

        // Then: 应该生成新密码
        assertThat(newPassword).isNotEmpty();
        assertThat(newPassword).isNotEqualTo(oldPassword);

        // 新密码可以登录
        assertThatNoException().isThrownBy(() ->
                authService.login("resettest", newPassword));

        // 旧密码不能登录
        assertThatThrownBy(() -> authService.login("resettest", oldPassword))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void listUsers_withFilters_shouldReturnFilteredResults() {
        // Given: 创建多个用户
        userService.create(new UserService.CreateUser(
                "admin1", "管理员1", UserRole.ADMIN, null, null, null));
        userService.create(new UserService.CreateUser(
                "minister1", "部长1", UserRole.MINISTER, null, null, null));
        userService.create(new UserService.CreateUser(
                "manager1", "负责人1", UserRole.CAMPUS_MANAGER,
                testCampus.getId(), null, null));

        // When: 按角色筛选
        var adminUsers = userService.list(1, 20, null, UserRole.ADMIN, null, null);
        var ministerUsers = userService.list(1, 20, null, UserRole.MINISTER, null, null);
        var managerUsers = userService.list(1, 20, null, UserRole.CAMPUS_MANAGER, null, null);

        // Then: 应该返回正确数量的用户
        assertThat(adminUsers.items().stream()
                .filter(u -> u.username().startsWith("admin")))
                .hasSize(1);
        assertThat(ministerUsers.items().stream()
                .filter(u -> u.username().startsWith("minister")))
                .hasSize(1);
        assertThat(managerUsers.items().stream()
                .filter(u -> u.username().startsWith("manager")))
                .hasSize(1);
    }
}
