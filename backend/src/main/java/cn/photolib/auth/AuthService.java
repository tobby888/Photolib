package cn.photolib.auth;

import cn.photolib.auth.mapper.AuthSessionMapper;
import cn.photolib.auth.mfa.MfaService;
import cn.photolib.auth.mfa.WebAuthnSupport;
import cn.photolib.auth.model.AuthSessionEntity;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import cn.photolib.user.UserAvatarService;
import cn.photolib.permission.PermissionGroupService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

@Service
public class AuthService {
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$12$wHhY5zYDsMdG2mvvEJPi7eM6FBfXwLwqEyqfL7IGRBxV6JrxEzk3q";
    private final UserMapper userMapper;
    private final AuthSessionMapper sessionMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final PermissionGroupService permissionGroups;
    private final MfaService mfa;

    @Autowired
    public AuthService(UserMapper userMapper, AuthSessionMapper sessionMapper,
                       PasswordEncoder passwordEncoder, AuthProperties properties,
                       PermissionGroupService permissionGroups, MfaService mfa) {
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.permissionGroups = permissionGroups;
        this.mfa = mfa;
    }

    public AuthService(UserMapper userMapper, AuthSessionMapper sessionMapper,
                       PasswordEncoder passwordEncoder, AuthProperties properties) {
        this(userMapper, sessionMapper, passwordEncoder, properties, null, null);
    }

