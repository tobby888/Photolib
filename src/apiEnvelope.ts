/**
 * `api()` 的两道防线，单独成模块好让 `node --test` 直接测（`api.ts` 一加载就要读
 * `import.meta.env`，在 Node 里跑不起来）。
 *
 * 现场：消息中心时不时整页崩成"页面加载失败"，控制台是
 * `Cannot read properties of undefined (reading 'length')`，在开发者工具里停用缓存就好。
 * 链条是：浏览器 HTTP 缓存里躺着一条 `GET /api/v1/notifications` 的 2xx 应答，但它不是
 * 本系统的 `{ code, message, data }` 信封（同域名上的旧部署、代理或网关写进去的 HTML /
 * 别的 JSON）。`api()` 直接取 `.data`，拿到 `undefined` 当列表交给页面，渲染时一读
 * `.length` 就崩。本系统的接口本身都带 `no-store`（Spring Security 默认头），所以缓存里
 * 能被命中的只可能是这种外来的旧条目——它不会自己过期，刷新也拿到同一份。
 */

/**
 * 读接口一律不走 HTTP 缓存。请求头里的 `Cache-Control: no-cache` 让浏览器跳过缓存
 * 直接去服务器取；拿回来的 `no-store` 应答还会顺手把那条旧缓存作废。
 *
 * 只对 `api()` 的 JSON 读接口加：用 `http` 直接下载的图片、PDF、头像是有意靠 HTTP
 * 缓存省流量的，不能一起绕开。
 */
export function noCacheHeaders(method: string | undefined): Record<string, string> {
  return (method ?? 'get').toLowerCase() === 'get' ? { 'Cache-Control': 'no-cache' } : {}
}

/**
 * 应答体必须是本系统的信封。不是的话宁可报一个能重试的错误，也不能把 `undefined`
 * 当数据交给页面——页面拿 `useLoad` 的初始值兜底，却兜不住"请求成功但数据是 undefined"。
 * 204 没有应答体，按"成功、没有数据"放行。
 */
export function isEnvelope(status: number, body: unknown): boolean {
  if (status === 204) return true
  return typeof body === 'object' && body !== null && !Array.isArray(body) && 'code' in body
}
