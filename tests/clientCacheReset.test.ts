import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { cacheBustedUrl, clearClientCaches } from '../src/clientCacheReset.ts'

function fakeStorage(length: number) {
  return { length, cleared: false, clear() { this.cleared = true } }
}

test('清缓存会把四类本地状态都清掉，Service Worker 排在 Cache Storage 前面', async () => {
  const order: string[] = []
  const localStorage = fakeStorage(3)
  const sessionStorage = fakeStorage(1)
  const deletedCaches: string[] = []
  const result = await clearClientCaches({
    serviceWorker: {
      async getRegistrations() {
        return [{ async unregister() { order.push('unregister'); return true } }]
      },
    },
    caches: {
      async keys() { return ['assets-v1', 'assets-v2'] },
      async delete(key) { order.push('delete'); deletedCaches.push(key); return true },
    },
    localStorage,
    sessionStorage,
  })

  assert.deepEqual(deletedCaches, ['assets-v1', 'assets-v2'])
  assert.equal(localStorage.cleared, true)
  assert.equal(sessionStorage.cleared, true)
  assert.deepEqual(result, {
    cleared: ['serviceWorker', 'caches', 'localStorage', 'sessionStorage'],
    failed: [],
  })
  // 还活着的 Service Worker 可能正往缓存里写，先注销再删缓存才删得干净。
  assert.equal(order[0], 'unregister')
})

test('某一步抛异常不影响其余几步，结果里如实记下失败的那步', async () => {
  const localStorage = fakeStorage(2)
  const result = await clearClientCaches({
    serviceWorker: { async getRegistrations() { throw new Error('SecurityError') } },
    caches: { async keys() { throw new Error('unavailable') }, async delete() { return true } },
    localStorage,
    sessionStorage: {
      get length(): number { throw new Error('无痕窗口读不了') },
      clear() { throw new Error('无痕窗口读不了') },
    },
  })

  assert.equal(localStorage.cleared, true)
  assert.deepEqual(result.cleared, ['localStorage'])
  assert.deepEqual(result.failed, ['serviceWorker', 'caches', 'sessionStorage'])
})

test('http 页面和内嵌 WebView 上这些 API 整个不存在，此时既不报错也不谎称清过', async () => {
  assert.deepEqual(await clearClientCaches({}), { cleared: [], failed: [] })
  // 本来就是空的不算"清掉了"，否则控制台那行日志分不出"清了"和"没东西可清"。
  assert.deepEqual(
    await clearClientCaches({ localStorage: fakeStorage(0), sessionStorage: fakeStorage(0) }),
    { cleared: [], failed: [] },
  )
})

test('重新加载的地址保留 hash 路由，连按多次也不会堆出一串 _reload', () => {
  const first = cacheBustedUrl('https://photowarehouse.cn/?a=1#/photos?page=2', 1000)
  assert.equal(first, 'https://photowarehouse.cn/?a=1&_reload=1000#/photos?page=2')
  assert.equal(
    cacheBustedUrl(first, 2000),
    'https://photowarehouse.cn/?a=1&_reload=2000#/photos?page=2',
  )
})

test('渲染失败页先确认再清，并且把"要重新登录"写在按钮旁边', async () => {
  const source = await readFile(new URL('../src/AppErrorBoundary.tsx', import.meta.url), 'utf8')
  assert.match(source, /清除本站缓存并重新加载/)
  // 直接清会把人悄悄登出，所以第一下只能进确认态。
  assert.match(source, /phase: 'confirming'/)
  assert.match(source, /确认清除并重新加载/)
  assert.match(source, /清除后需要重新登录/)
  assert.match(source, /clearClientCaches\(browserCacheHost\(\)\)/)
  // 清完必须重新加载，否则页面还是崩着的那份。
  assert.match(source, /await clearClientCaches[\s\S]*this\.reload\(\)/)
})
