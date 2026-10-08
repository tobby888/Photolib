/**
 * 上传限额。真正的数字由管理员在「系统管理 → 上传限额」里设，后端 `UploadLimitService`
 * 是唯一来源（`GET /upload-limits`，匿名可读）。前端只拿它做提示和预检，不让用户白等一次
 * 上传；真正作数的永远是服务端那一遍。
 *
 * 这个文件不依赖请求层，node 单测可以直接跑；取数的钩子在 `useUploadLimits.ts`。
 */
import { describeBytes } from './recruitmentUpload.ts'

export { describeBytes }

export const UPLOAD_LIMIT_KEYS = [
  'PHOTO_IMAGE_MAX_BYTES',
  'PHOTO_ZIP_MAX_BYTES',
  'PHOTO_ZIP_MAX_IMAGES',
  'RECRUITMENT_IMAGE_MAX_BYTES',
  'RECRUITMENT_ZIP_MAX_BYTES',
  'RECRUITMENT_MAX_IMAGES',
  'FORM_FILE_MAX_BYTES',
  'INLINE_IMAGE_MAX_BYTES',
  'DOC_PDF_MAX_BYTES',
  'TEACHING_PDF_MAX_BYTES',
  'TEACHING_WORD_MAX_BYTES',
  'TEACHING_PPT_MAX_BYTES',
  'FILE_MAX_BYTES',
  'FILE_USER_QUOTA_BYTES',
  'FILE_USER_DAILY_UPLOADS',
  'FILE_UPLOAD_QPS',
  'FILE_DOWNLOAD_QPS',
  'FILE_DOWNLOADS_PER_HOUR',
  'FILE_ANONYMOUS_DAILY_BYTES',
  'FILE_DOWNLOAD_LINK_TTL_SECONDS',
  'AVATAR_MAX_BYTES',
  'BRAND_ICON_MAX_BYTES',
  'PLACEHOLDER_IMAGE_MAX_BYTES',
  'DATABASE_BACKUP_MAX_BYTES',
] as const

export type UploadLimitKey = typeof UPLOAD_LIMIT_KEYS[number]
export type UploadLimits = Record<UploadLimitKey, number>

const KIB = 1024
const MIB = 1024 * KIB
const GIB = 1024 * MIB

/**
 * 接口还没回来（或取失败）时用的值，与后端 `UploadLimit` 的内置默认值一致。
 * 管理员改过的部署会在接口返回后换成真实值。
 */
export const DEFAULT_UPLOAD_LIMITS: UploadLimits = {
  PHOTO_IMAGE_MAX_BYTES: 100 * MIB,
  PHOTO_ZIP_MAX_BYTES: 1_500_000_000,
  PHOTO_ZIP_MAX_IMAGES: 100,
  RECRUITMENT_IMAGE_MAX_BYTES: 20 * MIB,
  RECRUITMENT_ZIP_MAX_BYTES: 200 * MIB,
  RECRUITMENT_MAX_IMAGES: 20,
  FORM_FILE_MAX_BYTES: 50 * MIB,
  INLINE_IMAGE_MAX_BYTES: 5 * MIB,
  DOC_PDF_MAX_BYTES: 50 * MIB,
  TEACHING_PDF_MAX_BYTES: 100 * MIB,
  TEACHING_WORD_MAX_BYTES: 20 * MIB,
  TEACHING_PPT_MAX_BYTES: 100 * MIB,
  FILE_MAX_BYTES: 200 * MIB,
  FILE_USER_QUOTA_BYTES: 2 * GIB,
  FILE_USER_DAILY_UPLOADS: 50,
  FILE_UPLOAD_QPS: 5,
  FILE_DOWNLOAD_QPS: 20,
  FILE_DOWNLOADS_PER_HOUR: 120,
  FILE_ANONYMOUS_DAILY_BYTES: 5 * GIB,
  FILE_DOWNLOAD_LINK_TTL_SECONDS: 300,
  AVATAR_MAX_BYTES: MIB,
  BRAND_ICON_MAX_BYTES: 512 * KIB,
  PLACEHOLDER_IMAGE_MAX_BYTES: 3 * MIB,
  DATABASE_BACKUP_MAX_BYTES: 512 * MIB,
}

