import type { ShareUploaderIdentity } from './projectShare'
import type { BatchUploadStatus, EntityId } from './types'
import { DEFAULT_UPLOAD_LIMITS, describeBytes, type UploadLimits } from './uploadLimits.ts'

/**
 * 访客上传用到的那几项限额：单张、ZIP 大小都与站内同一份（管理员在「上传限额」里设的
 * `PHOTO_*`），页面从 `/upload-limits` 取到后传进来，没取到时用内置默认值。
 */
export type ShareUploadLimits = Pick<UploadLimits, 'PHOTO_IMAGE_MAX_BYTES' | 'PHOTO_ZIP_MAX_BYTES'>
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
  /** 预设标签：入队时取「这批照片的标签」，传完之后还可以逐张改。 */
  tags: string[]
  /** 这张记在谁名下。签票据时才定（会话中途可以切换拍摄者），之前为空。 */
  photographerName?: string
  photographerStudentId?: string
}

/**
 * 列表里这一张现在能不能改标签：已经处理完，并且记在当前拍摄者名下。
 * 与服务端 `ProjectShareUploadService.updateTags` 同一条线——处理中途改标签会让压缩管线的
 * 提交失败；记在别人名下的要先切换回那个人。
 */
export function canRetag(item: ShareUploadItem, currentStudentId?: string | null): boolean {
  return item.stage === 'done' && !!item.photoId && !!currentStudentId
    && item.photographerStudentId === currentStudentId
}

/**
 * 前端的文件校验，与后端 `PhotoService.validateUploadFile` 同一套规则。
 *
 * <p>这里拦下来只是为了不让访客白等一次上传——真正作数的仍然是服务端那一遍，
 * 以及压缩管线里的魔数校验。放宽这里不会放宽任何东西。</p>
 */
