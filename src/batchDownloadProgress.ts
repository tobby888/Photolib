/**
 * 打包下载的进度：轮询导出任务、把任务状态翻成弹窗里的一行字和一个百分比。
 * 站内（`photoBatchDownload.ts`）和分享链接（`projectShare.ts`）共用；不依赖 React 和请求层，
 * node 单测可以直接跑。
 *
 * 轮询不设固定次数上限：两百张原图打包几分钟是正常的，早先写死的 60 秒一到前端就放弃了，
 * 后端其实还在打。现在只在这几种情况下停：成功、失败、用户点了停止、接口连续出错。
 * 服务重启这类"任务再也不会动"的情况由后端看门狗判为失败（`ExportJobWatchdog`）。
 */

export type ExportJobStatus = 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED'
export type ExportJobPhase = 'PACKING' | 'UPLOADING'

export interface ExportJobProgress {
  status: ExportJobStatus
  progress?: number | null
  phase?: ExportJobPhase | null
  totalItems?: number | null
  doneItems?: number | null
  totalBytes?: number | null
  doneBytes?: number | null
  errorMessage?: string | null
}

export interface ExportJobView {
  job: ExportJobProgress
  downloadUrl?: string | null
}

export interface ProgressDescription {
  percent: number
  title: string
  detail?: string
}

export function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value.toFixed(value >= 100 ? 0 : 1)} ${units[unit]}`
}

const clampPercent = (value: number | null | undefined) =>
  Math.max(0, Math.min(100, Math.round(Number.isFinite(value) ? value as number : 0)))

/** `requested` 是用户选了几张，任务还没开始、后端还没写总数时用它。 */
export function describeExportProgress(job: ExportJobProgress | undefined, requested: number): ProgressDescription {
  if (!job || job.status === 'PENDING') {
    return { percent: 0, title: `正在排队，马上开始打包 ${requested} 张图片…` }
  }
  if (job.status === 'SUCCEEDED') return { percent: 100, title: '打包完成，开始下载' }
  if (job.status === 'FAILED') {
    return { percent: clampPercent(job.progress), title: '打包失败', detail: job.errorMessage || undefined }
  }
  const percent = clampPercent(job.progress)
  const total = job.totalItems ?? requested
  const hasBytes = typeof job.totalBytes === 'number' && job.totalBytes > 0 && typeof job.doneBytes === 'number'
  if (job.phase === 'UPLOADING') {
    return {
      percent,
      title: `已打包 ${total} 张，正在生成下载文件…`,
      detail: hasBytes ? `${formatBytes(job.doneBytes as number)} / ${formatBytes(job.totalBytes as number)}` : undefined,
    }
  }
  const done = Math.min(job.doneItems ?? 0, total)
  return {
    percent,
    title: `正在打包 ${done} / ${total} 张`,
    detail: hasBytes
      ? `已读取 ${formatBytes(Math.min(job.doneBytes as number, job.totalBytes as number))} / ${formatBytes(job.totalBytes as number)}`
      : undefined,
  }
}

/** 用户点了「停止等待」。 */
export class BatchDownloadCancelled extends Error {
  constructor() {
    super('已停止等待打包')
    this.name = 'BatchDownloadCancelled'
  }
}

export interface WaitOptions {
  onProgress?: (job: ExportJobProgress) => void
  signal?: AbortSignal
  intervalMs?: number
  /** 接口连续出错几次后放弃；偶尔一次网络抖动不该打断几分钟的打包。 */
  maxConsecutiveErrors?: number
  failureMessage?: string
  sleep?: (milliseconds: number, signal?: AbortSignal) => Promise<void>
}

const abortableSleep = (milliseconds: number, signal?: AbortSignal) => new Promise<void>((resolve, reject) => {
  if (signal?.aborted) {
    reject(new BatchDownloadCancelled())
    return
  }
  const timer = setTimeout(() => {
    signal?.removeEventListener('abort', onAbort)
    resolve()
  }, milliseconds)
  const onAbort = () => {
    clearTimeout(timer)
    reject(new BatchDownloadCancelled())
  }
  signal?.addEventListener('abort', onAbort, { once: true })
})

/** 一直轮询到任务结束，返回下载地址；失败、取消、接口连续出错时抛错。 */
export async function waitForExportJob(fetchJob: () => Promise<ExportJobView>, options: WaitOptions = {}): Promise<string> {
  const {
    onProgress, signal, intervalMs = 1000, maxConsecutiveErrors = 5,
    failureMessage = '这批图片没能打包成 ZIP，请重新选择后再试。', sleep = abortableSleep,
  } = options
  let errors = 0
  for (;;) {
    if (signal?.aborted) throw new BatchDownloadCancelled()
    let view: ExportJobView | undefined
    try {
      view = await fetchJob()
      errors = 0
    } catch (error) {
      if (signal?.aborted) throw new BatchDownloadCancelled()
      errors += 1
      if (errors >= maxConsecutiveErrors) throw error
    }
    if (signal?.aborted) throw new BatchDownloadCancelled()
    if (view) {
      onProgress?.(view.job)
      if (view.job.status === 'SUCCEEDED') {
        if (view.downloadUrl) return view.downloadUrl
        throw new Error('打包结果已过期，请重新发起下载')
      }
      if (view.job.status === 'FAILED') throw new Error(view.job.errorMessage || failureMessage)
    }
    await sleep(intervalMs, signal)
  }
}