/** 接口返回的键值表 → 完整的限额；缺项或非法值用默认值补上。 */
export function normalizeUploadLimits(value: unknown): UploadLimits {
  const raw = typeof value === 'object' && value !== null ? value as Record<string, unknown> : {}
  const limits = { ...DEFAULT_UPLOAD_LIMITS }
  for (const key of UPLOAD_LIMIT_KEYS) {
    const candidate = raw[key]
    if (typeof candidate === 'number' && Number.isFinite(candidate) && candidate > 0) limits[key] = candidate
  }
  return limits
}

/** 非字节类限额的单位：张（图片）、个（文件）、次、次/秒（QPS）、秒。 */
export type UploadLimitUnit = 'BYTES' | 'COUNT' | 'FILES' | 'TIMES' | 'PER_SECOND' | 'SECONDS'

const UNIT_LABELS: Record<Exclude<UploadLimitUnit, 'BYTES'>, string> = {
  COUNT: '张', FILES: '个', TIMES: '次', PER_SECOND: '次/秒', SECONDS: '秒',
}

/** 管理页面上一项限额的完整信息（`GET /upload-limits/settings`）。 */
export interface UploadLimitSetting {
  key: UploadLimitKey
  group: string
  groupLabel: string
  label: string
  description: string
  unit: UploadLimitUnit
  /** 后端给的单位字样；老后端没有这个字段时按 unit 推。 */
  unitLabel?: string
  value: number
  defaultValue: number
  min: number
  max: number
  customized: boolean
}

function unitLabel(setting: Pick<UploadLimitSetting, 'unit' | 'unitLabel'>) {
  if (setting.unitLabel) return setting.unitLabel
  return setting.unit === 'BYTES' ? '' : UNIT_LABELS[setting.unit] ?? ''
}

/**
 * 管理页面里字节类限额用哪个单位输入：整个可调范围都在 2 MiB 以内的（站点图标）用 KiB，
 * 其余用 MiB（两位小数，0.25 MiB 这样的下限也写得出来）。张数、个数、次数、QPS、秒数不换算。
 */
export function inputUnit(
  setting: Pick<UploadLimitSetting, 'unit' | 'max'> & Partial<Pick<UploadLimitSetting, 'unitLabel'>>,
): { label: string; factor: number } {
  if (setting.unit !== 'BYTES') return { label: unitLabel(setting), factor: 1 }
  return setting.max <= 2 * MIB ? { label: 'KiB', factor: KIB } : { label: 'MiB', factor: MIB }
}

export function describeLimit(
  setting: Pick<UploadLimitSetting, 'unit'> & Partial<Pick<UploadLimitSetting, 'unitLabel'>>, value: number,
) {
  return setting.unit === 'BYTES' ? describeBytes(value) : `${value} ${unitLabel(setting)}`
}

/** 管理员改过、要提交的项：只带和当前生效值不同的键。 */
export function changedLimits(settings: UploadLimitSetting[], drafts: Partial<Record<UploadLimitKey, number>>) {
  const changes: Partial<Record<UploadLimitKey, number>> = {}
  for (const setting of settings) {
    const draft = drafts[setting.key]
    if (draft !== undefined && draft !== setting.value) changes[setting.key] = draft
  }
  return changes
}

/**
 * 教学资料按格式各有上限。前端只能按扩展名猜格式（后端按文件内容嗅探），猜不出来的交给后端判。
 */
export function teachingFileTooLarge(file: { name: string; size: number }, limits: UploadLimits): string | null {
  const name = file.name.toLowerCase()
  const [label, maxBytes] = name.endsWith('.pdf') ? ['PDF', limits.TEACHING_PDF_MAX_BYTES]
    : name.endsWith('.docx') ? ['Word', limits.TEACHING_WORD_MAX_BYTES]
      : name.endsWith('.pptx') ? ['PPT', limits.TEACHING_PPT_MAX_BYTES]
        : [null, 0]
  if (!label || file.size <= maxBytes) return null
  return `${label} 不能超过 ${describeBytes(maxBytes)}`
}
