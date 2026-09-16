/**
 * 选片页里「裁切 + 旋转」的几何与渲染（issue #94）。
 *
 * 整个编辑在浏览器本地完成，服务端只收结果字节——所以这里的算法必须自己说得通，
 * 没有服务端可以兜底。纯几何部分（旋转后的尺寸、裁切框的归一化、输出尺寸）
 * 单独拆出来，由 `tests/photoEdit.test.ts` 钉住；只有真正要碰 canvas 的那一段
 * 才依赖浏览器。
 *
 * ## 裁切框为什么是归一化坐标
 * 用户拖的是**屏幕上看到的那张图**，而屏幕上那张图已经旋转过、还被缩放到容器里。
 * 存像素坐标就得同时记住「按哪个缩放比例、旋转之前还是之后」，换个窗口宽度就对不上。
 * 归一化到旋转后图像的 0~1 之后，裁切框与显示尺寸无关，旋转时只要把矩形跟着转一下。
 *
 * ## 为什么只做 90° 的整数倍
 * 需求是「简单的编辑」。任意角度旋转要补边、要重采样，画质和构图都会变，
 * 而选片要回答的问题只是「这张能不能用」。整数倍旋转是无损重排，没有这些麻烦。
 */

export type Rotation = 0 | 90 | 180 | 270

/** 归一化裁切框，坐标系是**旋转之后**那张图，取值 0~1。 */
export interface CropRect {
  x: number
  y: number
  width: number
  height: number
}

export const FULL_CROP: CropRect = { x: 0, y: 0, width: 1, height: 1 }

/** 裁切框的最小边长（归一化）。再小就没法在屏幕上看清自己裁了什么。 */
export const MIN_CROP_SIZE = 0.02

export interface EditState {
  rotation: Rotation
  crop: CropRect
}

export const NO_EDITS: EditState = { rotation: 0, crop: FULL_CROP }

/** 旋转 90° / 270° 会交换宽高。 */
export function rotatedSize(width: number, height: number, rotation: Rotation) {
  return rotation === 90 || rotation === 270
    ? { width: height, height: width }
    : { width, height }
}

export function nextRotation(rotation: Rotation, quarterTurns: number): Rotation {
  const turns = (((rotation / 90 + quarterTurns) % 4) + 4) % 4
  return (turns * 90) as Rotation
}

/** 把任意矩形收进 0~1，并保证不小于 {@link MIN_CROP_SIZE}。 */
export function normalizeCrop(rect: CropRect): CropRect {
  const width = clamp(rect.width, MIN_CROP_SIZE, 1)
  const height = clamp(rect.height, MIN_CROP_SIZE, 1)
  return {
    x: clamp(rect.x, 0, 1 - width),
    y: clamp(rect.y, 0, 1 - height),
    width,
    height,
  }
}

/**
 * 顺时针转 90° 时裁切框在新坐标系里的位置。
 *
 * 新 x 由旧 y 决定、新 y 由旧 x 决定（且要从另一端量起），所以不能只交换宽高——
 * 那会让「裁了左上角」在转一下之后变成「裁了左上角」，而它应该变成右上角。
 */
export function rotateCrop(crop: CropRect, quarterTurns: number): CropRect {
  let result = crop
  const turns = ((quarterTurns % 4) + 4) % 4
  for (let index = 0; index < turns; index += 1) {
    result = {
      x: 1 - result.y - result.height,
      y: result.x,
      width: result.height,
      height: result.width,
    }
  }
  return normalizeCrop(result)
}

export function hasEdits(state: EditState): boolean {
  return state.rotation !== 0
    || Math.abs(state.crop.x) > 1e-6
    || Math.abs(state.crop.y) > 1e-6
    || Math.abs(state.crop.width - 1) > 1e-6
    || Math.abs(state.crop.height - 1) > 1e-6
}

/** 编辑结果的像素尺寸。至少 1×1，否则 canvas 会直接抛错。 */
export function outputSize(width: number, height: number, state: EditState) {
  const rotated = rotatedSize(width, height, state.rotation)
  return {
    width: Math.max(1, Math.round(rotated.width * state.crop.width)),
    height: Math.max(1, Math.round(rotated.height * state.crop.height)),
  }
}

/** JPEG 的编码质量。后端还会再压一次到 10 MiB 以内，这里只要别先自己糊掉。 */
const JPEG_QUALITY = 0.95

/**
 * 把编辑结果画成一张新图。
 *
 * 画布尺寸直接就是裁切后的尺寸，旋转靠 `ctx` 的变换完成——先把坐标系搬到
 * 「旋转之后的图像」那一套，再平移掉裁切框的左上角，然后原样画一次源图。
 * 这样只有一次重采样，没有中间画布。
 *
 * 源图必须是同源的（Blob URL）或带 CORS 头，否则画布会被污染、`toBlob` 抛
 * `SecurityError`。选片页用 `useLocalImageUrl` 取图，拿到的正是 Blob URL。
 */
export async function renderEdited(
  image: CanvasImageSource & { width: number; height: number },
  state: EditState,
  contentType: string,
): Promise<Blob> {
  const rotated = rotatedSize(image.width, image.height, state.rotation)
  const size = outputSize(image.width, image.height, state)
  const canvas = document.createElement('canvas')
  canvas.width = size.width
  canvas.height = size.height
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('这个浏览器不支持图片编辑')

  ctx.imageSmoothingQuality = 'high'
  ctx.translate(-state.crop.x * rotated.width, -state.crop.y * rotated.height)
  switch (state.rotation) {
    case 90:
      ctx.translate(rotated.width, 0)
      ctx.rotate(Math.PI / 2)
      break
    case 180:
      ctx.translate(rotated.width, rotated.height)
      ctx.rotate(Math.PI)
      break
    case 270:
      ctx.translate(0, rotated.height)
      ctx.rotate(-Math.PI / 2)
      break
    default:
      break
  }
  ctx.drawImage(image, 0, 0)

  const type = contentType === 'image/png' ? 'image/png' : 'image/jpeg'
  const blob = await new Promise<Blob | null>(resolve =>
    canvas.toBlob(resolve, type, type === 'image/jpeg' ? JPEG_QUALITY : undefined))
  if (!blob) throw new Error('编辑结果没能生成，请重试一次')
  return blob
}

export async function sha256Hex(blob: Blob) {
  const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
}

function clamp(value: number, min: number, max: number) {
  if (Number.isNaN(value)) return min
  return Math.min(max, Math.max(min, value))
}
