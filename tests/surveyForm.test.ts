import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  buildApplicationDetailsMarkdown,
  createRecruitmentField,
  formAnswerText,
  moveItem,
  nextOptionLabel,
  normalizeRecruitmentAnswers,
  normalizeRecruitmentFormSchema,
  validateFormAnswers,
  validateRecruitmentFormSchema,
  validateSurveyFormFields,
  EMPTY_RECRUITMENT_FORM,
  type RecruitmentFormField,
} from '../src/recruitmentForm.ts'
import { normalizeFormFileLimits, normalizeFormFileViews, validateFormFileSelection } from '../src/formFiles.ts'
import {
  addToSelection,
  filterAudience,
  normalizeSurvey,
  normalizeSurveyFill,
  removeFromSelection,
  surveyStatusDisplay,
  type AudienceCandidate,
} from '../src/surveyTypes.ts'

const fileField: RecruitmentFormField = { id: 'resume', type: 'FILE_UPLOAD', label: '简历', required: true }

test('file questions are a first-class field type without options', () => {
  const schema = normalizeRecruitmentFormSchema({ fields: [{ id: 'resume', type: 'FILE_UPLOAD', label: '简历', options: ['x'] }] })
  assert.equal(schema.fields[0].type, 'FILE_UPLOAD')
  assert.equal(schema.fields[0].options, undefined)
  assert.equal(createRecruitmentField('FILE_UPLOAD').options, undefined)
  assert.deepEqual(validateRecruitmentFormSchema({ ...EMPTY_RECRUITMENT_FORM, fields: [fileField] }), [])
})

test('file answers submit only uploaded ids, and required file questions count selected files', () => {
  const schema = { ...EMPTY_RECRUITMENT_FORM, fields: [fileField] }
  const answers = normalizeRecruitmentAnswers(schema, { resume: ['01ARZ3NDEKTSV4RRFFQ69G5FAV', { name: 'x.pdf' }, '01ARZ3NDEKTSV4RRFFQ69G5FAV'] })
  assert.deepEqual(answers.resume, ['01ARZ3NDEKTSV4RRFFQ69G5FAV'])

  assert.match(validateFormAnswers([fileField], {})[0]?.message || '', /还没传文件/)
  // 选中但还没上传的文件（UploadFile 对象）也算已作答。
  assert.deepEqual(validateFormAnswers([fileField], { resume: [{ name: 'a.pdf' }] }), [])
})

test('stored file answers render as file names in text and markdown', () => {
  const stored = [{ id: 'A', fileName: '简历.pdf', size: 3 }, { id: 'B', fileName: '作品集.zip' }]
  assert.equal(formAnswerText(stored), '简历.pdf、作品集.zip')
  assert.equal(formAnswerText(['拍摄', '后期']), '拍摄、后期')
  assert.equal(formAnswerText(null), '')
  const markdown = buildApplicationDetailsMarkdown({ ...EMPTY_RECRUITMENT_FORM, fields: [fileField] }, '001', { resume: stored })
  assert.match(markdown, /\| 简历 \| 简历\.pdf、作品集\.zip \|/)
})

test('option editing keeps draft blanks while typing and strict normalization cleans them on save', () => {
  const raw = { fields: [{ id: 'q', type: 'SINGLE_CHOICE', label: '方向', options: ['人像', '', '人像', ' 风光 '] }] }
  assert.deepEqual(normalizeRecruitmentFormSchema(raw, { keepDraftOptions: true }).fields[0].options, ['人像', '', '人像', ' 风光 '])
  assert.deepEqual(normalizeRecruitmentFormSchema(raw).fields[0].options, ['人像', '风光'])
})

test('options can be reordered and new options get a unique default label', () => {
  assert.deepEqual(moveItem(['a', 'b', 'c'], 0, 2), ['b', 'c', 'a'])
  assert.deepEqual(moveItem(['a', 'b', 'c'], 2, 0), ['c', 'a', 'b'])
  assert.deepEqual(moveItem(['a', 'b'], 0, 5), ['a', 'b'])
  assert.equal(nextOptionLabel(['选项 1', '选项 2']), '选项 3')
  assert.equal(nextOptionLabel(['选项 3', '其他']), '选项 4')
})

test('surveys need at least one question and reuse the recruitment field rules', () => {
  assert.match(validateSurveyFormFields([])[0].message, /至少要有一道题/)
  const issues = validateSurveyFormFields([{ id: 'q', type: 'MULTIPLE_CHOICE', label: '', required: false, options: ['唯一'] }])
  assert.ok(issues.some(issue => issue.message.includes('还没写题目')))
  assert.ok(issues.some(issue => issue.message.includes('至少要有两个')))
})

