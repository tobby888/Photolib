/**
 * 数据面板的指标目录、默认布局和几个纯函数（排序、格式化、阈值）。
 *
 * 数字都来自一次 `GET /admin-dashboard/metrics` 快照；这里只决定「一个指标 key 从快照的
 * 哪里取、叫什么、怎么显示」。布局存在后端，但后端只校验形状（见
 * `DashboardLayoutService`），指标 key 以这里为准：目录里没有的 key 在面板上显示为
 * 「指标已下线」，不会让整块面板渲染失败。
 *
 * 不依赖 React 和请求层，node 单测直接跑。
 */
import type {
  DashboardLayout, DashboardMetrics, DashboardWidget, DashboardWidgetSize, DashboardWidgetType,
  TrafficPoint, TrafficRange,
} from './types'

export type MetricKind = 'count' | 'bytes' | 'percent' | 'qps' | 'ms' | 'duration' | 'decimal'
export type MetricGroup = '用户' | '流量' | '内容' | '运行'

export interface MetricDefinition {
  key: string
  label: string
  group: MetricGroup
  kind: MetricKind
  description: string
  /** 取不到（业务计数查库失败、MXBean 不支持）时返回 null，面板显示「—」。 */
  read: (metrics: DashboardMetrics) => number | null
}

const ratio = (part: number, whole: number) => whole > 0 ? part / whole * 100 : null
const percentOf = (value: number | null | undefined) => value == null ? null : value * 100