    /**
     * 只用密码登录，不认信任浏览器。两步验证对账号生效时拿不到会话——调用方
     * （测试、内部工具）不该绕开第二步。
     */
    @Transactional
    public TokenPair login(String loginIdentifier, String password) {
        LoginOutcome outcome = login(loginIdentifier, password, userId -> null);
        if (outcome.pair() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "该账号需要完成两步验证");
        }
        return outcome.pair();
    }

    /**
     * 密码登录。两步验证对账号生效时，只有带着有效信任令牌的浏览器能直接拿到会话；
     * 其余情况返回一张登录票据，会话要等 {@link #completeMfaLogin} 验过第二步才签发。
     *
     * @param trustedToken 按账号取出浏览器带来的信任令牌（Cookie 名里带账号 id）
     */
    @Transactional
    public LoginOutcome login(String loginIdentifier, String password, Function<Long, String> trustedToken) {
        String identifier = loginIdentifier == null ? "" : loginIdentifier.trim();
        UserEntity user = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, identifier));
        if (user == null) {
            List<UserEntity> emailMatches = userMapper.selectList(Wrappers.<UserEntity>lambdaQuery()
                    .eq(UserEntity::getEmail, identifier.toLowerCase(Locale.ROOT))
                    .last("LIMIT 2"));
            user = emailMatches.size() == 1 ? emailMatches.getFirst() : null;
        }
        String passwordHash = user == null ? DUMMY_PASSWORD_HASH : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(password == null ? "" : password, passwordHash);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled()) || !passwordMatches) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号、邮箱或密码错误");
        }
        if (mfa != null && toPrincipal(user).mfa().active()) {
            String token = trustedToken.apply(user.getId());
            if (token != null && mfa.useTrustedBrowser(user.getId(), token)) {
                return new LoginOutcome(issue(user, null), null, token);
            }
            return new LoginOutcome(null, mfa.startLogin(user.getId()), null);
        }
        return new LoginOutcome(issue(user, null), null, null);
    }

    /** 验过第二步、消费登录票据之后签发会话。账号在两步之间被停用的，照样拒绝。 */
    @Transactional
    public TokenPair completeMfaLogin(String ticket, MfaService.Verification verification,
                                      WebAuthnSupport.RelyingParty rp) {
        Long userId = mfa.completeLogin(ticket, verification, rp);
        return issue(requireEnabledUser(userId), null);
    }

    @Transactional
    public TokenPair refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "刷新令牌不存在");
        }
        AuthSessionEntity session = sessionMapper.selectOne(Wrappers.<AuthSessionEntity>lambdaQuery()
                .eq(AuthSessionEntity::getRefreshTokenHash, TokenSupport.hash(rawRefreshToken)));
        LocalDateTime now = LocalDateTime.now();
        if (session == null || session.getRevokedAt() != null || !session.getIdleExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "会话已失效");
        }
        UserEntity user = requireEnabledUser(session.getUserId());
        if (sessionMapper.revokeActive(session.getId(), now) != 1) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "会话已失效");
        }
        // 访问令牌 15 分钟就换一次会话，敏感操作的 15 分钟信任期要跟着带过去，
        // 否则刚验证完就可能因为一次续期而失效。
        return issue(user, session.getStepUpUntil());
    }

    /**
     * 为一个已经在浏览器里批准过的 MCP 客户端签发会话（见
     * {@code cn.photolib.mcp.McpAuthorizationService}）。
     *
     * <p>刻意复用 {@link #issue}：MCP 客户端拿到的必须是一次**普通登录**签发的会话，
     * 权限、TTL、改密与停用后的失效全都和浏览器一致。这里唯一比登录少的是密码校验，
     * 那一步已经在浏览器里由成员本人完成过了；账号是否仍然启用仍要现查，因为批准
     * 和取令牌之间隔着一段时间。
     */
    @Transactional
    public TokenPair issueForPairedClient(Long userId) {
        return issue(requireEnabledUser(userId), null);
    }

    public SessionAuthentication authenticate(String rawAccessToken) {
        AuthSessionEntity session = sessionMapper.selectOne(Wrappers.<AuthSessionEntity>lambdaQuery()
                .eq(AuthSessionEntity::getAccessTokenHash, TokenSupport.hash(rawAccessToken)));
        LocalDateTime now = LocalDateTime.now();
        if (session == null || session.getRevokedAt() != null || !session.getAccessExpiresAt().isAfter(now)
                || !session.getIdleExpiresAt().isAfter(now)) {
            return null;
        }
        UserEntity user = requireEnabledUser(session.getUserId());
        return new SessionAuthentication(session.getId(), toPrincipal(user));
    }

    public void touch(Long sessionId) {
        sessionMapper.update(null, Wrappers.<AuthSessionEntity>lambdaUpdate()
                .eq(AuthSessionEntity::getId, sessionId)
                .isNull(AuthSessionEntity::getRevokedAt)
                .set(AuthSessionEntity::getIdleExpiresAt, LocalDateTime.now().plus(properties.idleTtl()))
                .set(AuthSessionEntity::getUpdatedAt, LocalDateTime.now()));
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        AuthSessionEntity session = sessionMapper.selectOne(Wrappers.<AuthSessionEntity>lambdaQuery()
                .eq(AuthSessionEntity::getRefreshTokenHash, TokenSupport.hash(rawRefreshToken)));
        if (session != null && session.getRevokedAt() == null) {
            session.setRevokedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
        }
    }

    @Transactional
    public TokenPair changeInitialPassword(AuthenticatedUser principal, String initialPassword, String newPassword) {
        UserEntity user = requireEnabledUser(principal.id());
        if (!Boolean.TRUE.equals(user.getMustChangePassword())) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "当前账号不需要首次改密");
        }
        if (!passwordEncoder.matches(initialPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "初始密码错误");
        }
        updatePassword(user, newPassword, false);
        revokeAll(user.getId());
        return issue(user, null);
    }

    @Transactional
    public void changePassword(AuthenticatedUser principal, String oldPassword, String newPassword) {
        UserEntity user = requireEnabledUser(principal.id());
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "原密码错误");
        }
        updatePassword(user, newPassword, false);
        revokeAll(user.getId());
    }

    @Transactional
    public void revokeAll(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        sessionMapper.update(null, Wrappers.<AuthSessionEntity>lambdaUpdate()
                .eq(AuthSessionEntity::getUserId, userId)
                .isNull(AuthSessionEntity::getRevokedAt)
                .set(AuthSessionEntity::getRevokedAt, now));
    }

    private TokenPair issue(UserEntity user, LocalDateTime stepUpUntil) {
        String access = TokenSupport.randomToken();
        String refresh = TokenSupport.randomToken();
        LocalDateTime now = LocalDateTime.now();
        AuthSessionEntity session = new AuthSessionEntity();
        session.setUserId(user.getId());
        session.setAccessTokenHash(TokenSupport.hash(access));
        session.setRefreshTokenHash(TokenSupport.hash(refresh));
        session.setAccessExpiresAt(now.plus(properties.accessTtl()));
        session.setIdleExpiresAt(now.plus(properties.idleTtl()));
        session.setStepUpUntil(stepUpUntil);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);
        return new TokenPair(access, refresh, properties.accessTtl().toSeconds(), toPrincipal(user));
    }

    private void updatePassword(UserEntity user, String password, boolean mustChange) {
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setMustChangePassword(mustChange);
        userMapper.updateById(user);
    }

    private UserEntity requireEnabledUser(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户已停用");
        }
        return user;
    }

    private AuthenticatedUser toPrincipal(UserEntity user) {
        return permissionGroups == null
                ? new AuthenticatedUser(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getRole(), user.getCampusId(), Boolean.TRUE.equals(user.getMustChangePassword()),
                UserAvatarService.avatarUrl(user))
                : permissionGroups.toPrincipal(user);
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresIn,
                            AuthenticatedUser user) {
    }

    public record SessionAuthentication(Long sessionId, AuthenticatedUser user) {
    }

    /**
     * 密码登录的结果：要么已签发会话（{@code pair}），要么还差第二步（{@code challenge}）。
     * {@code trustedToken} 非空表示这次凭信任浏览器跳过了第二步，调用方要顺延那枚 Cookie。
     */
    public record LoginOutcome(TokenPair pair, MfaService.LoginChallenge challenge, String trustedToken) {
    }
}
