import type { PermissionCode, User } from './types'

export function hasPermission(user: User | null | undefined, permission: PermissionCode) {
  return user?.permissions?.includes(permission) === true
}

export function hasAnyPermission(user: User | null | undefined, ...permissions: PermissionCode[]) {
  return permissions.some(permission => hasPermission(user, permission))
}

/**
 * 能不能进选题模块。PROJECT_VIEW 只看自己接到需求的选题，PROJECT_VIEW_ALL 看全部，
 * 两者任一即可；具体能看到哪些选题由后端裁剪，前端不做二次判断。
 */
export function canViewProjects(user: User | null | undefined) {
  return hasAnyPermission(user, 'PROJECT_VIEW', 'PROJECT_VIEW_ALL')
}

export function hasSystemAccess(user: User | null | undefined) {
  return Boolean(user) && (user?.permissionGroupCode === 'ADMIN' || user?.dataScope !== 'NONE')
}

/**
 * 头像菜单里要不要出现"两步验证"、外壳要不要挂再验证框：所在权限组不是"不使用"就要。
 * 放在这里而不是 `mfa.ts`，是为了不把两步验证的接口代码拉进首屏。
 */
export const canManageTwoFactor = (user: Pick<User, 'mfa'> | null | undefined) =>
  !!user?.mfa && user.mfa.policy !== 'OFF'
