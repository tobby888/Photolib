package cn.photolib.user;

import cn.photolib.auth.AuthService;
import cn.photolib.notification.NotificationService;
import cn.photolib.campus.mapper.CampusMapper;
import cn.photolib.campus.model.CampusEntity;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import cn.photolib.user.model.UserRole;
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
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public CreatedUser create(CreateUser command) {
        validateCampus(command.role(), command.campusId());
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
        user.setRole(command.role());
        user.setCampusId(command.campusId());
        user.setPhone(command.phone());
        user.setEmail(email);
        user.setEnabled(true);
        user.setMustChangePassword(true);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "用户名或邮箱已被其他用户使用");
        }
        notifications.notifyUser(user.getId(), "ACCOUNT_CREATED", "PhotoLib 账号已创建",
                "<p>您的 PhotoLib 账号已创建，请向管理员获取初始密码并在首次登录后修改。</p>");
        return new CreatedUser(toView(user), initialPassword);
    }

    public PageResponse<UserView> list(int page, int pageSize, String keyword, UserRole role,
                                       Long campusId, Boolean enabled) {
        var query = Wrappers.<UserEntity>lambdaQuery()
                .and(StringUtils.hasText(keyword), q -> q.like(UserEntity::getUsername, keyword)
                        .or().like(UserEntity::getDisplayName, keyword)
                        .or().like(UserEntity::getEmail, keyword))
                .eq(role != null, UserEntity::getRole, role)
                .eq(campusId != null, UserEntity::getCampusId, campusId)
                .eq(enabled != null, UserEntity::getEnabled, enabled)
                .orderByDesc(UserEntity::getCreatedAt);
        Page<UserEntity> result = userMapper.selectPage(Page.of(page, pageSize), query);
        return new PageResponse<>(result.getRecords().stream().map(this::toView).toList(),
                result.getCurrent(), result.getSize(), result.getTotal(), result.getPages());
    }

    public UserView get(Long id) {
        return toView(require(id));
    }

    @Transactional
    public UserView update(Long id, UpdateUser command) {
        UserEntity user = require(id);
        validateCampus(command.role(), command.campusId());
        String email = normalizeEmail(command.email());
        validateEmailAvailable(email, id);
        user.setDisplayName(command.displayName());
        user.setRole(command.role());
        user.setCampusId(command.campusId());
        user.setPhone(command.phone());
        user.setEmail(email);
        user.setEnabled(command.enabled());
        user.setVersion(command.version());
        try {
            if (userMapper.updateById(user) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "用户已被其他操作修改");
            }
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
        UserEntity user = require(id);
        if (user.getRole() != UserRole.CAMPUS_MANAGER) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "只能修改校区负责人的负责校区");
        }
        validateCampus(UserRole.CAMPUS_MANAGER, campusId);
        user.setCampusId(campusId);
        user.setVersion(version);
        if (userMapper.updateById(user) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "用户已被其他操作修改");
        }
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
        if (user.getRole() == UserRole.ADMIN && Boolean.TRUE.equals(user.getEnabled())
                && userMapper.selectCount(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getRole, UserRole.ADMIN).eq(UserEntity::getEnabled, true)) <= 1) {
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
        if (!enabled && user.getRole() == UserRole.ADMIN && Boolean.TRUE.equals(user.getEnabled())
                && userMapper.selectCount(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getRole, UserRole.ADMIN).eq(UserEntity::getEnabled, true)) <= 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "不能停用唯一可用的管理员");
        }
        user.setEnabled(enabled);
        userMapper.updateById(user);
        if (!enabled) authService.revokeAll(id);
        return toView(user);
    }

    private void validateCampus(UserRole role, Long campusId) {
        if (role == UserRole.CAMPUS_MANAGER && campusId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "校区负责人必须指定校区");
        }
        if (campusId != null) {
            CampusEntity campus = campusMapper.selectById(campusId);
            if (campus == null || !Boolean.TRUE.equals(campus.getEnabled())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "校区不存在或已停用");
            }
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
        return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                user.getCampusId(), user.getPhone(), user.getEmail(), user.getEnabled(),
                user.getMustChangePassword(), user.getCreatedAt(), user.getUpdatedAt(), user.getVersion());
    }

    public record CreateUser(String username, String displayName, UserRole role, Long campusId,
                             String phone, String email) {
    }

    public record UpdateUser(String displayName, UserRole role, Long campusId, String phone,
                             String email, boolean enabled, int version) {
    }

    public record CreatedUser(UserView user, String initialPassword) {
    }

    public record UserView(Long id, String username, String displayName, UserRole role, Long campusId,
                           String phone, String email, Boolean enabled, Boolean mustChangePassword,
                           java.time.LocalDateTime createdAt, java.time.LocalDateTime updatedAt,
                           Integer version) {
    }
}