export function rejectReason(
  file: { name: string; type: string; size: number },
  limits: ShareUploadLimits = DEFAULT_UPLOAD_LIMITS,
): string | null {
  const name = file.name.toLowerCase()
  const jpeg = file.type === 'image/jpeg' && (name.endsWith('.jpg') || name.endsWith('.jpeg'))
  const png = file.type === 'image/png' && name.endsWith('.png')
  if (!jpeg && !png) return `${file.name}：只能传 JPG 或 PNG 图片`
  if (file.size <= 0) return `${file.name}：这个文件是空的`
  if (file.size > limits.PHOTO_IMAGE_MAX_BYTES) {
    return `${file.name}：单张不能超过 ${describeBytes(limits.PHOTO_IMAGE_MAX_BYTES)}`
  }
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
export function addToQueue(
  existing: ShareUploadItem[],
  files: File[],
  limits: ShareUploadLimits = DEFAULT_UPLOAD_LIMITS,
  tags: readonly string[] = [],
): QueueAdditions {
  const seen = new Set(existing.map(item => signature(item.file)))
  const items: ShareUploadItem[] = []
  const errors: string[] = []
  let dropped = 0
  files.forEach((file, index) => {
    const reason = rejectReason(file, limits)
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
      tags: [...tags],
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
 * 把失败的那几张放回「等待上传」，返回新的列表和需要重新入队的条目。
 * 已完成和正在跑的不动——重试只该重传没传上去的，不该把传成功的再传一次。
 */
export function markFailedForRetry(items: ShareUploadItem[]) {
  const retried: ShareUploadItem[] = []
  const next = items.map(item => {
    if (item.stage !== 'failed') return item
    const reset = { ...item, stage: 'waiting' as const, percent: 0, error: undefined }
    retried.push(reset)
    return reset
  })
  return { items: next, retried }
}

/** ZIP 的前端校验，与后端 `ProjectShareUploadService.createZipTicket` 同一套规则。 */
export function rejectArchiveReason(
  file: { name: string; size: number },
  limits: ShareUploadLimits = DEFAULT_UPLOAD_LIMITS,
): string | null {
  if (!file.name.toLowerCase().endsWith('.zip')) return '只能上传 .zip 压缩包'
  if (file.size <= 0) return '这个压缩包是空的'
  if (file.size > limits.PHOTO_ZIP_MAX_BYTES) {
    return `ZIP 压缩包不得超过 ${describeBytes(limits.PHOTO_ZIP_MAX_BYTES)}`
  }
  return null
}

/** 批次跑到头了没有。终态之外都还在后台动，值得再问一次。 */
export function isTerminalBatchStatus(status: BatchUploadStatus) {
  return status === 'SUCCEEDED' || status === 'PARTIALLY_SUCCEEDED' || status === 'FAILED'
}

export interface BatchOutcome {
  tone: 'success' | 'warning' | 'error'
  message: string
}

/**
 * 把批次的终态翻译成一句给访客看的话。
 *
 * <p>「部分成功」必须说成功了几张、失败了几张：访客手里那一包是他自己的，
 * 只说"完成了"会让他以为整包都进去了，而少掉的那几张没有人会再去找。</p>
 */
export function describeBatchOutcome(batch: {
  status: BatchUploadStatus; successCount: number; failureCount: number; failureReason?: string | null
}): BatchOutcome {
  if (batch.status === 'SUCCEEDED') {
    return { tone: 'success', message: `压缩包里的 ${batch.successCount} 张图片已经全部上传完成` }
  }
  if (batch.status === 'PARTIALLY_SUCCEEDED') {
    return {
      tone: 'warning',
      message: `已完成 ${batch.successCount} 张，有 ${batch.failureCount} 张没能处理成功`
        + '（多半是文件本身损坏），需要的话把这几张单独再传一次',
    }
  }
  if (batch.status === 'FAILED') {
    return {
      tone: 'error',
      message: batch.failureReason
        || '压缩包没能解开。请确认它是完整的 .zip，里面有 JPG / PNG 图片，然后重新上传。',
    }
  }
  // 还没跑完就被问到结果：后台仍在处理，不要说成失败。
  return { tone: 'warning', message: '图片仍在后台处理，稍后可以问发给你链接的同学确认' }
}

export interface UploadPump<T> {
  /** 接到队尾。空着就立刻开跑；正在跑就由现有的并发位顺带取走，不另起一条。 */
  push(items: T[]): void
  /** 还有没跑完的（排队中或正在跑）。 */
  readonly busy: boolean
}

/**
 * 一条常驻的上传队列，最多 {@link UPLOAD_CONCURRENCY} 个并发。
 *
 * <p>拖进来就传，不再等一个「开始上传」按钮：访客把照片拖进来之后普遍以为已经在传了，
 * 页面一关，那一批其实一张都没出门。所以队列不是"一次跑完一批"，而是一直开着——
 * 第一批还在传的时候再拖一批进来，接在后面排队，同一组并发位接着取，不会多开一条
 * 把并发翻倍。</p>
 *
 * <p>一张失败不打断其余的：活动现场的一批照片里有一张坏文件是常事，因为它停下
 * 整批会让人重新从头挑一遍。失败的那几张留在列表里，可以单独重试。worker 自己
 * 抛出的异常在这里吞掉，免得成为没人接的 rejection；该记的失败由 worker 自己记。</p>
 *
 * <p>{@code onIdle} 在队列从忙变闲时调一次——排队的和正在跑的都清空之后。</p>
 */
export function createUploadPump<T>(
  worker: (item: T) => Promise<void>,
  onIdle: () => void = () => {},
  concurrency = UPLOAD_CONCURRENCY,
): UploadPump<T> {
  const queue: T[] = []
  let active = 0
  const pump = () => {
    while (active < concurrency && queue.length) {
      const item = queue.shift() as T
      active += 1
      void Promise.resolve()
        .then(() => worker(item))
        .catch(() => {})
        .finally(() => {
          active -= 1
          pump()
          if (!active && !queue.length) onIdle()
        })
    }
  }
  return {
    push(items) {
      if (!items.length) return
      queue.push(...items)
      pump()
    },
    get busy() {
      return active > 0 || queue.length > 0
    },
  }
}

/** 每条链接最多记住几个用过的拍摄者，供「切换拍摄者」一键切回。 */
export const MAX_REMEMBERED_UPLOADERS = 5

const uploadersKey = (token: string) => `photolib_share_uploaders_${token}`

/**
 * 把一个用过的拍摄者记到最前面：同一个学号只留一份（以最新填的姓名为准），最多
 * {@link MAX_REMEMBERED_UPLOADERS} 个。替两个人交图的人会在两个身份之间来回切，
 * 每次都重敲一遍姓名学号既慢又容易敲错——敲错一位，照片和工时就记到了别人名下。
 */
export function rememberUploader(
  list: readonly ShareUploaderIdentity[], identity: ShareUploaderIdentity,
): ShareUploaderIdentity[] {
  const studentId = identity.uploaderStudentId.trim()
  const entry = { uploaderName: identity.uploaderName.trim(), uploaderStudentId: studentId }
  if (!entry.uploaderName || !studentId) return [...list]
  return [entry, ...list.filter(item => item.uploaderStudentId !== studentId)]
    .slice(0, MAX_REMEMBERED_UPLOADERS)
}

/**
 * 读这条链接上用过的拍摄者。只是本机的便利：存不下、读不出都当成没有，页面照常可用；
 * 存的是访客自己填过的姓名学号，按 token 分键，和会话一样不跨链接。
 */
export function readRememberedUploaders(token: string): ShareUploaderIdentity[] {
  try {
    const raw = localStorage.getItem(uploadersKey(token))
    const parsed: unknown = raw ? JSON.parse(raw) : []
    if (!Array.isArray(parsed)) return []
    return parsed.filter((item): item is ShareUploaderIdentity =>
      typeof item?.uploaderName === 'string' && typeof item?.uploaderStudentId === 'string')
      .slice(0, MAX_REMEMBERED_UPLOADERS)
  } catch {
    return []
  }
}

export function storeRememberedUploaders(token: string, list: readonly ShareUploaderIdentity[]) {
  try {
    localStorage.setItem(uploadersKey(token), JSON.stringify(list))
  } catch {
    // 存不下只是下次要重新输一遍。
  }
}
