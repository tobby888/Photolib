import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  METRICS, SERIES, createWidget, defaultLayout, formatBytes, formatMetric, metricChoices, moveWidget,
  normalizeLayout, seriesPoints, thresholdStatus, widgetTitle,
} from '../src/adminDashboard.ts'
import { ADMIN_SECTIONS, adminPath, adminSectionOf, legacyAdminRedirect } from '../src/adminNav.ts'
import type { DashboardLayout, DashboardMetrics, TrafficWindow } from '../src/types.ts'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')
const readBackend = (path: string) =>
  readFile(new URL(`../backend/src/main/java/cn/photolib/${path}`, import.meta.url), 'utf8')

const windowOf = (values: Partial<TrafficWindow> = {}): TrafficWindow => ({
  requests: 0, successes: 0, redirects: 0, clientErrors: 0, serverErrors: 0,
  bytesIn: 0, bytesOut: 0, avgLatencyMs: 0, maxLatencyMs: 0, ...values,
})

function sampleMetrics(overrides: Partial<DashboardMetrics> = {}): DashboardMetrics {
  const point = (time: number, requests: number) => ({
    time, requests, qps: requests / 60, clientErrors: 1, serverErrors: 2, bytesIn: 10, bytesOut: 2048, avgLatencyMs: 12,
  })
  return {
    generatedAt: Date.UTC(2026, 8, 29, 4, 0),
    business: {
      updatedAt: 0,
      users: { total: 128, enabled: 120, newLast7Days: 6, newLast30Days: 20, pendingRegistrations: 3, liveSessions: 40 },
      content: { photos: 5000, photoBytes: 3 * 1024 ** 3, photosToday: 12, projects: 30, activeProjects: 4,
        openRequests: 9, unresolvedAlerts: 0 },
      dailyUsers: [{ day: '2026-09-28', count: 2 }, { day: '2026-09-29', count: 5 }],
      dailyPhotos: [{ day: '2026-09-29', count: 12 }],
    },
    activeUsers: { last5Minutes: 7, lastHour: 15, last24Hours: 60 },
    traffic: {
      generatedAt: 0, startedAt: 0, qps: 4.25, peakQpsLastHour: 31,
      lastMinute: windowOf({ requests: 255 }),
      lastHour: windowOf({ requests: 1000, successes: 950, clientErrors: 40, serverErrors: 10, avgLatencyMs: 35 }),
      last24Hours: windowOf({ requests: 20000 }),
      sinceStart: windowOf({ requests: 30000 }),
      series: { '5m': [], '1h': [point(Date.UTC(2026, 8, 29, 3, 59), 60), point(Date.UTC(2026, 8, 29, 4, 0), 90)], '24h': [] },
      topEndpoints: [],
    },
    runtime: {
      startedAt: 0, uptimeMs: 26 * 3600_000 + 5 * 60_000, heapUsed: 512 * 1024 ** 2, heapMax: 1024 ** 3,
      processCpu: 0.125, systemCpu: null, loadAverage: null, processors: 8, threads: 64,
      memoryTotal: 16 * 1024 ** 3, memoryFree: 4 * 1024 ** 3, diskTotal: 100, diskUsable: 25, javaVersion: '21',
    },
    ...overrides,
  }
}

test('管理员面板每一页都有独立地址，数据面板在 /admin', () => {
  assert.equal(adminPath(''), '/admin')
  assert.equal(adminPath('users'), '/admin/users')
  assert.equal(adminSectionOf('/admin')?.label, '数据面板')
  assert.equal(adminSectionOf('/admin/')?.label, '数据面板')
  assert.equal(adminSectionOf('/admin/registration-review')?.label, '注册审核')
  assert.equal(adminSectionOf('/admin/nope'), undefined)
  assert.equal(adminSectionOf('/admin/users/42'), undefined)
  assert.equal(new Set(ADMIN_SECTIONS.map(section => section.key)).size, ADMIN_SECTIONS.length)
})

