/**
 * 让页面里的签名预览地址不至于比页面本身活得短。
 *
 * `thumbnailUrl` 是一条预签名 OSS 地址，有效期只有
 * `download-url-ttl − signature-window`（生产上是 15m − 5m = 10 分钟）。它在
 * 列表/详情接口返回时就固定写进了 React state，之后没有任何东西会去换新的——
 * 只要页面比这 10 分钟活得久，那一屏地址就整批作废。
 *
 * 桌面端很少踩到：标签页一直在前台，图片早就下载完并留在内存里，真要重新取
 * 也通常是因为用户自己刷新了页面（顺带换了一批新地址）。移动端不一样：iOS
 * Safari 会把切走的标签页整个挂起，回到 App 时直接恢复原来的 DOM 和 React
 * state，**不重跑任何 effect**，于是页面拿着一批过期地址重新去请求图片。OSS
 * 对过期签名回 403，而它的错误响应不带 `Access-Control-Allow-Origin`，浏览器
 * 于是把这次失败报成跨域失败而不是 403——Safari 里就是一句 `Load failed`。
 * 结果是图库整屏变成占位图、详情页大图空白且直方图报错；手动重新进一次页面
 * 又好了，所以现象是"一会行一会不行"，而且看起来像 CORS 问题。
 *
 * 对策是页面回到前台时悄悄重取一次数据，换一批新签名。这里只放纯粹的判定逻辑，
 * DOM 事件的接线在 `useRefreshOnResume`（`src/hooks.ts`）里。
 */

/**
 * 后台待够多久就认为页面里的预览地址已经不可信。
 *
 * 必须明显小于上面那 10 分钟的最坏剩余有效期：多刷一次列表只是一个很小的 JSON
 * 请求，刷少了才会让人看见整屏占位图。改后端的 `download-url-ttl` 或
 * `OSS_SIGNATURE_WINDOW` 时要回头确认这个值仍然安全。
 */
export const PREVIEW_URL_STALE_AFTER_MS = 5 * 60 * 1000

export interface ResumeRefresh {
  visibilityChanged(state: 'visible' | 'hidden'): void
  pageShown(persisted: boolean): void
}

export function createResumeRefresh(
  refresh: () => void,
  options: { now?: () => number; initiallyHidden?: boolean } = {},
): ResumeRefresh {
  const now = options.now ?? (() => Date.now())
  let hiddenSince: number | null = options.initiallyHidden ? now() : null

  return {
    visibilityChanged(state) {
      if (state === 'hidden') {
        // 息屏、切 App、切标签页可能连着来好几次 hidden，保留最早那一次的
        // 时刻——否则中途的重复事件会把计时清零，真正待了半小时的页面反而
        // 被判成"刚切走"。
        if (hiddenSince === null) hiddenSince = now()
        return
      }
      const hiddenFor = hiddenSince === null ? 0 : now() - hiddenSince
      hiddenSince = null
      if (hiddenFor >= PREVIEW_URL_STALE_AFTER_MS) refresh()
    },

    pageShown(persisted) {
      // 从前进/后退缓存里恢复的页面同样没有重跑 effect，而且它在后台待了多久
      // 无从得知（`pageshow` 不带这个信息），一律当作地址已经过期。
      if (!persisted) return
      hiddenSince = null
      refresh()
    },
  }
}
