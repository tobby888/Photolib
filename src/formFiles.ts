import type { UploadFile } from 'antd'
import { describeBytes } from './recruitmentUpload.ts'
import { FORM_FILE_LIMITS, type RecruitmentFormField, type StoredFileAnswer } from './recruitmentForm.ts'
import { uploadToObjectStorage } from './storageUpload.ts'

export interface FormFileLimits {
  maxFileBytes: number
  maxFilesPerField: number
}

/** 服务端发回的直传票据：先按它 PUT 到对象存储，提交答卷时只带 fileId。 */
export interface FormFileTicket {
  fileId: string
  fileName: string
  uploadUrl: string
  method?: string
  contentType: string
}

/** 已提交答卷里的文件，下载地址由服务端临时签发。 */
export interface FormFileView {
  id: string
  fieldId: string
  fileName: string
  contentType: string
  size: number
  previewUrl?: string | null
  downloadUrl?: string | null
}

export function normalizeFormFileViews(value: unknown): FormFileView[] {
  if (!Array.isArray(value)) return []
  return value.flatMap(item => {
    if (typeof item !== 'object' || item === null) return []
    const raw = item as Record<string, unknown>
    const id = typeof raw.id === 'string' ? raw.id : ''
    if (!id) return []
    return [{
      id,
      fieldId: typeof raw.fieldId === 'string' ? raw.fieldId : '',
      fileName: typeof raw.fileName === 'string' ? raw.fileName : '未命名文件',
      contentType: typeof raw.contentType === 'string' ? raw.contentType : 'application/octet-stream',
      size: typeof raw.size === 'number' ? raw.size : 0,
      previewUrl: typeof raw.previewUrl === 'string' ? raw.previewUrl : null,
      downloadUrl: typeof raw.downloadUrl === 'string' ? raw.downloadUrl : null,
    }]
  })
}

export function normalizeFormFileLimits(value: unknown): FormFileLimits {
  const raw = typeof value === 'object' && value !== null ? value as Record<string, unknown> : {}
  const positive = (candidate: unknown, fallback: number) =>
    typeof candidate === 'number' && Number.isFinite(candidate) && candidate > 0 ? candidate : fallback
  return {
    maxFileBytes: positive(raw.maxFileBytes, FORM_FILE_LIMITS.maxFileBytes),
    maxFilesPerField: positive(raw.maxFilesPerField, FORM_FILE_LIMITS.maxFilesPerField),
  }
}

/**
 * 选文件那一刻就挡掉的问题：太多、太大、空文件。返回第一条要给人看的提示。
 * {@code alreadyStored} 是修改答卷时上一版留下来的文件数：它们占名额，但早就传过了，不再查大小。
 */
export function validateFormFileSelection(
  files: { name: string; size: number }[],
  limits: FormFileLimits = FORM_FILE_LIMITS,
  alreadyStored = 0,
) {
  if (files.length + alreadyStored > limits.maxFilesPerField) return `这道题最多传 ${limits.maxFilesPerField} 个文件`
  for (const file of files) {
    if (file.size === 0) return `「${file.name}」是空文件`
    if (file.size > limits.maxFileBytes) {
      return `「${file.name}」超过了 ${describeBytes(limits.maxFileBytes)}，请压缩后再传`
    }
    if (Array.from(file.name).length > 255) return `「${file.name}」的文件名太长了，请改短一点`
  }
  return undefined
}

function nativeFile(item: UploadFile) {
  return item.originFileObj || item as unknown as File
}

const STORED_UID_PREFIX = 'stored:'

/**
 * 修改答卷时，上一版已经交上去的文件在「上传文件」题里的样子：只有名字，没有可以再传的 File。
 * 用 uid 的前缀认它——antd 给新选的文件发的 uid 是 `rc-upload-…`，撞不上。
 */
export function storedUploadFile(file: StoredFileAnswer): UploadFile {
  return { uid: `${STORED_UID_PREFIX}${file.id}`, name: file.fileName, size: file.size, type: file.contentType, status: 'done' }
}

