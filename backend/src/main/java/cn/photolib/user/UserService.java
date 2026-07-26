package cn.photolib.user;

import cn.photolib.auth.AuthService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.notification.NotificationService;
import cn.photolib.campus.mapper.CampusMapper;
import cn.photolib.campus.model.CampusEntity;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import cn.photolib.user.model.UserRole;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionGroupEntity;
import cn.photolib.permission.PermissionGroupService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {
    private static final char[] PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private final UserMapper userMapper;
    private final CampusMapper campusMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final NotificationService notifications;
    private final PermissionGroupService permissionGroups;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public CreatedUser create(CreateUser command) {
        PermissionGroupEntity group = resolveGroup(command.permissionGroupId(), command.role());
        Set<Long> campusIds = resolveCampusIds(command.campusIds(), command.campusId());
        validateAuthorization(group, campusIds);
        if (userMapper.selectCount(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, command.username())) > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "资源已存在");
        }
        String email = normalizeEmail(command.email());
        validateEmailAvailable(email, null);
        String initialPassword = randomPassword();
        UserEntity user = new UserEntity();
        user.setUsername(command.username());
        user.setPasswordHash(passwordEncoder.encode(initialPassword));
        user.setDisplayName(command.displayName());
        user.setRole(permissionGroups.compatibleRole(group));
        user.setPermissionGroupId(group.getId());
        user.setCampusId(campusIds.stream().findFirst().orElse(null));
        user.setPhone(command.phone());
        user.setEmail(email);
        user.setEnabled(true);
        user.setMustChangePassword(true);
        try {
            userMapper.insert(user);
            permissionGroups.replaceUserCampuses(user.getId(), group.getDataScope(), campusIds);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "用户名或邮箱已被其他用户使用");
        }
        notifications.notifyUser(user.getId(), "ACCOUNT_CREATED", "PhotoLib 账号已创建",
                "<p>您的 PhotoLib 账号已创建，请向管理员获取初始密码并在首次登录后修改。</p>");
        return new CreatedUser(toView(user), initialPassword);
    }

    public PageResponse<UserView> list(int page, int pageSize, String keyword, UserRole role,
                                       Long campusId, Boolean enabled) {
        return list(page, pageSize, keyword, role, null, campusId, enabled);
    }

    public PageResponse<UserView> list(int page, int pageSize, String keyword, UserRole role,
                                       Long permissionGroupId, Long campusId, Boolean enabled) {
        var query = Wrappers.<UserEntity>lambdaQuery()
                .and(StringUtils.hasText(keyword), q -> q.like(UserEntity::getUsername, keyword)
                        .or().like(UserEntity::getDisplayName, keyword)
                        .or().like(UserEntity::getEmail, keyword))
                .eq(role != null, UserEntity::getRole, role)
                .eq(permissionGroupId != null, UserEntity::getPermissionGroupId, permissionGroupId)
                .inSql(campusId != null, UserEntity::getId,
                        "SELECT user_id FROM user_campus_permission WHERE campus_id = " + campusId)
                .eq(enabled != null, UserEntity::getEnabled, enabled)
                .orderByDesc(UserEntity::getCreatedAt);
        Page<UserEntity> result = userMapper.selectPage(Page.of(page, pageSize), query);
        return new PageResponse<>(result.getRecords().stream().map(this::toView).toList(),
                result.getCurrent(), result.getSize(), result.getTotal(), result.getPages());
    }

    public UserView get(Long id) {
        return toView(require(id));
    }

    public List<RecipientView> messageRecipients() {
        return userMapper.selectList(Wrappers.<UserEntity>lambdaQuery()
                        .eq(UserEntity::getEnabled, true)
                        .orderByAsc(UserEntity::getDisplayName))
                .stream().map(user -> {
                    PermissionGroupEntity group = permissionGroups.resolveForUser(user);
                    return new RecipientView(user.getId(), user.getDisplayName(), group.getName());
                }).toList();
    }

    public List<CampusAssignmentView> campusAssignableUsers() {
        return campusAssignableUsers(null);
    }

    public List<CampusAssignmentView> campusAssignableUsers(AuthenticatedUser principal) {
        return userMapper.selectList(Wrappers.<UserEntity>lambdaQuery()
                        .eq(UserEntity::getEnabled, true)
                        .orderByAsc(UserEntity::getDisplayName))
                .stream().filter(user -> permissionGroups.resolveForUser(user).getDataScope() == DataScope.CAMPUS)
                .filter(user -> principal == null || !principal.isCampusScoped()
                        || principal.campusIds().containsAll(permissionGroups.campusIds(user.getId())))
                .map(user -> new CampusAssignmentView(user.getId(), user.getDisplayName(),
                        permissionGroups.resolveForUser(user).getName(),
                        permissionGroups.campusIds(user.getId()), user.getVersion()))
                .toList();
    }

    @Transactional
    public UserView update(Long id, UpdateUser command) {
        UserEntity user = require(id);
        PermissionGroupEntity group = resolveGroup(command.permissionGroupId(), command.role());
        Set<Long> campusIds = resolveCampusIds(command.campusIds(), command.campusId());
        validateAuthorization(group, campusIds);
        requireAdminCanChange(user, group.getId(), command.enabled());
        String email = normalizeEmail(command.email());
        validateEmailAvailable(email, id);
        user.setDisplayName(command.displayName());
        user.setRole(permissionGroups.compatibleRole(group));
        user.setPermissionGroupId(group.getId());
        user.setCampusId(campusIds.stream().findFirst().orElse(null));
        user.setPhone(command.phone());
        user.setEmail(email);
        user.setEnabled(command.enabled());
        user.setVersion(command.version());
        try {
            if (userMapper.updateById(user) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "用户已被其他操作修改");
            }
            permissionGroups.replaceUserCampuses(id, group.getDataScope(), campusIds);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "邮箱已被其他用户使用");
        }
        if (!command.enabled()) {
            authService.revokeAll(id);
        }
        return toView(require(id));
    }

    @Transactional
    public UserView updateCampus(Long id, Long campusId, int version) {
        return updateCampus(id, campusId, version, null);
    }

    @Transactional
    public UserView updateCampus(Long id, Long campusId, int version, AuthenticatedUser principal) {
        UserEntity user = require(id);
        PermissionGroupEntity group = permissionGroups.resolveForUser(user);
        if (group.getDataScope() != DataScope.CAMPUS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "只能修改校区负责人的负责校区");
        }
        validateCampusId(campusId);
        Set<Long> currentCampuses = permissionGroups.campusIds(id);
        if (principal != null && principal.isCampusScoped()
                && (!principal.canAccessCampus(campusId)
                || !principal.campusIds().containsAll(currentCampuses))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权重新指定该账号的负责校区");
        }
        user.setCampusId(campusId);
        user.setVersion(version);
        if (userMapper.updateById(user) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "用户已被其他操作修改");
        }
        permissionGroups.replaceUserCampuses(id, DataScope.CAMPUS, Set.of(campusId));
        return toView(require(id));
    }

    @Transactional
    public UserView updateAuthorization(Long id, Long permissionGroupId, Set<Long> campusIds, int version) {
        UserEntity user = require(id);
        PermissionGroupEntity group = permissionGroups.require(permissionGroupId);
        Set<Long> normalizedCampuses = campusIds == null ? Set.of() : new LinkedHashSet<>(campusIds);
        validateAuthorization(group, normalizedCampuses);
        requireAdminCanChange(user, group.getId(), Boolean.TRUE.equals(user.getEnabled()));
        user.setPermissionGroupId(group.getId());
        user.setRole(permissionGroups.compatibleRole(group));
        user.setCampusId(normalizedCampuses.stream().findFirst().orElse(null));
        user.setVersion(version);
        if (userMapper.updateById(user) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "用户已被其他操作修改");
        }
        permissionGroups.replaceUserCampuses(id, group.getDataScope(), normalizedCampuses);
        return toView(require(id));
    }

    @Transactional
    public String resetPassword(Long id) {
        UserEntity user = require(id);
        String initialPassword = randomPassword();
        user.setPasswordHash(passwordEncoder.encode(initialPassword));
        user.setMustChangePassword(true);
        userMapper.updateById(user);
        authService.revokeAll(id);
        notifications.notifyUser(id, "PASSWORD_RESET", "PhotoLib 密码已重置",
                "<p>管理员已重置您的密码，请通过安全渠道获取新的初始密码。</p>");
        return initialPassword;
    }

    @Transactional
    public void delete(Long id, Long operatorId) {
        UserEntity user = require(id);
        if (id.equals(operatorId)) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "不能删除当前登录的账号");
        }
        if (isLastEnabledAdmin(user)) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "不能删除唯一可用的管理员");
        }
        // 释放用户名：软删除后 uk_user_username 唯一约束仍占用原用户名，
        // 重命名（保留唯一的 id 前缀）以便后续可重新创建同名账号。
        String freed = "del." + id + "." + user.getUsername();
        user.setUsername(freed.length() > 64 ? freed.substring(0, 64) : freed);
        user.setEmail(null);
        user.setEnabled(false);
        userMapper.updateById(user);
        userMapper.deleteById(id);
        authService.revokeAll(id);
    }

    @Transactional
    public UserView setEnabled(Long id, boolean enabled) {
        UserEntity user = require(id);
        if (!enabled && isLastEnabledAdmin(user)) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "不能停用唯一可用的管理员");
        }
        user.setEnabled(enabled);
        userMapper.updateById(user);
        if (!enabled) authService.revokeAll(id);
        return toView(user);
    }

    private PermissionGroupEntity resolveGroup(Long permissionGroupId, UserRole legacyRole) {
        return permissionGroupId == null
                ? permissionGroups.resolveLegacyRole(legacyRole)
                : permissionGroups.require(permissionGroupId);
    }

    private Set<Long> resolveCampusIds(Set<Long> campusIds, Long legacyCampusId) {
        if (campusIds != null) return new LinkedHashSet<>(campusIds);
        return legacyCampusId == null ? Set.of() : Set.of(legacyCampusId);
    }

    private void validateAuthorization(PermissionGroupEntity group, Set<Long> campusIds) {
        permissionGroups.replaceUserCampusesValidation(group.getDataScope(), campusIds);
    }

    private void validateCampusId(Long campusId) {
        CampusEntity campus = campusMapper.selectById(campusId);
        if (campus == null || !Boolean.TRUE.equals(campus.getEnabled())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "校区不存在或已停用");
        }
    }

    private boolean isLastEnabledAdmin(UserEntity user) {
        PermissionGroupEntity adminGroup = permissionGroups.requireByCode("ADMIN");
        return adminGroup.getId().equals(user.getPermissionGroupId())
                && Boolean.TRUE.equals(user.getEnabled())
                && userMapper.selectCount(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getPermissionGroupId, adminGroup.getId())
                .eq(UserEntity::getEnabled, true)) <= 1;
    }

    private void requireAdminCanChange(UserEntity user, Long targetGroupId, boolean targetEnabled) {
        PermissionGroupEntity adminGroup = permissionGroups.requireByCode("ADMIN");
        if (isLastEnabledAdmin(user)
                && (!adminGroup.getId().equals(targetGroupId) || !targetEnabled)) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "不能移除唯一可用管理员的管理员权限");
        }
    }

    private void validateEmailAvailable(String email, Long excludedUserId) {
        if (email == null) {
            return;
        }
        long existing = userMapper.selectCount(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getEmail, email)
                .ne(excludedUserId != null, UserEntity::getId, excludedUserId));
        if (existing > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "邮箱已被其他用户使用");
        }
    }

    private String normalizeEmail(String email) {
        return StringUtils.hasText(email) ? email.trim().toLowerCase(Locale.ROOT) : null;
    }

    private UserEntity require(Long id) {
        UserEntity user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private String randomPassword() {
        StringBuilder value = new StringBuilder("P1!");
        for (int i = 0; i < 13; i++) {
            value.append(PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.length)]);
        }
        return value.toString();
    }

    private UserView toView(UserEntity user) {
        PermissionGroupEntity resolvedGroup = permissionGroups.resolveForUser(user);
        PermissionGroupService.GroupView group = permissionGroups.get(resolvedGroup.getId());
        Set<Long> campusIds = permissionGroups.campusIds(user.getId());
        return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                user.getCampusId(), user.getPhone(), user.getEmail(), user.getEnabled(),
                user.getMustChangePassword(), user.getCreatedAt(), user.getUpdatedAt(), user.getVersion(),
                group.id(), group.code(), group.name(), group.dataScope(), campusIds);
    }

    public record CreateUser(String username, String displayName, UserRole role, Long campusId,
                             String phone, String email, Long permissionGroupId, Set<Long> campusIds) {
        public CreateUser(String username, String displayName, UserRole role, Long campusId,
                          String phone, String email) {
            this(username, displayName, role, campusId, phone, email, null, null);
        }
    }

    public record UpdateUser(String displayName, UserRole role, Long campusId, String phone,
                             String email, boolean enabled, int version, Long permissionGroupId,
                             Set<Long> campusIds) {
        public UpdateUser(String displayName, UserRole role, Long campusId, String phone,
                          String email, boolean enabled, int version) {
            this(displayName, role, campusId, phone, email, enabled, version, null, null);
        }
    }

    public record CreatedUser(UserView user, String initialPassword) {
    }

    public record UserView(Long id, String username, String displayName, UserRole role, Long campusId,
                           String phone, String email, Boolean enabled, Boolean mustChangePassword,
                           java.time.LocalDateTime createdAt, java.time.LocalDateTime updatedAt,
                           Integer version, Long permissionGroupId, String permissionGroupCode,
                           String permissionGroupName, DataScope dataScope, Set<Long> campusIds) {
    }

    public record RecipientView(Long id, String displayName, String permissionGroupName) {}
    public record CampusAssignmentView(Long id, String displayName, String permissionGroupName,
                                       Set<Long> campusIds, Integer version) {}
}