test('file selection is checked against count and size limits before upload', () => {
  const limits = normalizeFormFileLimits({ maxFileBytes: 10, maxFilesPerField: 2 })
  assert.equal(validateFormFileSelection([{ name: 'a', size: 1 }, { name: 'b', size: 1 }], limits), undefined)
  assert.match(validateFormFileSelection([{ name: 'a', size: 1 }, { name: 'b', size: 1 }, { name: 'c', size: 1 }], limits) || '', /最多传 2 个/)
  assert.match(validateFormFileSelection([{ name: 'big', size: 11 }], limits) || '', /超过了 10 字节/)
  assert.match(validateFormFileSelection([{ name: 'empty', size: 0 }], limits) || '', /空文件/)
  assert.deepEqual(normalizeFormFileLimits(null), { maxFileBytes: 50 * 1024 * 1024, maxFilesPerField: 10 })
  assert.deepEqual(normalizeFormFileViews([{ id: 'F', fieldId: 'resume', fileName: 'x.pdf', size: 2 }, { nope: 1 }]).map(file => file.id), ['F'])
})

const people: AudienceCandidate[] = [
  { id: '1', displayName: '张三', username: 'zhangsan', permissionGroupId: 'g1', permissionGroupName: '部长', campusIds: ['c1'] },
  { id: '2', displayName: '李四', username: 'lisi', permissionGroupId: 'g2', permissionGroupName: '负责人', campusIds: ['c1', 'c2'] },
  { id: '3', displayName: '王五', username: 'wangwu', permissionGroupId: 'g2', permissionGroupName: '负责人', campusIds: ['c2'] },
]

test('audience filter combines permission group, campus and name search', () => {
  const ids = (filters: Parameters<typeof filterAudience>[1]) => filterAudience(people, filters).map(person => person.id)
  assert.deepEqual(ids({}), ['1', '2', '3'])
  assert.deepEqual(ids({ groupIds: ['g2'] }), ['2', '3'])
  assert.deepEqual(ids({ campusIds: ['c1'] }), ['1', '2'])
  assert.deepEqual(ids({ groupIds: ['g2'], campusIds: ['c1'] }), ['2'])
  assert.deepEqual(ids({ keyword: ' 王 ' }), ['3'])
  assert.deepEqual(ids({ keyword: 'LISI' }), ['2'])
})

test('select-all of filtered results merges into the selection without duplicates and can be undone', () => {
  const selected = addToSelection(['3'], ['1', '2', '3'])
  assert.deepEqual(selected, ['3', '1', '2'])
  assert.deepEqual(removeFromSelection(selected, ['1', '2']), ['3'])
})

test('survey payloads normalize ids as strings and derive a readable status', () => {
  const survey = normalizeSurvey({ id: 123, status: 'PUBLISHED', open: false, targetUserIds: [9007199254740993n.toString(), 5],
    formSchema: { fields: [{ id: 'q', type: 'SHORT_TEXT', label: '题' }] }, version: 2 })
  assert.equal(survey.id, '123')
  assert.deepEqual(survey.targetUserIds, ['9007199254740993', '5'])
  assert.deepEqual(surveyStatusDisplay(survey), { label: '已截止', color: 'orange' })
  assert.deepEqual(surveyStatusDisplay({ status: 'PUBLISHED', open: true }).label, '进行中')
  const fill = normalizeSurveyFill({ id: 1, myResponse: { id: 7, answers: { q: 'x' }, files: [] } })
  assert.equal(fill.myResponse?.id, '7')
})

test('the choice editor builds options with add and drag instead of the tag input', async () => {
  const editor = await readFile(new URL('../src/RecruitmentFormEditor.tsx', import.meta.url), 'utf8')
  assert.doesNotMatch(editor, /mode="tags"/)
  assert.match(editor, /<ChoiceOptionsEditor/)
  assert.match(editor, /draggable=\{!disabled\}/)
  assert.match(editor, /添加选项/)
  assert.match(editor, /onDrop=/)
})

test('survey routes are gated by their three separate permissions', async () => {
  const app = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8')
  assert.match(app, /path="\/surveys\/:surveyId\/fill" element=\{hasPermission\(user, 'SURVEY_ACCESS'\)/)
  assert.match(app, /path="\/surveys\/:surveyId" element=\{hasAnyPermission\(user, 'SURVEY_CREATE', 'SURVEY_RESULT_VIEW'\)/)
  assert.match(app, /path="\/survey-responses\/:responseId" element=\{hasPermission\(user, 'SURVEY_RESULT_VIEW'\)/)
  const picker = await readFile(new URL('../src/SurveyAudiencePicker.tsx', import.meta.url), 'utf8')
  assert.match(picker, /全选筛选结果/)
  assert.match(picker, /全选所有人/)
})
