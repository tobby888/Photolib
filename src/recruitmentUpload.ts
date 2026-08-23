export const RECRUITMENT_MAX_FILE_COUNT = 100
export const RECRUITMENT_MAX_IMAGE_BYTES = 100 * 1024 * 1024
export const RECRUITMENT_MAX_ZIP_BYTES = 1_500_000_000

export type RecruitmentUploadMode = 'FILES' | 'ZIP'

export interface RecruitmentUploadFileLike {
  name: string
  type: string
  size: number
}

export interface RecruitmentUploadDescriptor {
  fileName: string
  contentType: string
  size: number
  sha256: string
}

const supportedImageTypes = new Set(['image/jpeg', 'image/png'])
const supportedImageExtensions = new Set(['jpg', 'jpeg', 'png'])

function extension(fileName: string) {
  const position = fileName.lastIndexOf('.')
  return position < 0 ? '' : fileName.slice(position + 1).toLowerCase()
}

export function isZipUploadFile(file: RecruitmentUploadFileLike) {
  // The server signs ZIP uploads with application/zip regardless of the
  // browser's inconsistent File.type value; the extension is authoritative.
  return extension(file.name) === 'zip'
}

export function inferRecruitmentUploadMode(files: RecruitmentUploadFileLike[]): RecruitmentUploadMode | undefined {
  if (!files.length) return undefined
  return files.length === 1 && isZipUploadFile(files[0]) ? 'ZIP' : 'FILES'
}

export function normalizedRecruitmentContentType(file: RecruitmentUploadFileLike) {
  const declared = file.type.toLowerCase()
  if (declared) return declared
  if (['jpg', 'jpeg'].includes(extension(file.name))) return 'image/jpeg'
  if (extension(file.name) === 'png') return 'image/png'
  return file.type
}

function codePointLength(value: string) {
  return Array.from(value).length
}

export function validateRecruitmentUploadFiles(
  mode: RecruitmentUploadMode,
  files: RecruitmentUploadFileLike[],
) {
  const errors: string[] = []
  if (!files.length) return ['还没有选文件哦']

  if (mode === 'ZIP') {
    if (files.length !== 1) errors.push('一次只能传一个压缩包')
    const archive = files[0]
    if (archive && !isZipUploadFile(archive)) errors.push('这里只收 .zip 压缩包，其他格式暂时不支持')
    if (archive && codePointLength(archive.name) > 255) errors.push('压缩包的文件名太长了，请改短一点（255 字以内）')
    if (archive?.size === 0) errors.push('这个压缩包是空的')
    if (archive && archive.size > RECRUITMENT_MAX_ZIP_BYTES) errors.push('压缩包不能超过 1.5 GB，可以分开传或删掉几张再试')
    return errors
  }

  if (files.length > RECRUITMENT_MAX_FILE_COUNT) errors.push('一次最多传 100 张，请先挑一挑')
  files.forEach(file => {
    const normalizedType = normalizedRecruitmentContentType(file)
    const fileExtension = extension(file.name)
    const imageType = supportedImageTypes.has(normalizedType)
    const imageExtension = supportedImageExtensions.has(fileExtension)
    const typeMatchesExtension = normalizedType === 'image/png'
      ? fileExtension === 'png'
      : normalizedType === 'image/jpeg' && ['jpg', 'jpeg'].includes(fileExtension)
    if (!imageType || !imageExtension || !typeMatchesExtension) {
      errors.push(`${file.name}：只能传 JPG 或 PNG 图片`)
    }
    if (codePointLength(file.name) > 255) errors.push(`${file.name}：文件名太长了，请改短一点（255 字以内）`)
    if (file.size === 0) errors.push(`${file.name}：这个文件是空的`)
    if (file.size > RECRUITMENT_MAX_IMAGE_BYTES) errors.push(`${file.name}：单张不能超过 100 MB`)
  })
  return errors
}

export async function sha256Hex(file: Blob) {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
}

export async function buildRecruitmentFilesRequest(files: File[]) {
  const descriptors: RecruitmentUploadDescriptor[] = []
  for (const file of files) {
    descriptors.push({
      fileName: file.name,
      contentType: normalizedRecruitmentContentType(file),
      size: file.size,
      sha256: await sha256Hex(file),
    })
  }
  return { mode: 'FILES' as const, files: descriptors }
}

export function buildRecruitmentZipRequest(file: RecruitmentUploadFileLike) {
  return {
    mode: 'ZIP' as const,
    archiveFileName: file.name,
    archiveSize: file.size,
  }
}

export type RecruitmentBatchStatus = 'UPLOADING' | 'PROCESSING' | 'SUCCEEDED' | 'PARTIALLY_SUCCEEDED' | 'FAILED'

export function normalizeRecruitmentBatchStatus(value: unknown): RecruitmentBatchStatus {
  const status = typeof value === 'object' && value !== null && 'batch' in value
    ? (value as { batch?: { status?: unknown } }).batch?.status
    : typeof value === 'object' && value !== null && 'status' in value
      ? (value as { status?: unknown }).status
      : value
  return status === 'SUCCEEDED' || status === 'PARTIALLY_SUCCEEDED' || status === 'FAILED'
    || status === 'PROCESSING' || status === 'UPLOADING'
    ? status
    : 'PROCESSING'
}

export function isRecruitmentBatchTerminal(value: unknown) {
  const status = normalizeRecruitmentBatchStatus(value)
  return status === 'SUCCEEDED' || status === 'PARTIALLY_SUCCEEDED' || status === 'FAILED'
}
