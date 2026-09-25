/**
 * 页面级渲染失败的分类，供 `RouteErrorBoundary` 决定给用户看哪一种提示。
 *
 * 放在一个不依赖 React / antd 的模块里，好让 `node --test` 直接测。
 */

/**
 * 各浏览器"按需加载的页面代码没取回来"时抛出的报错。发版后旧页面去要已经被替换掉的
 * chunk、或者网络抖了一下都会走到这里；它和页面自身的渲染 bug 是两回事——原地重试
 * 没用（`React.lazy` 会记住那次失败），只有整页重新加载才能拿到新的入口。
 */
const CHUNK_LOAD_ERROR = [
  /Failed to fetch dynamically imported module/i, // Chromium
  /error loading dynamically imported module/i, // Firefox
  /Importing a module script failed/i, // Safari
  /Unable to preload CSS/i, // Vite 的预加载助手
]

export function isChunkLoadError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : ''
  return CHUNK_LOAD_ERROR.some((pattern) => pattern.test(message))
}

/**
 * `main.tsx` 的 `vite:preloadError` 兜底一旦决定整页重载，被它拦下的那次 `import()`
 * 会以 `undefined` 结束，`React.lazy` 随即在渲染时报错。页面马上就要换掉了，这时候
 * 不该再弹一个"页面没能打开"吓人，边界据此改为显示加载中。
 */
let reloadPending = false

export function markReloadPending() {
  reloadPending = true
}

export function isReloadPending() {
  return reloadPending
}
