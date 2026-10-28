package cn.photolib.user;

import cn.photolib.auth.AuthService;
import cn.photolib.common.error.BusinessException;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 管理员在「修改账号」里改登录账号：{@code PUT /users/{id}} 的可选字段 {@code username}。 */
@SpringBootTest
@Transactional
class UserRenameTests {
    @Autowired
    UserService users;
    @Autowired
    AuthService auth;
    @Autowired
    JdbcClient jdbc;

    @Test
    void renamedAccountLogsInWithTheNewNameAndTheSamePassword() {
        UserService.CreatedUser created = create("rename-old");

        UserService.Updated updated = users.updateTracked(created.user().id(), rename(users.get(created.user().id()), "rename-new"));

        assertThat(updated.user().username()).isEqualTo("rename-new");
        assertThat(updated.previousUsername()).isEqualTo("rename-old");
        assertThatNoException().isThrownBy(() -> auth.login("rename-new", created.initialPassword()));
        assertThatThrownBy(() -> auth.login("rename-old", created.initialPassword()))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM user_notification WHERE user_id = :id"
                        + " AND event_type = 'ACCOUNT_USERNAME_CHANGED'")
                .param("id", created.user().id()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void leavingTheUsernameOutOrUnchangedIsNotARename() {
        UserService.CreatedUser created = create("rename-keep");
        UserService.UserView user = users.get(created.user().id());

        UserService.Updated untouched = users.updateTracked(user.id(), new UserService.UpdateUser(
                user.displayName(), null, null, null, "keep@example.com", null, true, user.version(),
                user.permissionGroupId(), user.campusIds()));
        UserService.Updated same = users.updateTracked(user.id(), rename(untouched.user(), "rename-keep"));

        assertThat(untouched.user().username()).isEqualTo("rename-keep");
        assertThat(untouched.previousUsername()).isNull();
        assertThat(same.previousUsername()).isNull();
    }

    @Test
    void cannotTakeAnotherAccountsName() {
        create("rename-taken");
        UserService.UserView user = users.get(create("rename-taker").user().id());

        assertThatThrownBy(() -> users.updateTracked(user.id(), rename(user, "rename-taken")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被其他用户");
    }

    @Test
    void cannotTakeANamePendingInARegistrationApplication() {
        UserService.UserView user = users.get(create("rename-applicant").user().id());
        jdbc.sql("""
                INSERT INTO registration_code(code, name, permission_group_id, max_uses, valid_from, valid_until, created_by)
                VALUES ('RENAMETEST', '测试码', (SELECT id FROM permission_group WHERE code = 'ADMIN'), 1,
                        :from, :until, :user)
                """).param("from", LocalDateTime.of(2020, 1, 1, 0, 0))
                .param("until", LocalDateTime.of(2099, 1, 1, 0, 0)).param("user", user.id()).update();
        jdbc.sql("""
                INSERT INTO registration_application(code_id, username, display_name, email, password_hash, status,
                                                     pending_username, pending_email)
                VALUES ((SELECT id FROM registration_code WHERE code = 'RENAMETEST'), 'rename-pending', '申请人',
                        'pending@example.com', 'hash', 'PENDING', 'rename-pending', 'pending@example.com')
                """).update();

        assertThatThrownBy(() -> users.updateTracked(user.id(), rename(user, "rename-pending")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("注册申请");
    }

    @Test
    void theDeletedAccountPrefixAndMalformedNamesAreRejected() {
        UserService.UserView user = users.get(create("rename-format").user().id());

        assertThatThrownBy(() -> users.updateTracked(user.id(), rename(user, "del.1.someone")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("del.");
        assertThatThrownBy(() -> users.updateTracked(user.id(), rename(user, "有中文")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("3-64");
    }

    @Test
    void fullWidthAndInvisibleCharactersAreFoldedBeforeSaving() {
        UserService.UserView user = users.get(create("rename-fold").user().id());

        // 中文输入法全角状态下打出来的账号，外加一个复制来的零宽空格。
        UserService.Updated updated = users.updateTracked(user.id(), rename(user, "ｒｅｎａｍｅ-wide​"));

        assertThat(updated.user().username()).isEqualTo("rename-wide");
    }

    private UserService.CreatedUser create(String username) {
        return users.create(new UserService.CreateUser(username, "改名测试", UserRole.MINISTER, null, null, null));
    }

    private UserService.UpdateUser rename(UserService.UserView user, String username) {
        return new UserService.UpdateUser(user.displayName(), null, null, user.phone(), user.email(),
                user.wecomUserid(), true, user.version(), user.permissionGroupId(), user.campusIds(), username);
    }
}
