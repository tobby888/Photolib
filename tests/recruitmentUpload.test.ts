import assert from 'node:assert/strict'
import test from 'node:test'
import {
  RECRUITMENT_MAX_FILE_COUNT,
  RECRUITMENT_MAX_IMAGE_BYTES,
  RECRUITMENT_MAX_ZIP_BYTES,
  buildRecruitmentFilesRequest,
  buildRecruitmentZipRequest,
  inferRecruitmentUploadMode,
  isRecruitmentBatchTerminal,
  normalizeRecruitmentBatchStatus,
  normalizedRecruitmentContentType,
  sha256Hex,
  validateRecruitmentUploadFiles,
  type RecruitmentUploadFileLike,
} from '../src/recruitmentUpload.ts'

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

test('FILES accepts exactly 100 images and the 100 MiB boundary', () => {
  const files = Array.from({ length: RECRUITMENT_MAX_FILE_COUNT }, (_, index) => image({
    name: `${index}.png`, type: 'image/png', size: RECRUITMENT_MAX_IMAGE_BYTES,
  }))
  assert.deepEqual(validateRecruitmentUploadFiles('FILES', files), [])
  assert.match(validateRecruitmentUploadFiles('FILES', [...files, image()])[0], /最多传 100/)
  assert.match(validateRecruitmentUploadFiles('FILES', [image({ size: RECRUITMENT_MAX_IMAGE_BYTES + 1 })])[0], /100 MiB/)
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

test('ZIP accepts 1.5GB exactly and rejects the next byte or multiple archives', () => {
  const archive = { name: 'works.zip', type: 'application/zip', size: RECRUITMENT_MAX_ZIP_BYTES }
  assert.deepEqual(validateRecruitmentUploadFiles('ZIP', [archive]), [])
  assert.match(validateRecruitmentUploadFiles('ZIP', [{ ...archive, size: RECRUITMENT_MAX_ZIP_BYTES + 1 }])[0], /1\.5 GB/)
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
