import assert from 'node:assert/strict'
import test from 'node:test'
import {
  EMPTY_RECIPIENT_FILTER, batchProgress, distinctValues, failedUserIds, filterRecipients, formatMinutes,
  insertVariable, isSelectable, keepSelectable, unavailableReason, unknownVariables,
} from '../src/settlementMail.ts'
import type { SettlementBatch, SettlementRecipient } from '../src/settlementMail.ts'

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
