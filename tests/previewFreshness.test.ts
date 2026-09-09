import assert from 'node:assert/strict'
import test from 'node:test'
import { createResumeRefresh, PREVIEW_URL_STALE_AFTER_MS } from '../src/previewFreshness.ts'

function harness(initiallyHidden = false) {
  let clock = 1_000_000
  const counted = { refreshes: 0 }
  const resume = createResumeRefresh(() => { counted.refreshes += 1 }, {
    now: () => clock,
    initiallyHidden,
  })
  const advance = (milliseconds: number) => { clock += milliseconds }
  return { resume, advance, counted }
}

test('刷新只发生在后台待够久之后', () => {
  const { resume, advance, counted } = harness()

  resume.visibilityChanged('hidden')
  advance(PREVIEW_URL_STALE_AFTER_MS - 1)
  resume.visibilityChanged('visible')
  assert.equal(counted.refreshes, 0, '还没到阈值就刷新等于白打一次请求')

  resume.visibilityChanged('hidden')
  advance(PREVIEW_URL_STALE_AFTER_MS)
  resume.visibilityChanged('visible')
  assert.equal(counted.refreshes, 1)
})

test('阈值留在签名最坏剩余有效期之内', () => {
  // download-url-ttl 15m 减去 signature-window 5m，是一条预览地址最短的剩余
  // 有效期。阈值一旦逼近它，页面回到前台时拿到的就已经是过期地址了。
  assert.ok(PREVIEW_URL_STALE_AFTER_MS < 10 * 60 * 1000)
})

test('连着几次 hidden 不会把后台计时清零', () => {
  const { resume, advance, counted } = harness()

  resume.visibilityChanged('hidden')
  advance(PREVIEW_URL_STALE_AFTER_MS - 1)
  // 锁屏、切 App、切标签页可能各报一次 hidden。取最晚那次的话，真正待了很久的
  // 页面会被当成刚切走，于是永远不刷新——这正是要防的退化。
  resume.visibilityChanged('hidden')
  advance(1)
  resume.visibilityChanged('visible')

  assert.equal(counted.refreshes, 1)
})

test('前台连续收到 visible 不会反复刷新', () => {
  const { resume, advance, counted } = harness()

  resume.visibilityChanged('hidden')
  advance(PREVIEW_URL_STALE_AFTER_MS)
  resume.visibilityChanged('visible')
  resume.visibilityChanged('visible')
  advance(PREVIEW_URL_STALE_AFTER_MS)
  resume.visibilityChanged('visible')

  assert.equal(counted.refreshes, 1)
})

test('bfcache 恢复的页面一律刷新，前进后退的普通导航不刷', () => {
  const { resume, counted } = harness()

  resume.pageShown(false)
  assert.equal(counted.refreshes, 0, '普通导航本来就会重跑 effect，重复取一次没意义')

  resume.pageShown(true)
  assert.equal(counted.refreshes, 1)
})

test('bfcache 恢复之后不会再因为之前那次 hidden 多刷一遍', () => {
  const { resume, advance, counted } = harness()

  resume.visibilityChanged('hidden')
  advance(PREVIEW_URL_STALE_AFTER_MS)
  resume.pageShown(true)
  resume.visibilityChanged('visible')

  assert.equal(counted.refreshes, 1)
})

test('页面在后台挂载时，第一次回到前台照样按后台时长判定', () => {
  const { resume, advance, counted } = harness(true)

  advance(PREVIEW_URL_STALE_AFTER_MS)
  resume.visibilityChanged('visible')

  assert.equal(counted.refreshes, 1)
})
