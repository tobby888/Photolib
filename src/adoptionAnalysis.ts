import type { AnalysisBucket } from './worklogAnalysis'
import { tableToCsv } from './worklogCsv.ts'

/**
 * 统计面板「被引分析」的接口类型，与后端 `AdoptionAnalysisService` 一一对应。
 *
 * 两个计数别混：**被引次数**数的是被引记录（一张图被两个选题引用算两次），**被引张数**按图片去重
 * （成员核算里的「被引张数」就是它）。按选题拆开时两者相同，跨选题加总时张数会少于各选题之和。
 */

/** `ADOPTED` 按被引时间取；`PROJECT_COMPLETED` 按选题结束时间取、只看已完成选题，与成员核算同口径。 */
export type AdoptionBasis = 'ADOPTED' | 'PROJECT_COMPLETED'
export type AdoptionGranularity = 'AUTO' | 'DAY' | 'WEEK' | 'MONTH'

export const BASIS_LABELS: Record<AdoptionBasis, string> = {
  ADOPTED: '被引时间',
  PROJECT_COMPLETED: '选题结束时间',
}

export interface AdoptionBucket {
  key: string
  label: string
  adoptionCount: number
  photoCount: number
  projectCount: number
  photographerCount: number
}

export interface AdoptionProjectBucket {
  projectId: number | string
  title: string
  status: string
  completedAt: string | null
  adoptionCount: number
  photoCount: number
  photographerCount: number
  firstAdoptedAt: string
  lastAdoptedAt: string
}

export interface AdoptionPhotographerBucket {
  studentId: string
  name: string
  adoptionCount: number
  photoCount: number
  projectCount: number
  campus: string
}

export interface AdoptionRow {
  adoptionId: number | string
  adoptedAt: string
  projectId: number | string
  projectTitle: string | null
  projectStatus: string | null
  projectCompletedAt: string | null
  photoId: number | string
  photoTitle: string | null
  takenAt: string | null
  campusId: number | string | null
  campusName: string | null
  photographerStudentId: string
  photographerName: string
  adoptedByName: string | null
  remark: string | null
}

export interface AdoptionSummary {
  adoptionCount: number
  photoCount: number
  projectCount: number
  photographerCount: number
  reusedPhotoCount: number
  activeDays: number
}

export interface AdoptionAnalysisResult {
  basis: AdoptionBasis
  summary: AdoptionSummary
  trend: AdoptionBucket[]
  granularity: AdoptionGranularity
  weekdays: AdoptionBucket[]
  lags: AdoptionBucket[]
  projects: AdoptionProjectBucket[]
  photographers: AdoptionPhotographerBucket[]
  campuses: AdoptionBucket[]
  rows: AdoptionRow[]
  rowsTruncated: boolean
}

export interface AdoptionOption { value: string; label: string }
export interface AdoptionOptions {
  projects: AdoptionOption[]
  campuses: AdoptionOption[]
  photographers: AdoptionOption[]
}

export interface AdoptionQuery {
  from: string
  to: string
  basis: AdoptionBasis
  projectId?: string
  campusId?: string
  studentId?: string
  granularity?: AdoptionGranularity
}

/** 只把有值的条件放进查询串：后端把缺省当「不限」，传空字符串的 id 会解析失败。 */
export function toParams(query: AdoptionQuery): Record<string, string> {
  const params: Record<string, string> = { from: query.from, to: query.to, basis: query.basis }
  if (query.projectId) params.projectId = query.projectId
  if (query.campusId) params.campusId = query.campusId
  if (query.studentId) params.studentId = query.studentId
  if (query.granularity && query.granularity !== 'AUTO') params.granularity = query.granularity
  return params
}

/**
 * 借工时分析的柱 / 条形图来画计数：图表组件只认 `AnalysisBucket` 的形状，这里把要画的那个计数
 * 放进 `worklogCount`，序列标上单位。悬停提示另外传，不会显示成「工时条数」。
 */
export function toChartBuckets<T extends { key: string; label: string }>(
  buckets: readonly T[], value: (bucket: T) => number,
): AnalysisBucket[] {
  return buckets.map(bucket => ({
    key: bucket.key, label: bucket.label, worklogCount: value(bucket),
    shootingMinutes: 0, retouchingMinutes: 0, totalMinutes: 0, memberCount: 0,
  }))
}

/** 排行只画前 `limit` 名，其余合成一行「其余 N 个」，免得长尾把图拉得很长、又让合计对不上。 */
export function foldRanking<T>(items: readonly T[], limit: number, merge: (rest: readonly T[]) => T): T[] {
  if (items.length <= limit) return [...items]
  return [...items.slice(0, limit - 1), merge(items.slice(limit - 1))]
}

/** 日期时间只取到分钟，按本地习惯用空格分开。 */
export const formatDateTime = (value: string | null | undefined) =>
  value ? value.replace('T', ' ').slice(0, 16) : '—'

export const PROJECT_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '进行中', COMPLETED: '已完成', CANCELLED: '已取消', DRAFT: '草稿',
}

/** 被引明细导出成 CSV（BOM + 防公式注入都在 `tableToCsv` 里）。 */
export function adoptionRowsToCsv(rows: readonly AdoptionRow[]): string {
  const header = ['被引时间', '摄影师', '学号', '图片', '图片编号', '拍摄时间', '校区', '选题', '选题状态',
    '选题结束时间', '标记人', '备注']
  return tableToCsv(header, rows.map(row => [
    formatDateTime(row.adoptedAt), row.photographerName, row.photographerStudentId, row.photoTitle ?? '',
    row.photoId, formatDateTime(row.takenAt), row.campusName ?? '', row.projectTitle ?? '',
    PROJECT_STATUS_LABELS[row.projectStatus ?? ''] ?? row.projectStatus ?? '',
    row.projectCompletedAt ? formatDateTime(row.projectCompletedAt) : '', row.adoptedByName ?? '', row.remark ?? '',
  ]))
}
