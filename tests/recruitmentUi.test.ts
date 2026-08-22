import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('public recruitment is wired outside the authenticated shell and login exposes its entry', async () => {
  const [app, login] = await Promise.all([
    readFile(new URL('../src/App.tsx', import.meta.url), 'utf8'),
    readFile(new URL('../src/pages/LoginPage.tsx', import.meta.url), 'utf8'),
  ])
  assert.match(app, /path="\/recruitment"/)
  assert.match(app, /user\s*\?\s*<Navigate/)
  assert.match(app, /path="\/recruitments\/:taskId"/)
  assert.match(app, /path="\/recruitment-applications\/:applicationId"/)
  assert.match(login, /navigate\('\/recruitment'\)/)
  assert.match(login, />\s*招募新成员\s*</)
})

test('public form preserves the exact empty-state copy and sends draft tokens on attachment and submit calls', async () => {
  const source = await readFile(new URL('../src/pages/PublicRecruitmentPage.tsx', import.meta.url), 'utf8')
  assert.match(source, /当前暂无大规模招募任务哦，敬请期待/)
  assert.match(source, /X-Recruitment-Draft-Token/)
  assert.match(source, /PARTIALLY_SUCCEEDED/)
  assert.match(source, /仅提交成功图片/)
  assert.match(source, /uploadToObjectStorage\(ticket\.tickets\[index\], files\[index\]/)
  assert.match(source, /normalizeStudentId\(values\.studentId\)/)
  assert.match(source, /这个学号已经提交过本次招募/)
  assert.doesNotMatch(source, /canvas|toDataURL|compress/i)
})

test('recruitment introduction editors disable protected image insertion for anonymous readability', async () => {
  const [list, detail] = await Promise.all([
    readFile(new URL('../src/pages/RecruitmentsPage.tsx', import.meta.url), 'utf8'),
    readFile(new URL('../src/pages/RecruitmentDetailPage.tsx', import.meta.url), 'utf8'),
  ])
  assert.match(list, /<MarkdownEditor allowImageUpload=\{false\}/)
  assert.match(detail, /<MarkdownEditor allowImageUpload=\{false\}/)
  assert.match(list, /RECRUITMENT_PUBLISH/)
  assert.match(detail, /action: 'publish' \| 'close'/)
  assert.match(detail, /`\/recruitment-tasks\/\$\{task\.id\}\/\$\{action\}`/)
})

test('application answers use the no-link Markdown renderer while attachment links stay explicit', async () => {
  const [renderer, detail] = await Promise.all([
    readFile(new URL('../src/MarkdownRenderer.tsx', import.meta.url), 'utf8'),
    readFile(new URL('../src/pages/RecruitmentApplicationDetailPage.tsx', import.meta.url), 'utf8'),
  ])
  assert.match(renderer, /allowLinks = true/)
  assert.match(renderer, /a: PlainMarkdownLink/)
  assert.match(detail, /<MarkdownRenderer value=\{markdown\} allowLinks=\{false\}/)
  assert.match(detail, /attachment\.downloadUrl \|\| ''/)
  assert.doesNotMatch(detail, /recruitment-attachments/)
})
