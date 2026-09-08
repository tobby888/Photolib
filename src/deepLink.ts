/**
 * 路径式深链到 hash 路由的转换。
 *
 * 后端把 `/recruitment`、`/docs` 这类 SPA 路径 forward 到 `index.html`
 * （见 `SpaForwardController`），但应用跑的是 `HashRouter`——它只认 hash，
 * 完全看不见路径。少了这一步，`photowarehouse.cn/recruitment` 这种"发给新生的
 * 报名链接"打开后停在 `/`，被外壳当成未登录访客弹去登录页，报名页根本没机会渲染。
 *
 * 只在"路径有内容、hash 是空的"时才改写：hash 一旦有值，路由已经由它决定
 * （`/recruitment#/login` 就是被弹走之后的地址），再改写会把访客锁死在原地。
 */
export function hashRouteForDeepLink(location: Pick<Location, 'pathname' | 'search' | 'hash'>) {
  const hash = location.hash.replace(/^#/, '')
  if (hash) return null

  const pathname = location.pathname
  // `/index.html` 是静态入口本身，不是任何一条前端路由；把它搬进 hash 只会得到 404 页。
  if (!pathname || pathname === '/' || pathname === '/index.html') return null

  return `/#${pathname}${location.search}`
}
