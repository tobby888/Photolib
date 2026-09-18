import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  MAX_QUEUE_SIZE, MAX_UPLOAD_BYTES, addToQueue, rejectReason, runQueue, summarize,
} from '../src/shareUpload.ts'
import type { ShareUploadItem } from '../src/shareUpload.ts'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')
const readBackend = (path: string) =>
  readFile(new URL(`../backend/src/main/java/cn/photolib/${path}`, import.meta.url), 'utf8')

const fakeFile = (name: string, size = 1024, type = 'image/jpeg') =>
  ({ name, size, type }) as File

const item = (key: string, stage: ShareUploadItem['stage']): ShareUploadItem =>
  ({ key, file: fakeFile(`${key}.jpg`), stage, percent: 0 })

test('前端的文件校验与后端 validateUploadFile 是同一套规则', () => {
  assert.equal(rejectReason(fakeFile('a.jpg')), null)
  assert.equal(rejectReason(fakeFile('a.png', 1024, 'image/png')), null)
  // 扩展名和 Content-Type 必须对得上：后端按同一条规则判，光看其中一个会在
  // 签完票据之后才被拒，访客白等一次上传。
  assert.match(String(rejectReason(fakeFile('a.png', 1024, 'image/jpeg'))), /只能传 JPG 或 PNG/)
  assert.match(String(rejectReason(fakeFile('a.gif', 1024, 'image/gif'))), /只能传 JPG 或 PNG/)
  assert.match(String(rejectReason(fakeFile('a.jpg', 0))), /这个文件是空的/)
  assert.match(String(rejectReason(fakeFile('a.jpg', MAX_UPLOAD_BYTES + 1))), /100 MiB/)
})

test('同一批照片再拖一次不会重复入队', () => {
  // 访客常常会把同一批再拖一次确认"到底传上去没有"。重复入队的结果是服务端按
  // SHA-256 判重，一整批红着报"已经上传过该图片"——看着像失败，其实是传成功了。
  const first = addToQueue([], [fakeFile('a.jpg'), fakeFile('b.jpg')])
  const again = addToQueue(first.items, [fakeFile('a.jpg'), fakeFile('c.jpg')])

  assert.equal(first.items.length, 2)
  assert.deepEqual(again.items.map(entry => entry.file.name), ['c.jpg'])
})

test('超出队列上限的那些被如实报出来，而不是悄悄少传几张', () => {
  const existing = Array.from({ length: MAX_QUEUE_SIZE - 1 },
    (_, index) => item(`old-${index}`, 'done'))
  const added = addToQueue(existing, [fakeFile('x.jpg'), fakeFile('y.jpg'), fakeFile('z.jpg')])

  assert.equal(added.items.length, 1)
  assert.equal(added.dropped, 2)
})

test('不合规的文件被挡下来，同一批里合规的照传不误', () => {
  const added = addToQueue([], [fakeFile('good.jpg'), fakeFile('bad.gif', 1024, 'image/gif')])

  assert.deepEqual(added.items.map(entry => entry.file.name), ['good.jpg'])
  assert.equal(added.errors.length, 1)
})

test('进度汇总把"还在跑"和"已失败"分开数', () => {
  const summary = summarize([
    item('1', 'done'), item('2', 'uploading'), item('3', 'processing'),
    item('4', 'waiting'), item('5', 'failed'),
  ])

  assert.deepEqual(summary, { total: 5, pending: 3, done: 1, failed: 1 })
})

test('队列按给定并发跑完每一张，且不会超发', async () => {
  const items = Array.from({ length: 7 }, (_, index) => index)
  const started: number[] = []
  let running = 0
  let peak = 0

  await runQueue(items, async value => {
    running += 1
    peak = Math.max(peak, running)
    started.push(value)
    await new Promise(resolve => setTimeout(resolve, 1))
    running -= 1
  }, 3)

  assert.deepEqual(started.sort((a, b) => a - b), items)
  assert.ok(peak <= 3, `并发峰值 ${peak} 超过了上限`)
})

