import type { MemberStats } from './types'

/**
 * 统计面板「成员核算」页签的筛选与被引分析。成员统计一次取回整张表（人数是一个部门的量级），
 * 筛选在前端做，改条件立刻出结果；口径（选题结束时间落区间、只算已确认工时）仍由后端决定。
 */

export type WorklogPresence = 'all' | 'with' | 'without'

export interface MemberFilter {
  /** 姓名或学号，包含匹配，不分大小写。 */
  keyword: string
  /** 校区名；跨校区的人（「甲校区、乙校区」）在任一校区下都算。 */
  campus?: string
  /** 被引张数下限 / 上限，两端都含；空表示不限。 */
  citedMin: number | null
  citedMax: number | null
  /** 有没有已确认工时：只有被引、没报工时的人工时为 0。 */
  worklog: WorklogPresence
}

export const EMPTY_FILTER: MemberFilter = { keyword: '', citedMin: null, citedMax: null, worklog: 'all' }

export const isFiltering = (filter: MemberFilter) => filter.keyword.trim() !== '' || filter.campus != null
  || filter.citedMin != null || filter.citedMax != null || filter.worklog !== 'all'

export const campusesOf = (member: MemberStats) => (member.campus || '').split('、').filter(Boolean)

export function campusOptions(members: readonly MemberStats[]): string[] {
  return [...new Set(members.flatMap(campusesOf))].sort((a, b) => a.localeCompare(b, 'zh-CN'))
}

/** 除被引张数以外的条件：分布图用它，点一根柱子改的是被引范围，柱子本身不能跟着变。 */
function matchesOthers(member: MemberStats, filter: MemberFilter) {
  const keyword = filter.keyword.trim().toLowerCase()
  if (keyword && !member.displayName.toLowerCase().includes(keyword)
    && !member.studentId.toLowerCase().includes(keyword)) return false
  if (filter.campus != null && !campusesOf(member).includes(filter.campus)) return false
  if (filter.worklog === 'with' && member.totalMinutes <= 0) return false
  if (filter.worklog === 'without' && member.totalMinutes > 0) return false
  return true
}

const inCitedRange = (count: number, min: number | null, max: number | null) =>
  (min == null || count >= min) && (max == null || count <= max)

export function filterMembers(members: readonly MemberStats[], filter: MemberFilter): MemberStats[] {
  return members.filter(member => matchesOthers(member, filter)
    && inCitedRange(member.adoptedCount, filter.citedMin, filter.citedMax))
}

export interface CitedSummary {
  memberCount: number
  citedTotal: number
  /** 有被引的人数。 */
  citedMembers: number
  /** 有被引但没报工时的人数：核算时要单独核对的那一批。 */
  citedWithoutWorklog: number
  totalMinutes: number
  /** 人均被引（只按有被引的人平均，没被引的人不摊薄），保留一位小数。 */
  averageCited: number
  /** 平均每张被引图片对应多少分钟工时；没有被引时为 null。 */
  minutesPerCited: number | null
}

export function summarize(members: readonly MemberStats[]): CitedSummary {
  const citedTotal = members.reduce((sum, member) => sum + member.adoptedCount, 0)
  const totalMinutes = members.reduce((sum, member) => sum + member.totalMinutes, 0)
  const cited = members.filter(member => member.adoptedCount > 0)
  return {
    memberCount: members.length,
    citedTotal,
    citedMembers: cited.length,
    citedWithoutWorklog: cited.filter(member => member.totalMinutes <= 0).length,
    totalMinutes,
    averageCited: cited.length ? Math.round(citedTotal / cited.length * 10) / 10 : 0,
    minutesPerCited: citedTotal ? Math.round(totalMinutes / citedTotal) : null,
  }
}

export interface CitedBucket {
  key: string
  label: string
  min: number
  max: number | null
  count: number
}

const BUCKETS: Omit<CitedBucket, 'count'>[] = [
  { key: '0', label: '0 张', min: 0, max: 0 },
  { key: '1-2', label: '1 ~ 2 张', min: 1, max: 2 },
  { key: '3-5', label: '3 ~ 5 张', min: 3, max: 5 },
  { key: '6-10', label: '6 ~ 10 张', min: 6, max: 10 },
  { key: '11-20', label: '11 ~ 20 张', min: 11, max: 20 },
  { key: '21+', label: '21 张以上', min: 21, max: null },
]

/** 被引张数分布：每一档有几个人。按被引以外的条件筛过，见 {@link matchesOthers}。 */
export function citedDistribution(members: readonly MemberStats[], filter: MemberFilter): CitedBucket[] {
  const scoped = members.filter(member => matchesOthers(member, filter))
  return BUCKETS.map(bucket => ({
    ...bucket,
    count: scoped.filter(member => inCitedRange(member.adoptedCount, bucket.min, bucket.max)).length,
  }))
}

export const bucketSelected = (bucket: CitedBucket, filter: MemberFilter) =>
  filter.citedMin === bucket.min && filter.citedMax === bucket.max

/** 平均每张被引图片的工时，表格和导出共用。没有被引的人没有这个数。 */
export const minutesPerCited = (member: MemberStats) =>
  member.adoptedCount > 0 ? Math.round(member.totalMinutes / member.adoptedCount) : null

const hours = (minutes: number) => (minutes / 60).toFixed(2)

export const MEMBER_CSV_HEADER = ['姓名', '学号', '校区', '被引张数', '拍摄（小时）', '修图（小时）', '合计（小时）',
  '平均每张被引工时（小时）', '备注']

export function memberCsvRows(members: readonly MemberStats[]): unknown[][] {
  return members.map(member => {
    const perCited = minutesPerCited(member)
    return [member.displayName, member.studentId, member.campus || '', member.adoptedCount,
      hours(member.shootingMinutes), hours(member.retouchingMinutes), hours(member.totalMinutes),
      perCited == null ? '' : hours(perCited),
      member.totalMinutes <= 0 && member.adoptedCount > 0 ? '有被引、未报工时' : '']
  })
}
