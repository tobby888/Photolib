import type { EntityId } from './types'

/**
 * 分享页打包下载的勾选集合。
 *
 * <p>分享页的图片列表是**服务端分页**的（一页 60 张），但勾选是跨页累加的：
 * 访客在第 1 页勾了 30 张，翻到第 2 页再勾 20 张，打包下载应该拿到 50 张。
 * 所以"全选本页"只能往集合里**并入**当前这一页，不能整体替换——替换会把上一页
 * 的勾选悄悄丢掉，而翻页之后用户根本看不见那些卡片，丢了也不会有人发现。</p>
 *
 * <p>上限 {@link MAX_SHARE_BATCH} 与后端 `ProjectSharePublicController.BatchDownloadRequest`
 * 的 `@Size(max = 200)` 是同一个数，超出会被服务端直接拒掉，所以这里先截断并告诉用户。</p>
 */
export const MAX_SHARE_BATCH = 200

export interface SelectionMerge {
  selected: EntityId[]
  /** 有 id 因为到了上限没能并进来——调用方据此提示，别让勾选悄悄少一截。 */
  truncated: boolean
}

/** 把一批 id 并入已有勾选：保留原有顺序、去重、到上限为止。 */
export function mergeSelection(
  current: readonly EntityId[], incoming: readonly EntityId[], limit = MAX_SHARE_BATCH,
): SelectionMerge {
  const known = new Set(current)
  const added: EntityId[] = []
  let truncated = false
  for (const id of incoming) {
    if (known.has(id)) continue
    if (current.length + added.length >= limit) {
      truncated = true
      break
    }
    known.add(id)
    added.push(id)
  }
  return { selected: added.length ? [...current, ...added] : [...current], truncated }
}

/** 取消勾选一批 id（"取消本页"），其余页的勾选原样保留。 */
export function dropFromSelection(current: readonly EntityId[], removed: readonly EntityId[]): EntityId[] {
  const drop = new Set(removed)
  return current.filter(id => !drop.has(id))
}

/** 这一页是不是已经整页勾上了——决定按钮是"全选本页"还是"取消本页"。 */
export function isFullySelected(ids: readonly EntityId[], selected: ReadonlySet<EntityId>): boolean {
  return ids.length > 0 && ids.every(id => selected.has(id))
}
