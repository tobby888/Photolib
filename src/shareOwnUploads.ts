import type { EntityId, ShareOwnUpload, ShareOwnUploadsDeleted } from './types'

/** 一次最多删多少张，与后端 `ProjectShareUploadService.MAX_DELETE_PER_REQUEST` 一致。 */
export const MAX_OWN_DELETE = 200

/** 「我传的图片」一页几张：缩略图网格四列时正好整行。 */
export const OWN_UPLOADS_PAGE_SIZE = 24

/** 「我传的图片」的筛选：关键字（标题、标签）、标签同时包含、拍摄日期两端含整天。 */
export interface OwnUploadFilters {
  keyword: string
  tags: string[]
  takenFrom: string | null
  takenTo: string | null
}

export const emptyOwnUploadFilters: OwnUploadFilters = { keyword: '', tags: [], takenFrom: null, takenTo: null }

export function hasOwnUploadFilters(filters: OwnUploadFilters): boolean {
  return !!(filters.keyword.trim() || filters.tags.length || filters.takenFrom || filters.takenTo)
}

/**
 * 勾选或取消一张。不能删的（被采用、已加入其他选题）勾不上；已经选满上限时也勾不上新的。
 * 返回新的选择；没有变化时返回原数组，方便调用方判断。
 */
export function toggleOwnSelection(selected: readonly EntityId[], photo: ShareOwnUpload,
                                   checked: boolean): EntityId[] {
  const has = selected.includes(photo.id)
  if (checked) {
    if (has || !photo.deletable || selected.length >= MAX_OWN_DELETE) return selected as EntityId[]
    return [...selected, photo.id]
  }
  return has ? selected.filter(id => id !== photo.id) : selected as EntityId[]
}

/** 本页能删的是不是都已勾上：决定「全选本页」按钮显示成「取消本页」。 */
export function pageFullySelected(selected: readonly EntityId[], photos: readonly ShareOwnUpload[]): boolean {
  const deletable = photos.filter(photo => photo.deletable)
  return deletable.length > 0 && deletable.every(photo => selected.includes(photo.id))
}

/**
 * 全选或取消本页能删的那几张。跨页的勾选保留；全选时按上限截断，多出的不勾。
 */
export function togglePageSelection(selected: readonly EntityId[], photos: readonly ShareOwnUpload[]): EntityId[] {
  const deletable = photos.filter(photo => photo.deletable).map(photo => photo.id)
  if (pageFullySelected(selected, photos)) return selected.filter(id => !deletable.includes(id))
  const next = [...selected]
  for (const id of deletable) {
    if (next.length >= MAX_OWN_DELETE) break
    if (!next.includes(id)) next.push(id)
  }
  return next
}

/** 删完之后给访客的一句话。跳过的要说原因，不然他会以为没删干净是出了错。 */
export function describeOwnDeleteOutcome(result: ShareOwnUploadsDeleted): { tone: 'success' | 'warning'; message: string } {
  const parts: string[] = []
  if (result.deleted) parts.push(`已删除 ${result.deleted} 张`)
  if (result.alreadyDeleted) parts.push(`${result.alreadyDeleted} 张之前已经删掉了`)
  if (result.skipped) parts.push(`${result.skipped} 张没有删：已被采用、已加入其他选题或还在处理中`)
  if (!parts.length) return { tone: 'warning', message: '没有删除任何图片' }
  return { tone: result.skipped ? 'warning' : 'success', message: parts.join('；') }
}

/**
 * 删完之后该回到哪一页：当前页删空了且不是第一页，就退一页，免得停在一张空页上。
 */
export function pageAfterDelete(page: number, total: number, deleted: number, pageSize = OWN_UPLOADS_PAGE_SIZE): number {
  const remaining = Math.max(total - deleted, 0)
  const lastPage = Math.max(Math.ceil(remaining / pageSize), 1)
  return Math.min(page, lastPage)
}
