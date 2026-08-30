import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('文档中心的入口对每个能进系统的账号都显示，不按 DOC_MANAGE 收起', async () => {
  const app = await read('App.tsx')

  // 这是回归点：入口一旦挂上 DOC_MANAGE，普通成员就再也找不到"需要登录才能看"
  // 的那些文档——而那些文档正是给他们准备的。
  assert.match(app, /common\.push\(\{ key: '\/documents'/)
  const navLine = app.split('\n').find(line => line.includes("key: '/documents'")) || ''
  assert.doesNotMatch(navLine, /hasPermission/)

  // 路由同样不设权限门；能看到哪些文档由服务端按令牌判定。
  assert.match(app, /path="\/documents" element=\{<DocumentsPage \/>\}/)
  assert.match(app, /path="\/documents\/:publicId" element=\{<DocumentsPage \/>\}/)
  assert.doesNotMatch(app, /path="\/documents"[^\n]*DOC_MANAGE/)
})

test('登录页前面的阅读页不需要登录，也不会把已登录的人弹回工作台', async () => {
  const [app, login] = await Promise.all([read('App.tsx'), read('pages/LoginPage.tsx')])

  assert.match(app, /path="\/docs" element=\{<DocsPage \/>\}/)
  assert.match(app, /path="\/docs\/:publicId" element=\{<DocsPage \/>\}/)
  // 招募页会把登录用户 Navigate 回工作台，文档页刻意不这么做：
  // 登录之后还能多看到"仅成员可见"的部分。
  const docsRoute = app.split('\n').find(line => line.includes('path="/docs"')) || ''
  assert.doesNotMatch(docsRoute, /Navigate/)

  assert.match(login, /navigate\('\/docs'\)/)
  assert.match(login, />查看文档</)
})

test('工作台里的文档中心默认是阅读模式，编辑器按权限收起并懒加载', async () => {
  const source = await read('pages/DocumentsPage.tsx')

  // 默认阅读：部长打开文档中心多数时候是来查东西的。
  assert.match(source, /useState\(false\)/)
  assert.match(source, /const canManage = hasPermission\(user, 'DOC_MANAGE'\)/)
  assert.match(source, /\{canManage && <Space>/)
  assert.match(source, />编辑文档</)
  assert.match(source, />返回阅读</)
  // 编辑器只有部长和管理员用得到，其余人不该下载这段代码。
  assert.match(source, /lazy\(\(\) => import\('\.\/DocsManagePage'\)\)/)
  // 切回阅读要强制重拉，否则刚改过的正文不会反映出来。
  assert.match(source, /setReaderToken\(token => token \+ 1\)/)
})

test('两个入口共用同一个阅读器，可见性判定不在前端做', async () => {
  const [reader, standalone, inShell] = await Promise.all([
    read('DocsReader.tsx'), read('pages/DocsPage.tsx'), read('pages/DocumentsPage.tsx'),
  ])

  assert.match(standalone, /<DocsReader basePath="\/docs"/)
  assert.match(inShell, /<DocsReader basePath="\/documents"/)

  // 目录和正文都直接用服务端返回的结果，前端不按 requiresLogin 过滤——
  // 那样的过滤是装饰，不是权限。
  assert.match(reader, /api<DocReaderNode\[\]>\(\{ url: '\/public\/docs' \}\)/)
  assert.doesNotMatch(reader, /filter\([^)]*requiresLogin/)
  // 403 才提示登录，404 走"没找到"，两者不能合并。
  assert.match(reader, /reason\.code === 'FORBIDDEN'/)
  assert.match(reader, /这篇文档需要登录后查看/)
})
