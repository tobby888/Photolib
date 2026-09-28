package cn.photolib.registration;

import cn.photolib.campus.CampusService;
import cn.photolib.common.error.BusinessException;
import cn.photolib.permission.PermissionGroupService;
import cn.photolib.registration.mapper.RegistrationApplicationMapper;
import cn.photolib.registration.mapper.RegistrationCodeMapper;
import cn.photolib.registration.model.RegistrationApplicationEntity;
import cn.photolib.registration.model.RegistrationStatus;
import cn.photolib.user.UserService;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 注册码与注册审核。
 *
 * <p>刻意不加 {@code @Transactional}：批量审核逐条各开一个事务，"一条失败不连累整批、
 * 失败那条原样回到待审核"正是要验的东西，套在测试事务里这些内层事务就都合并成一个了，
 * 回滚看不出来。所以每个用例自己清理插入的行（共用的 H2 库，见测试资源配置）。</p>
 */
@SpringBootTest
class RegistrationServiceTests {
    private static final long ADMIN_ID = 96_001L;
    private static final String PASSWORD = "Register2026pass";

    @Autowired private RegistrationService service;
    @Autowired private RegistrationCodeMapper codeMapper;
    @Autowired private RegistrationApplicationMapper applicationMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private UserService userService;
    @Autowired private CampusService campusService;
    @Autowired private PermissionGroupService permissionGroups;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcClient jdbc;
    @Autowired private Clock clock;

    private Long ministerGroupId;
    private Long campusGroupId;
    private Long campusId;

