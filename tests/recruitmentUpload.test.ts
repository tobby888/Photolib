import assert from 'node:assert/strict'
import test from 'node:test'
import {
  RECRUITMENT_FALLBACK_UPLOAD_LIMITS,
  buildRecruitmentFilesRequest,
  buildRecruitmentZipRequest,
  inferRecruitmentUploadMode,
  isRecruitmentBatchTerminal,
  normalizeRecruitmentBatchStatus,
  normalizedRecruitmentContentType,
  describeBytes,
  sha256Hex,
  validateRecruitmentUploadFiles,
  type RecruitmentUploadFileLike,
} from '../src/recruitmentUpload.ts'

const LIMITS = RECRUITMENT_FALLBACK_UPLOAD_LIMITS

const image = (overrides: Partial<RecruitmentUploadFileLike> = {}): RecruitmentUploadFileLike => ({
  name: 'photo.jpg', type: 'image/jpeg', size: 1024, ...overrides,
})

test('infers ZIP only for one archive and uses FILES for image selections', () => {
  assert.equal(inferRecruitmentUploadMode([]), undefined)
  assert.equal(inferRecruitmentUploadMode([image()]), 'FILES')
  assert.equal(inferRecruitmentUploadMode([{ name: 'works.ZIP', type: '', size: 10 }]), 'ZIP')
  assert.equal(inferRecruitmentUploadMode([
    { name: 'works.zip', type: 'application/zip', size: 10 }, image(),
  ]), 'FILES')
})

test('FILES accepts the count and per-image boundary exactly, and rejects the next step', () => {
  const files = Array.from({ length: LIMITS.maxImageCount }, (_, index) => image({
    name: `${index}.png`, type: 'image/png', size: LIMITS.maxImageBytes,
  }))
  assert.deepEqual(validateRecruitmentUploadFiles('FILES', files), [])
  assert.match(validateRecruitmentUploadFiles('FILES', [...files, image()])[0],
    new RegExp(`最多传 ${LIMITS.maxImageCount}`))
  assert.match(validateRecruitmentUploadFiles('FILES', [image({ size: LIMITS.maxImageBytes + 1 })])[0],
    new RegExp(describeBytes(LIMITS.maxImageBytes)))
})

test('server-supplied limits override the fallback in both directions', () => {
  const tighter = { maxImageCount: 2, maxImageBytes: 1024, maxArchiveBytes: 4096 }
  const three = Array.from({ length: 3 }, (_, index) => image({ name: `${index}.jpg` }))
  assert.match(validateRecruitmentUploadFiles('FILES', three, tighter)[0], /最多传 2/)
  assert.match(validateRecruitmentUploadFiles('FILES', [image({ size: 1025 })], tighter)[0], /1 KiB/)
  assert.match(
    validateRecruitmentUploadFiles('ZIP', [{ name: 'w.zip', type: 'application/zip', size: 4097 }], tighter)[0],
    /4 KiB/)

  // A department that raises the quota must not be second-guessed by the browser.
  const looser = { maxImageCount: 200, maxImageBytes: 300 * 1024 * 1024, maxArchiveBytes: 2 * 1024 ** 3 }
  assert.deepEqual(
    validateRecruitmentUploadFiles('FILES', [image({ size: 200 * 1024 * 1024 })], looser), [])
})

test('byte limits are described in the same binary units the backend states', () => {
  assert.equal(describeBytes(20 * 1024 * 1024), '20 MiB')
  assert.equal(describeBytes(200 * 1024 * 1024), '200 MiB')
  assert.equal(describeBytes(2 * 1024 ** 3), '2 GiB')
  assert.equal(describeBytes(512 * 1024), '512 KiB')
  assert.equal(describeBytes(1500), '1500 字节')
})

