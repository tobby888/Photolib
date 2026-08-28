import type { FeaturedCollection, FeaturedEntry } from './types'

export interface FeaturedStatusDisplay {
  label: string
  color: string
}

/**
 * 列表和详情页共用的状态文案。
 *
 * `status` 只有三个值，但用户关心的是"现在能不能填"，所以已发布的精选还要按
 * 开始/截止时间细分。判定必须和后端 `requireSubmissionOpen` 用同一套边界：
 * 开始时间含（>= 开始即可填），截止时间不含（到点即止）。
 */
export function featuredStatusDisplay(
  collection: Pick<FeaturedCollection, 'status' | 'startsAt' | 'endsAt'>,
  now: Date = new Date(),
): FeaturedStatusDisplay {
  if (collection.status === 'DRAFT') return { label: '草稿', color: 'default' }
  if (collection.status === 'CLOSED') return { label: '已截止', color: 'blue' }
  const startsAt = new Date(collection.startsAt).getTime()
  const endsAt = new Date(collection.endsAt).getTime()
  if (Number.isFinite(startsAt) && now.getTime() < startsAt) return { label: '待开始', color: 'gold' }
  // 到点但服务器的定时任务还没扫到，此时后端也已经拒绝提交，文案不能再显示"征集中"。
  if (Number.isFinite(endsAt) && now.getTime() >= endsAt) return { label: '待生成文档', color: 'orange' }
  return { label: '征集中', color: 'green' }
}

export const FEATURED_DOCUMENT_LABELS: Record<FeaturedCollection['documentStatus'], string> = {
  PENDING: '未生成',
  GENERATING: '生成中',
  READY: '可下载',
  FAILED: '生成失败',
}

export interface FeaturedChapter {
  campusId: string | null
  campusName: string
  entries: FeaturedEntry[]
}

/**
 * 按校区分章，顺序完全沿用服务端返回的顺序（校区编码 → 填报顺序 → id）。
 * Word 文档用的是同一个顺序，页面自己再排一次就会和成品文档对不上。
 */
export function groupEntriesByCampus(entries: FeaturedEntry[]): FeaturedChapter[] {
  const chapters: FeaturedChapter[] = []
  for (const entry of entries) {
    const campusId = entry.campusId == null ? null : String(entry.campusId)
    const campusName = entry.campusName || '未分配校区'
    const last = chapters[chapters.length - 1]
    // 服务端已把同校区的条目排在一起，所以只需在校区变化时开新章，不必另建索引表。
    if (last && last.campusId === campusId) last.entries.push(entry)
    else chapters.push({ campusId, campusName, entries: [entry] })
  }
  return chapters
}

/** 还能再提交几张。上限是"每人"的，与他人提交数无关。 */
export function remainingEntryQuota(collection: Pick<FeaturedCollection, 'entryLimit' | 'myEntryCount'>) {
  return Math.max(0, (collection.entryLimit ?? 0) - (collection.myEntryCount ?? 0))
}
