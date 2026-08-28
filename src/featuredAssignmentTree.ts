import type { Campus, CampusAssignmentUser, EntityId } from './types'

/**
 * 「谁需要提交」的树形指派。
 *
 * <p>树是**不级联**的：勾选校区节点和勾选它下面的某个人是两件不同的事。校区指派的
 * 含义是"该校区的全部负责人，包括之后新增的"，而点名只针对当下这个人；如果让父节点
 * 自动勾上全部子节点，这个区别就在界面上消失了，保存下去也会退化成一串当时的人名。
 * 所以校区与个人各自独立勾选，由 {@link checkedKeysToSelection} 分别还原成后端的
 * {@code campusIds} 与 {@code userIds}。</p>
 *
 * <p>一位负责人可能被授权多个校区，因此同一个人会出现在多个校区节点下。节点 key 必须
 * 带上所属校区才能唯一，而选中状态按 userId 归一——在任意一处勾选，其余位置同步显示
 * 为已选。</p>
 */

export const CAMPUS_KEY_PREFIX = 'campus:'
export const USER_KEY_PREFIX = 'user:'
/** 没有任何授权校区的负责人集中在这里；这个分组本身不可勾选。 */
export const UNASSIGNED_GROUP_KEY = 'group:unassigned'

export interface AssignmentNode {
  key: string
  kind: 'CAMPUS' | 'USER' | 'GROUP'
  /** 校区节点是自己的 id；人员节点是它挂在哪个校区下（未分配时为 null）。 */
  campusId: EntityId | null
  userId: EntityId | null
  label: string
  /** 次要说明，例如权限组名。 */
  caption?: string
  children?: AssignmentNode[]
}

export interface AssignmentSelection {
  campusIds: EntityId[]
  userIds: EntityId[]
}

export function campusKey(campusId: EntityId) {
  return `${CAMPUS_KEY_PREFIX}${campusId}`
}

export function userKey(campusId: EntityId | null, userId: EntityId) {
  return `${USER_KEY_PREFIX}${campusId ?? 'none'}:${userId}`
}

/**
 * 校区在前、其下挂各自的负责人，最后是一个"未分配校区"分组。
 * 没有负责人的校区仍然保留节点：它可以被指派，之后加入的成员会自动落进来。
 */
export function buildAssignmentTree(
  campuses: Campus[], managers: CampusAssignmentUser[],
): AssignmentNode[] {
  const known = new Set(campuses.map(campus => String(campus.id)))
  const nodes: AssignmentNode[] = campuses.map(campus => ({
    key: campusKey(campus.id),
    kind: 'CAMPUS',
    campusId: campus.id,
    userId: null,
    label: campus.name,
    children: managers
      .filter(manager => manager.campusIds?.some(id => String(id) === String(campus.id)))
      .map(manager => ({
        key: userKey(campus.id, manager.id),
        kind: 'USER' as const,
        campusId: campus.id,
        userId: manager.id,
        label: manager.displayName,
        caption: manager.permissionGroupName,
      })),
  }))

  // 权限组是校区范围、却还没被授予任何（现存）校区的账号。不放进来的话，
  // 部长在这个界面上就永远点不到他们，只能干等管理员补授权。
  const orphans = managers.filter(manager =>
    !manager.campusIds?.some(id => known.has(String(id))))
  if (orphans.length) {
    nodes.push({
      key: UNASSIGNED_GROUP_KEY,
      kind: 'GROUP',
      campusId: null,
      userId: null,
      label: '未分配校区',
      children: orphans.map(manager => ({
        key: userKey(null, manager.id),
        kind: 'USER' as const,
        campusId: null,
        userId: manager.id,
        label: manager.displayName,
        caption: manager.permissionGroupName,
      })),
    })
  }
  return nodes
}

/** 被选中的校区所覆盖的负责人。这些人已经要提交了，不必也不该再单独点名。 */
export function coveredUserIds(
  campusIds: EntityId[], managers: CampusAssignmentUser[],
): Set<string> {
  const selected = new Set(campusIds.map(String))
  const covered = new Set<string>()
  for (const manager of managers) {
    if (manager.campusIds?.some(id => selected.has(String(id)))) covered.add(String(manager.id))
  }
  return covered
}

/** 后端的 {campusIds, userIds} → 树的勾选状态。同一个人的多个节点一起勾上。 */
export function selectionToCheckedKeys(
  selection: AssignmentSelection, nodes: AssignmentNode[],
): string[] {
  const campuses = new Set(selection.campusIds.map(String))
  const users = new Set(selection.userIds.map(String))
  const keys: string[] = []
  for (const node of nodes) {
    if (node.kind === 'CAMPUS' && node.campusId != null && campuses.has(String(node.campusId))) {
      keys.push(node.key)
    }
    for (const child of node.children ?? []) {
      if (child.userId != null && users.has(String(child.userId))) keys.push(child.key)
    }
  }
  return keys
}

/**
 * 树的勾选状态 → 后端的 {campusIds, userIds}。人员按 userId 去重，
 * 因为同一个人可能在多个校区节点下同时被勾上。
 */
export function checkedKeysToSelection(keys: readonly (string | number | bigint)[]): AssignmentSelection {
  const campusIds: EntityId[] = []
  const userIds: EntityId[] = []
  const seenUsers = new Set<string>()
  for (const raw of keys) {
    const key = String(raw)
    if (key.startsWith(CAMPUS_KEY_PREFIX)) {
      campusIds.push(key.slice(CAMPUS_KEY_PREFIX.length))
    } else if (key.startsWith(USER_KEY_PREFIX)) {
      const userId = key.slice(key.lastIndexOf(':') + 1)
      if (!seenUsers.has(userId)) {
        seenUsers.add(userId)
        userIds.push(userId)
      }
    }
  }
  return { campusIds, userIds }
}

/**
 * 勾选校区后，把被它覆盖的人从点名列表里去掉。
 * 留着不会改变谁要提交，但会让"单独点名"看起来包含一堆其实由校区带进来的人，
 * 之后取消校区时更难看清究竟点了谁。
 */
export function pruneCoveredUsers(
  selection: AssignmentSelection, managers: CampusAssignmentUser[],
): AssignmentSelection {
  const covered = coveredUserIds(selection.campusIds, managers)
  return {
    campusIds: selection.campusIds,
    userIds: selection.userIds.filter(id => !covered.has(String(id))),
  }
}

/** 指派范围的一句话摘要，用于表单下方的即时反馈。 */
export function describeSelection(
  selection: AssignmentSelection, managers: CampusAssignmentUser[],
): string {
  if (!selection.campusIds.length && !selection.userIds.length) return '还没有选择任何提交对象'
  const covered = coveredUserIds(selection.campusIds, managers)
  const named = selection.userIds.filter(id => !covered.has(String(id))).length
  const parts: string[] = []
  if (selection.campusIds.length) parts.push(`${selection.campusIds.length} 个校区的全部负责人`)
  if (named) parts.push(`另外点名 ${named} 位负责人`)
  return `已选：${parts.join('，')}`
}