test('FILES rejects empty, zero-byte, spoofed and unsupported files', () => {
  assert.deepEqual(validateRecruitmentUploadFiles('FILES', []), ['还没有选文件哦'])
  assert.ok(validateRecruitmentUploadFiles('FILES', [image({ size: 0 })]).some(error => error.includes('是空的')))
  assert.ok(validateRecruitmentUploadFiles('FILES', [image({ name: 'photo.exe' })]).some(error => error.includes('只能传 JPG 或 PNG')))
  assert.ok(validateRecruitmentUploadFiles('FILES', [image({ name: 'photo.jpg', type: 'text/plain' })]).some(error => error.includes('只能传 JPG 或 PNG')))
  assert.ok(validateRecruitmentUploadFiles('FILES', [image({ name: 'photo.png', type: 'image/jpeg' })]).some(error => error.includes('只能传 JPG 或 PNG')))
  assert.ok(validateRecruitmentUploadFiles('FILES', [image({ name: `${'图'.repeat(252)}.jpg` })]).some(error => error.includes('文件名太长')))
  assert.deepEqual(validateRecruitmentUploadFiles('FILES', [image({ type: '' })]), [])
})

test('ZIP accepts the archive boundary exactly and rejects the next byte or multiple archives', () => {
  const archive = { name: 'works.zip', type: 'application/zip', size: LIMITS.maxArchiveBytes }
  assert.deepEqual(validateRecruitmentUploadFiles('ZIP', [archive]), [])
  assert.match(validateRecruitmentUploadFiles('ZIP', [{ ...archive, size: LIMITS.maxArchiveBytes + 1 }])[0],
    new RegExp(describeBytes(LIMITS.maxArchiveBytes)))
  assert.ok(validateRecruitmentUploadFiles('ZIP', [archive, archive]).some(error => error.includes('一个压缩包')))
  assert.ok(validateRecruitmentUploadFiles('ZIP', [image()]).some(error => error.includes('只收 .zip')))
  assert.ok(validateRecruitmentUploadFiles('ZIP', [{ name: 'works.bin', type: 'application/zip', size: 10 }])
    .some(error => error.includes('只收 .zip')))
  assert.ok(validateRecruitmentUploadFiles('ZIP', [{ name: `${'包'.repeat(252)}.zip`, type: 'application/zip', size: 10 }])
    .some(error => error.includes('文件名太长')))
})

test('content types are inferred from safe image extensions when browsers omit MIME', () => {
  assert.equal(normalizedRecruitmentContentType(image({ name: 'a.JPEG', type: '' })), 'image/jpeg')
  assert.equal(normalizedRecruitmentContentType(image({ name: 'a.png', type: '' })), 'image/png')
  assert.equal(normalizedRecruitmentContentType(image({ name: 'a.bin', type: 'application/octet-stream' })), 'application/octet-stream')
})

test('SHA-256 is stable and FILES payload retains original file bytes and names', async () => {
  assert.equal(await sha256Hex(new Blob(['abc'])), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
  const file = new File([new Uint8Array([0, 1, 2, 255])], '原图 001.PNG', { type: 'image/png' })
  const request = await buildRecruitmentFilesRequest([file])
  assert.equal(request.mode, 'FILES')
  assert.equal(request.files[0].fileName, '原图 001.PNG')
  assert.equal(request.files[0].contentType, 'image/png')
  assert.equal(request.files[0].size, 4)
  assert.equal(request.files[0].sha256.length, 64)
  assert.deepEqual(Array.from(new Uint8Array(await file.arrayBuffer())), [0, 1, 2, 255])
})

test('ZIP request contains metadata only and batch statuses normalize nested responses', () => {
  assert.deepEqual(buildRecruitmentZipRequest({ name: '作品.zip', type: 'application/zip', size: 42 }), {
    mode: 'ZIP', archiveFileName: '作品.zip', archiveSize: 42,
  })
  assert.equal(normalizeRecruitmentBatchStatus({ batch: { status: 'SUCCEEDED' } }), 'SUCCEEDED')
  assert.equal(normalizeRecruitmentBatchStatus({ status: 'FAILED' }), 'FAILED')
  assert.equal(normalizeRecruitmentBatchStatus('WAITING_METADATA'), 'PROCESSING')
  assert.equal(isRecruitmentBatchTerminal({ batch: { status: 'PARTIALLY_SUCCEEDED' } }), true)
  assert.equal(isRecruitmentBatchTerminal({ batch: { status: 'PROCESSING' } }), false)
})
