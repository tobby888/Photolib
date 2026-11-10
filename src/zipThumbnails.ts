// 「确认标签」对话框的缩略图：从上传者本机的 ZIP 里现解、现缩，不经过服务器。
//
// 每张图解出来后缩成宽 THUMBNAIL_WIDTH 的 JPEG 再丢掉原字节，避免 100 张相机原图同时
// 解码在内存里。按需生成：对话框里哪一行渲染出来就要哪一张，新要的排在前面（翻页之后
// 先出当前这一页），同时最多 CONCURRENCY 张。缩略图拿不到（压缩方式不认、图太大、浏览器
// 解不了）就返回 null，对话框显示占位图，不影响上传。

import { readZipEntry, readZipIndex, type LocalZipEntry } from './localZip.ts'

const THUMBNAIL_WIDTH = 240
const CONCURRENCY = 2
/** 解压后超过这个大小的不生成缩略图：在浏览器里整幅解码一张上百兆的 PNG 很可能撑爆标签页。 */
const MAX_THUMBNAIL_SOURCE_BYTES = 60 * 1024 * 1024

export interface ZipThumbnailer {
  /** 路径键（zipFolderTags.archiveItemKey）对应的缩略图地址；拿不到为 null。 */
  thumbnail(key: string): Promise<string | null>
  /** 关闭对话框时调用：停掉排队的、回收已经生成的 Blob 地址。 */
  dispose(): void
}

async function shrink(source: Blob): Promise<Blob> {
  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(source, { resizeWidth: THUMBNAIL_WIDTH, resizeQuality: 'medium' })
  } catch {
    // 不认 resize 选项的浏览器：整幅解码再自己缩。
    bitmap = await createImageBitmap(source)
  }
  try {
    const scale = Math.min(1, THUMBNAIL_WIDTH / bitmap.width)
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(bitmap.width * scale))
    canvas.height = Math.max(1, Math.round(bitmap.height * scale))
    const context = canvas.getContext('2d')
    if (!context) throw new Error('canvas 不可用')
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
    return await new Promise<Blob>((resolve, reject) => canvas.toBlob(
      blob => blob ? resolve(blob) : reject(new Error('缩略图编码失败')), 'image/jpeg', 0.82))
  } finally {
    bitmap.close()
  }
}

export function createZipThumbnailer(archive: Blob): ZipThumbnailer {
  let disposed = false
  let index: Promise<Map<string, LocalZipEntry>> | null = null
  const results = new Map<string, Promise<string | null>>()
  const urls: string[] = []
  const queue: (() => Promise<void>)[] = []
  let running = 0

  const pump = () => {
    while (!disposed && running < CONCURRENCY && queue.length) {
      const job = queue.shift()!
      running++
      void job().finally(() => {
        running--
        pump()
      })
    }
  }

  const generate = async (key: string): Promise<string | null> => {
    if (disposed) return null
    index ??= readZipIndex(archive)
    const entry = (await index).get(key)
    if (!entry || entry.uncompressedSize > MAX_THUMBNAIL_SOURCE_BYTES) return null
    const thumbnail = await shrink(await readZipEntry(archive, entry))
    if (disposed) return null
    const url = URL.createObjectURL(thumbnail)
    urls.push(url)
    return url
  }

  return {
    thumbnail(key) {
      let result = results.get(key)
      if (!result) {
        result = new Promise<string | null>(resolve => {
          // 新要的插到队首：翻到新的一页时先出眼前这一页。
          queue.unshift(() => generate(key).then(resolve, () => resolve(null)))
        })
        results.set(key, result)
        pump()
      }
      return result
    },
    dispose() {
      disposed = true
      queue.length = 0
      urls.forEach(url => URL.revokeObjectURL(url))
      urls.length = 0
    },
  }
}