export const METRICS: readonly MetricDefinition[] = [
  { key: 'users.total', label: '注册用户', group: '用户', kind: 'count',
    description: '系统里的全部账号（不含已删除）。', read: m => m.business?.users.total ?? null },
  { key: 'users.enabled', label: '启用账号', group: '用户', kind: 'count',
    description: '当前可以登录的账号数。', read: m => m.business?.users.enabled ?? null },
  { key: 'users.newLast7Days', label: '近 7 天新注册', group: '用户', kind: 'count',
    description: '最近 7 天（含今天）新建的账号。', read: m => m.business?.users.newLast7Days ?? null },
  { key: 'users.newLast30Days', label: '近 30 天新注册', group: '用户', kind: 'count',
    description: '最近 30 天（含今天）新建的账号。', read: m => m.business?.users.newLast30Days ?? null },
  { key: 'users.pendingRegistrations', label: '待审核注册', group: '用户', kind: 'count',
    description: '持注册码提交、还没审核的申请。', read: m => m.business?.users.pendingRegistrations ?? null },
  { key: 'users.liveSessions', label: '有效登录会话', group: '用户', kind: 'count',
    description: '没有退出、也没过期的登录会话；一个人多台设备算多个。',
    read: m => m.business?.users.liveSessions ?? null },
  { key: 'users.online5m', label: '在线用户', group: '用户', kind: 'count',
    description: '最近 5 分钟内访问过系统的账号数。', read: m => m.activeUsers.last5Minutes },
  { key: 'users.online1h', label: '近 1 小时活跃用户', group: '用户', kind: 'count',
    description: '最近 1 小时内访问过系统的账号数。', read: m => m.activeUsers.lastHour },
  { key: 'users.online24h', label: '近 24 小时活跃用户', group: '用户', kind: 'count',
    description: '最近 24 小时内访问过系统的账号数（服务重启后重新计）。', read: m => m.activeUsers.last24Hours },

  { key: 'traffic.qps', label: '实时 QPS', group: '流量', kind: 'qps',
    description: '最近 10 秒平均每秒处理的请求数。', read: m => m.traffic.qps },
  { key: 'traffic.peakQps', label: '近 1 小时峰值 QPS', group: '流量', kind: 'qps',
    description: '最近 1 小时里最忙的那一秒处理的请求数。', read: m => m.traffic.peakQpsLastHour },
  { key: 'traffic.requestsLastMinute', label: '近 1 分钟请求', group: '流量', kind: 'count',
    description: '最近 60 秒处理的请求数。', read: m => m.traffic.lastMinute.requests },
  { key: 'traffic.requestsLastHour', label: '近 1 小时请求', group: '流量', kind: 'count',
    description: '最近 1 小时处理的请求数。', read: m => m.traffic.lastHour.requests },
  { key: 'traffic.requests24h', label: '近 24 小时请求', group: '流量', kind: 'count',
    description: '最近 24 小时处理的请求数（服务重启后重新计）。', read: m => m.traffic.last24Hours.requests },
  { key: 'traffic.bytesOutLastHour', label: '近 1 小时出站流量', group: '流量', kind: 'bytes',
    description: '最近 1 小时服务器发出的响应体字节数。', read: m => m.traffic.lastHour.bytesOut },
  { key: 'traffic.bytesOut24h', label: '近 24 小时出站流量', group: '流量', kind: 'bytes',
    description: '最近 24 小时服务器发出的响应体字节数。', read: m => m.traffic.last24Hours.bytesOut },
  { key: 'traffic.bytesInLastHour', label: '近 1 小时入站流量', group: '流量', kind: 'bytes',
    description: '最近 1 小时收到的请求体字节数（上传为主）。', read: m => m.traffic.lastHour.bytesIn },
  { key: 'traffic.bytesIn24h', label: '近 24 小时入站流量', group: '流量', kind: 'bytes',
    description: '最近 24 小时收到的请求体字节数。', read: m => m.traffic.last24Hours.bytesIn },
  { key: 'traffic.serverErrorRate', label: '5xx 错误率', group: '流量', kind: 'percent',
    description: '最近 1 小时返回 5xx 的请求占比。',
    read: m => m.traffic.lastHour.requests ? ratio(m.traffic.lastHour.serverErrors, m.traffic.lastHour.requests) : 0 },
  { key: 'traffic.clientErrorRate', label: '4xx 占比', group: '流量', kind: 'percent',
    description: '最近 1 小时返回 4xx 的请求占比（未登录、无权限、找不到等）。',
    read: m => m.traffic.lastHour.requests ? ratio(m.traffic.lastHour.clientErrors, m.traffic.lastHour.requests) : 0 },
  { key: 'traffic.avgLatency', label: '平均响应时间', group: '流量', kind: 'ms',
    description: '最近 1 小时每个请求的平均处理耗时。', read: m => m.traffic.lastHour.avgLatencyMs },
  { key: 'traffic.maxLatency', label: '最慢响应', group: '流量', kind: 'ms',
    description: '最近 1 小时处理最慢的那个请求的耗时。', read: m => m.traffic.lastHour.maxLatencyMs },
  { key: 'traffic.requestsSinceStart', label: '启动以来请求', group: '流量', kind: 'count',
    description: '服务这次启动以来处理的请求总数。', read: m => m.traffic.sinceStart.requests },

  { key: 'content.photos', label: '图片总数', group: '内容', kind: 'count',
    description: '图库里可用的图片。', read: m => m.business?.content.photos ?? null },
  { key: 'content.photoBytes', label: '图片占用空间', group: '内容', kind: 'bytes',
    description: '图库里可用图片的原图总大小。', read: m => m.business?.content.photoBytes ?? null },
  { key: 'content.photosToday', label: '今日新增图片', group: '内容', kind: 'count',
    description: '今天（北京时间）进入图库的图片。', read: m => m.business?.content.photosToday ?? null },
  { key: 'content.projects', label: '选题总数', group: '内容', kind: 'count',
    description: '全部选题项目。', read: m => m.business?.content.projects ?? null },
  { key: 'content.activeProjects', label: '进行中选题', group: '内容', kind: 'count',
    description: '状态为进行中的选题。', read: m => m.business?.content.activeProjects ?? null },
  { key: 'content.openRequests', label: '进行中需求', group: '内容', kind: 'count',
    description: '已发布、已接单或已提交、还没结束的图片需求。', read: m => m.business?.content.openRequests ?? null },
  { key: 'content.unresolvedAlerts', label: '未处理告警', group: '内容', kind: 'count',
    description: '系统记下、还没标记处理的管理员告警。', read: m => m.business?.content.unresolvedAlerts ?? null },

  { key: 'runtime.uptime', label: '运行时长', group: '运行', kind: 'duration',
    description: '后端服务这次启动到现在的时长。', read: m => m.runtime.uptimeMs },
  { key: 'runtime.heapUsage', label: '堆内存使用率', group: '运行', kind: 'percent',
    description: 'JVM 堆内存已用 / 上限。', read: m => ratio(m.runtime.heapUsed, m.runtime.heapMax) },
  { key: 'runtime.heapUsed', label: '堆内存已用', group: '运行', kind: 'bytes',
    description: 'JVM 堆内存已用字节数。', read: m => m.runtime.heapUsed },
  { key: 'runtime.processCpu', label: '进程 CPU', group: '运行', kind: 'percent',
    description: '后端进程占用的 CPU（按全部核心折算）。', read: m => percentOf(m.runtime.processCpu) },
  { key: 'runtime.systemCpu', label: '系统 CPU', group: '运行', kind: 'percent',
    description: '整台服务器的 CPU 使用率。', read: m => percentOf(m.runtime.systemCpu) },
  { key: 'runtime.memoryUsage', label: '系统内存使用率', group: '运行', kind: 'percent',
    description: '整台服务器的物理内存使用率。',
    read: m => m.runtime.memoryTotal && m.runtime.memoryFree != null
      ? ratio(m.runtime.memoryTotal - m.runtime.memoryFree, m.runtime.memoryTotal) : null },
  { key: 'runtime.diskUsage', label: '磁盘使用率', group: '运行', kind: 'percent',
    description: '应用工作目录所在磁盘（本地存储、临时文件和备份都在这里）。',
    read: m => ratio(m.runtime.diskTotal - m.runtime.diskUsable, m.runtime.diskTotal) },
  { key: 'runtime.diskFree', label: '磁盘剩余空间', group: '运行', kind: 'bytes',
    description: '应用工作目录所在磁盘还能用的空间。', read: m => m.runtime.diskUsable },
  { key: 'runtime.threads', label: '线程数', group: '运行', kind: 'count',
    description: 'JVM 当前的活动线程数。', read: m => m.runtime.threads },
  { key: 'runtime.loadAverage', label: '系统负载', group: '运行', kind: 'decimal',
    description: '最近 1 分钟的系统平均负载（Windows 上取不到）。', read: m => m.runtime.loadAverage ?? null },
]

