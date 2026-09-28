import { normalizeRecruitmentFormSchema, type RecruitmentFormSchema } from './recruitmentForm.ts'
import { normalizeFormFileLimits, normalizeFormFileViews, type FormFileLimits, type FormFileView } from './formFiles.ts'

export type SurveyStatus = 'DRAFT' | 'PUBLISHED' | 'CLOSED'

export interface Survey {
  id: string
  title: string
  description: string | null
  introMarkdown: string | null
  formSchema: RecruitmentFormSchema
  endsAt: string | null
  status: SurveyStatus
  open: boolean
  creatorDisplayName: string | null
  publishedAt: string | null
  closedAt: string | null
  targetCount: number
  responseCount: number
  /** 只有发起人拿详情时才有。 */
  targetUserIds: string[] | null
  version: number
  createdAt?: string
}

export interface AssignedSurvey {
  id: string
  title: string
  description: string | null
  endsAt: string | null
  status: SurveyStatus
  open: boolean
  publishedAt: string | null
  creatorDisplayName: string | null
  submittedAt: string | null
}

export interface SurveyFill {
  id: string
  title: string
  description: string | null
  introMarkdown: string | null
  formSchema: RecruitmentFormSchema
  endsAt: string | null
  status: SurveyStatus
  open: boolean
  uploadLimits: FormFileLimits
  myResponse: {
    id: string
    submittedAt: string
    formSchema: RecruitmentFormSchema
    answers: Record<string, unknown>
    files: FormFileView[]
  } | null
}

export interface AudienceCandidate {
  id: string
  displayName: string
  username: string
  permissionGroupId: string
  permissionGroupName: string
  campusIds: string[]
}

export interface AudienceOptions {
  candidates: AudienceCandidate[]
  permissionGroups: { id: string; name: string }[]
  campuses: { id: string; name: string }[]
}

export interface SurveyResponseSummary {
  id: string
  userId: string
  displayName: string
  username: string
  permissionGroupName: string | null
  submittedAt: string
}

export interface SurveyTargetStatus {
  userId: string
  displayName: string
  username: string
  permissionGroupName: string | null
  campusNames: string[]
  responseId: string | null
  submittedAt: string | null
}

export interface SurveyResponseDetail extends SurveyResponseSummary {
  surveyId: string
  surveyTitle: string
  formSchema: RecruitmentFormSchema
  answers: Record<string, unknown>
  files: FormFileView[]
}

export interface SurveySummary {
  responseCount: number
  fields: {
    fieldId: string
    label: string
    type: 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE'
    answeredCount: number
    options: { option: string; count: number }[]
  }[]
}

function object(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? value as Record<string, unknown> : {}
}

function text(value: unknown, fallback = '') {
  return typeof value === 'string' ? value : fallback
}

function nullableText(value: unknown) {
  return typeof value === 'string' && value ? value : null
}

function id(value: unknown) {
  return value === null || value === undefined ? '' : String(value)
}

