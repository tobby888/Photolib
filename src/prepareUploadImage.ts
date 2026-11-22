/**
 * 上传前对超大渐进式 JPEG（SOF2）做客户端基线化预处理。
 *
 * 渐进式 JPEG 无法按扫描线流式解码，服务端 libjpeg 必须在内存里为整张图分配 DCT 系数缓冲，
 * 小内存服务器（2 GiB）上会触发内存上限而被拒或挤爆 Swap。浏览器端有充裕内存与硬件解码，
 * 仅当嗅探到是渐进式 JPEG 且像素规模超过安全阈值时，通过 Canvas 重编码为标准基线（Baseline）
 * JPEG 再上传——基线 JPEG 在服务端走纯流式解码，只需十余 MB 内存。
 *
 * 普通图片（Baseline JPEG、PNG、GIF、以及常规分辨率的渐进式 JPEG）直接原样返回，不做任何重编码。
 */

/** JPEG 帧头嗅探上限：EXIF / ICC / XMP 通常位于前 512 KiB 内，读前 2 MiB 足够越过常见大块 APP 段。 */
const HEADER_SNIFF_BYTES = 2 * 1024 * 1024

/**
 * 超过此像素数（45 MP）的渐进式 JPEG 在浏览器端自动转为标准基线 JPEG。
 * 45 MP 以下在服务端按真实色度抽样最多只占 ~130 MiB 系数缓冲，无需重编码。
 */
export const PROGRESSIVE_TRANSCODE_MIN_PIXELS = 45_000_000

/** 客户端重编码时的最大边长限制（服务端成品目标 10 MiB，限制在 10000px 兼顾清晰度与浏览器 Canvas 上限）。 */
export const TRANSCODE_MAX_DIMENSION = 10_000

/** 客户端转基线 JPEG 时的输出质量（足够高，留给服务端按 10 MiB 目标做精确质量二分）。 */
const TRANSCODE_JPEG_QUALITY = 0.95

export interface JpegFrameInfo {
  progressive: boolean
  width: number
  height: number
  /** true 表示所有分量采样因子均为 1x1（4:4:4），系数缓冲比 4:2:0 大一倍。 */
  unsubsampled: boolean
}

/**
 * 纯字节级解析 JPEG SOF0 / SOF2 帧头，不解码像素，耗时 <1ms。
 * 返回 null 表示不是合法 JPEG 或未能在已读字节中找到 SOF 帧头。
 */
export function inspectJpegFrame(bytes: Uint8Array): JpegFrameInfo | null {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return null
  let offset = 2
  while (offset + 4 <= bytes.length) {
    // 跳过填充字节 0xFF
    if (bytes[offset] !== 0xff) return null
    while (offset < bytes.length && bytes[offset] === 0xff) offset++
    if (offset >= bytes.length) return null
    const marker = bytes[offset++]

    // 无长度字段的独立标记（RST0-RST7, SOI, EOI, TEM）
    if (marker === 0x00 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd9)) {
      if (marker === 0xd9) return null
      continue
    }
    // 遇到 SOS（图像扫描数据开始）说明已经过了帧头区
    if (marker === 0xda) return null

    if (offset + 2 > bytes.length) return null
    const segmentLength = (bytes[offset] << 8) | bytes[offset + 1]
    if (segmentLength < 2) return null

    // SOF0..SOF15（排除 DHT=0xC4, JPG=0xC8, DAC=0xCC）
    const isSof = marker >= 0xc0 && marker <= 0xcf
      && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc
    if (isSof) {
      if (offset + 8 > bytes.length) return null
      const height = (bytes[offset + 3] << 8) | bytes[offset + 4]
      const width = (bytes[offset + 5] << 8) | bytes[offset + 6]
      const components = bytes[offset + 7]
      if (width <= 0 || height <= 0) return null
      let unsubsampled = true
      if (components >= 3 && offset + 8 + components * 3 <= bytes.length) {
        let maxH = 1
        let maxV = 1
        const factors: Array<[number, number]> = []
        for (let i = 0; i < components; i++) {
          const sampling = bytes[offset + 8 + i * 3 + 1]
          const h = (sampling >> 4) & 0x0f
          const v = sampling & 0x0f
          factors.push([h, v])
          if (h > maxH) maxH = h
          if (v > maxV) maxV = v
        }
        unsubsampled = factors.every(([h, v]) => h === maxH && v === maxV)
      }
      // SOF2 (0xC2)、SOF6 (0xC6)、SOF10 (0xCA)、SOF14 (0xCE) 均为渐进式（Progressive）
      const progressive = marker === 0xc2 || marker === 0xc6 || marker === 0xca || marker === 0xce
      return { progressive, width, height, unsubsampled }
    }

    offset += segmentLength
  }
  return null
}