export const METRIC_GROUPS: readonly MetricGroup[] = ['用户', '流量', '内容', '运行']

export interface SeriesDefinition {
  key: string
  label: string
  kind: MetricKind
  /** traffic：按 5 分钟 / 1 小时 / 24 小时的时间格；daily：最近 30 天每天一格。 */
  source: 'traffic' | 'daily'
  description: string
}

export const SERIES: readonly SeriesDefinition[] = [
  { key: 'traffic.requests', label: '请求量', kind: 'count', source: 'traffic', description: '每格处理的请求数。' },
  { key: 'traffic.qps', label: 'QPS', kind: 'qps', source: 'traffic', description: '每格平均每秒的请求数。' },
  { key: 'traffic.errors', label: '错误请求', kind: 'count', source: 'traffic', description: '每格返回 4xx 和 5xx 的请求数。' },
  { key: 'traffic.serverErrors', label: '5xx 错误', kind: 'count', source: 'traffic', description: '每格返回 5xx 的请求数。' },
  { key: 'traffic.bytesOut', label: '出站流量', kind: 'bytes', source: 'traffic', description: '每格发出的响应体字节数。' },
  { key: 'traffic.bytesIn', label: '入站流量', kind: 'bytes', source: 'traffic', description: '每格收到的请求体字节数。' },
  { key: 'traffic.latency', label: '平均响应时间', kind: 'ms', source: 'traffic', description: '每格请求的平均处理耗时。' },
  { key: 'users.daily', label: '每日新注册', kind: 'count', source: 'daily', description: '最近 30 天每天新建的账号数。' },
  { key: 'content.dailyPhotos', label: '每日新增图片', kind: 'count', source: 'daily', description: '最近 30 天每天进入图库的图片数。' },
]

