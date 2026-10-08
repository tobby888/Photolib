package cn.photolib.doc;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.permission.PermissionCode;

import java.util.Set;

/**
 * 文档中心（文档与文件库）眼里的"这次请求是谁"。
 *
 * <p>以前读者身份只是一个 {@code boolean authenticated}；读者范围多了"指定权限组 / 指定成员"
 * 一档之后，判定还要知道账号和它所在的权限组，所以收拢成这个记录。由
 * {@code AccessTokenFilter} 填好的 principal 转换而来：</p>
 * <ul>
 *   <li>没有令牌、未改初始密码、或被强制绑定两步验证的会话——principal 为 null，按匿名处理；</li>
 *   <li>还没分配权限组的会话——principal 存在、权限为空，算"已登录"，
 *       也带着它所在的（最低）权限组 id，可以被按组或按人授予读者范围。</li>
 * </ul>
 */
public record DocReader(Long userId, Long groupId, Set<PermissionCode> permissions) {
    public static final DocReader ANONYMOUS = new DocReader(null, null, Set.of());

    public DocReader {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public static DocReader of(AuthenticatedUser user) {
        if (user == null) return ANONYMOUS;
        // 和 AccessTokenFilter 对齐：没有系统访问权的会话拿到的是空 authority，
        // 这里也不能让它凭权限组里残留的权限码（比如校区范围组还没授权校区）绕过读者范围。
        return new DocReader(user.id(), user.permissionGroupId(),
                user.hasSystemAccess() ? user.permissions() : Set.of());
    }

    public boolean authenticated() {
        return userId != null;
    }

    public boolean has(PermissionCode permission) {
        return permissions.contains(permission);
    }
}
