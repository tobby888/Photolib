/**
 * 文档与文件库的"谁能看 / 谁能下载"。三档和后端 `DocVisibility` 一一对应：
 *
 * - `PUBLIC`：所有人，包括未登录的访客；
 * - `MEMBERS`：登录即可；
 * - `RESTRICTED`：必须登录，并且属于其中一个指定权限组、或者是其中一位指定成员。
 *
 * 判定只在服务端（`DocAudience.allows`），这里只负责编辑与展示。这个文件不依赖请求层和
 * 组件库，node 单测可以直接跑。
 */
import type { DocAudienceOptions, DocVisibility, EntityId } from './types'

export interface AudienceValue {
  visibility: DocVisibility
  groupIds: string[]
  userIds: string[]
}

export const AUDIENCE_LABELS: Record<DocVisibility, string> = {
  PUBLIC: '所有人',
  MEMBERS: '登录后',
  RESTRICTED: '指定成员',
}

/** 后端的 id 是数字（安全范围内）或字符串，统一成字符串比较，避免 7 和 '7' 对不上。 */
const ids = (values: readonly (EntityId | number)[] | null | undefined) =>
  Array.from(new Set((values ?? []).map(value => String(value)).filter(value => value && value !== 'null')))

/** 从节点 / 文件上取出当前的范围；只有 RESTRICTED 才带名单，其余两档名单一律为空。 */
export function audienceOf(source: {
  visibility: DocVisibility
  readerGroupIds?: readonly (EntityId | number)[] | null
  readerUserIds?: readonly (EntityId | number)[] | null
}): AudienceValue {
  const restricted = source.visibility === 'RESTRICTED'
  return {
    visibility: source.visibility,
    groupIds: restricted ? ids(source.readerGroupIds) : [],
    userIds: restricted ? ids(source.readerUserIds) : [],
  }
}

/** 和服务端 `DocAudience.normalize` 同一条规则：指定成员至少要选一个组或一个人。 */
export function audienceError(value: AudienceValue): string | null {
  if (value.visibility !== 'RESTRICTED') return null
  if (!value.groupIds.length && !value.userIds.length) return '「指定成员」至少要选一个权限组或一位成员'
  if (value.groupIds.length > 50) return '最多指定 50 个权限组'
  if (value.userIds.length > 500) return '最多单独指定 500 位成员，人更多时请改用权限组'
  return null
}

/** 发给后端的请求体：名单只在 RESTRICTED 时带上，免得旧名单在别的档位下"复活"。 */
export function audiencePayload(value: AudienceValue) {
  const restricted = value.visibility === 'RESTRICTED'
  return {
    visibility: value.visibility,
    groupIds: restricted ? value.groupIds : [],
    userIds: restricted ? value.userIds : [],
  }
}

export function sameAudience(left: AudienceValue, right: AudienceValue) {
  const same = (a: string[], b: string[]) => a.length === b.length && a.every(item => b.includes(item))
  return left.visibility === right.visibility && same(left.groupIds, right.groupIds)
    && same(left.userIds, right.userIds)
}

/** 接口返回 → 统一成字符串 id 的候选项；缺字段时给空列表，不让选择器崩掉。 */
export function normalizeAudienceOptions(raw: unknown): DocAudienceOptions {
  const value = (typeof raw === 'object' && raw !== null ? raw : {}) as Partial<DocAudienceOptions>
  return {
    groups: (value.groups ?? []).map(group => ({ ...group, id: String(group.id) })),
    users: (value.users ?? []).map(user => ({
      ...user,
      id: String(user.id),
      permissionGroupId: user.permissionGroupId == null ? null : String(user.permissionGroupId),
    })),
  }
}

/**
 * 一句话描述：「所有人」「登录后」「部长、校区负责人 及 张三 等 3 人」。
 * 名单里的 id 在候选项里找不到时（组被删、人被删）按"已删除"计数，不静默吞掉。
 */
export function describeAudience(value: AudienceValue, options?: DocAudienceOptions | null): string {
  if (value.visibility !== 'RESTRICTED') return AUDIENCE_LABELS[value.visibility]
  const groupNames = new Map((options?.groups ?? []).map(group => [String(group.id), group.name]))
  const userNames = new Map((options?.users ?? []).map(user => [String(user.id), user.displayName]))
  const parts: string[] = []
  if (value.groupIds.length) {
    const names = value.groupIds.map(id => groupNames.get(id)).filter(Boolean) as string[]
    const missing = value.groupIds.length - names.length
    parts.push(names.length ? names.slice(0, 3).join('、') + (names.length > 3 ? ` 等 ${names.length} 个组` : '')
      : `${value.groupIds.length} 个权限组`)
    if (names.length && missing) parts.push(`${missing} 个已删除的组`)
  }
  if (value.userIds.length) {
    const names = value.userIds.map(id => userNames.get(id)).filter(Boolean) as string[]
    parts.push(names.length
      ? names.slice(0, 3).join('、') + (value.userIds.length > 3 ? ` 等 ${value.userIds.length} 人` : '')
      : `${value.userIds.length} 位成员`)
  }
  return parts.length ? `仅 ${parts.join(' 及 ')}` : AUDIENCE_LABELS.RESTRICTED
}

/** 文件大小的简短写法：列表里用，比上传限额那套更紧凑。 */
export function formatFileSize(bytes: number) {
  if (!Number.isFinite(bytes) || bytes < 0) return '-'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KiB', 'MiB', 'GiB', 'TiB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value >= 100 ? Math.round(value) : value.toFixed(1).replace(/\.0$/, '')} ${units[unit]}`
}
