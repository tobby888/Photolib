import type { EntityId, PageData } from './types'

/**
 * 图片详情页的「上一张 / 下一张」。
 *
 * 详情页本身只拿得到一张图片，顺序完全由用户进来时那一屏图库决定，所以这里按
 * 详情页 URL 里带回来的同一套筛选条件（`src/photoLibrarySearch.ts`）重新取一页
 * 列表，再在这一页里定位当前图片。翻到页边界时相邻图片在隔壁页上，此刻还不知道
 * 它的 ID（`id` 为 null），要等真正点下去再取那一页——不预取是因为绝大多数图片
 * 都不在页边界上，为它们各多发一次请求不划算。
 */
export interface PhotoNeighbor {
  /** 相邻图片所在的列表页码，跨页时与当前页不同，跳转时要一并写回 URL。 */
  page: number
  /** 相邻图片 ID；为 null 表示它在隔壁页上，需要先取那一页才能确定。 */
  id: EntityId | null
}

export interface PhotoNeighbors {
  /**
   * 当前图片是否落在这一页里。直链进来、图片刚被删除或筛选条件已经变了都会是
   * false——这时候无从谈论前后顺序，两个按钮都该禁用。
   */
  positioned: boolean
  previous: PhotoNeighbor | null
  next: PhotoNeighbor | null
}

const NOT_POSITIONED: PhotoNeighbors = { positioned: false, previous: null, next: null }

/** 后端总会给 `totalPages`，但空页对象和旧响应不一定，兜底按总数算。 */
function countPages(page: PageData<unknown>): number {
  if (page.totalPages > 0) return page.totalPages
  return page.pageSize > 0 ? Math.ceil(page.total / page.pageSize) : 0
}

export function findPhotoNeighbors<T extends { id: EntityId }>(
  page: PageData<T>,
  photoId: EntityId | undefined,
): PhotoNeighbors {
  if (photoId === undefined || photoId === null) return NOT_POSITIONED
  const index = page.items.findIndex(item => String(item.id) === String(photoId))
  if (index < 0) return NOT_POSITIONED

  const currentPage = page.page > 0 ? page.page : 1
  const lastIndex = page.items.length - 1

  return {
    positioned: true,
    previous: index > 0
      ? { page: currentPage, id: page.items[index - 1].id }
      : currentPage > 1 ? { page: currentPage - 1, id: null } : null,
    next: index < lastIndex
      ? { page: currentPage, id: page.items[index + 1].id }
      : currentPage < countPages(page) ? { page: currentPage + 1, id: null } : null,
  }
}

/** 取隔壁页的第一张（往后翻）或最后一张（往前翻）。那一页恰好空了就返回 null。 */
export function pickEdgePhotoId<T extends { id: EntityId }>(
  page: PageData<T>,
  edge: 'first' | 'last',
): EntityId | null {
  const item = edge === 'first' ? page.items[0] : page.items[page.items.length - 1]
  return item ? item.id : null
}
