import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')
const readBackend = (path: string) =>
  readFile(new URL(`../backend/src/main/java/cn/photolib/${path}`, import.meta.url), 'utf8')

test('分享页是未登录可达的独立路由，不会把已登录的人弹回工作台', async () => {
  const app = await read('App.tsx')

  assert.match(app, /path="\/share\/:token" element=\{<SharedProjectPage \/>\}/)
  // 招募页会把登录用户 Navigate 回工作台，分享页刻意不这么做：链接常常是部员
  // 自己转发出去的，他点开时该看到访客看到的那一屏。
  const shareRoute = app.split('\n').find(line => line.includes('path="/share/:token"')) || ''
  assert.doesNotMatch(shareRoute, /Navigate/)
  // 页面懒加载，不能进 App 外壳的静态 import（见 AGENTS.md §2.18）。
  assert.match(app, /const SharedProjectPage = lazy\(\(\) => import\('\.\/pages\/SharedProjectPage'\)\)/)
})

test('深链要能直接打开：后端 forward 清单和前端路由一一对应', async () => {
  const [forward, app] = await Promise.all([
    readFile(new URL('../backend/src/main/java/cn/photolib/SpaForwardController.java', import.meta.url), 'utf8'),
    read('App.tsx'),
  ])

  assert.match(app, /path="\/share\/:token"/)
  // 漏在控制器就是 404，漏在 SecurityConfig 的 permitAll 就是 401 的 JSON，
  // 两种都不是"打开应用"。
  assert.match(forward, /"\/share\/\{token\}"/)
})

test('前端与后端用同一个分享会话头', async () => {
  const [client, controller] = await Promise.all([
    read('projectShare.ts'),
    readBackend('share/ProjectSharePublicController.java'),
  ])

  assert.match(client, /export const SHARE_SESSION_HEADER = 'X-Share-Session'/)
  assert.match(controller, /SESSION_HEADER = "X-Share-Session"/)
})

test('访客页的能力开关只用于隐藏按钮，权限判定在服务端', async () => {
  const page = await read('pages/SharedProjectPage.tsx')

  // 下载和标记被引都按当前这一刻取回来的 access 显示。
  assert.match(page, /access\?\.allowDownload/)
  assert.match(page, /access\?\.allowAdoption/)
  // access 不是登录后一次性算出来的：每次进入页面重新取，链接的开关可能刚被改过。
  assert.match(page, /shareApi\.access\(token, session\)/)
  // 403/404 一律退回密码页——链接被撤销、密码被重置、会话过期都是这一条出口。
  assert.match(page, /reason\.status === 403 \|\| reason\.status === 404/)
  assert.match(page, /dropSession\('分享访问已失效，请重新输入密码'\)/)
  // 预签名预览地址只活 10 分钟，切回前台要静默重取（见 src/previewFreshness.ts）。
  assert.match(page, /useRefreshOnResume/)
})

test('选题里的筛选全部对分享访客开放，且两页用同一个筛选组件', async () => {
  const [page, detail, bar, client, controller] = await Promise.all([
    read('pages/SharedProjectPage.tsx'),
    read('pages/ProjectDetailPage.tsx'),
    read('ProjectPhotoFilterBar.tsx'),
    read('projectShare.ts'),
    readBackend('share/ProjectSharePublicController.java'),
  ])

  assert.match(page, /<ProjectPhotoFilterBar value=\{filters\}/)
  assert.match(detail, /<ProjectPhotoFilterBar value=\{photoFilters\}/)
  for (const placeholder of ['按标签筛选', '拍摄开始日期', '按拍摄者筛选', '被引状态']) {
    assert.match(bar, new RegExp(placeholder))
  }
  // 访客页是服务端分页：筛选随列表请求发出，"全选全部"翻页时也必须带上，否则会勾到筛掉的图。
  assert.equal(page.match(/\.\.\.listQuery/g)?.length, 3)
  // 多选参数必须是 tags=a&tags=b，Spring 的 List 参数绑不上 axios 默认的 tags[]=a。
  assert.match(client, /paramsSerializer: \{ indexes: null \}/)
  for (const param of ['List<String> tags', 'LocalDate takenFrom', 'LocalDate takenTo',
    'List<String> photographers', 'AdoptionFilter adoption']) {
    assert.ok(controller.includes(param), `后端列表接口缺少参数 ${param}`)
  }
  assert.match(controller, /@GetMapping\("\/photo-filter-options"\)/)
  assert.match(client, /\/photo-filter-options/)
})

test('分享链接管理入口挂在 PROJECT_SHARE 上并懒加载', async () => {
  const [page, types] = await Promise.all([read('pages/ProjectDetailPage.tsx'), read('types.ts')])

  assert.match(types, /\| 'PROJECT_SHARE'/)
  assert.match(page, /const canShare = hasPermission\(user, 'PROJECT_SHARE'\)/)
  assert.match(page, /\{canShare && <Button icon=\{<ShareAltOutlined \/>\}/)
  assert.match(page, /lazy\(\(\) => import\('\.\.\/ProjectShareLinksModal'\)\)/)
})

test('密码只显示一次：管理面板不从任何接口回读明文密码', async () => {
  const [modal, types] = await Promise.all([read('ProjectShareLinksModal.tsx'), read('types.ts')])

  // 列表类型里没有 password 字段——服务端只存哈希，想再看只能重置。
  const linkType = types.slice(types.indexOf('export interface ProjectShareLink'))
    .slice(0, types.slice(types.indexOf('export interface ProjectShareLink')).indexOf('}'))
  assert.doesNotMatch(linkType, /password/)
  assert.match(modal, /密码只显示这一次/)
  assert.match(modal, /share-links\/\$\{link\.id\}\/password/)
})
