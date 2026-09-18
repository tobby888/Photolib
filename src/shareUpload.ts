import type { EntityId } from './types'

/** 与后端 `StorageProperties.imageMaxBytes()` 的默认值一致（100 MiB）。 */
export const MAX_UPLOAD_BYTES = 100 * 1024 * 1024
/** 一次最多往队列里放多少张。再多应该分几批，否则一个误点整目录会让页面卡住。 */
export const MAX_QUEUE_SIZE = 300
/** 同时进行的上传数。活动现场多是几十上百张，串行太慢；并发太高又会互相抢带宽。 */
export const UPLOAD_CONCURRENCY = 3

export type ShareUploadStage = 'waiting' | 'uploading' | 'processing' | 'done' | 'failed'

export interface ShareUploadItem {
  /** 队列内的稳定标识，与服务端的 photoId 无关（失败重传时 photoId 会换）。 */
  key: string
  file: File
  stage: ShareUploadStage
  percent: number
  photoId?: EntityId
  error?: string
}

/**
 * 前端的文件校验，与后端 `PhotoService.validateUploadFile` 同一套规则。
 *
 * <p>这里拦下来只是为了不让访客白等一次上传——真正作数的仍然是服务端那一遍，
 * 以及压缩管线里的魔数校验。放宽这里不会放宽任何东西。</p>
 */
export function rejectReason(file: { name: string; type: string; size: number }): string | null {
  const name = file.name.toLowerCase()
  const jpeg = file.type === 'image/jpeg' && (name.endsWith('.jpg') || name.endsWith('.jpeg'))
  const png = file.type === 'image/png' && name.endsWith('.png')
  if (!jpeg && !png) return `${file.name}：只能传 JPG 或 PNG 图片`
  if (file.size <= 0) return `${file.name}：这个文件是空的`
  if (file.size > MAX_UPLOAD_BYTES) return `${file.name}：单张不能超过 100 MiB`
  return null
}

export interface QueueAdditions {
  items: ShareUploadItem[]
  errors: string[]
  /** 因为超出队列上限而没有被放进来的张数。 */
  dropped: number
}

/**
 * 把新选中的文件并入队列。
 *
 * <p>同名同大小的文件视为同一张，不重复入队：访客常常会把同一批照片再拖一次
 * 确认"到底传上去没有"，而重复入队的结果是服务端按 SHA-256 判重，一整批红着
 * 报"已经上传过该图片"——看上去像失败，其实是传成功了。</p>
 */
export function addToQueue(existing: ShareUploadItem[], files: File[]): QueueAdditions {
  const seen = new Set(existing.map(item => signature(item.file)))
  const items: ShareUploadItem[] = []
  const errors: string[] = []
  let dropped = 0
  files.forEach((file, index) => {
    const reason = rejectReason(file)
    if (reason) {
      errors.push(reason)
      return
    }
    if (seen.has(signature(file))) return
    if (existing.length + items.length >= MAX_QUEUE_SIZE) {
      dropped += 1
      return
    }
    seen.add(signature(file))
    items.push({
      key: `${Date.now()}-${index}-${file.name}`,
      file,
      stage: 'waiting',
      percent: 0,
    })
  })
  return { items, errors, dropped }
}

const signature = (file: { name: string; size: number }) => `${file.name}|${file.size}`

export interface ShareUploadSummary {
  total: number
  pending: number
  done: number
  failed: number
}

export function summarize(items: ShareUploadItem[]): ShareUploadSummary {
  return {
    total: items.length,
    pending: items.filter(item => item.stage === 'waiting' || item.stage === 'uploading'
      || item.stage === 'processing').length,
    done: items.filter(item => item.stage === 'done').length,
    failed: items.filter(item => item.stage === 'failed').length,
  }
}

/**
 * 跑完整个队列，最多 {@link UPLOAD_CONCURRENCY} 个并发。
 *
 * <p>一张失败不打断其余的：活动现场的一批照片里有一张坏文件是常事，因为它停下
 * 整批会让人重新从头挑一遍。失败的那几张留在队列里，可以单独重试。</p>
 */
export async function runQueue<T>(
  items: T[],
  worker: (item: T) => Promise<void>,
  concurrency = UPLOAD_CONCURRENCY,
) {
  let cursor = 0
  const next = async (): Promise<void> => {
    const index = cursor
    cursor += 1
    if (index >= items.length) return
    await worker(items[index])
    return next()
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, next))
}
