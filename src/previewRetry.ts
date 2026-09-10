import type { PreviewUrlRefresher } from './previewImage'

/**
 * 预览图加载失败时，向后端重取一次签名地址再试。这里只放纯粹的状态机，
 * React 那一半在 `src/PreviewPhoto.tsx`。
 *
 * 起因见 `src/previewFreshness.ts`：`thumbnailUrl` 是一条只活 10 分钟的预签名
 * OSS 地址，写进 React state 之后没有任何东西会去换新。`useRefreshOnResume`
 * 已经在页面回到前台时补了一刀，但它管不到别的过期方式——页面一直开着慢慢看、
 * 图片懒加载到很久以后才真正发起请求、浏览器把整批地址从 HTTP 缓存里淘汰掉之后
 * 重新去取。这些情况下 OSS 对过期签名回 403，`<img>` 直接报 error，界面把占位图
 * 顶上去；用户看到的是"图没了"，而实际上图好好地在桶里。
 *
 * 所以失败之后先别急着认输：拿图片 ID 重新问一次后端，它会按当前签名窗口重新签
 * 一条地址，换上去再试一次。真的取不回来（对象存储故障、图片被删）才让占位图顶上。
 *
 * **只重试一次**，这是有意的：
 *
 * - 后端在同一个签名窗口（`OSS_SIGNATURE_WINDOW`，生产是 5 分钟）内对同一张图签
 *   出的地址是逐字节相同的，所以"重取的地址和刚失败的那条一样"就等于说明这次失败
 *   与签名过期无关，再试多少次都是同样的结果——这种情况连第二次加载都不用发起。
 * - 对象存储真出故障时，一屏几十张图各自反复重试会把故障放大成一场请求风暴。
 */

export type PreviewRetryPhase =
  /** 还在用页面给的那条地址，没出过错。 */
  | 'fresh'
  /** 已经失败过一次，正在向后端要新地址。 */
  | 'retrying'
  /** 换上了新地址，这是最后一次机会。 */
  | 'retried'
  /** 认输，交给占位图。 */
  | 'failed'

export interface PreviewAttempt {
  /** 页面传进来的原始地址，用来识别"这是同一张图，还是页面换了新数据"。 */
  origin: string
  /** 当前真正挂在 `<img>` 上的地址。 */
  src: string
  phase: PreviewRetryPhase
}

export function startAttempt(src: string): PreviewAttempt {
  return { origin: src, src, phase: 'fresh' }
}

/**
 * 页面数据换了新地址（列表重取、切到另一张图）就从头来过，重试次数跟着归零。
 * 这不会变成死循环：真·加载不出来的时候后端在同一个签名窗口内给的是同一条地址，
 * `refreshed` 会直接判成 `failed`。
 */
export function attemptFor(attempt: PreviewAttempt, src: string): PreviewAttempt {
  return attempt.origin === src ? attempt : startAttempt(src)
}

export function loadFailed(attempt: PreviewAttempt, canRefresh: boolean): PreviewAttempt {
  // 重试请求还在路上，或者已经认输：这次 error 事件没有新信息。
  if (attempt.phase === 'retrying' || attempt.phase === 'failed') return attempt
  if (attempt.phase === 'retried' || !canRefresh) return { ...attempt, phase: 'failed' }
  return { ...attempt, phase: 'retrying' }
}

/**
 * 后端给回地址之后往下走一步。`fresh` 为空、或者和刚失败的那条一模一样，都说明
 * 没什么可试的了。
 */
export function refreshed(attempt: PreviewAttempt, fresh: string | null | undefined): PreviewAttempt {
  if (attempt.phase !== 'retrying') return attempt
  if (!fresh || fresh === attempt.src) return { ...attempt, phase: 'failed' }
  return { ...attempt, src: fresh, phase: 'retried' }
}

export type { PreviewUrlRefresher }
