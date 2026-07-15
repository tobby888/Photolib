package cn.photolib.auth;

import cn.photolib.auth.mapper.AuthSessionMapper;
import cn.photolib.auth.model.AuthSessionEntity;
import cn.photolib.common.error.BusinessException;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import cn.photolib.user.model.UserRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * 认证服务测试
 * 测试用户登录、令牌刷新、密码修改等核心认证功能
 */
@SpringBootTest
@Transactional
class AuthServiceTests {
    @Autowired
    private AuthService authService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private AuthSessionMapper sessionMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private UserEntity testUser;

    @BeforeEach
    void setUp() {
        // 创建测试用户
        testUser = new UserEntity();
        testUser.setUsername("testuser");
        testUser.setPasswordHash(passwordEncoder.encode("password123"));
        testUser.setDisplayName("测试用户");
        testUser.setRole(UserRole.MINISTER);
        testUser.setEmail("test@example.com");
        testUser.setEnabled(true);
        testUser.setMustChangePassword(false);
        userMapper.insert(testUser);
    }

    @Test
    void loginWithEmail_shouldIgnoreCaseAndWhitespace() {
        AuthService.TokenPair tokens = authService.login("  TEST@EXAMPLE.COM ", "password123");

        assertThat(tokens.user().username()).isEqualTo("testuser");
    }

