import assert from 'node:assert/strict'
import test from 'node:test'
import {
  buildApplicationDetailsMarkdown,
  createRecruitmentField,
  escapeMarkdownTableCell,
  isRecruitmentOpen,
  normalizeRecruitmentAnswers,
  normalizeRecruitmentFormSchema,
  normalizeStudentId,
  recruitmentTaskFormPayload,
  validateRecruitmentAnswers,
  validateRecruitmentFormSchema,
  validateStudentId,
  type RecruitmentFormSchema,
} from '../src/recruitmentForm.ts'

const schema: RecruitmentFormSchema = {
  fields: [
    { id: 'motivation', type: 'LONG_TEXT', label: '加入原因', required: true },
    { id: 'campus', type: 'SINGLE_CHOICE', label: '校区', required: true, options: ['东区', '西区'] },
    { id: 'skills', type: 'MULTIPLE_CHOICE', label: '技能', required: false, options: ['拍摄', '后期'] },
    { id: 'availableAt', type: 'DATE', label: '可到岗日期', required: false },
  ],
  studentId: { label: '学号', helpText: '请填写完整学号' },
  upload: { label: '作品上传', required: true, prompt: '请上传作品' },
}

test('normalizes JSON schemas, legacy keys and duplicate choice options', () => {
  const normalized = normalizeRecruitmentFormSchema(JSON.stringify({
    fields: [
      { key: 'name', type: 'SHORT_TEXT', label: ' 姓名 ', description: ' 请填写 ', required: true },
      { id: 'name', type: 'SINGLE_CHOICE', label: '方向', options: ['纪实', '纪实', '', '人像'] },
      { id: 'ignored', type: 'UNSUPPORTED', label: '忽略' },
    ],
    uploadField: { required: true, helpText: ' 原图 ' },
  }))

  assert.deepEqual(normalized.fields, [
    { id: 'name', type: 'SHORT_TEXT', label: '姓名', helpText: '请填写', placeholder: undefined, required: true, options: undefined },
    { id: 'name_2', type: 'SINGLE_CHOICE', label: '方向', helpText: undefined, placeholder: undefined, required: false, options: ['纪实', '人像'] },
  ])
  assert.deepEqual(normalized.studentId, { label: '学号', helpText: '一个学号只能报一次名；开头的 0 请照写，不会被吞掉。' })
  assert.deepEqual(normalized.upload, { label: '作品上传', required: true, prompt: '原图' })
})

test('creates collision-free stable field identifiers', () => {
  assert.equal(createRecruitmentField('SHORT_TEXT', ['field_1', 'field_2']).id, 'field_3')
  assert.deepEqual(createRecruitmentField('MULTIPLE_CHOICE').options, ['选项 1', '选项 2'])
})

test('schema validation reports blank labels, duplicate ids, choice and upload errors', () => {
  const issues = validateRecruitmentFormSchema({
    fields: [
      { id: 'same', type: 'SHORT_TEXT', label: '', required: false },
      { id: 'same', type: 'SINGLE_CHOICE', label: '方向', required: false, options: ['唯一选项'] },
    ],
    studentId: { label: '学号', helpText: '' },
    upload: { label: '作品上传', required: false, prompt: '' },
  })
  assert.ok(issues.some(issue => issue.message.includes('还没写题目')))
  assert.ok(issues.some(issue => issue.message.includes('字段标识和前面的重复了')))
  assert.ok(issues.some(issue => issue.message.includes('至少要有两个')))
  assert.ok(issues.some(issue => issue.message.includes('作品上传区写一句提示')))
})

test('student identifiers remain strings and preserve leading zeroes', () => {
  assert.equal(normalizeStudentId(' 00123456 '), '00123456')
  assert.equal(normalizeStudentId(' ００１\u3000aB-2 '), '001AB-2')
  assert.equal(normalizeStudentId(123456), '')
  assert.equal(validateStudentId('００１ ab-2'), undefined)
  assert.match(validateStudentId('中文学号') || '', /字母、数字/)
})

test('task payload splits fixed fields and preserves the published input placeholder', () => {
  const localSchema: RecruitmentFormSchema = {
    ...schema,
    fields: [{
      id: 'portfolio_note', type: 'SHORT_TEXT', label: '作品说明', helpText: '一句话即可',
      placeholder: '例如：拍摄于运动会', required: true,
    }],
  }
  assert.deepEqual(recruitmentTaskFormPayload(localSchema), {
    formSchema: { fields: [{
      id: 'portfolio_note', type: 'SHORT_TEXT', label: '作品说明', helpText: '一句话即可',
      placeholder: '例如：拍摄于运动会', required: true, options: [],
    }] },
    studentIdLabel: '学号',
    studentIdHelp: '请填写完整学号',
    uploadLabel: '作品上传',
    uploadHelp: '请上传作品',
    uploadRequired: true,
  })
})

test('answers are restricted to the published schema without numeric coercion', () => {
  assert.deepEqual(normalizeRecruitmentAnswers(schema, {
    motivation: '  想记录校园  ',
    campus: '东区',
    skills: ['拍摄', '不存在', '拍摄'],
    availableAt: '2026-09-01',
    injected: '不应提交',
  }), {
    motivation: '想记录校园',
    campus: '东区',
    skills: ['拍摄'],
    availableAt: '2026-09-01',
  })
})

test('required text, choices and attachment are validated together', () => {
  const issues = validateRecruitmentAnswers(schema, '001', {
    motivation: '', campus: '无效校区', skills: [],
  }, 0)
  assert.deepEqual(issues.map(issue => issue.fieldId), ['motivation', 'campus', 'attachments'])

  assert.deepEqual(validateRecruitmentAnswers(schema, '001', {
    motivation: '喜欢摄影', campus: '东区', skills: [], availableAt: '',
  }, 1), [])

  assert.match(validateRecruitmentAnswers(schema, '中文学号', {
    motivation: '喜欢摄影', campus: '东区', skills: [], availableAt: '',
  }, 1)[0].message, /字母、数字/)
})

test('Markdown helpers escape table injection and line breaks', () => {
  assert.equal(escapeMarkdownTableCell('A|B\nC'), 'A\\|B<br>C')
  const markdown = buildApplicationDetailsMarkdown(schema, '001234', {
    motivation: '记录|校园', campus: '东区', skills: ['拍摄', '后期'], availableAt: '',
  })
  assert.match(markdown, /\| 学号 \| 001234 \|/)
  assert.match(markdown, /\| 加入原因 \| 记录\\\|校园 \|/)
  assert.match(markdown, /拍摄、后期/)
  assert.doesNotMatch(markdown, /undefined/)
})

test('recruitment time range includes its start, excludes its end and rejects invalid dates', () => {
  const start = '2026-08-22T10:00:00.000Z'
  const end = '2026-08-22T12:00:00.000Z'
  assert.equal(isRecruitmentOpen(start, end, Date.parse(start)), true)
  assert.equal(isRecruitmentOpen(start, end, Date.parse(end)), false)
  assert.equal(isRecruitmentOpen(start, end, Date.parse(start) - 1), false)
  assert.equal(isRecruitmentOpen(start, end, Date.parse(end) + 1), false)
  assert.equal(isRecruitmentOpen('invalid', end, Date.parse(end)), false)
})