test('原系统管理的页签全部搬进了管理员面板', () => {
  // 改版前 AdminPage 的全部页签 key，一个都不能丢。
  const legacyTabs = ['branding', 'site', 'users', 'registration-codes', 'registration-review', 'permissions',
    'campuses', 'two-factor', 'audit-logs', 'backups', 'upload-limits']
  for (const tab of legacyTabs) {
    assert.ok(ADMIN_SECTIONS.some(section => section.key === tab), `${tab} 没有对应的管理员面板页`)
    assert.equal(legacyAdminRedirect('/admin', `?tab=${tab}`), `/admin/${tab}`)
  }
  assert.equal(legacyAdminRedirect('/admin', ''), null)
  assert.equal(legacyAdminRedirect('/admin', '?tab=unknown'), null)
  assert.equal(legacyAdminRedirect('/admin/users', '?tab=backups'), null)
})

test('外壳为每一页都挂了路由，图库外壳只留入口', async () => {
  const [shell, app] = await Promise.all([read('AdminShell.tsx'), read('App.tsx')])
  for (const section of ADMIN_SECTIONS.filter(item => item.key)) {
    assert.match(shell, new RegExp(`<Route path="${section.key}"`), `${section.key} 没有路由`)
  }
  assert.match(shell, /<Route index element={<AdminDashboardPage \/>} \/>/)
  assert.match(shell, /返回图库面板/)
  assert.match(app, /<Route path="\/admin\/\*" element={<AdminShell \/>} \/>/)
  assert.match(app, /navigate\('\/admin'\)/)
  assert.doesNotMatch(app, /label: '系统管理'/)
  assert.doesNotMatch(app, /pages\/AdminPage/)
})

test('站内信和深链指向新地址', async () => {
  const [notifications, forward, security] = await Promise.all([
    readBackend('notification/NotificationService.java'),
    readBackend('SpaForwardController.java'),
    readBackend('auth/SecurityConfig.java'),
  ])
  assert.match(notifications, /return "\/admin\/registration-review"/)
  assert.match(forward, /"\/admin\/\{section\}"/)
  assert.match(security, /"\/admin", "\/admin\/\*"/)
})

test('默认布局只用目录里有的指标，编号不重复', () => {
  const layout = defaultLayout()
  const ids = layout.widgets.map(widget => widget.id)
  assert.equal(new Set(ids).size, ids.length)
  for (const widget of layout.widgets) {
    if (widget.type === 'trend') assert.ok(SERIES.some(series => series.key === widget.metric), widget.id)
    if (widget.type === 'stat' || widget.type === 'gauge') {
      assert.ok(METRICS.some(metric => metric.key === widget.metric), widget.id)
    }
    if (widget.type === 'shortcuts') {
      for (const link of widget.links ?? []) assert.ok(ADMIN_SECTIONS.some(section => section.key === link), link)
    }
  }
  // 用户要的几样：注册用户、流量、QPS。
  const metrics = layout.widgets.map(widget => widget.metric)
  for (const key of ['users.total', 'traffic.qps', 'traffic.bytesOut', 'traffic.requests']) {
    assert.ok(metrics.includes(key), `默认布局缺少 ${key}`)
  }
})

test('每个指标都能从快照里读出数，取不到的给 null 而不是 NaN', () => {
  const metrics = sampleMetrics()
  const read = (key: string) => METRICS.find(metric => metric.key === key)!.read(metrics)
  assert.equal(read('users.total'), 128)
  assert.equal(read('users.online5m'), 7)
  assert.equal(read('traffic.qps'), 4.25)
  assert.equal(read('traffic.serverErrorRate'), 1)
  assert.equal(read('traffic.clientErrorRate'), 4)
  assert.equal(read('runtime.heapUsage'), 50)
  assert.equal(read('runtime.processCpu'), 12.5)
  assert.equal(read('runtime.systemCpu'), null)
  assert.equal(read('runtime.memoryUsage'), 75)
  assert.equal(read('runtime.diskUsage'), 75)
  for (const metric of METRICS) {
    const value = metric.read(metrics)
    assert.ok(value === null || Number.isFinite(value), `${metric.key} 读出了 ${value}`)
  }
  // 数据库暂时查不到：业务计数一律是 null，流量照常。
  const degraded = sampleMetrics({ business: null, businessError: '数据库暂时无法访问' })
  assert.equal(METRICS.find(metric => metric.key === 'users.total')!.read(degraded), null)
  assert.equal(METRICS.find(metric => metric.key === 'traffic.qps')!.read(degraded), 4.25)
  assert.equal(seriesPoints(degraded, 'users.daily'), null)
})

test('没有请求时错误率是 0 而不是除零', () => {
  const idle = sampleMetrics()
  idle.traffic.lastHour = windowOf()
  assert.equal(METRICS.find(metric => metric.key === 'traffic.serverErrorRate')!.read(idle), 0)
})

