/**
 * 渲染失败页上那招「清除本站缓存并重新加载」。
 *
 * 发版之后偶尔会有某一台浏览器一直打不开：旧的 index.html 还在，指向的却是已经被
 * 覆盖的 chunk；或者 localStorage 里那份用户/表格配置被写坏了，每次进来都在同一处
 * 崩掉。这两种都不会自己好——刷新拿到的还是同一份坏状态，用户只能说"我这儿打不开"，
 * 而其他人都正常。所以要给一个不依赖用户会不会用开发者工具的自救按钮。
 *
 * 能清的只有本站自己的东西：Cache Storage、Service Worker、localStorage、
 * sessionStorage。浏览器的 HTTP 缓存 JS 碰不到，只能靠 `cacheBustedUrl` 换一个
 * 地址把入口文档重新取一遍；真要连子资源一起重取还得靠用户自己强制刷新，界面上
 * 得把这句话说出来，不能让人以为按一下就万事大吉。
 */

/** 只声明用到的那几个方法，测试里好塞假的，也不要求宿主一定实现全套 API。 */
export interface ClientCacheHost {
  caches?: { keys(): Promise<string[]>, delete(key: string): Promise<boolean> }
  serviceWorker?: { getRegistrations(): Promise<readonly { unregister(): Promise<boolean> }[]> }
  localStorage?: { length: number, clear(): void }
  sessionStorage?: { length: number, clear(): void }
}

export interface ClientCacheResetResult {
  /** 确实清掉了东西的步骤。 */
  cleared: string[]
  /** 报错的步骤；一步失败不影响其余几步继续。 */
  failed: string[]
}

/** 浏览器里这些 API 在非安全上下文（http、部分内嵌 WebView）下整个不存在，取值要防空。 */
export function browserCacheHost(): ClientCacheHost {
  const scope = globalThis as Partial<Window> & typeof globalThis
  return {
    caches: scope.caches,
    serviceWorker: scope.navigator?.serviceWorker,
    localStorage: scope.localStorage,
    sessionStorage: scope.sessionStorage,
  }
}

export async function clearClientCaches(host: ClientCacheHost): Promise<ClientCacheResetResult> {
  const cleared: string[] = []
  const failed: string[] = []
  // 每一步各自兜底：Safari 无痕窗口读 localStorage 会抛、http 页面没有 caches，
  // 让第一个异常中断整轮的话，后面几项本来能清的也清不成了。
  const step = async (name: string, run: () => Promise<boolean> | boolean) => {
    try {
      if (await run()) cleared.push(name)
    } catch {
      failed.push(name)
    }
  }

  // Service Worker 要排在 Cache Storage 前面：它还活着的时候可能正好在往缓存里写，
  // 先删缓存等于白删。本项目现在没有注册 SW，但用户的浏览器上可能留着同域下别的
  // 部署（或旧版本）注册过的那一个，它恰恰是"只有这台机器打不开"的典型成因。
  await step('serviceWorker', async () => {
    if (!host.serviceWorker) return false
    const registrations = await host.serviceWorker.getRegistrations()
    await Promise.all(registrations.map((registration) => registration.unregister()))
    return registrations.length > 0
  })
  await step('caches', async () => {
    if (!host.caches) return false
    const keys = await host.caches.keys()
    await Promise.all(keys.map((key) => host.caches!.delete(key)))
    return keys.length > 0
  })
  // localStorage 里装着 access token 和那份用户缓存，清掉就等于退出登录——这是有意的：
  // 被写坏的恰恰可能是它们。界面上必须先把"要重新登录"说清楚再让用户按。
  await step('localStorage', () => {
    if (!host.localStorage || host.localStorage.length === 0) return false
    host.localStorage.clear()
    return true
  })
  await step('sessionStorage', () => {
    if (!host.sessionStorage || host.sessionStorage.length === 0) return false
    host.sessionStorage.clear()
    return true
  })

  return { cleared, failed }
}

/**
 * 换一个浏览器没见过的地址重新加载。
 *
 * 用 `searchParams.set` 而不是往后追加，连按几次也只会有一个 `_reload`；
 * hash 原样留在末尾，HashRouter 才能回到用户原来那一页。
 */
export function cacheBustedUrl(href: string, now = Date.now()): string {
  const url = new URL(href)
  url.searchParams.set('_reload', now.toString())
  return url.toString()
}