export const TRAFFIC_RANGES: readonly { value: TrafficRange; label: string }[] = [
  { value: '5m', label: '近 5 分钟' },
  { value: '1h', label: '近 1 小时' },
  { value: '24h', label: '近 24 小时' },
]

export const REFRESH_INTERVALS = [0, 5, 10, 30, 60] as const
export const MAX_WIDGETS = 40

export const WIDGET_TYPES: readonly {
  type: DashboardWidgetType; label: string; description: string; size: DashboardWidgetSize
}[] = [
  { type: 'stat', label: '数值卡片', description: '一个指标的当前值，可设告警阈值。', size: 'small' },
  { type: 'gauge', label: '占用率', description: '百分比指标的进度条，可设告警阈值。', size: 'small' },
  { type: 'trend', label: '趋势图', description: '流量或每日新增的变化曲线。', size: 'medium' },
  { type: 'status', label: '状态码分布', description: '近 1 小时各类响应状态的数量。', size: 'medium' },
  { type: 'endpoints', label: '热门接口', description: '启动以来调用最多的接口及其耗时。', size: 'medium' },
  { type: 'shortcuts', label: '快捷入口', description: '常用管理页面的跳转按钮。', size: 'medium' },
  { type: 'note', label: '便签', description: '一段自己写的文字，比如值班安排。', size: 'small' },
]

export const WIDGET_SIZES: readonly { value: DashboardWidgetSize; label: string }[] = [
  { value: 'small', label: '小（1/4 行）' },
  { value: 'medium', label: '中（半行）' },
  { value: 'large', label: '大（整行）' },
]

export const metricOf = (key: string | undefined) => METRICS.find(metric => metric.key === key)
export const seriesOf = (key: string | undefined) => SERIES.find(series => series.key === key)
export const widgetTypeOf = (type: DashboardWidgetType) => WIDGET_TYPES.find(item => item.type === type)

/** 某种小面板能选哪些指标：占用率只接受百分比，趋势图用曲线目录。 */
export function metricChoices(type: DashboardWidgetType): { key: string; label: string; group: string }[] {
  if (type === 'trend') return SERIES.map(series => ({
    key: series.key, label: series.label, group: series.source === 'traffic' ? '流量' : '每日新增',
  }))
  if (type === 'gauge') return METRICS.filter(metric => metric.kind === 'percent')
    .map(metric => ({ key: metric.key, label: metric.label, group: metric.group }))
  if (type === 'stat') return METRICS.map(metric => ({ key: metric.key, label: metric.label, group: metric.group }))
  return []
}

export function widgetTitle(widget: DashboardWidget) {
  if (widget.title?.trim()) return widget.title.trim()
  if (widget.type === 'trend') return seriesOf(widget.metric)?.label ?? '趋势图'
  if (widget.type === 'stat' || widget.type === 'gauge') return metricOf(widget.metric)?.label ?? '指标'
  return widgetTypeOf(widget.type)?.label ?? '小面板'
}

export interface ChartPoint {
  label: string
  value: number
}

const pad = (value: number) => String(value).padStart(2, '0')

/** 时间格的标签。服务器在上海时区，浏览器也按本地时间显示——两者一致时读起来最顺。 */
function timeLabel(time: number, range: TrafficRange) {
  const date = new Date(time)
  const clock = `${pad(date.getHours())}:${pad(date.getMinutes())}`
  return range === '5m' ? `${clock}:${pad(date.getSeconds())}` : clock
}