test('上传页是未登录可达的独立路由，且后端 forward 与 permitAll 都补上了', async () => {
  const [app, forward, security] = await Promise.all([
    read('App.tsx'),
    readFile(new URL('../backend/src/main/java/cn/photolib/SpaForwardController.java', import.meta.url), 'utf8'),
    readBackend('auth/SecurityConfig.java'),
  ])

  assert.match(app, /path="\/upload\/:token" element=\{<SharedUploadPage \/>\}/)
  // 和分享页一样不把登录用户弹回工作台：链接常常是部员自己转发出去的。
  const route = app.split('\n').find(line => line.includes('path="/upload/:token"')) || ''
  assert.doesNotMatch(route, /Navigate/)
  assert.match(app, /const SharedUploadPage = lazy\(\(\) => import\('\.\/pages\/SharedUploadPage'\)\)/)
  // 漏在控制器就是 404，漏在 permitAll 就是 401 的 JSON，两种都不是"打开应用"。
  assert.match(forward, /"\/upload\/\{token\}"/)
  assert.match(security, /"\/upload\/\*\*"/)
})

test('两种用途的链接互相把访客送到对的那一页', async () => {
  const [uploadPage, sharePage] = await Promise.all([
    read('pages/SharedUploadPage.tsx'),
    read('pages/SharedProjectPage.tsx'),
  ])

  // greet 回的 purpose 决定这个 token 该由哪一页接；拿错了页面不该是一屏空白。
  assert.match(uploadPage, /greeting\.purpose === 'UPLOAD'/)
  assert.match(uploadPage, /Navigate to=\{`\/share\/\$\{token\}`\}/)
  assert.match(sharePage, /setPurpose\(greeting\.purpose\)/)
  assert.match(sharePage, /purpose === 'UPLOAD'\) return <Navigate to=\{`\/upload\/\$\{token\}`\}/)
})

test('拿着上传链接打开相册页，不会把手里的会话清掉', async () => {
  const page = await read('pages/SharedProjectPage.tsx')

  // 上传链接在浏览侧一律被服务端 403 挡回，而这一页对 403 的处理是"清掉会话、
  // 退回密码页"。所以所有访客请求都要等 greet 说清楚这是哪种链接之后再发，
  // 否则一个有效的上传会话会在跳去上传页之前被自己清掉。
  const guard = /if \(!session \|\| purpose !== 'BROWSE'\) return/g
  // access、筛选候选、图片列表三条请求都要挡住，漏一条就足够把会话清掉。
  assert.equal(page.match(guard)?.length, 3)
  assert.match(page, /const \[purpose, setPurpose\] = useState<ShareLinkPurpose \| null>\(null\)/)
})

test('上传页只上传，不显示选题里已有的任何图片', async () => {
  const page = await read('pages/SharedUploadPage.tsx')

  // 上传链接被授权的是"往里放"，不是"看里面有什么"——服务端按同一条线拒绝。
  assert.doesNotMatch(page, /shareApi\.photos/)
  assert.doesNotMatch(page, /shareApi\.downloadUrl/)
  // 选题结束后立刻停止收图，这个开关每次取 access 时由服务端现算。
  assert.match(page, /access\?\.allowUpload/)
  // 403/404 一律退回密码页，与相册访客页同一条出口。
  assert.match(page, /reason\.status === 403 \|\| reason\.status === 404/)
})

test('生成上传链接的入口只对进行中的活动选题出现', async () => {
  const [detail, modal] = await Promise.all([
    read('pages/ProjectDetailPage.tsx'),
    read('ProjectShareLinksModal.tsx'),
  ])

  assert.match(detail, /uploadLinksAvailable=\{isEvent && project\.status === 'ACTIVE'\}/)
  assert.match(modal, /uploadLinksAvailable && <Button icon=\{<CloudUploadOutlined \/>\}/)
  // 上传链接没有下载/标记被引这两项能力，界面上也就不该摆出点不动的勾选框。
  assert.match(modal, /link\.purpose === 'UPLOAD'\s*\n\s*\? <Typography\.Text type="secondary">仅上传/)
})
