import { normalizeRecruitmentFormSchema, type RecruitmentAnswers, type RecruitmentFormSchema } from './recruitmentForm.ts'

export type RecruitmentId = string | number
export type RecruitmentTaskStatus = 'DRAFT' | 'PUBLISHED' | 'ACTIVE' | 'CLOSED' | 'CANCELLED'

export interface PublicRecruitmentTask {
  id?: RecruitmentId
  publicId: string
  title: string
  description?: string | null
  startAt: string
  endAt: string
  formSchema: RecruitmentFormSchema
}

export interface RecruitmentTask extends PublicRecruitmentTask {
  id: RecruitmentId
  status: RecruitmentTaskStatus
  creatorId?: RecruitmentId
  creatorDisplayName?: string | null
  applicationCount?: number
  version?: number
  createdAt?: string
  updatedAt?: string
}

export interface RecruitmentDraft {
  draftId: string
  token: string
  expiresAt: string
}

export interface RecruitmentUploadTicket {
  uploadUrl: string
  method?: string
  contentType: string
  fileName?: string
  itemId?: RecruitmentId
}

export interface RecruitmentBatchView {
  batch?: {
    id?: RecruitmentId
    status: string
    totalCount?: number
    successCount?: number
    failureCount?: number
    failureReason?: string | null
  }
  id?: RecruitmentId
  status?: string
  totalCount?: number
  successCount?: number
  failureCount?: number
  failureReason?: string | null
  items?: {
    id?: RecruitmentId
    originalFileName?: string
    fileName?: string
    status?: string
    failureReason?: string | null
  }[]
}

export interface RecruitmentBatchTicketResponse extends RecruitmentBatchView {
  batchId: RecruitmentId
  tickets: RecruitmentUploadTicket[]
}

export interface RecruitmentApplicationSummary {
  id: RecruitmentId
  taskId?: RecruitmentId
  studentId: string
  submittedAt: string
  attachmentCount?: number
}

export interface RecruitmentAttachment {
  id: RecruitmentId
  fileName: string
  contentType: string
  size: number
  previewUrl?: string | null
  downloadUrl?: string | null
}

export interface RecruitmentApplicationDetail extends RecruitmentApplicationSummary {
  taskTitle?: string
  detailsMarkdown?: string | null
  answers?: RecruitmentAnswers
  formSchema?: RecruitmentFormSchema | string | null
  attachments: RecruitmentAttachment[]
}

export interface RecruitmentPage<T> {
  items: T[]
  page: number
  pageSize: number
  total: number
  totalPages: number
}

function object(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? value as Record<string, unknown> : {}
}

function string(value: unknown, fallback = '') {
  return typeof value === 'string' ? value : fallback
}

function number(value: unknown, fallback = 0) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

/**
 * Distinguishes "the server omitted this key" from "the server stored no value".
 * Substituting a built-in default for an explicit null would silently resurrect
 * text the operator deleted, and — because the backend freezes the student-id
 * and upload configuration once a task is published — would make every later
 * edit of that task fail with a spurious "表单已冻结" conflict.
 */
function storedText(value: unknown, fallbackWhenAbsent: string) {
  if (value === undefined) return fallbackWhenAbsent
  return typeof value === 'string' ? value : ''
}

export function normalizePublicRecruitmentTask(value: unknown): PublicRecruitmentTask {
  const raw = object(value)
  const formSchema = normalizeRecruitmentFormSchema(raw.formSchema ?? raw.schema)
  return {
    id: raw.id as RecruitmentId | undefined,
    publicId: string(raw.publicId || raw.id),
    title: string(raw.title, '未命名招募'),
    description: string(raw.description || raw.introMarkdown) || null,
    startAt: string(raw.startAt || raw.startsAt),
    endAt: string(raw.endAt || raw.endsAt),
    formSchema: {
      ...formSchema,
      studentId: {
        label: string(raw.studentIdLabel, formSchema.studentId.label),
        helpText: storedText(raw.studentIdHelp, formSchema.studentId.helpText),
      },
      upload: {
        label: string(raw.uploadLabel, formSchema.upload.label),
        prompt: storedText(raw.uploadHelp, formSchema.upload.prompt),
        required: typeof raw.uploadRequired === 'boolean' ? raw.uploadRequired : formSchema.upload.required,
      },
    },
  }
}