    @BeforeEach
    void setUp() {
        cleanUp();
        ministerGroupId = permissionGroups.requireByCode("MINISTER").getId();
        campusGroupId = permissionGroups.requireByCode("CAMPUS_MANAGER").getId();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, permission_group_id, enabled, must_change_password)
                VALUES (:id, 'reg-test-admin', 'hash', '注册审核管理员', 'ADMIN', :groupId, TRUE, FALSE)
                """).param("id", ADMIN_ID).param("groupId", permissionGroups.requireByCode("ADMIN").getId()).update();
        campusId = campusService.create("REG-TEST", "注册测试校区").getId();
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM registration_application WHERE username LIKE 'reg-test-%'").update();
        jdbc.sql("""
                DELETE FROM registration_code_campus WHERE code_id IN
                    (SELECT id FROM registration_code WHERE name LIKE 'reg-test-%')
                """).update();
        jdbc.sql("DELETE FROM registration_code WHERE name LIKE 'reg-test-%'").update();
        jdbc.sql("DELETE FROM user_notification WHERE event_type LIKE 'REGISTRATION_%'").update();
        jdbc.sql("""
                DELETE FROM user_notification WHERE user_id IN
                    (SELECT id FROM app_user WHERE username LIKE 'reg-test-%')
                """).update();
        jdbc.sql("""
                DELETE FROM user_campus_permission WHERE user_id IN
                    (SELECT id FROM app_user WHERE username LIKE 'reg-test-%')
                """).update();
        jdbc.sql("DELETE FROM app_user WHERE username LIKE 'reg-test-%'").update();
        jdbc.sql("DELETE FROM campus WHERE code = 'REG-TEST'").update();
    }

    @Test
    void generatedCodeCarriesTheAdministratorsChoices() {
        var code = createCode("reg-test-秋招", campusGroupId, Set.of(campusId), 30);

        assertThat(code.code()).hasSize(16).matches("[" + RegistrationService.CODE_ALPHABET + "]+");
        assertThat(code.permissionGroupId()).isEqualTo(campusGroupId);
        assertThat(code.campusIds()).containsExactly(campusId);
        assertThat(code.maxUses()).isEqualTo(30);
        assertThat(code.usedCount()).isZero();
        assertThat(code.status()).isEqualTo(RegistrationService.CodeStatus.ACTIVE);
    }

    @Test
    void codesCannotGrantTheAdministratorGroupOrSkipCampuses() {
        Long adminGroup = permissionGroups.requireByCode("ADMIN").getId();
        assertThatThrownBy(() -> createCode("reg-test-管理员", adminGroup, Set.of(), 5))
                .hasMessageContaining("系统管理员");
        assertThatThrownBy(() -> createCode("reg-test-缺校区", campusGroupId, Set.of(), 5))
                .hasMessageContaining("至少指定一个校区");
        LocalDateTime now = LocalDateTime.now(clock);
        assertThatThrownBy(() -> service.createCode(new RegistrationService.CreateCode("reg-test-过期",
                ministerGroupId, Set.of(), 5, now.minusDays(2), now.minusDays(1)), ADMIN_ID))
                .hasMessageContaining("晚于当前时间");
    }

    @Test
    void submittingCreatesAPendingApplicationButNoAccount() {
        var code = createCode("reg-test-提交", ministerGroupId, Set.of(), 3);
        // 同学手抄的注册码：小写、带连字符和空格也要认。
        String typed = code.code().toLowerCase().replaceAll("(.{4})(?!$)", "$1- ");

        var submitted = register(typed, "reg-test-alice", "张三", "Alice@Example.com");

        RegistrationApplicationEntity application = applicationMapper.selectById(submitted.id());
        assertThat(application.getStatus()).isEqualTo(RegistrationStatus.PENDING);
        assertThat(application.getEmail()).isEqualTo("alice@example.com");
        assertThat(application.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, application.getPasswordHash())).isTrue();
        assertThat(userByName("reg-test-alice")).isNull();
        assertThat(codeMapper.selectById(code.id()).getUsedCount()).isEqualTo(1);
    }

    @Test
    void unusableCodesAreRefusedWithTheReason() {
        LocalDateTime now = LocalDateTime.now(clock);
        assertThatThrownBy(() -> register("AAAA-BBBB-CCCC-DDDD", "reg-test-x1", "某", "x1@example.com"))
                .hasMessageContaining("注册码无效");

        var future = service.createCode(new RegistrationService.CreateCode("reg-test-未开始", ministerGroupId,
                Set.of(), 5, now.plusDays(1), now.plusDays(2)), ADMIN_ID);
        assertThatThrownBy(() -> register(future.code(), "reg-test-x2", "某", "x2@example.com"))
                .hasMessageContaining("尚未生效");

        var disabled = createCode("reg-test-停用", ministerGroupId, Set.of(), 5);
        service.updateCode(disabled.id(), new RegistrationService.UpdateCode(disabled.name(), 5,
                disabled.validFrom(), disabled.validUntil(), false, disabled.version()));
        assertThatThrownBy(() -> register(disabled.code(), "reg-test-x3", "某", "x3@example.com"))
                .hasMessageContaining("已停用");

        var single = createCode("reg-test-单人", ministerGroupId, Set.of(), 1);
        register(single.code(), "reg-test-x4", "某", "x4@example.com");
        assertThatThrownBy(() -> register(single.code(), "reg-test-x5", "某", "x5@example.com"))
                .hasMessageContaining("名额已满");
        // 失败的提交不占名额。
        assertThat(codeMapper.selectById(single.id()).getUsedCount()).isEqualTo(1);

        jdbc.sql("UPDATE registration_code SET valid_from = :from, valid_until = :until WHERE id = :id")
                .param("from", now.minusDays(2)).param("until", now.minusSeconds(1))
                .param("id", future.id()).update();
        assertThatThrownBy(() -> register(future.code(), "reg-test-x6", "某", "x6@example.com"))
                .hasMessageContaining("已过期");
    }

    @Test
    void usernamesAndEmailsMustBeFreeAmongAccountsAndPendingApplications() {
        var code = createCode("reg-test-查重", ministerGroupId, Set.of(), 10);
        register(code.code(), "reg-test-bob", "李四", "bob@example.com");

        assertThatThrownBy(() -> register(code.code(), "reg-test-bob", "李四", "bob2@example.com"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("登录账号已被使用");
        assertThatThrownBy(() -> register(code.code(), "reg-test-bob2", "李四", "BOB@example.com"))
                .hasMessageContaining("邮箱已被使用");

        userService.create(new UserService.CreateUser("reg-test-carol", "王五", null, null, null,
                "carol@example.com", null, ministerGroupId, Set.of()));
        assertThatThrownBy(() -> register(code.code(), "reg-test-carol", "王五", "carol2@example.com"))
                .hasMessageContaining("登录账号已被使用");
        assertThatThrownBy(() -> register(code.code(), "reg-test-carol2", "王五", "carol@example.com"))
                .hasMessageContaining("邮箱已被使用");
        assertThatThrownBy(() -> register(code.code(), "del.1.reg-test", "某", "del@example.com"))
                .hasMessageContaining("del.");
        assertThat(codeMapper.selectById(code.id()).getUsedCount()).isEqualTo(1);
    }

    @Test
    void approvingCreatesTheAccountWithTheCodesGroupAndTheApplicantsPassword() {
        var code = createCode("reg-test-通过", campusGroupId, Set.of(campusId), 5);
        var submitted = register(code.code(), "reg-test-dave", "赵六", "dave@example.com");

        var result = service.approve(List.of(submitted.id()), ADMIN_ID);

        assertThat(result.succeeded()).containsExactly(submitted.id());
        assertThat(result.failed()).isEmpty();
        UserEntity user = userByName("reg-test-dave");
        assertThat(user).isNotNull();
        assertThat(user.getDisplayName()).isEqualTo("赵六");
        assertThat(user.getEmail()).isEqualTo("dave@example.com");
        assertThat(user.getPermissionGroupId()).isEqualTo(campusGroupId);
        assertThat(user.getEnabled()).isTrue();
        assertThat(user.getMustChangePassword()).isFalse();
        assertThat(passwordEncoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(permissionGroups.campusIds(user.getId())).containsExactly(campusId);

        RegistrationApplicationEntity application = applicationMapper.selectById(submitted.id());
        assertThat(application.getStatus()).isEqualTo(RegistrationStatus.APPROVED);
        assertThat(application.getUserId()).isEqualTo(user.getId());
        assertThat(application.getReviewerId()).isEqualTo(ADMIN_ID);
        assertThat(application.getPendingUsername()).isNull();
        assertThat(application.getPendingEmail()).isNull();
        // 通过不归还名额。
        assertThat(codeMapper.selectById(code.id()).getUsedCount()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM user_notification WHERE user_id = :id AND event_type = 'REGISTRATION_APPROVED'
                """).param("id", user.getId()).query(Long.class).single()).isEqualTo(1L);

        var again = service.approve(List.of(submitted.id()), ADMIN_ID);
        assertThat(again.succeeded()).isEmpty();
        assertThat(again.failed()).singleElement()
                .satisfies(failure -> assertThat(failure.message()).contains("已经审核过了"));
    }

    @Test
    void oneFailureInABatchDoesNotRollBackTheOthers() {
        var code = createCode("reg-test-批量", ministerGroupId, Set.of(), 5);
        var first = register(code.code(), "reg-test-erin", "孙七", "erin@example.com");
        var second = register(code.code(), "reg-test-frank", "周八", "frank@example.com");
        // 申请提交之后，管理员又手工建了一个同名账号：这一份只能失败。
        userService.create(new UserService.CreateUser("reg-test-frank", "周八", null, null, null,
                null, null, ministerGroupId, Set.of()));

        var result = service.approve(List.of(first.id(), second.id()), ADMIN_ID);

        assertThat(result.succeeded()).containsExactly(first.id());
        assertThat(result.failed()).singleElement().satisfies(failure -> {
            assertThat(failure.id()).isEqualTo(second.id());
            assertThat(failure.username()).isEqualTo("reg-test-frank");
            assertThat(failure.message()).contains("已被其他用户使用");
        });
        // 失败那条整条回滚：仍是待审核，占位还在，可以改为驳回。
        RegistrationApplicationEntity failed = applicationMapper.selectById(second.id());
        assertThat(failed.getStatus()).isEqualTo(RegistrationStatus.PENDING);
        assertThat(failed.getPendingUsername()).isEqualTo("reg-test-frank");
        assertThat(applicationMapper.selectById(first.id()).getStatus()).isEqualTo(RegistrationStatus.APPROVED);
    }

    @Test
    void rejectingReturnsTheSlotAndFreesTheUsername() {
        var code = createCode("reg-test-驳回", ministerGroupId, Set.of(), 1);
        var submitted = register(code.code(), "reg-test-grace", "吴九", "grace@example.com");

        var result = service.reject(List.of(submitted.id()), "  姓名与名单不符  ", ADMIN_ID);

        assertThat(result.succeeded()).containsExactly(submitted.id());
        RegistrationApplicationEntity application = applicationMapper.selectById(submitted.id());
        assertThat(application.getStatus()).isEqualTo(RegistrationStatus.REJECTED);
        assertThat(application.getRejectReason()).isEqualTo("姓名与名单不符");
        assertThat(application.getPendingUsername()).isNull();
        assertThat(userByName("reg-test-grace")).isNull();
        assertThat(codeMapper.selectById(code.id()).getUsedCount()).isZero();

        // 名额和账号都回来了：本人改正后可以用同一个码、同一个账号重新提交。
        var resubmitted = register(code.code(), "reg-test-grace", "吴九", "grace@example.com");
        assertThat(applicationMapper.selectById(resubmitted.id()).getStatus()).isEqualTo(RegistrationStatus.PENDING);
    }

    @Test
    void codesInUseCannotShrinkBelowTheirUsageOrBeDeleted() {
        var code = createCode("reg-test-修改", ministerGroupId, Set.of(), 3);
        register(code.code(), "reg-test-heidi", "郑十", "heidi@example.com");
        register(code.code(), "reg-test-ivan", "钱一", "ivan@example.com");
        var current = service.listCodes(1, 20, "reg-test-修改").items().getFirst();
        assertThat(current.usedCount()).isEqualTo(2);
        assertThat(current.pendingCount()).isEqualTo(2);

        assertThatThrownBy(() -> service.updateCode(code.id(), new RegistrationService.UpdateCode(code.name(), 1,
                code.validFrom(), code.validUntil(), true, current.version())))
                .hasMessageContaining("不能少于已占用的 2 个名额");
        // 拿着提交之前的版本号来改：提交占名额时版本号也加了一，所以这次修改被拒。
        assertThatThrownBy(() -> service.updateCode(code.id(), new RegistrationService.UpdateCode(code.name(), 5,
                code.validFrom(), code.validUntil(), true, code.version())))
                .hasMessageContaining("已被其他操作修改");
        assertThatThrownBy(() -> service.deleteCode(code.id())).hasMessageContaining("不能删除");

        var unused = createCode("reg-test-删除", ministerGroupId, Set.of(), 3);
        service.deleteCode(unused.id());
        assertThat(codeMapper.selectById(unused.id())).isNull();
    }

    @Test
    void applicationsCanBeSearchedAndFiltered() {
        var code = createCode("reg-test-搜索", ministerGroupId, Set.of(), 10);
        var judy = register(code.code(), "reg-test-judy", "冯小红", "judy@example.com");
        register(code.code(), "reg-test-kate", "陈小明", "kate@example.com");
        service.reject(List.of(judy.id()), null, ADMIN_ID);

        assertThat(service.listApplications(1, 20, "小明", null, code.id()).items())
                .extracting(RegistrationService.ApplicationView::username).containsExactly("reg-test-kate");
        assertThat(service.listApplications(1, 20, "judy@", null, code.id()).items())
                .extracting(RegistrationService.ApplicationView::username).containsExactly("reg-test-judy");
        var pending = service.listApplications(1, 20, null, RegistrationStatus.PENDING, code.id()).items();
        assertThat(pending).extracting(RegistrationService.ApplicationView::username).containsExactly("reg-test-kate");
        assertThat(pending.getFirst().codeName()).isEqualTo("reg-test-搜索");
        assertThat(pending.getFirst().permissionGroupName()).isEqualTo("摄影部部长");
        var rejected = service.listApplications(1, 20, null, RegistrationStatus.REJECTED, code.id()).items();
        assertThat(rejected.getFirst().reviewerName()).isEqualTo("注册审核管理员");
    }

    @Test
    void administratorsGetOneUnreadReminderNotOnePerApplication() {
        var code = createCode("reg-test-提醒", ministerGroupId, Set.of(), 10);
        register(code.code(), "reg-test-leo", "褚一", "leo@example.com");
        register(code.code(), "reg-test-mia", "卫二", "mia@example.com");

        assertThat(reminders()).isEqualTo(1L);

        jdbc.sql("UPDATE user_notification SET read_at = CURRENT_TIMESTAMP WHERE user_id = :id")
                .param("id", ADMIN_ID).update();
        register(code.code(), "reg-test-nina", "蒋三", "nina@example.com");
        assertThat(reminders()).isEqualTo(2L);
    }

    @Test
    void approvalIsRefusedOnceTheCodesGroupIsGone() {
        var group = permissionGroups.create(new PermissionGroupService.CreateCommand("REG_TEST_TEMP",
                "reg-test-临时组", null, cn.photolib.permission.DataScope.GLOBAL,
                cn.photolib.permission.PhotoVisibility.GLOBAL, Set.of(cn.photolib.permission.PermissionCode.PHOTO_VIEW)));
        try {
            var code = createCode("reg-test-删组", group.id(), Set.of(), 5);
            var submitted = register(code.code(), "reg-test-otto", "沈四", "otto@example.com");
            permissionGroups.delete(group.id());

            var result = service.approve(List.of(submitted.id()), ADMIN_ID);

            assertThat(result.failed()).singleElement()
                    .satisfies(failure -> assertThat(failure.message()).contains("权限组已被删除"));
            assertThat(userByName("reg-test-otto")).isNull();
            assertThatThrownBy(() -> register(code.code(), "reg-test-pete", "韩五", "pete@example.com"))
                    .hasMessageContaining("已失效");
        } finally {
            jdbc.sql("DELETE FROM permission_group_permission WHERE group_id = :id").param("id", group.id()).update();
            jdbc.sql("DELETE FROM permission_group WHERE id = :id").param("id", group.id()).update();
        }
    }

    private long reminders() {
        return jdbc.sql("""
                SELECT COUNT(*) FROM user_notification WHERE user_id = :id AND event_type = 'REGISTRATION_PENDING'
                """).param("id", ADMIN_ID).query(Long.class).single();
    }

    private RegistrationService.CodeView createCode(String name, Long groupId, Set<Long> campuses, int maxUses) {
        return service.createCode(new RegistrationService.CreateCode(name, groupId, campuses, maxUses, null,
                LocalDateTime.now(clock).plusDays(7)), ADMIN_ID);
    }

    private RegistrationService.SubmittedApplication register(String code, String username, String name, String email) {
        return service.register(new RegistrationService.Register(code, username, name, email, PASSWORD));
    }

    private UserEntity userByName(String username) {
        return userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getUsername, username));
    }
}
