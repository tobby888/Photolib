/**
 * 选片页大图的缩放与平移（issue #94 追加）。
 *
 * 纯几何，不碰 DOM——`tests/photoZoom.test.ts` 钉住它。放这里而不是散在组件里，
 * 是因为「以光标为锚点缩放」这件事很容易写成「以中心缩放」，而两者在图片放大到
 * 三四倍之后差得非常远：用户想看左上角那张脸，中心缩放会把它推出屏幕。
 *
 * ## 坐标约定
 * 图片先以 `object-fit: contain` 铺在容器里（这就是 1 倍、也就是「适应窗口」），
 * 然后套一层 `translate(x, y) scale(s)`，`transform-origin` 取默认的中心。
 * 因此 `x` / `y` 是相对「居中位置」的偏移，单位是 CSS 像素，锚点坐标也一律
 * 相对容器中心来量——这样 1 倍时 x=y=0 天然成立，不需要额外的居中逻辑。
 */

export interface ZoomState {
  scale: number
  x: number
  y: number
}

export interface Size {
  width: number
  height: number
}

export interface Point {
  x: number
  y: number
}

/** 「适应窗口」：整张图刚好铺满容器，不偏移。 */
export const FIT_ZOOM: ZoomState = { scale: 1, x: 0, y: 0 }

/** 不允许缩到比「适应窗口」还小——留出一圈空白对选片没有任何用处。 */
export const MIN_SCALE = 1
/**
 * 上限。8 倍已经远超「判虚焦」需要的倍数（成品图本身通常就比屏幕大好几倍），
 * 再高只是在放大 JPEG 的块效应。
 */
export const MAX_SCALE = 8
/** 滚一格、或点一次按钮的倍率。 */
export const ZOOM_STEP = 1.3

export function clampScale(scale: number): number {
  if (!Number.isFinite(scale)) return MIN_SCALE
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale))
}

export function isZoomed(state: ZoomState): boolean {
  return state.scale > MIN_SCALE + 1e-6
}

/**
 * 把平移收进「图片边缘不越过容器边缘」的范围。
 *
 * 缩放后的内容比容器小时（1 倍就是这种情况）可动范围是 0，于是自动回到居中——
 * 「适应窗口」因此不需要单独一条分支。
 */
export function clampPan(state: ZoomState, viewport: Size, content: Size): ZoomState {
  const limitX = Math.max(0, (content.width * state.scale - viewport.width) / 2)
  const limitY = Math.max(0, (content.height * state.scale - viewport.height) / 2)
  return {
    scale: state.scale,
    x: clamp(state.x, -limitX, limitX),
    y: clamp(state.y, -limitY, limitY),
  }
}

/**
 * 以 `anchor`（相对容器中心的坐标）为锚点缩放到 `nextScale`。
 *
 * 推导：锚点下的那个内容点在缩放前后必须落在同一个屏幕位置。
 * 设该点在未缩放内容里的坐标为 u，则 `anchor = x + s·u`，于是 `u = (anchor − x) / s`；
 * 缩放后要求 `anchor = x' + s'·u`，代入即得下面这一行。
 */
export function zoomAt(state: ZoomState, nextScale: number, anchor: Point,
  viewport: Size, content: Size): ZoomState {
  const scale = clampScale(nextScale)
  const ratio = scale / state.scale
  return clampPan({
    scale,
    x: anchor.x - ratio * (anchor.x - state.x),
    y: anchor.y - ratio * (anchor.y - state.y),
  }, viewport, content)
}

/** 按住拖动。位移直接加在偏移上，再收进边界。 */
export function panBy(state: ZoomState, delta: Point, viewport: Size, content: Size): ZoomState {
  return clampPan({ scale: state.scale, x: state.x + delta.x, y: state.y + delta.y }, viewport, content)
}

/**
 * 「1:1」需要的倍率：让图片的一个原始像素正好占一个 CSS 像素。
 *
 * 图片在 1 倍时已经被缩到 `content`，所以这里是「原始宽度 ÷ 当前显示宽度」。
 * 原图比容器还小时结果会小于 1，夹回 {@link MIN_SCALE}——那种情况下「适应窗口」
 * 本身就已经不放大了，1:1 与它等价。
 */
export function scaleForActualPixels(natural: Size, content: Size): number {
  if (!content.width || !natural.width) return MIN_SCALE
  return clampScale(natural.width / content.width)
}

/**
 * 容器里按 `object-fit: contain` 铺开之后，图片实际占多大。
 * 缩放和平移的边界都按这个尺寸算，而不是容器尺寸——否则一张竖图在宽容器里
 * 上下能拖出大片空白。
 */
export function containSize(natural: Size, viewport: Size): Size {
  if (!natural.width || !natural.height || !viewport.width || !viewport.height) {
    return { width: viewport.width, height: viewport.height }
  }
  const scale = Math.min(viewport.width / natural.width, viewport.height / natural.height)
  return { width: natural.width * scale, height: natural.height * scale }
}

function clamp(value: number, min: number, max: number) {
  if (!Number.isFinite(value)) return 0
  const result = Math.min(max, Math.max(min, value))
  // 把 -0 归一成 0。夹在 [-0, 0] 之间必然产出 -0，它在 transform 里无害，
  // 但会让「回到适应窗口了吗」这类相等比较（以及测试里的 deepEqual）莫名其妙地不等。
  return result === 0 ? 0 : result
}
