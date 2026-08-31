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

test('还没分配权限组的账号在拦截页上仍有文档中心入口', async () => {
  const app = await read('App.tsx')

  // 后端把这类会话当成已登录的成员来判文档可见范围（AccessTokenFilter），
  // 前端这里就必须留一个入口——否则新同学登录后依然看不到"入部须知"那类
  // 要求登录才能看的文档。
  const pending = app.slice(app.indexOf('access-pending-page'))
  assert.match(pending, />查看文档中心</)
  assert.match(pending, /navigate\('\/docs'\)/)
  assert.match(pending, />退出登录</)
})

test('PDF 文档和 Markdown 文档共用同一套可见性与目录判定', async () => {
  const [reader, tree] = await Promise.all([read('DocsReader.tsx'), read('docsTree.ts')])

  // 叶子判据是"不是文件夹"：写死 DOCUMENT 会让 PDF 既点不开、
  // 也不会被"默认打开第一篇"选中。
  assert.match(reader, /isLeaf: node\.nodeType !== 'FOLDER'/)
  assert.match(tree, /node\.nodeType !== 'FOLDER'/)
  // 正文和 PDF 二选一渲染，两条分支都由服务端给的 nodeType 决定。
  assert.match(reader, /document\.nodeType === 'PDF' && document\.fileUrl/)
})

test('PDF 一律走 axios 取 Blob，不把接口地址直接塞给 iframe', async () => {
  const viewer = await read('DocPdfViewer.tsx')

  // iframe 不会带上 localStorage 里的令牌：直接用地址会让仅成员的 PDF
  // 变成一个 403 的白框，编辑器里预览草稿更是必须带令牌。
  assert.match(viewer, /http\.get<Blob>\(path, \{ responseType: 'blob' \}\)/)
  assert.match(viewer, /URL\.createObjectURL/)
  // 几十 MiB 的 PDF 必须回收，否则点几篇就能把标签页撑爆。
  assert.match(viewer, /URL\.revokeObjectURL\(currentUrl\)/)
  assert.doesNotMatch(viewer, /<iframe[^>]*src=\{path\}/)
})

test('编辑器把 PDF 当成一等文档：能传、能换、能设发布与可见范围', async () => {
  const manage = await read('pages/DocsManagePage.tsx')

  assert.match(manage, /url: '\/docs\/pdf'/)
  assert.match(manage, /url: `\/docs\/\$\{selected\.id\}\/pdf`/)
  // 发布/可见范围那一整块按"不是文件夹"展开，写死 DOCUMENT 会让上传的 PDF
  // 永远停在草稿上——它连开关都看不到。
  assert.match(manage, /selected\.nodeType !== 'FOLDER' && <>/)
  // 前端的上限必须和后端 PdfUpload.MAX_BYTES 对上。
  assert.match(manage, /const PDF_MAX_BYTES = 50 \* 1024 \* 1024/)
})