/** 这一项如果是上一版留下来的文件，返回它的 fileId；新选的文件返回 undefined。 */
export function storedFileId(item: unknown): string | undefined {
  if (typeof item !== 'object' || item === null) return undefined
  const { uid, originFileObj } = item as Partial<UploadFile>
  return !originFileObj && typeof uid === 'string' && uid.startsWith(STORED_UID_PREFIX)
    ? uid.slice(STORED_UID_PREFIX.length) || undefined
    : undefined
}

function formFileItems(value: unknown): UploadFile[] {
  return Array.isArray(value)
    ? value.filter((item): item is UploadFile => typeof item === 'object' && item !== null)
    : []
}

/** 表单里「上传文件」题当前选中、还没上传的文件；上一版留下来的不算。 */
export function selectedFormFiles(value: unknown): File[] {
  return formFileItems(value).filter(item => !storedFileId(item)).map(nativeFile)
}

/**
 * 把一份已经提交的答卷变回表单的初始值，用于修改答卷：「上传文件」题的存档项换成上传框认的
 * 列表项（保存时原样带回它们的 id），其余题目的答案原样填回去。
 */
export function answersForEditing(
  fields: RecruitmentFormField[],
  answers: Record<string, unknown>,
): Record<string, unknown> {
  const editable: Record<string, unknown> = {}
  fields.forEach(field => {
    const value = answers[field.id]
    if (field.type !== 'FILE_UPLOAD') {
      if (value !== null && value !== undefined) editable[field.id] = value
      return
    }
    editable[field.id] = Array.isArray(value)
      ? value.flatMap(item => {
          if (typeof item !== 'object' || item === null) return []
          const raw = item as Record<string, unknown>
          if (typeof raw.id !== 'string' || !raw.id) return []
          return [storedUploadFile({
            id: raw.id,
            fileName: typeof raw.fileName === 'string' ? raw.fileName : '未命名文件',
            contentType: typeof raw.contentType === 'string' ? raw.contentType : undefined,
            size: typeof raw.size === 'number' ? raw.size : undefined,
          })]
        })
      : []
  })
  return editable
}

/**
 * 把答案里「上传文件」题选中的文件逐个直传，换成服务端认的 fileId。
 * {@code requestTicket} 由调用方决定打哪个接口（招募草稿 / 问卷），其余流程两边一样。
 * 修改答卷时上一版留下来的文件不重传，直接带回原来的 fileId，顺序不变。
 */
export async function uploadFileAnswers(
  fields: RecruitmentFormField[],
  answers: Record<string, unknown>,
  requestTicket: (request: { fieldId: string; fileName: string; contentType: string; size: number }) => Promise<FormFileTicket>,
  onProgress?: (message: string, percent: number) => void,
): Promise<Record<string, unknown>> {
  const fileFields = fields.filter(field => field.type === 'FILE_UPLOAD')
  const items = fileFields.flatMap(field => formFileItems(answers[field.id]).map(item => ({ field, item })))
  const total = items.filter(({ item }) => !storedFileId(item)).length
  const result: Record<string, unknown> = { ...answers }
  fileFields.forEach(field => { result[field.id] = [] })
  let index = -1
  for (const { field, item } of items) {
    const stored = storedFileId(item)
    if (stored) {
      ;(result[field.id] as string[]).push(stored)
      continue
    }
    index += 1
    const file = nativeFile(item)
    onProgress?.(`正在上传 ${file.name}（第 ${index + 1} 个，共 ${total} 个）…`,
      Math.round(index / total * 100))
    const ticket = await requestTicket({
      fieldId: field.id,
      fileName: file.name,
      contentType: file.type || 'application/octet-stream',
      size: file.size,
    })
    try {
      const current = index
      await uploadToObjectStorage(ticket, file, percent => onProgress?.(
        `正在上传 ${file.name}（第 ${current + 1} 个，共 ${total} 个）…`,
        Math.round((current + percent / 100) / total * 100)))
    } catch {
      throw new Error(`「${file.name}」没能上传成功。请检查网络后重试；如果一直不行，请联系管理员。`)
    }
    ;(result[field.id] as string[]).push(ticket.fileId)
  }
  onProgress?.('文件都传好了', 100)
  return result
}