/** 判断该 JPEG 帧是否需要在浏览器端转为标准基线 JPEG。 */
export function shouldTranscodeProgressiveJpeg(info: JpegFrameInfo | null): boolean {
  if (!info || !info.progressive) return false
  const pixels = info.width * info.height
  return pixels >= PROGRESSIVE_TRANSCODE_MIN_PIXELS
}

/** 计算客户端重编码目标尺寸（按比例缩放至 maxDimension 内，不放大）。 */
export function computeTranscodeDimensions(
  width: number,
  height: number,
  maxDimension: number = TRANSCODE_MAX_DIMENSION,
): { width: number; height: number } {
  const longest = Math.max(width, height)
  if (longest <= maxDimension) return { width, height }
  const scale = maxDimension / longest
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  }
}

/**
 * 预处理待上传图片：若为超大分辨率的渐进式 JPEG，则在浏览器端转为标准基线 JPEG，
 * 让服务端走流式解码以大幅降低内存占用；其它文件直接原样返回。
 *
 * 如果浏览器不支持 Canvas / createImageBitmap 或重编码异常，静默回退原文件，
 * 由服务端按原有校验逻辑兜底，绝不阻塞正常上传。
 */
export async function prepareImageForUpload(file: File): Promise<File> {
  if (file.type !== 'image/jpeg' && !/\.jpe?g$/i.test(file.name)) return file
  if (typeof document === 'undefined' && typeof OffscreenCanvas === 'undefined') return file

  try {
    const headerSlice = file.slice(0, Math.min(file.size, HEADER_SNIFF_BYTES))
    const info = inspectJpegFrame(new Uint8Array(await headerSlice.arrayBuffer()))
    if (!shouldTranscodeProgressiveJpeg(info)) return file

    const blob = await transcodeToBaselineJpeg(file)
    if (!blob || blob.size <= 0) return file

    return new File([blob], file.name, {
      type: 'image/jpeg',
      lastModified: file.lastModified,
    })
  } catch {
    return file
  }
}

async function transcodeToBaselineJpeg(file: File): Promise<Blob | null> {

  if (typeof createImageBitmap === 'function') {
    // 不直接传 resizeWidth/resizeHeight：带 EXIF 旋转 6/8 的竖拍照片在未解码前宽和高是反的，
    // 强行按 SOF 原始宽高 resize 会把图像拉扁。先按 EXIF 自动旋正解码，再按实际宽高等比缩放。
    const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
    try {
      const scaled = computeTranscodeDimensions(bitmap.width, bitmap.height)
      if (typeof OffscreenCanvas !== 'undefined') {
        const canvas = new OffscreenCanvas(scaled.width, scaled.height)
        const ctx = canvas.getContext('2d')
        if (!ctx) return null
        ctx.drawImage(bitmap, 0, 0, scaled.width, scaled.height)
        return await canvas.convertToBlob({ type: 'image/jpeg', quality: TRANSCODE_JPEG_QUALITY })
      }
      const canvas = document.createElement('canvas')
      canvas.width = scaled.width
      canvas.height = scaled.height
      const ctx = canvas.getContext('2d')
      if (!ctx) return null
      ctx.drawImage(bitmap, 0, 0, scaled.width, scaled.height)
      return await new Promise<Blob | null>(resolve =>
        canvas.toBlob(resolve, 'image/jpeg', TRANSCODE_JPEG_QUALITY))
    } finally {
      bitmap.close()
    }
  }

  return null
}
