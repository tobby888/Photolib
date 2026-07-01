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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;

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
        String initialPassword = randomPassword();
        UserEntity user = new UserEntity();
        user.setUsername(command.username());
        user.setPasswordHash(passwordEncoder.encode(initialPassword));
        user.setDisplayName(command.displayName());
        user.setRole(command.role());
        user.setCampusId(command.campusId());
        user.setPhone(command.phone());
        user.setEmail(command.email());
        user.setEnabled(true);
        user.setMustChangePassword(true);
        userMapper.insert(user);
        notifications.notifyUser(user.getId(), "ACCOUNT_CREATED", "PhotoLib 账号已创建",
                "<p>您的 PhotoLib 账号已创建，请向管理员获取初始密码并在首次登录后修改。</p>");
        return new CreatedUser(toView(user), initialPassword);
    }

    public PageResponse<UserView> list(int page, int pageSize, String keyword, UserRole role,
                                       Long campusId, Boolean enabled) {
        var query = Wrappers.<UserEntity>lambdaQuery()
                .and(StringUtils.hasText(keyword), q -> q.like(UserEntity::getUsername, keyword)
                        .or().like(UserEntity::getDisplayName, keyword))
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
        user.setDisplayName(command.displayName());
        user.setRole(command.role());
        user.setCampusId(command.campusId());
        user.setPhone(command.phone());
        user.setEmail(command.email());
        user.setEnabled(command.enabled());
        user.setVersion(command.version());
        if (userMapper.updateById(user) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "用户已被其他操作修改");
        }
        if (!command.enabled()) {
            authService.revokeAll(id);
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
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "校区负责人必须关联校区");
        }
        if (campusId != null) {
            CampusEntity campus = campusMapper.selectById(campusId);
            if (campus == null || !Boolean.TRUE.equals(campus.getEnabled())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "校区不存在或已停用");
            }
        }
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
