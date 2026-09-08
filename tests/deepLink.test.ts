import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { hashRouteForDeepLink } from '../src/deepLink.ts'

const at = (pathname: string, search = '', hash = '') => ({ pathname, search, hash })

test('moves a path-style deep link into the hash route', () => {
  // 新生拿到的就是这种链接：后端 forward 到 index.html，HashRouter 只认 hash。
  assert.equal(hashRouteForDeepLink(at('/recruitment')), '/#/recruitment')
  assert.equal(hashRouteForDeepLink(at('/docs/join-us')), '/#/docs/join-us')
})

test('keeps the query string with the route it belongs to', () => {
  assert.equal(hashRouteForDeepLink(at('/docs', '?tab=notice')), '/#/docs?tab=notice')
})

test('leaves an address that already carries a hash route alone', () => {
  // /recruitment#/login 是"被弹去登录页"之后的地址；再改写会把访客锁在那里。
  assert.equal(hashRouteForDeepLink(at('/recruitment', '', '#/login')), null)
  assert.equal(hashRouteForDeepLink(at('/', '', '#/recruitment')), null)
})

test('leaves the static entry points alone', () => {
  assert.equal(hashRouteForDeepLink(at('/')), null)
  assert.equal(hashRouteForDeepLink(at('/index.html')), null)
  // 空 hash 写成 "#" 的浏览器也不该被当成"已经有路由了"。
  assert.equal(hashRouteForDeepLink(at('/recruitment', '', '#')), '/#/recruitment')
})

test('the app boot applies the rewrite before HashRouter mounts', async () => {
  const source = await readFile(new URL('../src/main.tsx', import.meta.url), 'utf8')
  const rewriteAt = source.indexOf('hashRouteForDeepLink(window.location)')
  const renderAt = source.indexOf('ReactDOM.createRoot')

  assert.ok(rewriteAt > 0, 'main.tsx 必须调用 hashRouteForDeepLink')
  // 挂载之后再改地址就晚了：HashRouter 只在挂载时读一次 hash。
  assert.ok(rewriteAt < renderAt, '改写必须发生在渲染之前')
  assert.match(source, /history\.replaceState/)
})

test('the shell keeps authenticated-only polling away from logged-out visitors', async () => {
  const source = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8')

  // 未登录访客命中外壳（例如首页）时 Hook 照样会跑，早退只发生在渲染阶段；
  // 两个轮询都必须先确认有会话，否则 /notifications 和 /preview-generation/status
  // 会连打 401 并触发一次注定失败的 /auth/refresh。
  assert.match(source, /const shellPollingEnabled = !!user && user\.dataScope !== 'NONE'/)
  const guards = source.match(/if \(!shellPollingEnabled\) return/g) || []
  assert.equal(guards.length, 2)
  assert.doesNotMatch(source, /if \(user\?\.dataScope === 'NONE'\) return/)
})
