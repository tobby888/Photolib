import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { isChunkLoadError, isReloadPending, markReloadPending } from '../src/routeErrors.ts'

// Windows 上 git 会把源码检出成 CRLF，统一成 LF 再断言。
const read = async (path: string) =>
  (await readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')).replace(/\r\n/g, '\n')

test('各浏览器"页面代码没取回来"的报错都认得出来', () => {
  for (const message of [
    'Failed to fetch dynamically imported module: https://example.com/assets/NotificationsPage-abc.js',
    'error loading dynamically imported module: https://example.com/assets/NotificationsPage-abc.js',
    'Importing a module script failed.',
    'Unable to preload CSS for /assets/AvatarSettingsModal-abc.css',
  ]) {
    assert.equal(isChunkLoadError(new TypeError(message)), true, message)
  }
  assert.equal(isChunkLoadError('Failed to fetch dynamically imported module: /assets/x.js'), true)
})

test('页面自身的渲染异常不当成资源加载失败', () => {
  assert.equal(isChunkLoadError(new TypeError("Cannot read properties of undefined (reading 'length')")), false)
  assert.equal(isChunkLoadError(new Error('网络连接失败')), false)
  assert.equal(isChunkLoadError(null), false)
  assert.equal(isChunkLoadError(undefined), false)
})

test('preloadError 兜底决定重载之后，边界知道重载已经在路上', () => {
  assert.equal(isReloadPending(), false)
  markReloadPending()
  assert.equal(isReloadPending(), true)
})

test('工作台的路由包在页面级错误边界里，一页崩了不会把整个应用换成全屏错误页', async () => {
  const app = await read('App.tsx')
  const stage = app.slice(app.indexOf('<div className="route-stage" key={location.pathname}>'))
  assert.ok(stage.length > 0, '找不到按 pathname 做 key 的 route-stage')
  // 边界必须在 route-stage 里面：换一页就是一个新的边界，上一页的错误不会带过去。
  assert.match(stage, /^<div className="route-stage" key=\{location\.pathname\}>\s*<RouteErrorBoundary><Suspense/)
  assert.match(stage, /<\/Routes><\/Suspense><\/RouteErrorBoundary>\s*<\/div>/)
})

test('preloadError 兜底在整页重载前先打上标记', async () => {
  const main = await read('main.tsx')
  const handler = main.slice(main.indexOf("addEventListener('vite:preloadError'"))
  const mark = handler.indexOf('markReloadPending()')
  assert.ok(mark !== -1, 'preloadError 兜底没有调用 markReloadPending')
  assert.ok(mark > handler.indexOf('event.preventDefault()'))
  assert.ok(mark < handler.indexOf('window.location.replace('))
})
