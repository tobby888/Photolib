package cn.photolib.auth;

import cn.photolib.user.model.UserRole;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;

import java.util.Set;

public record AuthenticatedUser(
        Long id,
        String username,
        String displayName,
        UserRole role,
        Long campusId,
        boolean mustChangePassword,
        Long permissionGroupId,
        String permissionGroupCode,
        String permissionGroupName,
        DataScope dataScope,
        Set<PermissionCode> permissions,
        Set<Long> campusIds,
        String avatarUrl
) {
    public AuthenticatedUser(Long id, String username, String displayName, UserRole role,
                             Long campusId, boolean mustChangePassword) {
        this(id, username, displayName, role, campusId, mustChangePassword, null);
    }

    public AuthenticatedUser(Long id, String username, String displayName, UserRole role,
                             Long campusId, boolean mustChangePassword, String avatarUrl) {
        this(id, username, displayName, role, campusId, mustChangePassword,
                -1L, role.name(), role.name(),
                role == UserRole.CAMPUS_MANAGER ? DataScope.CAMPUS : DataScope.GLOBAL,
                legacyPermissions(role), campusId == null ? Set.of() : Set.of(campusId), avatarUrl);
    }

    public AuthenticatedUser(Long id, String username, String displayName, UserRole role,
                             Long campusId, boolean mustChangePassword, Long permissionGroupId,
                             String permissionGroupCode, String permissionGroupName,
                             DataScope dataScope, Set<PermissionCode> permissions,
                             Set<Long> campusIds) {
        this(id, username, displayName, role, campusId, mustChangePassword, permissionGroupId,
                permissionGroupCode, permissionGroupName, dataScope, permissions, campusIds, null);
    }

    private static Set<PermissionCode> legacyPermissions(UserRole role) {
        return switch (role) {
            case ADMIN -> Set.of(PermissionCode.values());
            case MINISTER -> Set.of(
                    PermissionCode.PROJECT_VIEW, PermissionCode.PROJECT_ADOPT,
                    PermissionCode.PROJECT_CREATE, PermissionCode.PROJECT_COMPLETE,
                    PermissionCode.PROJECT_DOWNLOAD, PermissionCode.PHOTO_VIEW,
                    PermissionCode.PHOTO_DELETE, PermissionCode.PHOTO_UPLOAD,
                    PermissionCode.PHOTO_DOWNLOAD, PermissionCode.REQUEST_VIEW,
                    PermissionCode.REQUEST_CREATE, PermissionCode.REQUEST_CLOSE,
                    PermissionCode.REQUEST_CONFIRM, PermissionCode.REQUEST_PHOTO_MANAGE,
                    PermissionCode.WORKLOG_CONFIRM, PermissionCode.WORKLOG_EXPORT,
                    PermissionCode.DIRECTORY_VIEW, PermissionCode.DIRECTORY_MANAGE,
                    PermissionCode.MESSAGE_SEND, PermissionCode.RECRUITMENT_VIEW,
                    PermissionCode.RECRUITMENT_PUBLISH,
                    PermissionCode.STATISTICS_DOWNLOAD, PermissionCode.MANAGER_CAMPUS_ASSIGN);
            case CAMPUS_MANAGER -> Set.of(
                    PermissionCode.PROJECT_VIEW, PermissionCode.PROJECT_ADOPT,
                    PermissionCode.PHOTO_VIEW,
                    PermissionCode.PHOTO_UPLOAD, PermissionCode.PHOTO_DOWNLOAD,
                    PermissionCode.REQUEST_VIEW, PermissionCode.REQUEST_PHOTO_MANAGE,
                    PermissionCode.WORKLOG_SUBMIT, PermissionCode.DIRECTORY_VIEW,
                    PermissionCode.DIRECTORY_MANAGE, PermissionCode.RECRUITMENT_VIEW);
        };
    }

    public boolean hasPermission(PermissionCode permission) {
        return permissions != null && permissions.contains(permission);
    }

    public boolean hasAnyPermission(PermissionCode... candidates) {
        if (permissions == null) return false;
        for (PermissionCode candidate : candidates) {
            if (permissions.contains(candidate)) return true;
        }
        return false;
    }

    public boolean isAdministrator() {
        return "ADMIN".equals(permissionGroupCode);
    }

    public boolean isCampusScoped() {
        return dataScope == DataScope.CAMPUS;
    }

    public boolean canAccessCampus(Long targetCampusId) {
        return dataScope == DataScope.GLOBAL
                || (dataScope == DataScope.CAMPUS && targetCampusId != null
                && campusIds != null && campusIds.contains(targetCampusId));
    }

    public boolean hasSystemAccess() {
        return isAdministrator()
                || dataScope == DataScope.GLOBAL
                || (dataScope == DataScope.CAMPUS && campusIds != null && !campusIds.isEmpty());
    }

    /**
     * 用于统计/导出类查询的校区范围。全局范围返回空集合表示"不加校区过滤"；
     * 校区范围永远返回非空集合，未授权任何校区时返回哨兵 -1 以命中零行。
     * 不能直接用 {@link #campusIds()}：校区范围账号的授权校区可能为空
     * （例如权限组的数据范围被从 GLOBAL 改成 CAMPUS 后成员尚未补授权校区），
     * 此时"空集合"会被下游误判为全局范围而放行全量数据。
     */
    public Set<Long> scopedCampusIds() {
        if (dataScope != DataScope.CAMPUS) {
            return Set.of();
        }
        return campusIds == null || campusIds.isEmpty() ? Set.of(-1L) : Set.copyOf(campusIds);
    }
}
