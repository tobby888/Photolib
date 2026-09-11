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