export function normalizeRecruitmentTask(value: unknown): RecruitmentTask {
  const raw = object(value)
  const publicTask = normalizePublicRecruitmentTask(raw)
  return {
    ...publicTask,
    id: (raw.id ?? raw.publicId ?? '') as RecruitmentId,
    status: string(raw.status, 'DRAFT') as RecruitmentTaskStatus,
    creatorId: (raw.creatorId ?? raw.createdBy) as RecruitmentId | undefined,
    creatorDisplayName: string(raw.creatorDisplayName || raw.creatorName) || null,
    applicationCount: number(raw.applicationCount),
    version: number(raw.version),
    createdAt: string(raw.createdAt) || undefined,
    updatedAt: string(raw.updatedAt) || undefined,
  }
}

export function normalizeRecruitmentDraft(value: unknown): RecruitmentDraft {
  const raw = object(value)
  return {
    draftId: string(raw.draftId || raw.id),
    token: string(raw.token || raw.draftToken),
    expiresAt: string(raw.expiresAt),
  }
}

export function normalizeRecruitmentPage<T>(
  value: unknown,
  itemNormalizer: (item: unknown) => T,
  requestedPage = 1,
  requestedPageSize = 20,
): RecruitmentPage<T> {
  if (Array.isArray(value)) {
    const items = value.map(itemNormalizer)
    return { items, page: 1, pageSize: Math.max(items.length, requestedPageSize), total: items.length, totalPages: 1 }
  }
  const raw = object(value)
  const items = Array.isArray(raw.items) ? raw.items.map(itemNormalizer) : []
  const page = number(raw.page, requestedPage)
  const pageSize = number(raw.pageSize, requestedPageSize)
  const total = number(raw.total, items.length)
  return {
    items,
    page,
    pageSize,
    total,
    totalPages: number(raw.totalPages, Math.max(1, Math.ceil(total / Math.max(1, pageSize)))),
  }
}

export function normalizeApplicationSummary(value: unknown): RecruitmentApplicationSummary {
  const raw = object(value)
  return {
    id: (raw.id ?? '') as RecruitmentId,
    taskId: raw.taskId as RecruitmentId | undefined,
    studentId: string(raw.studentId),
    submittedAt: string(raw.submittedAt || raw.createdAt),
    attachmentCount: typeof raw.attachmentCount === 'number' ? number(raw.attachmentCount) : undefined,
  }
}

export function normalizeApplicationDetail(value: unknown): RecruitmentApplicationDetail {
  const raw = object(value)
  const summary = normalizeApplicationSummary(raw)
  const attachments = Array.isArray(raw.attachments) ? raw.attachments.map(item => {
    const attachment = object(item)
    return {
      id: (attachment.id ?? '') as RecruitmentId,
      fileName: string(attachment.fileName || attachment.originalFileName, '未命名文件'),
      contentType: string(attachment.contentType, 'application/octet-stream'),
      size: number(attachment.size),
      previewUrl: string(attachment.previewUrl) || null,
      downloadUrl: string(attachment.downloadUrl) || null,
    }
  }) : []
  return {
    ...summary,
    taskTitle: string(raw.taskTitle) || undefined,
    detailsMarkdown: string(raw.detailsMarkdown || raw.markdown) || null,
    answers: object(raw.answers) as RecruitmentAnswers,
    formSchema: raw.formSchema as RecruitmentFormSchema | string | null | undefined,
    attachments,
  }
}
