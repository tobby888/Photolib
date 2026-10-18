import type { EntityId, PageData } from './types'

/**
 * 图库（以及选题详情页「从图库添加图片」）里的批量勾选。
 *
 * 「筛选结果全选」会一口气选上几百张，而后端每个批量接口（打包下载、批量标签、批量删除、
 * 加入选题）都是 `@Size(max = 200)`。所以勾选上限放宽到 {@link MAX_LIBRARY_SELECTION}，
 * 提交时由 {@link runInChunks} 按 {@link BATCH_REQUEST_LIMIT} 一批一批地发。
 * 打包下载是例外：一个 ZIP 就是一个任务，拆成几个 ZIP 会让浏览器连着弹好几次下载，
 * 所以超过 200 张时让用户先收窄，而不是悄悄只打包前 200 张。
 */
export const MAX_LIBRARY_SELECTION = 1000

/** 后端批量接口一次最多接受多少张，与 `@Size(max = 200) List<@NotNull Long> photoIds` 对齐。 */
export const BATCH_REQUEST_LIMIT = 200

/** 「筛选结果全选」逐页取数时的页长，等于 `GET /photos` 的 pageSize 上限。 */
export const SELECT_ALL_PAGE_SIZE = 100

export function chunk<T>(items: readonly T[], size = BATCH_REQUEST_LIMIT): T[][] {
  if (size < 1) throw new Error('chunk size must be positive')
  const chunks: T[][] = []
  for (let index = 0; index < items.length; index += size) chunks.push(items.slice(index, index + size))
  return chunks
}

export interface ChunkRunResult {
  /** 成功提交了多少条（按批累加，失败那一批不算）。 */
  done: number
  /** 第一批失败的原因；之后的批次不再发——前面的失败多半意味着后面也会失败。 */
  error?: Error
}

/**
 * 按批顺序提交。顺序而不是并发：批量删除要过两步验证，第一批弹了验证之后，
 * 后面几批沿用同一次验证；并发的话会同时弹出好几个验证框。
 */
export async function runInChunks<T>(
  items: readonly T[], run: (batch: T[]) => Promise<void>, size = BATCH_REQUEST_LIMIT,
): Promise<ChunkRunResult> {
  let done = 0
  for (const batch of chunk(items, size)) {
    try {
      await run(batch)
    } catch (reason) {
      return { done, error: reason instanceof Error ? reason : new Error(String(reason)) }
    }
    done += batch.length
  }
  return { done }
}

export interface MergeResult<T> {
  selected: T[]
  /** 这次实际新加进来几张。 */
  added: number
  /** 有图片因为到了上限没能并进来——调用方据此提示，别让勾选悄悄少一截。 */
  truncated: boolean
}

/** 把一批图片并入已有勾选：保留原有顺序、按 id 去重、到上限为止。已勾的图片换成新取回的那份（版本号、标签更新）。 */
export function mergeById<T extends { id: EntityId }>(
  current: readonly T[], incoming: readonly T[], limit = MAX_LIBRARY_SELECTION,
): MergeResult<T> {
  const fresh = new Map(incoming.map(item => [String(item.id), item]))
  const known = new Set(current.map(item => String(item.id)))
  const selected = current.map(item => fresh.get(String(item.id)) ?? item)
  let added = 0
  let truncated = false
  for (const item of incoming) {
    const key = String(item.id)
    if (known.has(key)) continue
    if (selected.length >= limit) {
      truncated = true
      break
    }
    known.add(key)
    selected.push(item)
    added += 1
  }
  return { selected, added, truncated }
}

/** 反选本页：本页勾着的取消、没勾的勾上；其他页的勾选原样保留。 */
export function invertPage<T extends { id: EntityId }>(
  current: readonly T[], page: readonly T[], limit = MAX_LIBRARY_SELECTION,
): MergeResult<T> {
  const pageIds = new Set(page.map(item => String(item.id)))
  const selectedIds = new Set(current.map(item => String(item.id)))
  const kept = current.filter(item => !pageIds.has(String(item.id)))
  return mergeById(kept, page.filter(item => !selectedIds.has(String(item.id))), limit)
}

export interface CollectedPages<T> {
  items: T[]
  /** 筛选结果的总数（可能大于取回来的条数）。 */
  total: number
}

/**
 * 按当前筛选条件逐页把结果取回来，最多取到 `limit` 条。第一页先取，知道总页数之后
 * 余下的页并发取。每一页都走和列表同一个接口、同一套参数，所以「全选」选中的
 * 正是用户翻页能看到的那些图片，可见范围也由后端按同一套规则裁。
 */
export async function collectAllPages<T>(
  fetchPage: (page: number, pageSize: number) => Promise<PageData<T>>,
  limit = MAX_LIBRARY_SELECTION, pageSize = SELECT_ALL_PAGE_SIZE,
): Promise<CollectedPages<T>> {
  const first = await fetchPage(1, pageSize)
  const total = Number(first.total) || 0
  const pages = Math.min(Number(first.totalPages) || 1, Math.ceil(limit / pageSize))
  const rest = await Promise.all(Array.from({ length: Math.max(0, pages - 1) },
    (_, index) => fetchPage(index + 2, pageSize)))
  return { items: [first, ...rest].flatMap(page => page.items).slice(0, limit), total }
}
