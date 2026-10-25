import assert from 'node:assert/strict'
import test from 'node:test'
import {
  clampLiveWindow, describeMinutes, describeWindow, errorRate, featureName, formatMs, progressPercent, remaining,
  snapshotMinutes,
} from '../src/performance.ts'
import { ADMIN_SECTIONS, adminSectionOf } from '../src/adminNav.ts'

test('实时窗口：自定义窗口夹在 5 秒到 10 分钟之间', () => {
  assert.equal(clampLiveWindow(1), 5)
  assert.equal(clampLiveWindow(90.4), 90)
  assert.equal(clampLiveWindow(3600), 600)
  assert.equal(clampLiveWindow(null), null)
  assert.equal(clampLiveWindow(Number.NaN), null)
  assert.equal(describeWindow(30), '30 秒')
  assert.equal(describeWindow(180), '3 分钟')
  assert.equal(describeWindow(90), '1 分 30 秒')
})

test('快照时长最长 72 小时、最短 1 分钟', () => {
  assert.equal(snapshotMinutes(72, 0), 4320)
  assert.equal(snapshotMinutes(72, 1), null)
  assert.equal(snapshotMinutes(0, 0), null)
  assert.equal(snapshotMinutes(0, 1), 1)
  assert.equal(snapshotMinutes(1, 30, 60), null)
  assert.equal(describeMinutes(90), '1 小时 30 分钟')
  assert.equal(describeMinutes(4320), '72 小时')
  assert.equal(describeMinutes(45), '45 分钟')
})

test('耗时格式化与失败率', () => {
  assert.equal(formatMs(3.25), '3.3 ms')
  assert.equal(formatMs(250.4), '250 ms')
  assert.equal(formatMs(1500), '1.50 秒')
  assert.equal(formatMs(12_345), '12.3 秒')
  assert.equal(formatMs(120_000), '2.0 分')
  assert.equal(formatMs(null), '—')
  assert.equal(errorRate(0, 0), null)
  assert.equal(errorRate(200, 3), 1.5)
})

test('功能名优先用操作名，没有就用去掉前缀的路由', () => {
  assert.equal(featureName({ key: 'POST /api/v1/auth/login', label: '登录' }), '登录')
  assert.equal(featureName({ key: 'GET /api/v1/photos/{photoId}', label: null }), 'GET /photos/{photoId}')
  assert.equal(featureName({ key: 'THUMBNAIL', label: null }), '生成缩略图')
})

test('快照进度与剩余时间', () => {
  const start = '2026-10-26T10:00:00'
  const end = '2026-10-26T12:00:00'
  const middle = new Date('2026-10-26T11:00:00').getTime()
  assert.equal(progressPercent(start, end, middle), 50)
  assert.equal(progressPercent(start, end, new Date('2026-10-26T13:00:00').getTime()), 100)
  assert.equal(remaining(end, middle), '01:00:00')
  assert.equal(remaining(end, new Date('2026-10-26T12:30:00').getTime()), '00:00:00')
})

test('性能分析在管理员面板里有独立地址', () => {
  assert.equal(adminSectionOf('/admin/performance')?.label, '性能分析')
  assert.equal(ADMIN_SECTIONS.find(section => section.key === 'performance')?.group, '安全与运维')
})
