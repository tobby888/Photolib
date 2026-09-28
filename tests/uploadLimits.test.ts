import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  DEFAULT_UPLOAD_LIMITS, UPLOAD_LIMIT_KEYS, changedLimits, describeBytes, describeLimit, inputUnit,
  normalizeUploadLimits, teachingFileTooLarge, type UploadLimitSetting,
} from '../src/uploadLimits.ts'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')
const readBackend = (path: string) =>
  readFile(new URL(`../backend/src/main/java/cn/photolib/${path}`, import.meta.url), 'utf8')

const MIB = 1024 * 1024

const setting = (overrides: Partial<UploadLimitSetting>): UploadLimitSetting => ({
  key: 'PHOTO_IMAGE_MAX_BYTES', group: 'PHOTO', groupLabel: '图库与交付', label: '单张图片', description: '',
  unit: 'BYTES', value: 100 * MIB, defaultValue: 100 * MIB, min: MIB, max: 100 * MIB, customized: false,
  ...overrides,
})

test('前端认识的限额键与后端 UploadLimit 一一对应、顺序一致', async () => {
  const source = await readBackend('uploadlimit/UploadLimit.java')
  const backendKeys = Array.from(source.matchAll(/^ {4}([A-Z_]+)\(Group\./gm), match => match[1])
  assert.deepEqual([...UPLOAD_LIMIT_KEYS], backendKeys)
  assert.deepEqual(Object.keys(DEFAULT_UPLOAD_LIMITS), backendKeys)
})

test('接口缺项或给了非法值时用内置默认值补上', () => {
  const limits = normalizeUploadLimits({
    PHOTO_ZIP_MAX_IMAGES: 300, PHOTO_IMAGE_MAX_BYTES: -1, FORM_FILE_MAX_BYTES: '50', UNKNOWN: 7,
  })
  assert.equal(limits.PHOTO_ZIP_MAX_IMAGES, 300)
  assert.equal(limits.PHOTO_IMAGE_MAX_BYTES, DEFAULT_UPLOAD_LIMITS.PHOTO_IMAGE_MAX_BYTES)
  assert.equal(limits.FORM_FILE_MAX_BYTES, DEFAULT_UPLOAD_LIMITS.FORM_FILE_MAX_BYTES)
  assert.equal('UNKNOWN' in limits, false)
  assert.deepEqual(normalizeUploadLimits(null), DEFAULT_UPLOAD_LIMITS)
})

test('字节数按后端 ImageUploadPolicy.describe 的写法显示', () => {
  assert.equal(describeBytes(100 * MIB), '100 MiB')
  assert.equal(describeBytes(512 * 1024), '512 KiB')
  assert.equal(describeBytes(2 * 1024 * MIB), '2 GiB')
  // 管理员可能填出不整的值：十进制整数读成 GB / MB，其余保留两位小数。
  assert.equal(describeBytes(1_500_000_000), '1.5 GB')
  assert.equal(describeBytes(3_000_000_000), '3 GB')
  assert.equal(describeBytes(20_000_000), '20 MB')
  assert.equal(describeBytes(Math.round(2.5 * MIB)), '2.5 MiB')
  assert.equal(describeBytes(1500), '1500 字节')
  assert.equal(describeLimit({ unit: 'COUNT' }, 100), '100 张')
})

test('后端的 describe 与前端用同一套规则', async () => {
  const policy = await readBackend('common/upload/ImageUploadPolicy.java')
  assert.match(policy, /bytes % 100_000_000L == 0\) return decimal\(bytes \/ 1e9\) \+ " GB"/)
  assert.match(policy, /bytes % 1_000_000L == 0\) return \(bytes \/ 1_000_000L\) \+ " MB"/)
})

test('管理页面按范围选输入单位：小的用 KiB，大的用 MiB，张数不换算', () => {
  assert.deepEqual(inputUnit(setting({ unit: 'COUNT', min: 1, max: 1000 })), { label: '张', factor: 1 })
  assert.deepEqual(inputUnit(setting({ min: 64 * 1024, max: 2 * MIB })), { label: 'KiB', factor: 1024 })
  assert.deepEqual(inputUnit(setting({ min: 256 * 1024, max: 20 * MIB })), { label: 'MiB', factor: MIB })
  assert.deepEqual(inputUnit(setting({ min: MIB, max: 100 * MIB })), { label: 'MiB', factor: MIB })
})

test('只提交真正改过的项', () => {
  const settings = [
    setting({ key: 'PHOTO_IMAGE_MAX_BYTES', value: 100 * MIB }),
    setting({ key: 'PHOTO_ZIP_MAX_IMAGES', unit: 'COUNT', value: 100, defaultValue: 100, min: 1, max: 1000 }),
  ]
  assert.deepEqual(changedLimits(settings, { PHOTO_IMAGE_MAX_BYTES: 100 * MIB, PHOTO_ZIP_MAX_IMAGES: 300 }),
    { PHOTO_ZIP_MAX_IMAGES: 300 })
  assert.deepEqual(changedLimits(settings, {}), {})
})

test('教学资料按扩展名套对应格式的上限，认不出的交给服务端', () => {
  const limits = { ...DEFAULT_UPLOAD_LIMITS, TEACHING_WORD_MAX_BYTES: 5 * MIB }
  assert.equal(teachingFileTooLarge({ name: '讲义.docx', size: 5 * MIB }, limits), null)
  assert.match(String(teachingFileTooLarge({ name: '讲义.DOCX', size: 5 * MIB + 1 }, limits)), /Word 不能超过 5 MiB/)
  assert.equal(teachingFileTooLarge({ name: '课件.pptx', size: 50 * MIB }, limits), null)
  assert.match(String(teachingFileTooLarge({ name: '课件.pdf', size: 101 * MIB }, limits)), /PDF 不能超过 100 MiB/)
  assert.equal(teachingFileTooLarge({ name: '未知.bin', size: 900 * MIB }, limits), null)
})

test('上传入口不再写死限额，统一从 /upload-limits 取', async () => {
  const pages = [
    'pages/PhotosPage.tsx', 'pages/RequestDeliveryPage.tsx', 'pages/BatchUploadPage.tsx',
    'pages/SharedUploadPage.tsx', 'pages/DocsManagePage.tsx', 'pages/TeachingPage.tsx',
    'pages/AdminPage.tsx', 'MarkdownEditor.tsx', 'RichTextEditor.tsx', 'AvatarSettingsModal.tsx',
    'DatabaseBackupPanel.tsx', 'FormFields.tsx', 'RecruitmentFormEditor.tsx',
  ]
  for (const page of pages) {
    const source = await read(page)
    assert.match(source, /useUploadLimits\(\)/, `${page} 没有读取管理员设的上传限额`)
    assert.doesNotMatch(source, /1\.5 GB|100 MiB|50 MiB|512 KiB|3 MiB|最大 5 MiB|1 MiB，/, `${page} 还有写死的限额文案`)
  }
  const [hook, security] = await Promise.all([read('useUploadLimits.ts'), readBackend('auth/SecurityConfig.java')])
  assert.match(hook, /url: '\/upload-limits'/)
  // 访客页面（选题上传链接、公开招募）没有登录，也得读得到。
  assert.match(security, /HttpMethod\.GET, "\/api\/v1\/upload-limits"\)\.permitAll\(\)/)
})
