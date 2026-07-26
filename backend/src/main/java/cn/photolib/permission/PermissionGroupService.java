package cn.photolib.permission;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.mapper.CampusMapper;
import cn.photolib.campus.model.CampusEntity;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.permission.mapper.PermissionGroupMapper;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import cn.photolib.user.model.UserRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class PermissionGroupService {
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    private final PermissionGroupMapper mapper;
    private final UserMapper userMapper;
    private final CampusMapper campusMapper;
    private final JdbcClient jdbc;

    public List<CategoryDefinition> definitions() {
        return Arrays.stream(PermissionCategory.values())
                .map(category -> new CategoryDefinition(category.name(), category.label(),
                        Arrays.stream(PermissionCode.values())
                                .filter(permission -> permission.category() == category)
                                .map(permission -> new PermissionDefinition(permission.name(), permission.label()))
                                .toList()))
                .toList();
    }

    public List<GroupView> list() {
        return mapper.selectList(Wrappers.<PermissionGroupEntity>lambdaQuery()
                        .orderByDesc(PermissionGroupEntity::getBuiltIn)
                        .orderByAsc(PermissionGroupEntity::getId))
                .stream().map(this::toView).toList();
    }

    public GroupView get(Long id) {
        return toView(require(id));
    }

    public PermissionGroupEntity require(Long id) {
        PermissionGroupEntity group = mapper.selectById(id);
        if (group == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "权限组不存在");
        }
        return group;
    }

    public PermissionGroupEntity requireByCode(String code) {
        PermissionGroupEntity group = mapper.selectOne(Wrappers.<PermissionGroupEntity>lambdaQuery()
                .eq(PermissionGroupEntity::getCode, code));
        if (group == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "权限组不存在");
        }
        return group;
    }

    public PermissionGroupEntity resolveLegacyRole(UserRole role) {
        return requireByCode(role == null ? "NO_ACCESS" : role.name());
    }

    public PermissionGroupEntity resolveForUser(UserEntity user) {
        PermissionGroupEntity group = user.getPermissionGroupId() == null
                ? null
                : mapper.selectById(user.getPermissionGroupId());
        return group == null ? resolveLegacyRole(user.getRole()) : group;
    }

    @Transactional
    public GroupView create(CreateCommand command) {
        String code = normalizeCode(command.code());
        validateMutableFields(command.name(), command.dataScope(), command.permissions());
        PermissionGroupEntity group = new PermissionGroupEntity();
        group.setCode(code);
        group.setName(command.name().trim());
        group.setDescription(normalizeDescription(command.description()));
        group.setDataScope(command.dataScope());
        group.setBuiltIn(false);
        group.setLowest(false);
        try {
            mapper.insert(group);
            replacePermissions(group.getId(), command.permissions());
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "权限组代码已存在");
        }
        return get(group.getId());
    }

    @Transactional
    public GroupView update(Long id, UpdateCommand command) {
        PermissionGroupEntity group = require(id);
        if (Boolean.TRUE.equals(group.getLowest())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "最低权限组不能编辑");
        }
        validateMutableFields(command.name(), command.dataScope(), command.permissions());
        Set<PermissionCode> permissions = command.permissions();
        if ("ADMIN".equals(group.getCode())) {
            // 系统管理员组是权限系统自身的管理入口（权限组、账号、校区、操作日志都靠
            // hasRole('ADMIN')）。允许裁剪它的权限明细会造出"能进管理页但业务权限被削掉"
            // 的不一致状态，且没有其他角色能恢复，因此固定为全集。
            permissions = Set.of(PermissionCode.values());
        }
        if (!Boolean.TRUE.equals(group.getBuiltIn())) {
            group.setName(command.name().trim());
            group.setDescription(normalizeDescription(command.description()));
            group.setDataScope(command.dataScope());
        }
        group.setVersion(command.version());
        if (mapper.updateById(group) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "权限组已被其他操作修改");
        }
        replacePermissions(id, permissions);
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        PermissionGroupEntity group = require(id);
        if (Boolean.TRUE.equals(group.getBuiltIn())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "系统内置权限组不能删除");
        }
        PermissionGroupEntity lowest = requireByCode("NO_ACCESS");
        jdbc.sql("""
                DELETE FROM user_campus_permission
                WHERE user_id IN (SELECT id FROM app_user WHERE permission_group_id = :groupId)
                """).param("groupId", id).update();
        jdbc.sql("""
                UPDATE app_user
                SET permission_group_id = :lowestId, role = 'CAMPUS_MANAGER', campus_id = NULL,
                    version = version + 1, updated_at = :updatedAt
                WHERE permission_group_id = :groupId
                """).param("lowestId", lowest.getId()).param("groupId", id)
                .param("updatedAt", LocalDateTime.now()).update();
        if (mapper.deletePhysically(id) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "权限组删除失败");
        }
    }

    public AuthenticatedUser toPrincipal(UserEntity user) {
        PermissionGroupEntity group = resolveForUser(user);
        Set<PermissionCode> permissions = permissionCodes(group.getId());
        Set<Long> campusIds = new LinkedHashSet<>(jdbc.sql("""
                SELECT campus_id FROM user_campus_permission WHERE user_id = :userId ORDER BY campus_id
                """).param("userId", user.getId()).query(Long.class).list());
        if (campusIds.isEmpty() && user.getCampusId() != null && group.getDataScope() == DataScope.CAMPUS) {
            campusIds.add(user.getCampusId());
        }
        UserRole compatibleRole = switch (group.getCode()) {
            case "ADMIN" -> UserRole.ADMIN;
            case "MINISTER" -> UserRole.MINISTER;
            default -> UserRole.CAMPUS_MANAGER;
        };
        Long primaryCampusId = campusIds.stream().findFirst().orElse(null);
        return new AuthenticatedUser(user.getId(), user.getUsername(), user.getDisplayName(),
                compatibleRole, primaryCampusId, Boolean.TRUE.equals(user.getMustChangePassword()),
                group.getId(), group.getCode(), group.getName(), group.getDataScope(),
                permissions, campusIds);
    }

    public Set<Long> campusIds(Long userId) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT campus_id FROM user_campus_permission WHERE user_id = :userId ORDER BY campus_id
                """).param("userId", userId).query(Long.class).list());
    }

    @Transactional
    public void replaceUserCampuses(Long userId, DataScope scope, Set<Long> campusIds) {
        replaceUserCampusesValidation(scope, campusIds);
        Set<Long> normalized = campusIds == null ? Set.of() : new LinkedHashSet<>(campusIds);
        jdbc.sql("DELETE FROM user_campus_permission WHERE user_id = :userId")
                .param("userId", userId).update();
        for (Long campusId : normalized) {
            jdbc.sql("INSERT INTO user_campus_permission(user_id, campus_id) VALUES (:userId, :campusId)")
                    .param("userId", userId).param("campusId", campusId).update();
        }
    }

    public void replaceUserCampusesValidation(DataScope scope, Set<Long> campusIds) {
        Set<Long> normalized = campusIds == null ? Set.of() : new LinkedHashSet<>(campusIds);
        if (scope == DataScope.CAMPUS && normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "校区范围权限组必须至少指定一个校区");
        }
        if (scope != DataScope.CAMPUS && !normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "仅校区范围权限组可以指定校区权限");
        }
        for (Long campusId : normalized) {
            CampusEntity campus = campusMapper.selectById(campusId);
            if (campus == null || !Boolean.TRUE.equals(campus.getEnabled())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "校区不存在或已停用");
            }
        }
    }

    public UserRole compatibleRole(PermissionGroupEntity group) {
        return switch (group.getCode()) {
            case "ADMIN" -> UserRole.ADMIN;
            case "MINISTER" -> UserRole.MINISTER;
            default -> UserRole.CAMPUS_MANAGER;
        };
    }

    private void validateMutableFields(String name, DataScope scope, Set<PermissionCode> permissions) {
        if (!StringUtils.hasText(name) || name.trim().length() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "权限组名称长度必须为 1 到 100 个字符");
        }
        if (scope == null || scope == DataScope.NONE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "自定义权限组的数据范围必须为全局或校区");
        }
        if (permissions == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "权限列表不能为空");
        }
    }

    private String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "权限组代码需为 3-64 位大写字母、数字或下划线");
        }
        return normalized;
    }

    private String normalizeDescription(String description) {
        if (!StringUtils.hasText(description)) return null;
        String normalized = description.trim();
        if (normalized.length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "权限组说明不能超过 500 个字符");
        }
        return normalized;
    }

    private void replacePermissions(Long groupId, Set<PermissionCode> permissions) {
        jdbc.sql("DELETE FROM permission_group_permission WHERE group_id = :groupId")
                .param("groupId", groupId).update();
        permissions.stream().sorted(Comparator.comparing(PermissionCode::name)).forEach(permission ->
                jdbc.sql("""
                        INSERT INTO permission_group_permission(group_id, permission_code)
                        VALUES (:groupId, :permissionCode)
                        """).param("groupId", groupId).param("permissionCode", permission.name()).update());
    }

    private Set<PermissionCode> permissionCodes(Long groupId) {
        Set<PermissionCode> result = new LinkedHashSet<>();
        jdbc.sql("""
                SELECT permission_code FROM permission_group_permission
                WHERE group_id = :groupId ORDER BY permission_code
                """).param("groupId", groupId).query(String.class).list().forEach(code -> {
            try {
                result.add(PermissionCode.valueOf(code));
            } catch (IllegalArgumentException ignored) {
                // Ignore codes introduced by a newer deployment during a rolling update.
            }
        });
        return result;
    }

    private GroupView toView(PermissionGroupEntity group) {
        long memberCount = userMapper.selectCount(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getPermissionGroupId, group.getId()));
        return new GroupView(group.getId(), group.getCode(), group.getName(), group.getDescription(),
                group.getDataScope(), Boolean.TRUE.equals(group.getBuiltIn()),
                Boolean.TRUE.equals(group.getLowest()), permissionCodes(group.getId()), memberCount,
                group.getCreatedAt(), group.getUpdatedAt(), group.getVersion());
    }

    public record PermissionDefinition(String code, String label) {}
    public record CategoryDefinition(String code, String label, List<PermissionDefinition> permissions) {}
    public record GroupView(Long id, String code, String name, String description, DataScope dataScope,
                            boolean builtIn, boolean lowest, Set<PermissionCode> permissions,
                            long memberCount, LocalDateTime createdAt, LocalDateTime updatedAt,
                            Integer version) {}
    public record CreateCommand(String code, String name, String description, DataScope dataScope,
                                Set<PermissionCode> permissions) {}
    public record UpdateCommand(String name, String description, DataScope dataScope,
                                Set<PermissionCode> permissions, int version) {}
}
