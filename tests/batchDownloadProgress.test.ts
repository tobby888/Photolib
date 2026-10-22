import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  BatchDownloadCancelled, describeExportProgress, formatBytes, waitForExportJob,
  type ExportJobView,
} from '../src/batchDownloadProgress.ts'

const noSleep = async () => {}

/** 按顺序吐出预设的任务状态，用完了就一直停在最后一个。 */
function scripted(views: Array<ExportJobView | Error>) {
  let calls = 0
  const fetchJob = async () => {
    const view = views[Math.min(calls, views.length - 1)]
    calls += 1
    if (view instanceof Error) throw view
    return view
  }
  return { fetchJob, calls: () => calls }
}

const processing = (doneItems: number): ExportJobView => ({
  job: { status: 'PROCESSING', phase: 'PACKING', progress: doneItems, totalItems: 200, doneItems },
})

test('进度文案：排队、打包张数和字节、生成下载文件、完成、失败', () => {
  assert.equal(describeExportProgress(undefined, 120).title, '正在排队，马上开始打包 120 张图片…')
  const packing = describeExportProgress({
    status: 'PROCESSING', phase: 'PACKING', progress: 30, totalItems: 120, doneItems: 37,
    totalBytes: 3 * 1024 ** 3, doneBytes: 1.5 * 1024 ** 3,
  }, 120)
  assert.equal(packing.percent, 30)
  assert.equal(packing.title, '正在打包 37 / 120 张')
  assert.equal(packing.detail, '已读取 1.5 GB / 3.0 GB')
  const uploading = describeExportProgress({
    status: 'PROCESSING', phase: 'UPLOADING', progress: 80, totalItems: 120, totalBytes: 2048, doneBytes: 1024,
  }, 120)
  assert.equal(uploading.title, '已打包 120 张，正在生成下载文件…')
  assert.equal(uploading.detail, '1.0 KB / 2.0 KB')
  assert.equal(describeExportProgress({ status: 'SUCCEEDED', progress: 100 }, 1).percent, 100)
  assert.equal(describeExportProgress({ status: 'FAILED', errorMessage: '图片已删除' }, 1).detail, '图片已删除')
})

test('进度百分比夹在 0–100，没记字节数时不显示字节行', () => {
  assert.equal(describeExportProgress({ status: 'PROCESSING', progress: 140 }, 1).percent, 100)
  assert.equal(describeExportProgress({ status: 'PROCESSING', progress: -3 }, 1).percent, 0)
  assert.equal(describeExportProgress({ status: 'PROCESSING', phase: 'PACKING', doneItems: 2, totalItems: 5 }, 5).detail,
    undefined)
  assert.equal(formatBytes(512), '512 B')
  assert.equal(formatBytes(250 * 1024 ** 2), '250 MB')
})

test('打包超过一分钟也一直等下去，不再到 60 次就放弃', async () => {
  // 早先写死了 60 次 × 1 秒：打两百张原图要好几分钟，前端提示「仍在后台生成」就走了，后端还在打。
  const views: ExportJobView[] = []
  for (let i = 0; i < 300; i += 1) views.push(processing(Math.floor(i / 2)))
  views.push({ job: { status: 'SUCCEEDED', progress: 100 }, downloadUrl: 'https://oss.example/zip' })
  const { fetchJob, calls } = scripted(views)
  const seen: number[] = []

  const url = await waitForExportJob(fetchJob, { sleep: noSleep, onProgress: job => seen.push(job.doneItems ?? 0) })

  assert.equal(url, 'https://oss.example/zip')
  assert.equal(calls(), 301)
  assert.equal(seen.length, 301)
})

test('失败带上后端的原因；没有原因时用调用方给的说明', async () => {
  await assert.rejects(waitForExportJob(scripted([{ job: { status: 'FAILED', errorMessage: '打包中断了' } }]).fetchJob,
    { sleep: noSleep }), /打包中断了/)
  await assert.rejects(waitForExportJob(scripted([{ job: { status: 'FAILED' } }]).fetchJob,
    { sleep: noSleep, failureMessage: '分享链接打包失败' }), /分享链接打包失败/)
  // 成功了却没有下载地址：结果已过期，不能当成功。
  await assert.rejects(waitForExportJob(scripted([{ job: { status: 'SUCCEEDED' } }]).fetchJob,
    { sleep: noSleep }), /已过期/)
})

test('偶尔一次网络抖动接着等，连续出错才放弃', async () => {
  const flaky = scripted([processing(1), new Error('timeout'), new Error('timeout'), processing(2),
    { job: { status: 'SUCCEEDED' }, downloadUrl: 'u' }])
  assert.equal(await waitForExportJob(flaky.fetchJob, { sleep: noSleep, maxConsecutiveErrors: 3 }), 'u')

  const down = scripted([processing(1), new Error('502'), new Error('502'), new Error('502')])
  await assert.rejects(waitForExportJob(down.fetchJob, { sleep: noSleep, maxConsecutiveErrors: 3 }), /502/)
  assert.equal(down.calls(), 4)
})

test('停止等待立刻中断轮询，包括正在两次轮询之间等待的时候', async () => {
  const controller = new AbortController()
  const { fetchJob, calls } = scripted([processing(1)])
  const waiting = waitForExportJob(fetchJob, { signal: controller.signal, intervalMs: 60_000 })
  setTimeout(() => controller.abort(), 10)

  await assert.rejects(waiting, (error: unknown) => error instanceof BatchDownloadCancelled)
  assert.equal(calls(), 1)
})

test('站内和分享链接两条入口都走同一个轮询，不再各自写死次数', async () => {
  for (const file of ['../src/photoBatchDownload.ts', '../src/projectShare.ts']) {
    const code = await readFile(new URL(file, import.meta.url), 'utf8')
    assert.match(code, /waitForExportJob\(/, file)
    assert.doesNotMatch(code, /attempt < 60/, file)
  }
})
