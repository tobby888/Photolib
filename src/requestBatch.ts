import type { EntityId, PhotoRequest } from './types'

export interface RequestBatchRow {
  key: string
  batchId: string | null
  requests: PhotoRequest[]
  representative: PhotoRequest
}

/**
 * 把同一批多校区发布出来的需求合并成一行。没有 batchId 的单校区需求
 * 仍各自成行，列表顺序保持不变。
 */
export function groupPhotoRequests(requests: readonly PhotoRequest[]): RequestBatchRow[] {
  const rows: RequestBatchRow[] = []
  const byBatch = new Map<string, RequestBatchRow>()

  for (const request of requests) {
    if (!request.batchId) {
      rows.push({
        key: `request-${request.id}`,
        batchId: null,
        requests: [request],
        representative: request,
      })
      continue
    }

    const key = `batch-${request.batchId}`
    const existing = byBatch.get(key)
    if (existing) {
      existing.requests.push(request)
    } else {
      const row: RequestBatchRow = {
        key,
        batchId: request.batchId,
        requests: [request],
        representative: request,
      }
      byBatch.set(key, row)
      rows.push(row)
    }
  }

  return rows
}

export function selectedRequestFor(
  row: RequestBatchRow,
  selectedIds: Readonly<Record<string, EntityId>>,
): PhotoRequest {
  const selectedId = selectedIds[row.key]
  return row.requests.find(request => request.id === selectedId) ?? row.representative
}
