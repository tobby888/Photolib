import type { UploadFile } from 'antd'
import { describeBytes } from './recruitmentUpload.ts'
import { FORM_FILE_LIMITS, type RecruitmentFormField } from './recruitmentForm.ts'
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

/** 选文件那一刻就挡掉的问题：太多、太大、空文件。返回第一条要给人看的提示。 */
export function validateFormFileSelection(
  files: { name: string; size: number }[],
  limits: FormFileLimits = FORM_FILE_LIMITS,
) {
  if (files.length > limits.maxFilesPerField) return `这道题最多传 ${limits.maxFilesPerField} 个文件`
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

/** 表单里「上传文件」题当前选中的文件（还没上传）。 */
export function selectedFormFiles(value: unknown): File[] {
  return Array.isArray(value)
    ? value.filter((item): item is UploadFile => typeof item === 'object' && item !== null).map(nativeFile)
    : []
}

/**
 * 把答案里「上传文件」题选中的文件逐个直传，换成服务端认的 fileId。
 * {@code requestTicket} 由调用方决定打哪个接口（招募草稿 / 问卷），其余流程两边一样。
 */
export async function uploadFileAnswers(
  fields: RecruitmentFormField[],
  answers: Record<string, unknown>,
  requestTicket: (request: { fieldId: string; fileName: string; contentType: string; size: number }) => Promise<FormFileTicket>,
  onProgress?: (message: string, percent: number) => void,
): Promise<Record<string, unknown>> {
  const jobs = fields
    .filter(field => field.type === 'FILE_UPLOAD')
    .flatMap(field => selectedFormFiles(answers[field.id]).map(file => ({ field, file })))
  const result: Record<string, unknown> = { ...answers }
  fields.filter(field => field.type === 'FILE_UPLOAD').forEach(field => { result[field.id] = [] })
  for (let index = 0; index < jobs.length; index += 1) {
    const { field, file } = jobs[index]
    onProgress?.(`正在上传 ${file.name}（第 ${index + 1} 个，共 ${jobs.length} 个）…`,
      Math.round(index / jobs.length * 100))
    const ticket = await requestTicket({
      fieldId: field.id,
      fileName: file.name,
      contentType: file.type || 'application/octet-stream',
      size: file.size,
    })
    try {
      await uploadToObjectStorage(ticket, file, percent => onProgress?.(
        `正在上传 ${file.name}（第 ${index + 1} 个，共 ${jobs.length} 个）…`,
        Math.round((index + percent / 100) / jobs.length * 100)))
    } catch {
      throw new Error(`「${file.name}」没能上传成功。请检查网络后重试；如果一直不行，请联系管理员。`)
    }
    ;(result[field.id] as string[]).push(ticket.fileId)
  }
  onProgress?.('文件都传好了', 100)
  return result
}