    @Test
    void databaseConstraint_shouldRejectDuplicateEmail() {
        UserEntity duplicate = new UserEntity();
        duplicate.setUsername("duplicate-email-user");
        duplicate.setPasswordHash(passwordEncoder.encode("anotherPassword123"));
        duplicate.setDisplayName("重复邮箱用户");
        duplicate.setRole(UserRole.MINISTER);
        duplicate.setEmail("test@example.com");
        duplicate.setEnabled(true);
        duplicate.setMustChangePassword(false);
        assertThatThrownBy(() -> userMapper.insert(duplicate))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void loginWithValidCredentials_shouldReturnTokenPair() {
        // When: 使用正确的凭据登录
        AuthService.TokenPair tokens = authService.login("testuser", "password123");

        // Then: 应该返回访问令牌和刷新令牌
        assertThat(tokens.accessToken()).isNotNull();
        assertThat(tokens.refreshToken()).isNotNull();
        assertThat(tokens.user().username()).isEqualTo("testuser");
        assertThat(tokens.user().role()).isEqualTo(UserRole.MINISTER);

        // 验证会话已创建
        AuthSessionEntity session = sessionMapper.selectOne(
                Wrappers.<AuthSessionEntity>lambdaQuery()
                        .eq(AuthSessionEntity::getUserId, testUser.getId()));
        assertThat(session).isNotNull();
        assertThat(session.getAccessExpiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void loginWithInvalidPassword_shouldThrowException() {
        // When & Then: 使用错误的密码登录应该抛出异常
        assertThatThrownBy(() -> authService.login("testuser", "wrongpassword"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号、邮箱或密码错误");
    }

    @Test
    void loginWithDisabledUser_shouldThrowException() {
        // Given: 禁用用户
        testUser.setEnabled(false);
        userMapper.updateById(testUser);

        // When & Then: 登录应该失败
        assertThatThrownBy(() -> authService.login("testuser", "password123"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号、邮箱或密码错误");
    }

    @Test
    void refreshToken_shouldIssueNewTokens() {
        // Given: 用户已登录
        AuthService.TokenPair initialTokens = authService.login("testuser", "password123");

        // When: 使用刷新令牌获取新的访问令牌
        AuthService.TokenPair newTokens = authService.refresh(initialTokens.refreshToken());

        // Then: 应该返回新的令牌对
        assertThat(newTokens.accessToken()).isNotNull();
        assertThat(newTokens.accessToken()).isNotEqualTo(initialTokens.accessToken());
        assertThat(newTokens.refreshToken()).isNotNull();
        assertThat(newTokens.refreshToken()).isNotEqualTo(initialTokens.refreshToken());

        // 旧的刷新令牌应该被撤销
        AuthSessionEntity oldSession = sessionMapper.selectOne(
                Wrappers.<AuthSessionEntity>lambdaQuery()
                        .eq(AuthSessionEntity::getRefreshTokenHash,
                            TokenSupport.hash(initialTokens.refreshToken())));
        assertThat(oldSession.getRevokedAt()).isNotNull();
    }

    @Test
    void refreshRevocationIsConditionalAndSingleUse() {
        AuthService.TokenPair tokens = authService.login("testuser", "password123");
        AuthSessionEntity session = sessionMapper.selectOne(Wrappers.<AuthSessionEntity>lambdaQuery()
                .eq(AuthSessionEntity::getRefreshTokenHash, TokenSupport.hash(tokens.refreshToken())));
        LocalDateTime now = LocalDateTime.now();

        assertThat(sessionMapper.revokeActive(session.getId(), now)).isEqualTo(1);
        assertThat(sessionMapper.revokeActive(session.getId(), now.plusNanos(1))).isZero();
    }

    @Test
    void changePassword_shouldRevokeAllSessions() {
        // Given: 用户已登录
        authService.login("testuser", "password123");
        authService.login("testuser", "password123"); // 创建第二个会话

        AuthenticatedUser principal = new AuthenticatedUser(
                testUser.getId(), "testuser", "测试用户", UserRole.MINISTER, null, false);

        // When: 修改密码
        authService.changePassword(principal, "password123", "newPassword456");

        // Then: 所有会话应该被撤销
        long activeSessionCount = sessionMapper.selectCount(
                Wrappers.<AuthSessionEntity>lambdaQuery()
                        .eq(AuthSessionEntity::getUserId, testUser.getId())
                        .isNull(AuthSessionEntity::getRevokedAt));
        assertThat(activeSessionCount).isZero();

        // 验证新密码可以登录
        AuthService.TokenPair newTokens = authService.login("testuser", "newPassword456");
        assertThat(newTokens).isNotNull();
    }

    @Test
    void changeInitialPassword_shouldSetMustChangePasswordToFalse() {
        // Given: 用户必须修改初始密码
        testUser.setMustChangePassword(true);
        userMapper.updateById(testUser);
        authService.login("testuser", "password123");

        AuthenticatedUser principal = new AuthenticatedUser(
                testUser.getId(), "testuser", "测试用户", UserRole.MINISTER, null, true);

        // When: 修改初始密码
        AuthService.TokenPair newTokens = authService.changeInitialPassword(
                principal, "password123", "newPassword456");

        // Then: mustChangePassword 应该为 false
        UserEntity updatedUser = userMapper.selectById(testUser.getId());
        assertThat(updatedUser.getMustChangePassword()).isFalse();
        assertThat(newTokens.user().mustChangePassword()).isFalse();
    }

    @Test
    void logout_shouldRevokeSession() {
        // Given: 用户已登录
        AuthService.TokenPair tokens = authService.login("testuser", "password123");

        // When: 登出
        authService.logout(tokens.refreshToken());

        // Then: 会话应该被撤销
        AuthSessionEntity session = sessionMapper.selectOne(
                Wrappers.<AuthSessionEntity>lambdaQuery()
                        .eq(AuthSessionEntity::getRefreshTokenHash,
                            TokenSupport.hash(tokens.refreshToken())));
        assertThat(session.getRevokedAt()).isNotNull();

        // 使用已撤销的刷新令牌应该失败
        assertThatThrownBy(() -> authService.refresh(tokens.refreshToken()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void authenticate_withValidToken_shouldReturnAuthentication() {
        // Given: 用户已登录
        AuthService.TokenPair tokens = authService.login("testuser", "password123");

        // When: 验证访问令牌
        AuthService.SessionAuthentication auth = authService.authenticate(tokens.accessToken());

        // Then: 应该返回有效的认证信息
        assertThat(auth).isNotNull();
        assertThat(auth.user().username()).isEqualTo("testuser");
        assertThat(auth.user().role()).isEqualTo(UserRole.MINISTER);
    }
}
