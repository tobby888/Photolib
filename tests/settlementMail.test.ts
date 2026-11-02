import assert from 'node:assert/strict'
import test from 'node:test'
import {
  EMPTY_RECIPIENT_FILTER, batchProgress, channelsLabel, distinctValues, failedChannels, failedUserIds,
  filterRecipients, formatMinutes, insertVariable, isSelectable, keepSelectable, statementFileName,
  statementIdFromActionUrl, unavailableReason, uniqueNames, unknownVariables,
} from '../src/settlementMail.ts'
import type { SettlementBatch, SettlementRecipient } from '../src/settlementMail.ts'
import { blockedRows, chunkPages, cssColorToHex, pageStarts } from '../src/settlementPdf.ts'

const recipient = (patch: Partial<SettlementRecipient>): SettlementRecipient => ({
  userId: 1, username: 'u', displayName: '张三', email: 'z@example.cn', campus: '北校区', groupName: '校区负责人',
  worklogCount: 0, confirmedMinutes: 0, pendingCount: 0, rejectedCount: 0, adoptedPhotos: 0, adoptionCount: 0,
  sameNameCount: 0, ...patch,
})

test('没有邮箱或同名的账号不能选，并说明原因', () => {
  assert.equal(unavailableReason(recipient({})), null)
  assert.match(unavailableReason(recipient({ email: null })) ?? '', /邮箱/)
  assert.match(unavailableReason(recipient({ sameNameCount: 1 })) ?? '', /同名/)
  assert.equal(isSelectable(recipient({ email: '' })), false)
})

test('发站内信时没有邮箱也能选，同名的始终不能选', () => {
  const inbox = { email: false, inbox: true }
  const both = { email: true, inbox: true }
  assert.equal(isSelectable(recipient({ email: null }), inbox), true)
  assert.equal(isSelectable(recipient({ email: null }), both), true)
  assert.equal(isSelectable(recipient({ sameNameCount: 1 }), inbox), false)
  const list = [recipient({ userId: 1 }), recipient({ userId: 2, email: null })]
  assert.deepEqual(keepSelectable(['1', '2'], list, both), ['1', '2'])
  // 改回只发邮件，没有邮箱的人自动取消勾选。
  assert.deepEqual(keepSelectable(['1', '2'], list, { email: true, inbox: false }), ['1'])
})

test('筛选：关键词、校区、权限组、有无记录、只看有退回的', () => {
  const list = [
    recipient({ userId: 1, displayName: '张三', worklogCount: 2, rejectedCount: 1 }),
    recipient({ userId: 2, displayName: '李四', username: 'lisi', campus: '南校区', adoptionCount: 3 }),
    recipient({ userId: 3, displayName: '王五', groupName: '部长', email: 'wangwu@example.cn' }),
  ]
  const ids = (filter: Partial<typeof EMPTY_RECIPIENT_FILTER>) =>
    filterRecipients(list, { ...EMPTY_RECIPIENT_FILTER, ...filter }).map(r => r.userId)

  // 默认只看期内有记录的人：工时或被引任一即可。
  assert.deepEqual(ids({}), [1, 2])
  // 只上传过图片（参与了选题）也算有记录。
  assert.equal(filterRecipients([recipient({ uploadedPhotos: 3, projectCount: 1 })], EMPTY_RECIPIENT_FILTER).length, 1)
  assert.deepEqual(ids({ records: 'all' }), [1, 2, 3])
  assert.deepEqual(ids({ records: 'without' }), [3])
  assert.deepEqual(ids({ records: 'all', keyword: 'LISI' }), [2])
  assert.deepEqual(ids({ records: 'all', keyword: 'wangwu@' }), [3])
  assert.deepEqual(ids({ records: 'all', campus: '南校区' }), [2])
  assert.deepEqual(ids({ records: 'all', group: '部长' }), [3])
  assert.deepEqual(ids({ onlyRejected: true }), [1])
})

test('名单刷新后，勾选里去掉不再可选的人', () => {
  const list = [recipient({ userId: 1 }), recipient({ userId: 2, email: null }), recipient({ userId: 3, sameNameCount: 1 })]
  assert.deepEqual(keepSelectable(['1', '2', '3', '4'], list), ['1'])
  // 雪花 id 以字符串返回，不能被转成 number 丢精度。
  const big = recipient({ userId: '2117247722871742465' })
  assert.deepEqual(keepSelectable(['2117247722871742465'], [big]), ['2117247722871742465'])
})

test('下拉候选去重排序', () => {
  const list = [recipient({ campus: '南校区' }), recipient({ campus: '北校区' }), recipient({ campus: null }),
    recipient({ campus: '南校区' })]
  assert.deepEqual(distinctValues(list, 'campus'), ['北校区', '南校区'])
})

test('时长、进度和失败名单', () => {
  assert.equal(formatMinutes(0), '0')
  assert.equal(formatMinutes(45), '45 分钟')
  assert.equal(formatMinutes(120), '2 小时')
  assert.equal(formatMinutes(150), '2 小时 30 分')
  assert.equal(batchProgress({ total: 4, sent: 1, failed: 1 }), 50)
  assert.equal(batchProgress({ total: 0, sent: 0, failed: 0 }), 100)
  const batch = { deliveries: [
    { userId: 1, status: 'SENT' }, { userId: 2, status: 'FAILED' }, { userId: 3, status: 'FAILED' },
  ] } as unknown as SettlementBatch
  assert.deepEqual(failedUserIds(batch), ['2', '3'])
})

