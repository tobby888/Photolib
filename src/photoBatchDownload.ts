import { api } from './api'
import type { EntityId } from './types'
import { waitForExportJob, type ExportJobView, type WaitOptions } from './batchDownloadProgress'

/**
 * 站内打包下载：建任务，一直等到打包完成，返回 ZIP 的下载地址。
 * 进度经 `onProgress` 交给弹窗（`BatchDownloadModal.tsx`），`signal` 让用户能停止等待。
 */
export async function preparePhotoBatchDownload(
  photoIds: EntityId[], options: Pick<WaitOptions, 'onProgress' | 'signal'> = {},
): Promise<string> {
  const job = await api<{ id: string }>({
    method: 'POST',
    url: '/photos/batch-download',
    data: { photoIds },
  })
  return waitForExportJob(() => api<ExportJobView>({ url: `/export-jobs/${job.id}` }), {
    ...options,
    failureMessage: '这批图片没能打包成 ZIP。可能其中有图片正在处理或已被删除，重新选一次再试。',
  })
}
