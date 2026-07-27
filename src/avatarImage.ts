export const AVATAR_MAX_BYTES = 1024 * 1024
export const AVATAR_MAX_DIMENSION = 1024
export const AVATAR_OUTPUT_DIMENSION = 512
export const AVATAR_ACCEPT = 'image/jpeg,image/png'

export const AVATAR_ALLOWED_TYPES: ReadonlySet<string> = new Set([
  'image/jpeg',
  'image/png',
])

export interface ImageDimensions {
  width: number
  height: number
}

export interface PixelCrop extends ImageDimensions {
  x: number
  y: number
}

export type AvatarValidationResult =
  | { valid: true }
  | { valid: false; message: string }

/** Pure metadata validation shared by the picker and future tests. */
export function validateAvatarFile(file: Pick<File, 'size' | 'type'>): AvatarValidationResult {
  if (!AVATAR_ALLOWED_TYPES.has(file.type.toLowerCase())) {
    return { valid: false, message: '头像仅支持 JPEG 或 PNG 图片' }
  }
  if (file.size <= 0) return { valid: false, message: '所选图片为空，请重新选择' }
  if (file.size > AVATAR_MAX_BYTES) {
    return { valid: false, message: '头像原图不能超过 1 MiB' }
  }
  return { valid: true }
}

/** Pure decoded-size validation. The server repeats this check as the final boundary. */
export function validateAvatarDimensions({ width, height }: ImageDimensions): AvatarValidationResult {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    return { valid: false, message: '无法读取图片尺寸，请换一张图片重试' }
  }
  if (width > AVATAR_MAX_DIMENSION || height > AVATAR_MAX_DIMENSION) {
    return { valid: false, message: '头像宽高均不能超过 1024 像素' }
  }
  return { valid: true }
}

export function clampPixelCrop(crop: PixelCrop, image: ImageDimensions): PixelCrop {
  const x = Math.max(0, Math.min(image.width - 1, Math.round(crop.x)))
  const y = Math.max(0, Math.min(image.height - 1, Math.round(crop.y)))
  const width = Math.max(1, Math.min(Math.round(crop.width), image.width - x))
  const height = Math.max(1, Math.min(Math.round(crop.height), image.height - y))
  return { x, y, width, height }
}

export function avatarRequestPath(avatarUrl?: string | null): string | null {
  if (!avatarUrl) return null
  const trimmed = avatarUrl.trim()
  const [rawPath, query] = trimmed.split('?', 2)
  const path = rawPath.startsWith('/api/v1/') ? rawPath.slice('/api/v1'.length) : rawPath
  if (!/^\/users\/(?:me|[^/]+)\/avatar$/.test(path)) return null
  return query ? `${path}?${query}` : path
}

function loadImage(source: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error('图片无法解码，请确认文件未损坏'))
    image.src = source
  })
}

export async function readImageDimensions(file: File): Promise<ImageDimensions> {
  const source = URL.createObjectURL(file)
  try {
    const image = await loadImage(source)
    return { width: image.naturalWidth, height: image.naturalHeight }
  } finally {
    URL.revokeObjectURL(source)
  }
}

function canvasToJpeg(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(blob => {
      if (blob) resolve(blob)
      else reject(new Error('浏览器无法生成裁切后的头像'))
    }, 'image/jpeg', 0.9)
  })
}

export async function createCroppedAvatar(source: string, crop: PixelCrop): Promise<File> {
  const image = await loadImage(source)
  const safeCrop = clampPixelCrop(crop, {
    width: image.naturalWidth,
    height: image.naturalHeight,
  })
  const outputSize = Math.min(AVATAR_OUTPUT_DIMENSION, safeCrop.width, safeCrop.height)
  const canvas = document.createElement('canvas')
  canvas.width = outputSize
  canvas.height = outputSize
  const context = canvas.getContext('2d')
  if (!context) throw new Error('浏览器不支持头像裁切')

  context.imageSmoothingEnabled = true
  context.imageSmoothingQuality = 'high'
  context.fillStyle = '#ffffff'
  context.fillRect(0, 0, outputSize, outputSize)
  context.drawImage(
    image,
    safeCrop.x,
    safeCrop.y,
    safeCrop.width,
    safeCrop.height,
    0,
    0,
    outputSize,
    outputSize,
  )
  const blob = await canvasToJpeg(canvas)
  if (blob.size > AVATAR_MAX_BYTES) {
    throw new Error('裁切后的头像仍超过 1 MiB，请缩小裁切范围后重试')
  }
  return new File([blob], 'avatar.jpg', { type: blob.type, lastModified: Date.now() })
}