test('在光标处插入变量；拼错的变量会被找出来', () => {
  assert.deepEqual(insertVariable('ab', 1, 1, 'siteName'), { text: 'a{{siteName}}b', cursor: 13 })
  assert.deepEqual(insertVariable('abcd', 1, 3, 'x'), { text: 'a{{x}}d', cursor: 6 })
  const variables = { siteName: '', siteUrl: '' }
  assert.deepEqual(unknownVariables('{{siteName}}{{#siteUrl}}x{{/siteUrl}}{{ siteNmae }}{{nope}}', variables),
    ['siteNmae', 'nope'])
})

test('重发失败的：只补失败的那一种方式', () => {
  const batch = (deliveries: object[]) => ({ deliveries } as unknown as SettlementBatch)
  assert.deepEqual(failedChannels(batch([
    { status: 'SENT', emailStatus: 'SENT', inboxStatus: 'SENT' },
    { status: 'FAILED', emailStatus: 'FAILED', inboxStatus: 'SENT' },
  ])), { email: true, inbox: false })
  assert.deepEqual(failedChannels(batch([
    { status: 'FAILED', emailStatus: null, inboxStatus: 'FAILED' },
  ])), { email: false, inbox: true })
  // 分渠道之前的老批次：失败的就是邮件。
  assert.deepEqual(failedChannels(batch([{ status: 'FAILED' }])), { email: true, inbox: false })
  assert.equal(channelsLabel({ sendEmail: true, sendInbox: true }), '站内信 + 邮件')
  assert.equal(channelsLabel({ sendEmail: false, sendInbox: true }), '站内信')
  assert.equal(channelsLabel({}), '邮件')
})

test('PDF 文件名去掉非法字符、ZIP 里重名加序号；认得出清算单通知', () => {
  assert.equal(statementFileName('张/三:*', '2026-10-01', '2026-10-31'), '张_三_-清算单-2026-10-01至2026-10-31.pdf')
  assert.deepEqual(uniqueNames(['a.pdf', 'b.pdf', 'a.pdf', 'a.pdf']), ['a.pdf', 'b.pdf', 'a (2).pdf', 'a (3).pdf'])
  assert.equal(statementIdFromActionUrl('/settlements/42'), '42')
  assert.equal(statementIdFromActionUrl('/settlements/42/x'), null)
  assert.equal(statementIdFromActionUrl('/worklogs'), null)
  assert.equal(statementIdFromActionUrl(null), null)
})

test('PDF 分页：不从一行字或一张小卡片中间切开', () => {
  // 0-80 页眉；80-260 一张卡片（不到半页，不能切）；300-1200 一个大块（超过半页，可以切）里有几行字。
  const bands = [{ top: 0, bottom: 80 }, { top: 80, bottom: 260 }, { top: 300, bottom: 1200 },
    { top: 380, bottom: 400 }, { top: 390, bottom: 420 }]
  const blocked = blockedRows(bands, 1200, 500)
  assert.equal(blocked[80], 0) // 边沿本身可以断
  assert.equal(blocked[81] > 0, true)
  assert.equal(blocked[259] > 0, true)
  assert.equal(blocked[260], 0)
  assert.equal(blocked[700], 0) // 大块内部、没有文字的地方可以断
  assert.equal(blocked[400] > 0, true) // 重叠的两行字连在一起都不能断
  // 页高 400：第一页尾巴 400 落在字里，往上找到 380 断开。
  assert.deepEqual(pageStarts(blocked, 1200, 400), [0, 380, 780, 1180])
  // 往上找不到能断的地方（整页都是一个小块）时硬切在页尾。
  const solid = blockedRows([{ top: 0, bottom: 900 }], 1000, 1000)
  assert.deepEqual(pageStarts(solid, 1000, 400), [0, 400, 800])
  // 正好一页，不多出空白页。
  assert.deepEqual(pageStarts(new Int32Array(401), 400, 400), [0])
})

test('PDF 截图分段：相邻几页合成一段，每段不超过上限', () => {
  assert.deepEqual(chunkPages([0, 1000, 2000, 3000], 3500, 2500).map(c => [c.from, c.to, c.pages]),
    [[0, 2000, [0, 1]], [2000, 3500, [2, 3]]])
  assert.deepEqual(chunkPages([0], 300, 2500).map(c => [c.from, c.to, c.pages]), [[0, 300, [0]]])
})

test('页面底色转成 jsPDF 用的十六进制', () => {
  assert.equal(cssColorToHex('rgb(238, 242, 246)'), '#eef2f6')
  assert.equal(cssColorToHex('rgba(0, 0, 0, 0)'), null)
  assert.equal(cssColorToHex('rgba(255, 255, 255, 1)'), '#ffffff')
  assert.equal(cssColorToHex('transparent'), null)
})
