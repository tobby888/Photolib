/**
 * 动图（多帧 GIF、APNG）的播放。
 *
 * 后端把动图的成品图原样保存，缩略图只是静态首帧（见后端 `AnimatedImages`）。要放动画就得拿
 * 成品图：`Photo.animationUrl` 是它的内联签名地址。
 *
 * 播放不直接用 `<img src>`：GIF 自己带循环次数，只放一遍的文件会停在最后一帧，而这里要求一直
 * 重复播放，暂停时回到首帧。所以把字节取回来交给 `ImageDecoder`，自己按帧时长逐帧画到 canvas
 * 上。浏览器没有 `ImageDecoder`（或不认这个格式）时退回 `<img>`，按文件自己的循环次数播放。
 */

/** 鼠标在动图格子上停住多久开始播放。 */
export const HOVER_PLAY_DELAY_MS = 3000
/** 停住期间允许的手抖幅度（像素）。超过它就算挪动了，重新计时。 */
export const HOVER_JITTER_PX = 4

export interface Point {
  x: number
  y: number
}

/** 鼠标从停住的位置挪开了吗（超过手抖幅度才算）。 */
export function movedBeyondJitter(anchor: Point, point: Point): boolean {
  return Math.hypot(point.x - anchor.x, point.y - anchor.y) > HOVER_JITTER_PX
}

/**
 * 一帧要停多久（毫秒）。`ImageDecoder` 给的是微秒。
 *
 * 帧时长为 0 或极短（≤ 10ms）的 GIF 很常见，主流浏览器都把它们当 100ms 播放，
 * 照原值播就会快得像快进，这里跟浏览器保持一致。
 */
export function frameDelayMs(durationMicros: number | null | undefined): number {
  const ms = durationMicros == null ? 0 : durationMicros / 1000
  if (!Number.isFinite(ms) || ms <= 10) return 100
  return ms
}

/**
 * 按文件头认出格式。对象存储大多会回正确的 Content-Type，但本地存储的老对象、
 * 或被代理改写过的响应可能是 `application/octet-stream`，`ImageDecoder` 必须拿到真实类型。
 */
export function sniffImageType(head: Uint8Array): string | null {
  if (head.length >= 6 && head[0] === 0x47 && head[1] === 0x49 && head[2] === 0x46
    && head[3] === 0x38 && (head[4] === 0x37 || head[4] === 0x39) && head[5] === 0x61) {
    return 'image/gif'
  }
  const png = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
  if (head.length >= png.length && png.every((byte, index) => head[index] === byte)) return 'image/png'
  if (head.length >= 12 && head[0] === 0x52 && head[1] === 0x49 && head[2] === 0x46 && head[3] === 0x46
    && head[8] === 0x57 && head[9] === 0x45 && head[10] === 0x42 && head[11] === 0x50) {
    return 'image/webp'
  }
  return null
}

/** 已取回的动图字节，按地址缓存，最近用过的排在后面。总量超过上限时从最久没用的开始丢。 */
const MAX_CACHE_BYTES = 64 * 1024 * 1024

interface CachedAnimation {
  promise: Promise<Blob>
  size: number
}

const cache = new Map<string, CachedAnimation>()

function trimCache() {
  let total = 0
  for (const entry of cache.values()) total += entry.size
  for (const [key, entry] of cache) {
    // 至少留一份：刚取回来的那张正要播放。
    if (total <= MAX_CACHE_BYTES || cache.size <= 1) break
    cache.delete(key)
    total -= entry.size
  }
}

/**
 * 取回动图字节。同一个地址只取一次，悬停离开再回来不用重新下载。
 *
 * `mode: 'cors'` 才能读到字节；`cache: 'reload'` 不是可选的：同一个签名地址可能已经被
 * 选片页的普通 `<img>` 以 no-cors 方式取过，HTTP 缓存里那条响应没有 CORS 头，读它会被判成
 * 跨域失败（原理同 `src/useLocalImageUrl.ts`）。上面的内存缓存补回了不能用 HTTP 缓存的损失。
 */
export function loadAnimationBlob(url: string): Promise<Blob> {
  const hit = cache.get(url)
  if (hit) {
    cache.delete(url)
    cache.set(url, hit)
    return hit.promise
  }
  const entry: CachedAnimation = { size: 0, promise: Promise.resolve(new Blob()) }
  entry.promise = fetch(url, { mode: 'cors', cache: 'reload' })
    .then(async response => {
      if (!response.ok) throw new Error(`动图没能加载出来（HTTP ${response.status}）`)
      const raw = await response.blob()
      const type = sniffImageType(new Uint8Array(await raw.slice(0, 12).arrayBuffer()))
      if (!type) throw new Error('动图文件好像损坏了')
      const blob = raw.type === type ? raw : new Blob([raw], { type })
      entry.size = blob.size
      trimCache()
      return blob
    })
  entry.promise.catch(() => {
    if (cache.get(url) === entry) cache.delete(url)
  })
  cache.set(url, entry)
  trimCache()
  return entry.promise
}

/** 打开好的动图：能逐帧解码就给解码器，否则给一个本地 Blob 地址交给 `<img>`。 */
export type AnimationSource =
  | { kind: 'decoder'; decoder: ImageDecoder; frameCount: number }
  | { kind: 'image'; objectUrl: string }

export async function openAnimation(url: string): Promise<AnimationSource> {
  const blob = await loadAnimationBlob(url)
  if (typeof ImageDecoder !== 'undefined' && await ImageDecoder.isTypeSupported(blob.type).catch(() => false)) {
    const decoder = new ImageDecoder({ data: await blob.arrayBuffer(), type: blob.type })
    try {
      await decoder.tracks.ready
      await decoder.completed
      const frameCount = Math.max(1, decoder.tracks.selectedTrack?.frameCount ?? 1)
      return { kind: 'decoder', decoder, frameCount }
    } catch (reason) {
      decoder.close()
      throw reason
    }
  }
  return { kind: 'image', objectUrl: URL.createObjectURL(blob) }
}

export function closeAnimation(source: AnimationSource) {
  if (source.kind === 'decoder') source.decoder.close()
  else URL.revokeObjectURL(source.objectUrl)
}
