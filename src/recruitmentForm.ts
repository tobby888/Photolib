export const RECRUITMENT_FIELD_TYPES = [
  'SHORT_TEXT',
  'LONG_TEXT',
  'SINGLE_CHOICE',
  'MULTIPLE_CHOICE',
  'DATE',
] as const

export type RecruitmentFieldType = typeof RECRUITMENT_FIELD_TYPES[number]

export interface RecruitmentFormField {
  id: string
  type: RecruitmentFieldType
  label: string
  helpText?: string
  placeholder?: string
  required: boolean
  options?: string[]
}

export interface RecruitmentUploadField {
  label: string
  required: boolean
  prompt: string
}

export interface RecruitmentStudentIdField {
  label: string
  helpText: string
}

export interface RecruitmentFormSchema {
  fields: RecruitmentFormField[]
  studentId: RecruitmentStudentIdField
  upload: RecruitmentUploadField
}

export type RecruitmentAnswer = string | string[]
export type RecruitmentAnswers = Record<string, RecruitmentAnswer>

export interface RecruitmentFormValidationIssue {
  fieldId?: string
  message: string
}

const choiceTypes = new Set<RecruitmentFieldType>(['SINGLE_CHOICE', 'MULTIPLE_CHOICE'])

export const EMPTY_RECRUITMENT_FORM: RecruitmentFormSchema = {
  fields: [],
  studentId: {
    label: '学号',
    helpText: '同一学号只能提交一次；学号中的前导 0 会完整保留。',
  },
  upload: {
    label: '作品上传',
    required: false,
    prompt: '请上传能展现你摄影能力的原创图片（JPG / PNG），也可以上传 ZIP 压缩包。',
  },
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function text(value: unknown, fallback = '') {
  return typeof value === 'string' ? value : fallback
}

function boolean(value: unknown, fallback = false) {
  return typeof value === 'boolean' ? value : fallback
}

function normalizeOptions(value: unknown) {
  if (!Array.isArray(value)) return []
  const seen = new Set<string>()
  return value
    .map(option => text(option).trim())
    .filter(option => option.length > 0 && !seen.has(option) && Boolean(seen.add(option)))
}

export function normalizeRecruitmentFormSchema(value: unknown): RecruitmentFormSchema {
  let source = value
  if (typeof source === 'string') {
    try {
      source = JSON.parse(source) as unknown
    } catch {
      source = undefined
    }
  }
  if (!isRecord(source)) return structuredClone(EMPTY_RECRUITMENT_FORM)

  const rawFields = Array.isArray(source.fields) ? source.fields : []
  const usedIds = new Set<string>()
  const fields = rawFields.flatMap((raw, index): RecruitmentFormField[] => {
    if (!isRecord(raw) || !RECRUITMENT_FIELD_TYPES.includes(raw.type as RecruitmentFieldType)) return []
    const preferredId = text(raw.id || raw.key).trim()
    let id = preferredId || `field_${index + 1}`
    while (usedIds.has(id)) id = `${id}_${index + 1}`
    usedIds.add(id)
    const type = raw.type as RecruitmentFieldType
    return [{
      id,
      type,
      label: text(raw.label).trim(),
      helpText: text(raw.helpText || raw.description).trim() || undefined,
      placeholder: text(raw.placeholder).trim() || undefined,
      required: boolean(raw.required),
      options: choiceTypes.has(type) ? normalizeOptions(raw.options) : undefined,
    }]
  })

  const rawUpload = isRecord(source.upload)
    ? source.upload
    : isRecord(source.uploadField)
      ? source.uploadField
      : {}
  const rawStudentId = isRecord(source.studentId) ? source.studentId : {}

  return {
    fields,
    studentId: {
      label: text(rawStudentId.label, EMPTY_RECRUITMENT_FORM.studentId.label).trim()
        || EMPTY_RECRUITMENT_FORM.studentId.label,
      helpText: text(rawStudentId.helpText, EMPTY_RECRUITMENT_FORM.studentId.helpText).trim(),
    },
    upload: {
      label: text(rawUpload.label, EMPTY_RECRUITMENT_FORM.upload.label).trim()
        || EMPTY_RECRUITMENT_FORM.upload.label,
      required: boolean(rawUpload.required),
      prompt: text(rawUpload.prompt || rawUpload.helpText, EMPTY_RECRUITMENT_FORM.upload.prompt).trim()
        || EMPTY_RECRUITMENT_FORM.upload.prompt,
    },
  }
}

export function createRecruitmentField(type: RecruitmentFieldType, existingIds: Iterable<string> = []): RecruitmentFormField {
  const used = new Set(existingIds)
  let sequence = 1
  let id = `field_${sequence}`
  while (used.has(id)) id = `field_${++sequence}`
  return {
    id,
    type,
    label: '',
    required: false,
    options: choiceTypes.has(type) ? ['选项 1', '选项 2'] : undefined,
  }
}

export function validateRecruitmentFormSchema(schema: RecruitmentFormSchema): RecruitmentFormValidationIssue[] {
  const issues: RecruitmentFormValidationIssue[] = []
  const ids = new Set<string>()
  if (schema.fields.length > 50) issues.push({ message: '自定义问题最多 50 个' })
  if (!schema.studentId?.label?.trim()) issues.push({ fieldId: 'studentId', message: '请填写学号字段标题' })
  if (!schema.upload?.label?.trim()) issues.push({ fieldId: 'attachments', message: '请填写作品上传区标题' })
  schema.fields.forEach((field, index) => {
    const position = `第 ${index + 1} 个问题`
    if (!field.id.trim()) issues.push({ fieldId: field.id, message: `${position}缺少字段标识` })
    else if (!/^[a-z][a-z0-9_-]{0,63}$/.test(field.id) || ['student_id', 'studentid', 'uploads', 'attachments'].includes(field.id)) {
      issues.push({ fieldId: field.id, message: `${position}的字段标识不合法` })
    }
    if (ids.has(field.id)) issues.push({ fieldId: field.id, message: `${position}的字段标识重复` })
    ids.add(field.id)
    if (!field.label.trim()) issues.push({ fieldId: field.id, message: `${position}缺少题目` })
    if (!RECRUITMENT_FIELD_TYPES.includes(field.type)) {
      issues.push({ fieldId: field.id, message: `${position}使用了不支持的类型` })
    }
    if (choiceTypes.has(field.type)) {
      const options = normalizeOptions(field.options)
      if (options.length < 2) issues.push({ fieldId: field.id, message: `${position}至少需要两个不重复的选项` })
    }
  })
  if (!schema.upload?.prompt?.trim()) issues.push({ message: '请填写图片上传区提示' })
  return issues
}

export function normalizeStudentId(value: unknown) {
  // Student numbers are identifiers rather than numbers. Never coerce them to a
  // numeric type: leading zeroes are meaningful at some campuses. Mirror the
  // server's NFKC/whitespace/case normalization so duplicate feedback arrives
  // before an anonymous draft or a large upload is created.
  return typeof value === 'string'
    ? value.normalize('NFKC').replace(/[\s\p{Z}]+/gu, '').toUpperCase()
    : ''
}

export function validateStudentId(value: unknown) {
  const studentId = normalizeStudentId(value)
  if (!studentId) return '请输入学号'
  if (!/^[A-Z0-9_-]{2,64}$/.test(studentId)) {
    return '学号只能包含字母、数字、下划线或连字符，长度须为 2 至 64 位'
  }
  return undefined
}

export function normalizeRecruitmentAnswers(
  schema: RecruitmentFormSchema,
  value: Record<string, unknown>,
): RecruitmentAnswers {
  const answers: RecruitmentAnswers = {}
  schema.fields.forEach(field => {
    const raw = value[field.id]
    if (field.type === 'MULTIPLE_CHOICE') {
      const allowed = new Set(field.options || [])
      const selected = Array.isArray(raw)
        ? raw.filter((item): item is string => typeof item === 'string' && allowed.has(item))
        : []
      answers[field.id] = [...new Set(selected)]
      return
    }
    const answer = typeof raw === 'string' ? raw.trim() : ''
    answers[field.id] = field.type === 'SINGLE_CHOICE' && !(field.options || []).includes(answer) ? '' : answer
  })
  return answers
}

export function validateRecruitmentAnswers(
  schema: RecruitmentFormSchema,
  studentIdValue: unknown,
  value: Record<string, unknown>,
  attachmentCount: number,
): RecruitmentFormValidationIssue[] {
  const issues: RecruitmentFormValidationIssue[] = []
  const studentIdMessage = validateStudentId(studentIdValue)
  if (studentIdMessage) issues.push({ fieldId: 'studentId', message: studentIdMessage })

  const answers = normalizeRecruitmentAnswers(schema, value)
  schema.fields.forEach(field => {
    const answer = answers[field.id]
    if (field.required && (Array.isArray(answer) ? answer.length === 0 : !answer)) {
      issues.push({ fieldId: field.id, message: `请填写“${field.label || '未命名问题'}”` })
    }
  })
  if (schema.upload.required && attachmentCount === 0) {
    issues.push({ fieldId: 'attachments', message: '请上传招募任务要求的图片或 ZIP 压缩包' })
  }
  return issues
}

export function escapeMarkdownTableCell(value: unknown) {
  const rendered = Array.isArray(value) ? value.join('、') : String(value ?? '')
  return rendered
    .replace(/\\/g, '\\\\')
    .replace(/\|/g, '\\|')
    .replace(/\r?\n/g, '<br>')
}

export function buildApplicationDetailsMarkdown(
  schema: RecruitmentFormSchema,
  studentId: string,
  answers: Record<string, unknown>,
) {
  const normalized = normalizeRecruitmentAnswers(schema, answers)
  const rows = [
    `| 学号 | ${escapeMarkdownTableCell(normalizeStudentId(studentId)) || '未填写'} |`,
    ...schema.fields.map(field => {
      const answer = normalized[field.id]
      const rendered = Array.isArray(answer) ? answer.join('、') : answer
      return `| ${escapeMarkdownTableCell(field.label)} | ${escapeMarkdownTableCell(rendered) || '未填写'} |`
    }),
  ]
  return ['# 招募申请详情', '', '| 项目 | 填写内容 |', '| --- | --- |', ...rows].join('\n')
}

export function isRecruitmentOpen(startAt: string, endAt: string, now = Date.now()) {
  const start = Date.parse(startAt)
  const end = Date.parse(endAt)
  return Number.isFinite(start) && Number.isFinite(end) && start <= now && now < end
}

export function serializeRecruitmentFormSchema(schema: RecruitmentFormSchema) {
  return {
    fields: schema.fields.map(field => ({
      id: field.id,
      type: field.type,
      label: field.label,
      helpText: field.helpText || null,
      placeholder: field.placeholder || null,
      required: field.required,
      options: field.options || [],
    })),
  }
}

export function recruitmentTaskFormPayload(schema: RecruitmentFormSchema) {
  return {
    formSchema: serializeRecruitmentFormSchema(schema),
    studentIdLabel: schema.studentId.label,
    studentIdHelp: schema.studentId.helpText || null,
    uploadLabel: schema.upload.label,
    uploadHelp: schema.upload.prompt || null,
    uploadRequired: schema.upload.required,
  }
}
