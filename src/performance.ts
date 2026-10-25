/**
 * 管理员面板「性能分析」的纯函数：窗口与时长选项、耗时格式化、功能名。
 * 不依赖 React 和请求层，`tests/performance.test.ts` 直接测。
 */
import type { PerformanceFeature, PerformanceSnapshot, PerformanceStopReason } from './types'

/** 实时窗口的预设（秒）。自定义最长 10 分钟。 */
export const LIVE_WINDOWS = [
  { value: 30, label: '30 秒' },
  { value: 60, label: '1 分钟' },
  { value: 180, label: '3 分钟' },
  { value: 300, label: '5 分钟' },
] as const
export const MIN_LIVE_WINDOW_SECONDS = 5
export const MAX_LIVE_WINDOW_SECONDS = 600
/** 实时窗口自动刷新间隔。 */
export const LIVE_REFRESH_MS = 5000

/** 把用户输入的自定义窗口夹到 5 秒～10 分钟之间；无效输入返回 null。 */
export function clampLiveWindow(seconds: number | null | undefined): number | null {
  if (seconds == null || !Number.isFinite(seconds)) return null
  return Math.min(MAX_LIVE_WINDOW_SECONDS, Math.max(MIN_LIVE_WINDOW_SECONDS, Math.round(seconds)))
}

export function describeWindow(seconds: number) {
  if (seconds % 60 === 0) return `${seconds / 60} 分钟`
  if (seconds > 60) return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
  return `${seconds} 秒`
}

/** 快照时长预设（分钟）。最长 72 小时，后端同样卡这个上限。 */
export const SNAPSHOT_DURATIONS = [
  { value: 30, label: '30 分钟' },
  { value: 60, label: '1 小时' },
  { value: 360, label: '6 小时' },
  { value: 720, label: '12 小时' },
  { value: 1440, label: '24 小时' },
  { value: 4320, label: '72 小时' },
] as const
export const MAX_SNAPSHOT_MINUTES = 72 * 60

/** 自定义时长（小时 + 分钟）换算成分钟并校验：1 分钟到上限之间才合法。 */
export function snapshotMinutes(hours: number | null | undefined, minutes: number | null | undefined,
  maxMinutes = MAX_SNAPSHOT_MINUTES): number | null {
  const total = Math.round((hours ?? 0) * 60 + (minutes ?? 0))
  if (!Number.isFinite(total) || total < 1 || total > maxMinutes) return null
  return total
}

export function describeMinutes(total: number) {
  const hours = Math.floor(total / 60)
  const minutes = total % 60
  if (!hours) return `${minutes} 分钟`
  return minutes ? `${hours} 小时 ${minutes} 分钟` : `${hours} 小时`
}

/** 耗时：1 秒以内显示毫秒（小于 10ms 留一位小数），以上显示秒。 */
export function formatMs(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return '—'
  if (value >= 60_000) return `${(value / 60_000).toFixed(1)} 分`
  if (value >= 1000) return `${(value / 1000).toFixed(value >= 10_000 ? 1 : 2)} 秒`
  if (value < 10) return `${value.toFixed(1)} ms`
  return `${Math.round(value)} ms`
}

/** 失败率，保留一位小数；没有调用时为 null。 */
export function errorRate(count: number, errors: number) {
  return count > 0 ? errors / count * 100 : null
}

/** 表格里显示的功能名：有操作名用操作名，否则用接口路由（去掉 /api/v1 前缀）。 */
export function featureName(feature: Pick<PerformanceFeature, 'label' | 'key'>) {
  if (feature.label) return feature.label
  if (feature.key === 'THUMBNAIL') return '生成缩略图'
  if (feature.key === 'COMPRESS') return '压缩原图'
  return feature.key.replace(' /api/v1/', ' /')
}

export const STOP_REASON_LABELS: Record<PerformanceStopReason, string> = {
  MANUAL: '手动停止',
  TIMEOUT: '到点自动停止',
  LIMIT: '事件数到上限',
}

export const SNAPSHOT_STATUS: Record<PerformanceSnapshot['status'], { text: string; color: string }> = {
  RECORDING: { text: '记录中', color: 'processing' },
  EXPORTING: { text: '正在导出', color: 'gold' },
  COMPLETED: { text: '已完成', color: 'green' },
  FAILED: { text: '导出失败', color: 'red' },
}

/** 还剩多久结束，`HH:mm:ss`；已过点返回 `00:00:00`。 */
export function remaining(plannedEndAt: string, now: number) {
  const left = Math.max(0, Math.floor((new Date(plannedEndAt).getTime() - now) / 1000))
  const hours = Math.floor(left / 3600)
  const minutes = Math.floor(left % 3600 / 60)
  const seconds = left % 60
  return [hours, minutes, seconds].map(value => String(value).padStart(2, '0')).join(':')
}

/** 记录进度（0～100）：从开始到计划结束走了多少。 */
export function progressPercent(startedAt: string, plannedEndAt: string, now: number) {
  const start = new Date(startedAt).getTime()
  const end = new Date(plannedEndAt).getTime()
  if (!(end > start)) return 100
  return Math.min(100, Math.max(0, (now - start) / (end - start) * 100))
}