function trafficValue(key: string, point: TrafficPoint) {
  switch (key) {
    case 'traffic.requests': return point.requests
    case 'traffic.qps': return point.qps
    case 'traffic.errors': return point.clientErrors + point.serverErrors
    case 'traffic.serverErrors': return point.serverErrors
    case 'traffic.bytesOut': return point.bytesOut
    case 'traffic.bytesIn': return point.bytesIn
    case 'traffic.latency': return point.avgLatencyMs
    default: return 0
  }
}

/** 趋势图的数据点；指标已下线或业务计数暂时取不到时返回 null。 */
export function seriesPoints(metrics: DashboardMetrics, key: string | undefined,
  range: TrafficRange = '1h'): ChartPoint[] | null {
  const definition = seriesOf(key)
  if (!definition) return null
  if (definition.source === 'traffic') {
    return (metrics.traffic.series[range] ?? []).map(point => ({
      label: timeLabel(point.time, range), value: trafficValue(definition.key, point),
    }))
  }
  const daily = definition.key === 'users.daily' ? metrics.business?.dailyUsers : metrics.business?.dailyPhotos
  return daily ? daily.map(item => ({ label: item.day.slice(5), value: item.count })) : null
}

export function formatBytes(bytes: number) {
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  let value = Math.max(0, bytes)
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return unit === 0 ? `${Math.round(value)} B` : `${value >= 100 ? value.toFixed(0) : value.toFixed(1)} ${units[unit]}`
}

export function formatDuration(ms: number) {
  const minutes = Math.floor(ms / 60_000)
  const days = Math.floor(minutes / 1440)
  const hours = Math.floor(minutes % 1440 / 60)
  const rest = minutes % 60
  if (days) return `${days} 天 ${hours} 小时`
  if (hours) return `${hours} 小时 ${rest} 分`
  return `${rest} 分钟`
}

export function formatMetric(kind: MetricKind, value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return '—'
  switch (kind) {
    case 'bytes': return formatBytes(value)
    case 'percent': return `${value.toFixed(1)}%`
    case 'qps': return value < 10 ? value.toFixed(2) : value.toFixed(1)
    case 'ms': return value < 1000 ? `${Math.round(value)} ms` : `${(value / 1000).toFixed(2)} s`
    case 'duration': return formatDuration(value)
    case 'decimal': return value.toFixed(2)
    default: return Math.round(value).toLocaleString('zh-CN')
  }
}

export type ThresholdStatus = 'normal' | 'warning' | 'critical'

/** 阈值是「达到即告警」：值越大越糟。只设了一个也能用。 */
export function thresholdStatus(value: number | null | undefined, warnAt?: number, criticalAt?: number): ThresholdStatus {
  if (value == null || !Number.isFinite(value)) return 'normal'
  if (criticalAt != null && value >= criticalAt) return 'critical'
  if (warnAt != null && value >= warnAt) return 'warning'
  return 'normal'
}

export function moveWidget<T>(items: readonly T[], from: number, to: number): T[] {
  if (from === to || from < 0 || from >= items.length) return [...items]
  const target = Math.max(0, Math.min(items.length - 1, to))
  const next = [...items]
  const [moved] = next.splice(from, 1)
  next.splice(target, 0, moved)
  return next
}

let idSequence = 0
export function newWidgetId() {
  idSequence = (idSequence + 1) % 1296
  return `w${Date.now().toString(36)}${idSequence.toString(36)}`
}

export function createWidget(type: DashboardWidgetType): DashboardWidget {
  const size = widgetTypeOf(type)?.size ?? 'small'
  const base: DashboardWidget = { id: newWidgetId(), type, size }
  switch (type) {
    case 'stat': return { ...base, metric: 'users.total' }
    case 'gauge': return { ...base, metric: 'runtime.heapUsage', warnAt: 75, criticalAt: 90 }
    case 'trend': return { ...base, metric: 'traffic.requests', range: '1h' }
    case 'endpoints': return { ...base, limit: 8 }
    case 'shortcuts': return { ...base, links: ['users', 'registration-review', 'audit-logs'] }
    case 'note': return { ...base, title: '便签', text: '' }
    default: return base
  }
}

