import type { EntityId } from './types'

/**
 * 管理员直接改图片信息（`PUT /photos/{id}/admin-metadata`、`POST /photos/batch-metadata`）
 * 的前端约定。长度上限与 PhotoController 上的注解一致，`tests/photoMetadata.test.ts` 会对照源码。
 */
export const PHOTO_METADATA_LIMITS = {
  title: 200,
  description: 5000,
  photographerName: 100,
  photographerStudentId: 64,
} as const

/** 批量编辑可以改的几项。不含拍摄时间：一批图改成同一时刻会抹掉先后顺序。 */
export type BatchMetadataField = 'photographer' | 'campus' | 'title' | 'description'

export const BATCH_METADATA_FIELDS: { value: BatchMetadataField; label: string }[] = [
  { value: 'photographer', label: '拍摄者' },
  { value: 'campus', label: '校区' },
  { value: 'title', label: '标题' },
  { value: 'description', label: '说明' },
]

export interface BatchMetadataValues {
  fields?: BatchMetadataField[]
  photographerName?: string
  photographerStudentId?: string
  /** 清空（undefined / null）表示改成「无校区」。 */
  campusId?: EntityId | null
  title?: string
  description?: string
  syncAdoptions?: boolean
}

export interface BatchMetadataPayload {
  photoIds: EntityId[]
  title?: string
  description?: string
  photographer?: { name: string; studentId: string }
  /** 包一层：缺省是「不改校区」，`{ campusId: null }` 是「改成无校区」。 */
  campus?: { campusId: EntityId | null }
  syncAdoptions: boolean
}

/** 只把勾选了的项放进请求体，没勾的项后端一概不动。 */
export function batchMetadataPayload(photoIds: EntityId[], values: BatchMetadataValues): BatchMetadataPayload {
  const fields = new Set(values.fields ?? [])
  const payload: BatchMetadataPayload = { photoIds, syncAdoptions: values.syncAdoptions !== false }
  if (fields.has('photographer')) {
    payload.photographer = {
      name: (values.photographerName ?? '').trim(),
      studentId: (values.photographerStudentId ?? '').trim(),
    }
  }
  if (fields.has('campus')) payload.campus = { campusId: values.campusId ?? null }
  if (fields.has('title')) payload.title = (values.title ?? '').trim()
  // 说明留空就是清空，所以空串也要发出去。
  if (fields.has('description')) payload.description = values.description ?? ''
  return payload
}

/** 拍摄者有没有被改动——没改就不必问要不要同步被引记录。 */
export function photographerChanged(
  photo: { photographerName: string; photographerStudentId: string },
  name: string | undefined, studentId: string | undefined,
) {
  return (name ?? '').trim() !== photo.photographerName
    || (studentId ?? '').trim() !== (photo.photographerStudentId ?? '')
}

export interface BatchMetadataResult {
  photoCount: number
  changedCount: number
  adoptionsUpdated: number
}

/** 把多批的结果加起来，并写成一句给人看的话。 */
export function describeBatchMetadataResult(results: BatchMetadataResult[]) {
  const total = results.reduce((sum, item) => ({
    photoCount: sum.photoCount + item.photoCount,
    changedCount: sum.changedCount + item.changedCount,
    adoptionsUpdated: sum.adoptionsUpdated + item.adoptionsUpdated,
  }), { photoCount: 0, changedCount: 0, adoptionsUpdated: 0 })
  const unchanged = total.photoCount - total.changedCount
  return [
    `已修改 ${total.changedCount} 张图片`,
    unchanged ? `${unchanged} 张本来就是这些信息，没有改动` : '',
    total.adoptionsUpdated ? `同步更正了 ${total.adoptionsUpdated} 条被引记录` : '',
  ].filter(Boolean).join('，')
}