test('趋势图的数据点按曲线取值', () => {
  const metrics = sampleMetrics()
  assert.deepEqual(seriesPoints(metrics, 'traffic.requests', '1h')?.map(point => point.value), [60, 90])
  assert.deepEqual(seriesPoints(metrics, 'traffic.errors', '1h')?.map(point => point.value), [3, 3])
  assert.deepEqual(seriesPoints(metrics, 'users.daily'), [{ label: '09-28', value: 2 }, { label: '09-29', value: 5 }])
  assert.equal(seriesPoints(metrics, 'traffic.gone'), null)
})

test('占用率小面板只能选百分比指标', () => {
  const gauge = metricChoices('gauge')
  assert.ok(gauge.length > 0)
  for (const choice of gauge) assert.equal(METRICS.find(metric => metric.key === choice.key)?.kind, 'percent')
  assert.equal(metricChoices('trend').length, SERIES.length)
  assert.deepEqual(metricChoices('note'), [])
})

test('读回的布局去掉认不出的类型、补齐编号和尺寸，已下线的指标留着', () => {
  const raw = {
    refreshSeconds: 7,
    widgets: [
      { id: 'a', type: 'stat', size: 'small', metric: 'users.retired' },
      { id: 'a', type: 'note', size: 'huge', text: 'hi' },
      { id: 'b', type: 'iframe', size: 'small' },
    ],
  } as unknown as DashboardLayout
  const layout = normalizeLayout(raw)
  assert.equal(layout.refreshSeconds, 10)
  assert.equal(layout.widgets.length, 2)
  assert.notEqual(layout.widgets[0].id, layout.widgets[1].id)
  assert.equal(layout.widgets[1].size, 'small')
  assert.equal(layout.widgets[0].metric, 'users.retired')
  assert.equal(widgetTitle(layout.widgets[0]), '指标')
  assert.deepEqual(normalizeLayout(null), defaultLayout())
})

test('调整顺序不改原数组，越界时夹在两端', () => {
  const items = ['a', 'b', 'c', 'd']
  assert.deepEqual(moveWidget(items, 0, 2), ['b', 'c', 'a', 'd'])
  assert.deepEqual(moveWidget(items, 3, 0), ['d', 'a', 'b', 'c'])
  assert.deepEqual(moveWidget(items, 1, 99), ['a', 'c', 'd', 'b'])
  assert.deepEqual(moveWidget(items, 5, 0), items)
  assert.deepEqual(items, ['a', 'b', 'c', 'd'])
})

test('阈值达到即告警，严重优先', () => {
  assert.equal(thresholdStatus(50, 75, 90), 'normal')
  assert.equal(thresholdStatus(75, 75, 90), 'warning')
  assert.equal(thresholdStatus(95, 75, 90), 'critical')
  assert.equal(thresholdStatus(95, undefined, 90), 'critical')
  assert.equal(thresholdStatus(3, 1), 'warning')
  assert.equal(thresholdStatus(null, 1, 2), 'normal')
})

test('数值按类型格式化', () => {
  assert.equal(formatMetric('count', 12345), '12,345')
  assert.equal(formatMetric('percent', 12.345), '12.3%')
  assert.equal(formatMetric('qps', 4.256), '4.26')
  assert.equal(formatMetric('qps', 31), '31.0')
  assert.equal(formatMetric('ms', 35.4), '35 ms')
  assert.equal(formatMetric('ms', 1520), '1.52 s')
  assert.equal(formatMetric('duration', 26 * 3600_000 + 5 * 60_000), '1 天 2 小时')
  assert.equal(formatMetric('duration', 90 * 60_000), '1 小时 30 分')
  assert.equal(formatMetric('count', null), '—')
  assert.equal(formatBytes(512), '512 B')
  assert.equal(formatBytes(3 * 1024 ** 3), '3.0 GiB')
  assert.equal(formatBytes(150 * 1024 ** 2), '150 MiB')
})

test('新建的小面板带着能直接用的默认值', () => {
  assert.equal(createWidget('stat').metric, 'users.total')
  assert.equal(createWidget('trend').range, '1h')
  assert.equal(createWidget('gauge').criticalAt, 90)
  assert.notEqual(createWidget('note').id, createWidget('note').id)
})
