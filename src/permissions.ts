import type { PermissionCode, User } from './types'

export function hasPermission(user: User | null | undefined, permission: PermissionCode) {
  return user?.permissions?.includes(permission) === true
}

export function hasAnyPermission(user: User | null | undefined, ...permissions: PermissionCode[]) {
  return permissions.some(permission => hasPermission(user, permission))
}

export function hasSystemAccess(user: User | null | undefined) {
  return Boolean(user) && (user?.permissionGroupCode === 'ADMIN' || user?.dataScope !== 'NONE')
}