function count(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

function status(value: unknown): SurveyStatus {
  return value === 'PUBLISHED' || value === 'CLOSED' ? value : 'DRAFT'
}

export function normalizeSurvey(value: unknown): Survey {
  const raw = object(value)
  return {
    id: id(raw.id),
    title: text(raw.title, '未命名问卷'),
    description: nullableText(raw.description),
    introMarkdown: nullableText(raw.introMarkdown),
    formSchema: normalizeRecruitmentFormSchema(raw.formSchema),
    endsAt: nullableText(raw.endsAt),
    status: status(raw.status),
    open: raw.open === true,
    creatorDisplayName: nullableText(raw.creatorDisplayName),
    publishedAt: nullableText(raw.publishedAt),
    closedAt: nullableText(raw.closedAt),
    targetCount: count(raw.targetCount),
    responseCount: count(raw.responseCount),
    targetUserIds: Array.isArray(raw.targetUserIds) ? raw.targetUserIds.map(id) : null,
    version: count(raw.version) || 1,
    createdAt: text(raw.createdAt) || undefined,
  }
}

export function normalizeAssignedSurvey(value: unknown): AssignedSurvey {
  const raw = object(value)
  return {
    id: id(raw.id),
    title: text(raw.title, '未命名问卷'),
    description: nullableText(raw.description),
    endsAt: nullableText(raw.endsAt),
    status: status(raw.status),
    open: raw.open === true,
    publishedAt: nullableText(raw.publishedAt),
    creatorDisplayName: nullableText(raw.creatorDisplayName),
    submittedAt: nullableText(raw.submittedAt),
  }
}

export function normalizeSurveyFill(value: unknown): SurveyFill {
  const raw = object(value)
  const mine = raw.myResponse ? object(raw.myResponse) : null
  return {
    id: id(raw.id),
    title: text(raw.title, '未命名问卷'),
    description: nullableText(raw.description),
    introMarkdown: nullableText(raw.introMarkdown),
    formSchema: normalizeRecruitmentFormSchema(raw.formSchema),
    endsAt: nullableText(raw.endsAt),
    status: status(raw.status),
    open: raw.open === true,
    uploadLimits: normalizeFormFileLimits(raw.uploadLimits),
    myResponse: mine ? {
      id: id(mine.id),
      submittedAt: text(mine.submittedAt),
      formSchema: normalizeRecruitmentFormSchema(mine.formSchema),
      answers: object(mine.answers),
      files: normalizeFormFileViews(mine.files),
    } : null,
  }
}

export function normalizeAudienceOptions(value: unknown): AudienceOptions {
  const raw = object(value)
  const options = (list: unknown) => Array.isArray(list)
    ? list.map(item => ({ id: id(object(item).id), name: text(object(item).name) }))
    : []
  return {
    candidates: Array.isArray(raw.candidates) ? raw.candidates.map(item => {
      const candidate = object(item)
      return {
        id: id(candidate.id),
        displayName: text(candidate.displayName),
        username: text(candidate.username),
        permissionGroupId: id(candidate.permissionGroupId),
        permissionGroupName: text(candidate.permissionGroupName),
        campusIds: Array.isArray(candidate.campusIds) ? candidate.campusIds.map(id) : [],
      }
    }) : [],
    permissionGroups: options(raw.permissionGroups),
    campuses: options(raw.campuses),
  }
}

export function normalizeResponseSummary(value: unknown): SurveyResponseSummary {
  const raw = object(value)
  return {
    id: id(raw.id),
    userId: id(raw.userId),
    displayName: text(raw.displayName),
    username: text(raw.username),
    permissionGroupName: nullableText(raw.permissionGroupName),
    submittedAt: text(raw.submittedAt),
  }
}

export function normalizeTargetStatus(value: unknown): SurveyTargetStatus {
  const raw = object(value)
  return {
    userId: id(raw.userId),
    displayName: text(raw.displayName),
    username: text(raw.username),
    permissionGroupName: nullableText(raw.permissionGroupName),
    campusNames: Array.isArray(raw.campusNames) ? raw.campusNames.map(item => text(item)) : [],
    responseId: raw.responseId === null || raw.responseId === undefined ? null : id(raw.responseId),
    submittedAt: nullableText(raw.submittedAt),
  }
}

export function normalizeResponseDetail(value: unknown): SurveyResponseDetail {
  const raw = object(value)
  return {
    ...normalizeResponseSummary(raw),
    surveyId: id(raw.surveyId),
    surveyTitle: text(raw.surveyTitle, '问卷'),
    formSchema: normalizeRecruitmentFormSchema(raw.formSchema),
    answers: object(raw.answers),
    files: normalizeFormFileViews(raw.files),
  }
}

export function normalizeSurveySummary(value: unknown): SurveySummary {
  const raw = object(value)
  return {
    responseCount: count(raw.responseCount),
    fields: Array.isArray(raw.fields) ? raw.fields.map(item => {
      const field = object(item)
      return {
        fieldId: text(field.fieldId),
        label: text(field.label),
        type: field.type === 'MULTIPLE_CHOICE' ? 'MULTIPLE_CHOICE' as const : 'SINGLE_CHOICE' as const,
        answeredCount: count(field.answeredCount),
        options: Array.isArray(field.options)
          ? field.options.map(option => ({ option: text(object(option).option), count: count(object(option).count) }))
          : [],
      }
    }) : [],
  }
}

/** 问卷状态给人看的样子：进行中的再按截止时间细分。 */
export function surveyStatusDisplay(survey: Pick<Survey, 'status' | 'open'>) {
  if (survey.status === 'DRAFT') return { label: '草稿', color: 'default' }
  if (survey.status === 'CLOSED') return { label: '已结束', color: 'blue' }
  return survey.open ? { label: '进行中', color: 'green' } : { label: '已截止', color: 'orange' }
}

/**
 * 发放对象筛选：权限组、校区（都是「任选其一」）加姓名/账号搜索，三者同时满足。
 * 空条件表示不限。
 */
export function filterAudience(
  candidates: AudienceCandidate[],
  { groupIds = [], campusIds = [], keyword = '' }: { groupIds?: string[]; campusIds?: string[]; keyword?: string },
) {
  const needle = keyword.trim().toLowerCase()
  return candidates.filter(candidate =>
    (!groupIds.length || groupIds.includes(candidate.permissionGroupId))
    && (!campusIds.length || candidate.campusIds.some(campusId => campusIds.includes(campusId)))
    && (!needle || candidate.displayName.toLowerCase().includes(needle)
      || candidate.username.toLowerCase().includes(needle)))
}

/** 把一批人并进 / 移出已选名单，保持原有顺序、不重复。 */
export function addToSelection(selected: string[], ids: string[]) {
  const set = new Set(selected)
  return [...selected, ...ids.filter(value => !set.has(value) && Boolean(set.add(value)))]
}

export function removeFromSelection(selected: string[], ids: string[]) {
  const remove = new Set(ids)
  return selected.filter(value => !remove.has(value))
}
