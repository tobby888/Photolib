package cn.photolib.auth;

import cn.photolib.auth.mapper.AuthSessionMapper;
import cn.photolib.auth.model.AuthSessionEntity;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import cn.photolib.permission.PermissionGroupService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class AuthService {
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$12$wHhY5zYDsMdG2mvvEJPi7eM6FBfXwLwqEyqfL7IGRBxV6JrxEzk3q";
    private final UserMapper userMapper;
    private final AuthSessionMapper sessionMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final PermissionGroupService permissionGroups;

    @Autowired
    public AuthService(UserMapper userMapper, AuthSessionMapper sessionMapper,
                       PasswordEncoder passwordEncoder, AuthProperties properties,
                       PermissionGroupService permissionGroups) {
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.permissionGroups = permissionGroups;
    }

    public AuthService(UserMapper userMapper, AuthSessionMapper sessionMapper,
                       PasswordEncoder passwordEncoder, AuthProperties properties) {
        this(userMapper, sessionMapper, passwordEncoder, properties, null);
    }

    @Transactional
    public TokenPair login(String loginIdentifier, String password) {
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
        return issue(user);
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
        return issue(user);
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
        return issue(user);
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

    private TokenPair issue(UserEntity user) {
        String access = TokenSupport.randomToken();
        String refresh = TokenSupport.randomToken();
        LocalDateTime now = LocalDateTime.now();
        AuthSessionEntity session = new AuthSessionEntity();
        session.setUserId(user.getId());
        session.setAccessTokenHash(TokenSupport.hash(access));
        session.setRefreshTokenHash(TokenSupport.hash(refresh));
        session.setAccessExpiresAt(now.plus(properties.accessTtl()));
        session.setIdleExpiresAt(now.plus(properties.idleTtl()));
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
                user.getRole(), user.getCampusId(), Boolean.TRUE.equals(user.getMustChangePassword()))
                : permissionGroups.toPrincipal(user);
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresIn,
                            AuthenticatedUser user) {
    }

    public record SessionAuthentication(Long sessionId, AuthenticatedUser user) {
    }
}
