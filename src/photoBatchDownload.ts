import { api } from './api'
import type { EntityId } from './types'

interface ExportJobView {
  job: {
    status: 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED'
    errorMessage?: string
  }
  downloadUrl?: string
}

const wait = (milliseconds: number) => new Promise(resolve => window.setTimeout(resolve, milliseconds))

export async function preparePhotoBatchDownload(photoIds: EntityId[]): Promise<string | null> {
  const job = await api<{ id: string }>({
    method: 'POST',
    url: '/photos/batch-download',
    data: { photoIds },
  })
  for (let attempt = 0; attempt < 60; attempt += 1) {
    const result = await api<ExportJobView>({ url: `/export-jobs/${job.id}` })
    if (result.job.status === 'SUCCEEDED' && result.downloadUrl) return result.downloadUrl
    if (result.job.status === 'FAILED') {
      throw new Error(result.job.errorMessage
        || '这批图片没能打包成 ZIP。可能其中有图片正在处理或已被删除，重新选一次再试。')
    }
    await wait(1000)
  }
  return null
}
