import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  MAX_QUEUE_SIZE, MAX_REMEMBERED_UPLOADERS, addToQueue, canRetag, rememberUploader,
  createUploadPump, describeBatchOutcome, isTerminalBatchStatus, markFailedForRetry,
  rejectArchiveReason, rejectReason, summarize,
} from '../src/shareUpload.ts'
import type { ShareUploadItem } from '../src/shareUpload.ts'
import { DEFAULT_UPLOAD_LIMITS } from '../src/uploadLimits.ts'

const MAX_UPLOAD_BYTES = DEFAULT_UPLOAD_LIMITS.PHOTO_IMAGE_MAX_BYTES
const MAX_ARCHIVE_BYTES = DEFAULT_UPLOAD_LIMITS.PHOTO_ZIP_MAX_BYTES

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')
const readBackend = (path: string) =>
  readFile(new URL(`../backend/src/main/java/cn/photolib/${path}`, import.meta.url), 'utf8')

const fakeFile = (name: string, size = 1024, type = 'image/jpeg') =>
  ({ name, size, type }) as File

const item = (key: string, stage: ShareUploadItem['stage']): ShareUploadItem =>
  ({ key, file: fakeFile(`${key}.jpg`), stage, percent: 0, tags: [] })

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

test('单张上限跟着管理员设的值走，提示里写的也是那个数', () => {
  const limits = { ...DEFAULT_UPLOAD_LIMITS, PHOTO_IMAGE_MAX_BYTES: 10 * 1024 * 1024 }
  assert.equal(rejectReason(fakeFile('a.jpg', 10 * 1024 * 1024), limits), null)
  assert.match(String(rejectReason(fakeFile('a.jpg', 10 * 1024 * 1024 + 1), limits)), /10 MiB/)
  const added = addToQueue([], [fakeFile('big.jpg', 20 * 1024 * 1024), fakeFile('ok.jpg')], limits)
  assert.deepEqual(added.items.map(entry => entry.file.name), ['ok.jpg'])
  assert.match(added.errors[0], /big\.jpg：单张不能超过 10 MiB/)
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

const tick = (ms = 1) => new Promise(resolve => setTimeout(resolve, ms))

test('队列按给定并发跑完每一张，且不会超发', async () => {
  const items = Array.from({ length: 7 }, (_, index) => index)
  const started: number[] = []
  let running = 0
  let peak = 0

  await new Promise<void>(resolve => {
    const pump = createUploadPump<number>(async value => {
      running += 1
      peak = Math.max(peak, running)
      started.push(value)
      await tick()
      running -= 1
    }, resolve, 3)
    pump.push(items)
  })

  assert.deepEqual(started.sort((a, b) => a - b), items)
  assert.ok(peak <= 3, `并发峰值 ${peak} 超过了上限`)
})

test('放进队列就开传，不等任何「开始」', async () => {
  const started: number[] = []
  const pump = createUploadPump<number>(async value => { started.push(value) })

  pump.push([1, 2])
  assert.equal(pump.busy, true)
  await tick()

  assert.deepEqual(started, [1, 2])
  assert.equal(pump.busy, false)
})

test('正在传的时候再拖一批，接在同一条队列后面，并发不翻倍', async () => {
  const started: number[] = []
  let running = 0
  let peak = 0
  let idleCount = 0
  const gates = new Map<number, () => void>()
  const pump = createUploadPump<number>(value => {
    running += 1
    peak = Math.max(peak, running)
    started.push(value)
    return new Promise<void>(resolve => gates.set(value, () => { running -= 1; resolve() }))
  }, () => { idleCount += 1 }, 2)

  pump.push([1, 2, 3])
  await tick()
  // 第一批还卡着，再拖一批进来：只能排队，不能另开并发位。
  pump.push([4, 5])
  await tick()
  assert.deepEqual(started, [1, 2])

  for (const value of [1, 2, 3, 4, 5]) {
    while (!gates.has(value)) await tick()
    gates.get(value)?.()
    await tick()
  }

  assert.deepEqual(started, [1, 2, 3, 4, 5])
  assert.ok(peak <= 2, `并发峰值 ${peak} 超过了上限`)
  // 两批拼成一段连续的忙碌，收尾只该有一次。
  assert.equal(idleCount, 1)
})

test('一张抛错不会卡死队列，也不会留下没人接的 rejection', async () => {
  const done: number[] = []
  await new Promise<void>(resolve => {
    const pump = createUploadPump<number>(async value => {
      if (value === 2) throw new Error('坏文件')
      done.push(value)
    }, resolve, 1)
    pump.push([1, 2, 3])
  })
  assert.deepEqual(done, [1, 3])
})

test('重试只把失败的放回队列，传成功的不再传一次', () => {
  const { items, retried } = markFailedForRetry([
    item('ok', 'done'), item('bad', 'failed'), item('busy', 'uploading'),
  ].map(entry => entry.key === 'bad' ? { ...entry, error: '网络断了', percent: 40 } : entry))

  assert.deepEqual(retried.map(entry => entry.key), ['bad'])
  assert.deepEqual(items.map(entry => entry.stage), ['done', 'waiting', 'uploading'])
  assert.equal(items[1].error, undefined)
  assert.equal(items[1].percent, 0)
})

test('上传页拖进来即开传，没有「开始上传」按钮，传的时候也能接着拖', async () => {
  const page = await read('pages/SharedUploadPage.tsx')

  assert.doesNotMatch(page, /startUpload|「开始上传」|>\s*开始上传/)
  assert.match(page, /updateItems\(current => \[\.\.\.current, \.\.\.added\.items\]\)\s*startQueue\(added\.items\)/)
  // 逐张的拖拽区不能因为"正在传"而禁用，否则又退回成"传完一批才能拖下一批"。
  assert.match(page, /<Upload\.Dragger multiple accept="image\/jpeg,image\/png" showUploadList=\{false\}\s*disabled=\{!access\?\.allowUpload\}/)
})

test('ZIP 的前端校验与后端同一套：只收 .zip，且不超过 1.5 GB', () => {
  assert.equal(rejectArchiveReason({ name: '活动.zip', size: 1024 }), null)
  assert.equal(rejectArchiveReason({ name: '活动.ZIP', size: 1024 }), null)
  assert.match(String(rejectArchiveReason({ name: '活动.rar', size: 1024 })), /只能上传 \.zip/)
  assert.match(String(rejectArchiveReason({ name: '活动.zip', size: 0 })), /空的/)
  assert.match(String(rejectArchiveReason({ name: '活动.zip', size: MAX_ARCHIVE_BYTES + 1 })), /1\.5 GB/)
  const raised = { ...DEFAULT_UPLOAD_LIMITS, PHOTO_ZIP_MAX_BYTES: 3 * 1024 ** 3 }
  assert.equal(rejectArchiveReason({ name: '活动.zip', size: MAX_ARCHIVE_BYTES + 1 }, raised), null)
  assert.match(String(rejectArchiveReason({ name: '活动.zip', size: 3 * 1024 ** 3 + 1 }, raised)), /3 GiB/)
})

test('批次终态的判定和站内那套状态一致', () => {
  assert.equal(isTerminalBatchStatus('SUCCEEDED'), true)
  assert.equal(isTerminalBatchStatus('PARTIALLY_SUCCEEDED'), true)
  assert.equal(isTerminalBatchStatus('FAILED'), true)
  // 这三个都还在后台动，值得再问一次。
  assert.equal(isTerminalBatchStatus('UPLOADING'), false)
  assert.equal(isTerminalBatchStatus('PROCESSING'), false)
  assert.equal(isTerminalBatchStatus('WAITING_METADATA'), false)
})

test('部分成功要把失败的张数说出来，不能只说"完成了"', () => {
  // 访客手里那一包是他自己的，少掉的那几张没有人会再替他去找。
  const partial = describeBatchOutcome({
    status: 'PARTIALLY_SUCCEEDED', successCount: 8, failureCount: 2,
  })
  assert.equal(partial.tone, 'warning')
  assert.match(partial.message, /8 张/)
  assert.match(partial.message, /2 张/)

  const ok = describeBatchOutcome({ status: 'SUCCEEDED', successCount: 10, failureCount: 0 })
  assert.equal(ok.tone, 'success')
  assert.match(ok.message, /10 张/)

  // 解包失败时把后端给的原因原样带出来（不是 ZIP、里面没有图片、超限额）。
  const failed = describeBatchOutcome({
    status: 'FAILED', successCount: 0, failureCount: 0, failureReason: 'ZIP 中没有 JPG/PNG 图片',
  })
  assert.equal(failed.tone, 'error')
  assert.equal(failed.message, 'ZIP 中没有 JPG/PNG 图片')

  // 还没跑完就被问到结果：后台仍在处理，不能说成失败。
  assert.equal(describeBatchOutcome({
    status: 'PROCESSING', successCount: 0, failureCount: 0,
  }).tone, 'warning')
})

test('访客页的 ZIP 限额与站内同一份，而且是管理员设的那一份', async () => {
  const [service, page] = await Promise.all([
    readBackend('share/ProjectShareUploadService.java'),
    read('pages/SharedUploadPage.tsx'),
  ])

  // 站外不另开一套阈值：松的那一套就是被用来打进来的那一套。
  assert.match(service, /UploadLimit\.PHOTO_IMAGE_MAX_BYTES/)
  assert.doesNotMatch(service, /imageMaxBytes\(\)/)
  // 页面上的数字来自 /upload-limits，不能再写死。
  assert.match(page, /useUploadLimits\(\)/)
  assert.match(page, /uploadLimits\.PHOTO_ZIP_MAX_IMAGES/)
  assert.doesNotMatch(page, /1\.5 GB|100 MiB/)
})

test('访客的 ZIP 走的是站内那条批次通道', async () => {
  const [service, client] = await Promise.all([
    readBackend('share/ProjectShareUploadService.java'),
    read('projectShare.ts'),
  ])

  // 建批次、解包、落照片全部复用 BatchUploadService，这里只加一层访客的授权。
  assert.match(service, /batchService\.createZipBatch/)
  assert.match(service, /batchService\.completeZipBatch/)
  assert.match(service, /batchService\.finishAllWithSnapshot/)
  // 批次归属只认 share_link_id：created_by 记的是链接创建者，对访客不是凭据。
  assert.match(service, /link\.getId\(\)\.equals\(batch\.getShareLinkId\(\)\)/)
  assert.match(client, /upload-batches/)
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

test('一次拖进来好几个压缩包，只会跑一条上传', async () => {
  const page = await read('pages/SharedUploadPage.tsx')

  // antd 对每个拖进来的文件都调一次 beforeUpload；不认第一个的话会并发跑好几条，
  // 而进度、结果和 running 只有一份，先跑完的那条会把拖拽区提前解禁。
  assert.match(page, /if \(file !== fileList\[0\]\) return Upload\.LIST_IGNORE/)
  assert.match(page, /if \(!session \|\| running\) return/)
})

test('失败原因分两类：文件自己的毛病照说，基础设施的报错不外抬', async () => {
  const [helper, batch, photo] = await Promise.all([
    readBackend('common/upload/UploadFailureMessage.java'),
    readBackend('photo/batch/BatchProcessingService.java'),
    readBackend('photo/PhotoProcessingService.java'),
  ])

  // 按异常类型分，不按文案匹配：文案会改，类型不会。
  assert.match(helper, /exception instanceof IllegalArgumentException/)
  // 两条流水线都要走它，否则 failure_reason 里仍会留下 OSS 的原文，
  // 而这个字段会经由上传链接回给未登录的访客。
  assert.match(batch, /UploadFailureMessage\.forUploader/)
  assert.match(photo, /UploadFailureMessage\.forUploader/)
  // 原文不能就这么丢了，得进日志。
  assert.match(batch, /UploadFailureMessage\.isInternal[\s\S]{0,200}log\.error/)
  assert.match(photo, /UploadFailureMessage\.isInternal[\s\S]{0,200}log\.error/)
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

test('生成上传链接的入口对进行中的选题出现，创作选题和活动选题都可以', async () => {
  const [detail, modal, service] = await Promise.all([
    read('pages/ProjectDetailPage.tsx'),
    read('ProjectShareLinksModal.tsx'),
    readFile(new URL('../backend/src/main/java/cn/photolib/share/ProjectShareService.java', import.meta.url), 'utf8'),
  ])

  // 只看状态、不看类型——与服务端 requireUploadableProject 同一条规则。
  assert.match(detail, /uploadLinksAvailable=\{project\.status === 'ACTIVE'\}/)
  const rule = service.slice(service.indexOf('private void requireUploadableProject'),
    service.indexOf('boolean canAcceptUploads'))
  assert.match(rule, /project\.getStatus\(\) != ProjectStatus\.ACTIVE/)
  assert.doesNotMatch(rule, /ProjectType/)
  assert.match(modal, /uploadLinksAvailable && <Button icon=\{<CloudUploadOutlined \/>\}/)
  // 上传链接没有下载/标记被引这两项能力，界面上也就不该摆出点不动的勾选框。
  assert.match(modal, /link\.purpose === 'UPLOAD'\s*\n\s*\? <Typography\.Text type="secondary">仅上传/)
})

test('入队时把「这批照片的标签」抄到每一张上，之后改标签框不影响已经排上的', () => {
  const batch = ['舞台']
  const added = addToQueue([], [fakeFile('a.jpg'), fakeFile('b.jpg')], DEFAULT_UPLOAD_LIMITS, batch)
  assert.deepEqual(added.items.map(entry => entry.tags), [['舞台'], ['舞台']])
  batch.push('观众')
  assert.deepEqual(added.items[0].tags, ['舞台'])
  // 不带标签时是空数组而不是 undefined：complete 照样发得出去。
  assert.deepEqual(addToQueue([], [fakeFile('c.jpg')]).items[0].tags, [])
})

test('只有处理完、记在当前拍摄者名下的那张才能改标签', () => {
  const done = { ...item('a', 'done'), photoId: '1', photographerStudentId: '20230001' }
  assert.equal(canRetag(done, '20230001'), true)
  // 切换到别人之后，前一个人的图要切回去才能改——与服务端 updateTags 同一条线。
  assert.equal(canRetag(done, '20230002'), false)
  assert.equal(canRetag(done, null), false)
  // 还在处理中：压缩管线提交成品时按 version 做 CAS，这时改标签会让那一步失败。
  assert.equal(canRetag({ ...done, stage: 'processing' }, '20230001'), false)
  assert.equal(canRetag({ ...done, photoId: undefined }, '20230001'), false)
})

test('用过的拍摄者按学号去重、最新的在前，最多记几个', () => {
  const zhang = { uploaderName: '张三', uploaderStudentId: '20230001' }
  const li = { uploaderName: '李四', uploaderStudentId: '20230002' }
  let list = rememberUploader([], zhang)
  list = rememberUploader(list, li)
  assert.deepEqual(list.map(entry => entry.uploaderName), ['李四', '张三'])
  // 同一个学号再来一次：挪到最前面，姓名以最新填的为准，不重复。
  list = rememberUploader(list, { uploaderName: ' 张三丰 ', uploaderStudentId: '20230001 ' })
  assert.deepEqual(list, [{ uploaderName: '张三丰', uploaderStudentId: '20230001' }, li])
  // 空的不记。
  assert.deepEqual(rememberUploader(list, { uploaderName: ' ', uploaderStudentId: '1' }), list)
  for (let index = 0; index < 10; index += 1) {
    list = rememberUploader(list, { uploaderName: `同学${index}`, uploaderStudentId: `2024${index}` })
  }
  assert.equal(list.length, MAX_REMEMBERED_UPLOADERS)
  assert.equal(list[0].uploaderName, '同学9')
})

test('切换拍摄者和改标签都接到了后端的访客接口上', async () => {
  const [client, controller, page] = await Promise.all([
    read('projectShare.ts'), readBackend('share/ProjectSharePublicController.java'),
    read('pages/SharedUploadPage.tsx'),
  ])
  assert.match(client, /\/session\/uploader/)
  assert.match(controller, /@PutMapping\("\/session\/uploader"\)/)
  assert.match(client, /\/uploads\/\$\{photoId\}\/tags/)
  assert.match(controller, /@PutMapping\("\/uploads\/\{photoId\}\/tags"\)/)
  // 正在传的时候不让切：排着的那几张会在切换之后才签票据，一批图就分到两个人名下。
  assert.match(page, /disabled=\{uploadBusy \|\| !access\}/)
})
