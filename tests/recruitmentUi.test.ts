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
  assert.match(login, />\s*我要报名\s*</)
})

test('the public page only bounces a session the backend confirmed, never a cached one', async () => {
  const [app, page, auth] = await Promise.all([
    readFile(new URL('../src/App.tsx', import.meta.url), 'utf8'),
    readFile(new URL('../src/pages/PublicRecruitmentPage.tsx', import.meta.url), 'utf8'),
    readFile(new URL('../src/auth.tsx', import.meta.url), 'utf8'),
  ])

  // `user` 是从 localStorage 乐观读出来的。只看它，浏览器上留着一份过期身份的访客
  // 就会被当成已登录弹去登录页——报名页"有时候打得开有时候打不开"正是这么来的。
  assert.match(app, /element=\{user && sessionVerified\s*\n?\s*\?\s*<Navigate/)
  assert.match(page, /if \(user && sessionVerified\) return <Navigate to="\/" replace \/>/)
  assert.doesNotMatch(page, /if \(user\) return <Navigate/)

  // 标志只在后端应答之后才立起来，失效的每条路径都要把它放下。
  assert.match(auth, /sessionVerified: boolean/)
  assert.match(auth, /api<User>\(\{ url: '\/auth\/me' \}\)\.then\(current => \{\s*\n\s*storeUser\(current\)\s*\n\s*setSessionVerified\(true\)/)
  assert.equal((auth.match(/setSessionVerified\(false\)/g) || []).length, 2)
})

test('a cached user without an access token is not treated as a session at all', async () => {
  const auth = await readFile(new URL('../src/auth.tsx', import.meta.url), 'utf8')
  const readUser = auth.slice(auth.indexOf('function readUser'), auth.indexOf('export function AuthProvider'))

  // 两项一起写入、一起清除；落单的 photolib_user 是脏数据，认它只会让路由乱跳。
  assert.match(readUser, /if \(!localStorage\.getItem\('photolib_access_token'\)\) return null/)
})

test('the public task list does not reload when the session state changes', async () => {
  const page = await readFile(new URL('../src/pages/PublicRecruitmentPage.tsx', import.meta.url), 'utf8')

  // 挂 `user` 会让"清掉过期会话"这一下重新拉列表，卡片整块消失、表单卸载，
  // 访客正在填的内容就丢了。列表是公开数据，本来也不跟着会话变。
  assert.doesNotMatch(page, /\[\] as PublicRecruitmentTask\[\],\s*\n\s*\[user\],/)
})

test('public form preserves the exact empty-state copy and sends draft tokens on attachment and submit calls', async () => {
  const source = await readFile(new URL('../src/pages/PublicRecruitmentPage.tsx', import.meta.url), 'utf8')
  assert.match(source, /现在还没有正在进行的招募，过阵子再来看看吧/)
  assert.match(source, /X-Recruitment-Draft-Token/)
  assert.match(source, /PARTIALLY_SUCCEEDED/)
  assert.match(source, /就交已传好的/)
  assert.match(source, /uploadToObjectStorage\(ticket\.tickets\[index\], files\[index\]/)
  assert.match(source, /normalizeStudentId\(values\.studentId\)/)
  assert.match(source, /这个学号已经报过名/)
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
