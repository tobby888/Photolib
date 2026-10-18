import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  FIELDS, changeField, changeOperator, defaultCondition, foldTail, isComplete, newGroup, newLeaf, toHours, toPayload,
} from '../src/worklogAnalysis.ts'
import type { AnalysisBucket, ConditionField, ConditionLeaf } from '../src/worklogAnalysis.ts'
import { csvCell, rowsToCsv } from '../src/worklogCsv.ts'
import { summarizeProjectWorklogs } from '../src/projectWorklogs.ts'

const leaf = (field: ConditionField, operator: ConditionLeaf['operator'], ...values: string[]): ConditionLeaf =>
  ({ ...newLeaf(field), operator, values })

test('条件树发给后端时去掉界面用的 key，没填完的条件和因此变空的分组一并去掉', () => {
  const tree = newGroup('AND', [
    leaf('STATUS', 'IN', 'CONFIRMED'),
    leaf('MEMBER_NAME', 'CONTAINS'),
    { ...newGroup('OR', [leaf('TOTAL_MINUTES', 'BETWEEN', '60')]), not: true },
    { ...newGroup('OR', [leaf('CAMPUS', 'IS_EMPTY'), leaf('REMARK', 'CONTAINS', ' 开幕 ')]), not: true },
  ])
  assert.deepEqual(toPayload(tree), {
    logic: 'AND',
    children: [
      { field: 'STATUS', operator: 'IN', values: ['CONFIRMED'] },
      { logic: 'OR', not: true, children: [
        { field: 'CAMPUS', operator: 'IS_EMPTY', values: [] },
        { field: 'REMARK', operator: 'CONTAINS', values: ['开幕'] },
      ] },
    ],
  })
  assert.equal(toPayload(newGroup('AND', [leaf('MEMBER_NAME', 'EQ')])), null)
})

test('换字段重置取值，换运算符尽量保留', () => {
  const members = leaf('MEMBER_STUDENT_ID', 'IN', 'S1', 'S2')
  assert.deepEqual(changeOperator(members, 'NOT_IN').values, ['S1', 'S2'])
  assert.deepEqual(changeOperator(members, 'EQ').values, ['S1'])
  assert.deepEqual(changeOperator(members, 'IS_EMPTY').values, [])
  const switched = changeField(members, 'WORK_DATE')
  assert.equal(switched.operator, FIELDS.WORK_DATE.operators[0])
  assert.deepEqual(switched.values, [])
  assert.equal(isComplete(leaf('WORK_DATE', 'BETWEEN', '2026-01-01', '')), false)
  assert.equal(isComplete(leaf('CAMPUS', 'IS_NOT_EMPTY')), true)
})

test('字段和运算符与后端白名单一一对应', async () => {
  const java = await readFile(new URL(
    '../backend/src/main/java/cn/photolib/statistics/analysis/WorklogCondition.java', import.meta.url), 'utf8')
  for (const field of Object.keys(FIELDS)) assert.match(java, new RegExp(`\\b${field}\\(`), `后端没有字段 ${field}`)
  const operators = new Set(Object.values(FIELDS).flatMap(meta => meta.operators))
  const declared = /enum Operator \{([^}]*)\}/.exec(java)?.[1] ?? ''
  for (const operator of operators) assert.match(declared, new RegExp(`\\b${operator}\\b`))
  // 「为空」只给可空列：后端只对 nullable 的字段放行。
  for (const [field, meta] of Object.entries(FIELDS)) {
    if (meta.operators.includes('IS_EMPTY')) assert.match(java, new RegExp(`${field}\\("[^"]+", ValueType\\.\\w+, true`))
  }
})

test('默认条件只看已确认工时，从选题跳过来时再加上选题', () => {
  assert.deepEqual(toPayload(defaultCondition('42')), {
    logic: 'AND', children: [
      { field: 'STATUS', operator: 'IN', values: ['CONFIRMED'] },
      { field: 'PROJECT', operator: 'IN', values: ['42'] },
    ],
  })
})

