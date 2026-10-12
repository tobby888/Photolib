import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  MAX_WORKLOG_MINUTES, alreadyDeclaredOn, describeMinutes, describeWorklogStatus, rejectWorklogReason,
} from '../src/shareWorklog.ts'
import type { ShareGuestWorklog } from '../src/types.ts'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')
const readBackend = (path: string) =>
  readFile(new URL(`../backend/src/main/java/cn/photolib/${path}`, import.meta.url), 'utf8')

const TODAY = '2026-10-12'
const worklog = (changes: Partial<ShareGuestWorklog>): ShareGuestWorklog => ({
  id: '1', workDate: TODAY, shootingMinutes: 60, retouchingMinutes: 0, status: 'SUBMITTED', ...changes,
})

test('申报前的校验与后端同一套规则', () => {
  const ok = { workDate: TODAY, shootingMinutes: 120, retouchingMinutes: 45 }
  assert.equal(rejectWorklogReason(ok, TODAY), null)
  // 只拍没修、只修没拍都算数。
  assert.equal(rejectWorklogReason({ ...ok, retouchingMinutes: 0 }, TODAY), null)
  assert.equal(rejectWorklogReason({ ...ok, shootingMinutes: 0 }, TODAY), null)

  assert.match(String(rejectWorklogReason({ ...ok, workDate: '2026-10-13' }, TODAY)), /不能晚于今天/)
  assert.match(String(rejectWorklogReason({ ...ok, workDate: '' }, TODAY)), /请选择工作日期/)
  assert.match(String(rejectWorklogReason(
    { ...ok, shootingMinutes: 0, retouchingMinutes: 0 }, TODAY)), /不能同时为 0/)
  assert.match(String(rejectWorklogReason(
    { ...ok, shootingMinutes: MAX_WORKLOG_MINUTES + 1 }, TODAY)), /拍摄时长不能超过 1440 分钟/)
  assert.match(String(rejectWorklogReason({ ...ok, retouchingMinutes: -5 }, TODAY)), /修图时长/)
  // 后端的字段是 int，1.5 分钟发过去会被 Jackson 拒掉，在这里先说清楚。
  assert.match(String(rejectWorklogReason({ ...ok, shootingMinutes: 1.5 }, TODAY)), /整数分钟/)
})

test('同一天已有在审或已确认的申报时提前说出来，被退回的不算', () => {
  assert.equal(alreadyDeclaredOn([worklog({ status: 'SUBMITTED' })], TODAY), true)
  assert.equal(alreadyDeclaredOn([worklog({ status: 'CONFIRMED' })], TODAY), true)
  // 退回之后重新申报正是它该走的路。
  assert.equal(alreadyDeclaredOn([worklog({ status: 'REJECTED' })], TODAY), false)
  assert.equal(alreadyDeclaredOn([worklog({ workDate: '2026-10-11' })], TODAY), false)
  assert.equal(alreadyDeclaredOn([], TODAY), false)
})

test('申报人看得到审核结果和退回原因', () => {
  assert.equal(describeWorklogStatus(worklog({ status: 'SUBMITTED' })).text, '待确认')
  assert.equal(describeWorklogStatus(worklog({ status: 'CONFIRMED' })).text, '已确认')
  const rejected = describeWorklogStatus(worklog({ status: 'REJECTED', rejectReason: '时长填多了' }))
  assert.equal(rejected.text, '已退回')
  // 退回通知发给的是开链接的成员，申报人没有账号：这句话是他唯一能看到的说明。
  assert.match(rejected.hint, /时长填多了/)
  assert.match(rejected.hint, /重新申报/)
  assert.match(describeWorklogStatus(worklog({ status: 'REJECTED', rejectReason: null })).hint, /重新申报/)
  // 原因自带句号时不拼出「。。」。
  assert.equal(describeWorklogStatus(worklog({ status: 'REJECTED', rejectReason: '请按实际填写。' })).hint,
    '退回原因：请按实际填写。改好后在上面重新申报一条即可。')
})

test('时长写成人话', () => {
  assert.equal(describeMinutes(45), '45 分钟')
  assert.equal(describeMinutes(120), '2 小时')
  assert.equal(describeMinutes(165), '2 小时 45 分钟')
  assert.equal(describeMinutes(0), '0 分钟')
})

test('前后端的单项时长上限是同一个数', async () => {
  const controller = await readBackend('share/ProjectSharePublicController.java')
  const record = controller.slice(controller.indexOf('record WorklogDeclarationRequest'))

  assert.equal(record.match(/@Max\((\d+)\)/g)?.length, 2)
  assert.match(record, new RegExp(`@Min\\(0\\) @Max\\(${MAX_WORKLOG_MINUTES}\\) int shootingMinutes`))
  assert.match(record, new RegExp(`@Min\\(0\\) @Max\\(${MAX_WORKLOG_MINUTES}\\) int retouchingMinutes`))
})

test('上传页的最后一步是申报工时，身份沿用进门时那一份', async () => {
  const [page, client] = await Promise.all([read('pages/SharedUploadPage.tsx'), read('projectShare.ts')])

  // 申报卡片排在上传卡片之后：先交图，再报工时。
  assert.ok(page.indexOf('title="申报工时"') > page.indexOf('Upload.Dragger'), '申报工时要排在上传区之后')
  assert.match(page, /shareUploadApi\.declareWorklog\(token, session,/)
  // 选题结束后不再收申报，这个开关每次取 access 时由服务端现算。
  assert.match(page, /access\?\.allowWorklog/)
  // 姓名学号不在申报时再填一遍，也不随请求发出去——服务端取会话上的那一份，
  // 否则就有了「传图的是张三、报工时的是李四」的口子。
  const declare = client.slice(client.indexOf('declareWorklog:'), client.indexOf('worklogs:'))
  assert.doesNotMatch(declare, /uploaderName|uploaderStudentId|memberName|studentId/)
})

test('申报记录只在确认是上传链接之后才去取', async () => {
  const page = await read('pages/SharedUploadPage.tsx')

  // 浏览链接调这个接口一律 403，而这一页对 403 的处理是清会话退回密码页——
  // 与 tests/shareUpload.test.ts 里「拿着上传链接打开相册页」是同一个坑的镜像。
  assert.match(page, /if \(!session \|\| access\?\.purpose !== 'UPLOAD'\) return/)
})

test('审核面板把链接申报的工时标出来，并且不给开链接的人「提交」按钮', async () => {
  const page = await read('pages/WorklogsPage.tsx')

  assert.match(page, /item\.shareLinkId\s*\n\s*\? <Space size=\{4\} wrap>\s*\n\s*<Tag color="purple">上传链接<\/Tag>/)
  assert.match(page, /item\.projectTitle \|\| '已删除的选题'/)
  // 记在名下的账号是开链接的成员，他不是干活的人；后端同样拒绝（requireInAppWorklog）。
  assert.match(page, /canSubmit && !item\.shareLinkId && item\.userId === user\?\.id/)
})

test('统计明细里没有需求的工时标成「上传链接申报」', async () => {
  const page = await read('pages/StatisticsPage.tsx')

  assert.match(page, /detail\.viaShareLink\s*\n\s*\? <Tag color="purple">上传链接申报<\/Tag>/)
})
