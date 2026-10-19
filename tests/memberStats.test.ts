import assert from 'node:assert/strict'
import test from 'node:test'
import {
  EMPTY_FILTER, bucketSelected, campusOptions, citedDistribution, filterMembers, isFiltering, memberCsvRows,
  minutesPerCited, summarize,
} from '../src/memberStats.ts'
import type { MemberStats } from '../src/types.ts'

const member = (studentId: string, displayName: string, adoptedCount: number, totalMinutes: number,
  campus = '北校区'): MemberStats => ({
  userId: totalMinutes > 0 ? 1 : null, studentId, displayName, campus, adoptedCount,
  shootingMinutes: totalMinutes, retouchingMinutes: 0, totalMinutes,
})

const members = [
  member('A1', '张三', 0, 120),
  member('A2', '李四', 2, 60, '北校区、南校区'),
  member('A3', '王五', 7, 300, '南校区'),
  member('A4', '赵六', 25, 0, '南校区'),
]

test('被引张数按两端都含的范围筛，空的一端不限', () => {
  const ids = (filter: Partial<typeof EMPTY_FILTER>) =>
    filterMembers(members, { ...EMPTY_FILTER, ...filter }).map(row => row.studentId)
  assert.deepEqual(ids({ citedMin: 2, citedMax: 7 }), ['A2', 'A3'])
  assert.deepEqual(ids({ citedMin: 1 }), ['A2', 'A3', 'A4'])
  assert.deepEqual(ids({ citedMax: 0 }), ['A1'])
  assert.deepEqual(ids({ citedMin: 8, citedMax: 3 }), [])
})

test('关键词匹配姓名或学号，校区按任一校区算，工时情况区分有没有报', () => {
  const ids = (filter: Partial<typeof EMPTY_FILTER>) =>
    filterMembers(members, { ...EMPTY_FILTER, ...filter }).map(row => row.studentId)
  assert.deepEqual(ids({ keyword: ' a3 ' }), ['A3'])
  assert.deepEqual(ids({ keyword: '李' }), ['A2'])
  assert.deepEqual(ids({ campus: '南校区' }), ['A2', 'A3', 'A4'])
  assert.deepEqual(ids({ worklog: 'without' }), ['A4'])
  assert.deepEqual(ids({ worklog: 'with', citedMin: 1 }), ['A2', 'A3'])
  assert.equal(isFiltering(EMPTY_FILTER), false)
  assert.equal(isFiltering({ ...EMPTY_FILTER, citedMin: 0 }), true)
  assert.deepEqual(campusOptions(members), ['北校区', '南校区'])
})

test('概况：人均只按有被引的人平均，每张被引工时按合计算', () => {
  const summary = summarize(members)
  assert.equal(summary.memberCount, 4)
  assert.equal(summary.citedTotal, 34)
  assert.equal(summary.citedMembers, 3)
  assert.equal(summary.citedWithoutWorklog, 1)
  assert.equal(summary.averageCited, 11.3)
  assert.equal(summary.minutesPerCited, Math.round(480 / 34))
  assert.equal(summarize([]).minutesPerCited, null)
  assert.equal(minutesPerCited(members[0]), null)
  assert.equal(minutesPerCited(members[2]), 43)
})

test('分布图不受被引范围本身影响，选中的那一档能认出来', () => {
  const filter = { ...EMPTY_FILTER, campus: '南校区', citedMin: 6, citedMax: 10 }
  const buckets = citedDistribution(members, filter)
  assert.deepEqual(buckets.map(bucket => bucket.count), [0, 1, 0, 1, 0, 1])
  assert.deepEqual(buckets.filter(bucket => bucketSelected(bucket, filter)).map(bucket => bucket.key), ['6-10'])
})

test('导出行写小时并标出有被引却没报工时的人', () => {
  const [, , , last] = memberCsvRows(members)
  assert.deepEqual(last, ['赵六', 'A4', '南校区', 25, '0.00', '0.00', '0.00', '0.00', '有被引、未报工时'])
  assert.equal(memberCsvRows([members[0]])[0][7], '')
})