test('排行长尾折成「其他」，合计不变', () => {
  const buckets: AnalysisBucket[] = Array.from({ length: 20 }, (_, index) => ({
    key: String(index), label: `成员${index}`, worklogCount: 1, shootingMinutes: 60, retouchingMinutes: 30,
    totalMinutes: 90, memberCount: 1,
  }))
  const folded = foldTail(buckets, 15)
  assert.equal(folded.length, 15)
  assert.equal(folded[14].label, '其他 6 项')
  assert.equal(folded.reduce((sum, bucket) => sum + bucket.totalMinutes, 0), 20 * 90)
  assert.equal(foldTail(buckets.slice(0, 3), 15).length, 3)
  assert.equal(toHours(95), 1.6)
})

test('CSV 单元格转义引号和换行，并挡住表格公式注入', () => {
  assert.equal(csvCell('=HYPERLINK("http://x")'), `"'=HYPERLINK(""http://x"")"`)
  assert.equal(csvCell('+1'), "'+1")
  assert.equal(csvCell('@SUM(A1)'), "'@SUM(A1)")
  assert.equal(csvCell('普通, 文本'), '"普通, 文本"')
  assert.equal(csvCell(null), '')
  const csv = rowsToCsv([{
    worklogId: 1, workDate: '2026-05-01', memberStudentId: 'S1', memberName: '-张三', shootingMinutes: 90,
    retouchingMinutes: 30, status: 'CONFIRMED', requestId: null, projectId: 1, projectTitle: '校庆', userId: 1,
  }])
  assert.equal(csv.charCodeAt(0), 0xfeff)
  assert.match(csv, /2026-05-01,'-张三,S1,校庆,上传链接申报,,1\.50,0\.50,2\.00,已确认/)
})

test('选题工时按学号归并，已确认和待确认分开算，草稿不算', () => {
  const base = { userId: 1, remark: '', version: 1, id: 0 }
  const worklog = (id: number, studentId: string, name: string, status: 'CONFIRMED' | 'SUBMITTED' | 'REJECTED' | 'DRAFT',
    shooting: number, retouching: number) =>
    ({ ...base, id, memberStudentId: studentId, memberName: name, status, shootingMinutes: shooting,
      retouchingMinutes: retouching, workDate: '2026-05-01' }) as never
  const summary = summarizeProjectWorklogs([
    worklog(1, 'S1', '张三', 'CONFIRMED', 60, 30),
    worklog(2, 'S1', '张三 ', 'CONFIRMED', 30, 0),
    worklog(3, 'S1', '张三', 'SUBMITTED', 45, 0),
    worklog(4, 'S2', '李四', 'REJECTED', 600, 0),
    worklog(5, 'S3', '王五', 'DRAFT', 999, 0),
  ])
  assert.equal(summary.confirmedMinutes, 120)
  assert.equal(summary.pendingMinutes, 45)
  assert.equal(summary.pendingCount, 1)
  assert.equal(summary.rejectedCount, 1)
  assert.equal(summary.memberCount, 2)
  assert.deepEqual(summary.members[0], {
    studentId: 'S1', name: '张三', confirmedShooting: 90, confirmedRetouching: 30, confirmedTotal: 120,
    pendingTotal: 45, worklogCount: 3,
  })
})

test('选题详情页的「本选题工时」按 projectId 取，并能跳去工时页和统计面板', async () => {
  const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')
  const [drawer, detail, worklogs, statistics] = await Promise.all([
    read('ProjectWorklogsDrawer.tsx'), read('pages/ProjectDetailPage.tsx'), read('pages/WorklogsPage.tsx'),
    read('pages/StatisticsPage.tsx')])
  assert.match(drawer, /url: '\/worklogs', params: \{ page, pageSize: 100, projectId \}/)
  assert.match(drawer, /\/statistics\?tab=analysis&projectId=/)
  assert.match(detail, /hasAnyPermission\(user, 'WORKLOG_SUBMIT', 'WORKLOG_CONFIRM', 'WORKLOG_EXPORT'\)/)
  assert.match(worklogs, /projectId: projectFilter/)
  assert.match(statistics, /<WorklogAnalysisPanel initialProjectId=\{initialProjectId\} \/>/)
})
