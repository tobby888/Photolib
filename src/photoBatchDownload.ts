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
      throw new Error(result.job.errorMessage || '图片打包失败')
    }
    await wait(1000)
  }
  return null
}
