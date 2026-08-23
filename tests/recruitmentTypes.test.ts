import assert from 'node:assert/strict'
import test from 'node:test'
import {
  normalizeApplicationDetail,
  normalizeApplicationSummary,
  normalizePublicRecruitmentTask,
  normalizeRecruitmentDraft,
  normalizeRecruitmentPage,
  normalizeRecruitmentTask,
} from '../src/recruitmentTypes.ts'

const publicDto = {
  publicId: '01KTESTPUBLICID000000000000',
  title: '秋季招新',
  introMarkdown: '欢迎加入',
  formSchema: { fields: [{
    id: 'direction', type: 'SINGLE_CHOICE', label: '方向', helpText: null,
    required: true, options: ['摄影', '后期'],
  }] },
  studentIdLabel: '你的学号',
  studentIdHelp: '请勿填写身份证号',
  uploadLabel: '原创作品',
  uploadHelp: '请提交未压缩原图',
  uploadRequired: true,
  startsAt: '2026-09-01T00:00:00',
  endsAt: '2026-09-14T23:59:59',
}

test('normalizes the exact backend public task DTO and merges fixed form fields', () => {
  const task = normalizePublicRecruitmentTask(publicDto)
  assert.equal(task.description, '欢迎加入')
  assert.equal(task.startAt, publicDto.startsAt)
  assert.equal(task.endAt, publicDto.endsAt)
  assert.deepEqual(task.formSchema.studentId, { label: '你的学号', helpText: '请勿填写身份证号' })
  assert.deepEqual(task.formSchema.upload, { label: '原创作品', prompt: '请提交未压缩原图', required: true })
  assert.equal(task.formSchema.fields[0].id, 'direction')
})

test('null help text stays empty so republished tasks round-trip unchanged', () => {
  // The backend freezes the student-id and upload configuration once a task is
  // published and compares the submitted values byte for byte. Substituting the
  // built-in placeholder for a stored null would make every later edit of a
  // published task fail with a spurious "表单已冻结" conflict.
  const task = normalizePublicRecruitmentTask({ ...publicDto, studentIdHelp: null, uploadHelp: null })
  assert.equal(task.formSchema.studentId.helpText, '')
  assert.equal(task.formSchema.upload.prompt, '')

  // An absent key still falls back, so a brand-new form is seeded with defaults.
  const withoutHelp: Record<string, unknown> = { ...publicDto }
  delete withoutHelp.studentIdHelp
  delete withoutHelp.uploadHelp
  const seeded = normalizePublicRecruitmentTask(withoutHelp)
  assert.ok(seeded.formSchema.studentId.helpText.length > 0)
  assert.ok(seeded.formSchema.upload.prompt.length > 0)
})

test('normalizes internal task metadata and backend PageResponse', () => {
  const taskDto = {
    ...publicDto, id: 17, status: 'PUBLISHED', createdBy: 3, creatorDisplayName: '部长',
    applicationCount: 21, version: 4,
  }
  const page = normalizeRecruitmentPage({
    items: [taskDto], page: 2, pageSize: 12, total: 25, totalPages: 3,
  }, normalizeRecruitmentTask, 2, 12)
  assert.equal(page.items[0].id, 17)
  assert.equal(page.items[0].creatorId, 3)
  assert.equal(page.items[0].applicationCount, 21)
  assert.equal(page.items[0].version, 4)
  assert.equal(page.totalPages, 3)
})

test('draft normalizer accepts the backend draftToken name without exposing coercion', () => {
  assert.deepEqual(normalizeRecruitmentDraft({
    draftId: 'draft-1', draftToken: 'secret-token', expiresAt: '2026-09-01T01:00:00',
  }), { draftId: 'draft-1', token: 'secret-token', expiresAt: '2026-09-01T01:00:00' })
})

test('application DTO keeps signed attachment URLs and omits unavailable summary counts', () => {
  assert.deepEqual(normalizeApplicationSummary({
    id: 'app-1', taskId: 17, studentId: '001234', submittedAt: '2026-09-01T12:00:00',
  }).attachmentCount, undefined)
  const detail = normalizeApplicationDetail({
    id: 'app-1', taskId: 17, taskTitle: '秋季招新', studentId: '001234',
    submittedAt: '2026-09-01T12:00:00', detailsMarkdown: '# 申请',
    attachments: [{
      id: 8, fileName: '作品.jpg', contentType: 'image/jpeg', size: 2048,
      previewUrl: 'https://oss.example/preview', downloadUrl: 'https://oss.example/download',
    }],
  })
  assert.equal(detail.detailsMarkdown, '# 申请')
  assert.equal(detail.attachments[0].previewUrl, 'https://oss.example/preview')
  assert.equal(detail.attachments[0].downloadUrl, 'https://oss.example/download')
})