/** 第一次打开、或者点了「恢复默认」时的布局。 */
export function defaultLayout(): DashboardLayout {
  return {
    refreshSeconds: 10,
    widgets: [
      { id: 'users-total', type: 'stat', size: 'small', metric: 'users.total' },
      { id: 'users-online', type: 'stat', size: 'small', metric: 'users.online5m' },
      { id: 'traffic-qps', type: 'stat', size: 'small', metric: 'traffic.qps' },
      { id: 'traffic-peak', type: 'stat', size: 'small', metric: 'traffic.peakQps' },
      { id: 'trend-requests', type: 'trend', size: 'medium', metric: 'traffic.requests', range: '1h' },
      { id: 'trend-bytes', type: 'trend', size: 'medium', metric: 'traffic.bytesOut', range: '1h' },
      { id: 'gauge-heap', type: 'gauge', size: 'small', metric: 'runtime.heapUsage', warnAt: 75, criticalAt: 90 },
      { id: 'gauge-cpu', type: 'gauge', size: 'small', metric: 'runtime.systemCpu', warnAt: 70, criticalAt: 90 },
      { id: 'gauge-disk', type: 'gauge', size: 'small', metric: 'runtime.diskUsage', warnAt: 80, criticalAt: 95 },
      { id: 'gauge-errors', type: 'gauge', size: 'small', metric: 'traffic.serverErrorRate', warnAt: 1, criticalAt: 5 },
      { id: 'status', type: 'status', size: 'medium' },
      { id: 'endpoints', type: 'endpoints', size: 'medium', limit: 8 },
      { id: 'users-pending', type: 'stat', size: 'small', metric: 'users.pendingRegistrations', warnAt: 1 },
      { id: 'users-new', type: 'stat', size: 'small', metric: 'users.newLast7Days' },
      { id: 'content-photos', type: 'stat', size: 'small', metric: 'content.photos' },
      { id: 'content-bytes', type: 'stat', size: 'small', metric: 'content.photoBytes' },
      { id: 'trend-registrations', type: 'trend', size: 'medium', metric: 'users.daily' },
      { id: 'shortcuts', type: 'shortcuts', size: 'medium',
        links: ['users', 'registration-review', 'audit-logs', 'backups', 'upload-limits'] },
    ],
  }
}

const TYPES = new Set<DashboardWidgetType>(WIDGET_TYPES.map(item => item.type))
const SIZES = new Set<DashboardWidgetSize>(['small', 'medium', 'large'])

/**
 * 读回来的布局先过一遍：认不出的小面板类型丢掉、尺寸兜底、编号去重、数量封顶。后端存之前
 * 校验过，这一步防的是「前端改版后读到旧布局」。指标 key 不在这里剔除——留着让面板显示
 * 「指标已下线」，管理员自己决定换成什么。
 */
export function normalizeLayout(layout: DashboardLayout | null | undefined): DashboardLayout {
  if (!layout || !Array.isArray(layout.widgets)) return defaultLayout()
  const seen = new Set<string>()
  const widgets: DashboardWidget[] = []
  for (const widget of layout.widgets) {
    if (!widget || !TYPES.has(widget.type)) continue
    let id = typeof widget.id === 'string' && widget.id ? widget.id : newWidgetId()
    while (seen.has(id)) id = newWidgetId()
    seen.add(id)
    widgets.push({ ...widget, id, size: SIZES.has(widget.size) ? widget.size : 'small' })
    if (widgets.length >= MAX_WIDGETS) break
  }
  const refreshSeconds = (REFRESH_INTERVALS as readonly number[]).includes(layout.refreshSeconds)
    ? layout.refreshSeconds : 10
  return { widgets, refreshSeconds }
}
