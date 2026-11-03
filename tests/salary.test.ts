import assert from 'node:assert/strict'
import test from 'node:test'
import {
  DEFAULT_SALARY_FORMULA, filterProjects, formatYuan, insertSnippet, linesOfMember, minutesToHours, salaryRule,
} from '../src/salary.ts'
import type { SalaryCalculationDetail, SalaryLine, SalaryProjectGroup } from '../src/types.ts'

const line = (projectId: string, memberKey: string, memberName: string, studentId: string | null,
  salaryAmount: number): SalaryLine => ({
  projectId, projectTitle: `选题${projectId}`, projectCompletedAt: '2026-10-10T18:00:00', memberKey, studentId,
  memberName, campus: '北校区', shootingMinutes: 60, retouchingMinutes: 0, confirmedWorklogCount: 1,
  pendingWorklogCount: 0, pendingMinutes: 0, adoptedPhotos: 0, uploadedPhotos: 0, salaryAmount,
  process: `max(1 × 20, 0 × 10) = ${salaryAmount}`,
})

const project = (projectId: string, title: string, lines: SalaryLine[]): SalaryProjectGroup => ({
  projectId, projectTitle: title, completedAt: '2026-10-10T18:00:00', confirmedMinutes: 60 * lines.length,
  adoptedPhotos: 0, uploadedPhotos: 0, salaryAmount: lines.reduce((sum, item) => sum + item.salaryAmount, 0),
  pendingWorklogCount: 0, lines,
})

const projects = [
  project('1', '运动会', [line('1', 'id:S1', '张三', 'S1', 40), line('1', 'id:S2', '李四', 'S2', 20)]),
  project('2', '校报', [line('2', 'id:S1', '张三', 'S1', 10), line('2', 'name:老成员', '老成员', null, 20)]),
]

test('formats money with two decimals and thousands separators', () => {
  assert.equal(formatYuan(1234.5), '1,234.50')
  assert.equal(formatYuan(0), '0.00')
  assert.equal(formatYuan(null), '0.00')
  assert.equal(formatYuan(6.666), '6.67')
})

test('converts minutes to hours without trailing zeros', () => {
  assert.equal(minutesToHours(90), '1.5')
  assert.equal(minutesToHours(20), '0.33')
  assert.equal(minutesToHours(120), '2')
  assert.equal(minutesToHours(undefined), '0')
})

test('describes the rule with the stored formula', () => {
  assert.equal(salaryRule(DEFAULT_SALARY_FORMULA),
    '每位成员在每个选题的工资 = max(工时 * 20, 被引张数 * 10)（单位元，小于 0 按 0 计，四舍五入到分）')
})

test('inserts fields and functions at the cursor', () => {
  // 空编辑框插入函数：光标落在括号里。
  assert.deepEqual(insertSnippet('', 0, 0, 'max()'), { text: 'max()', cursor: 4 })
  // 在括号里插入字段。
  assert.deepEqual(insertSnippet('max()', 4, 4, '工时'), { text: 'max(工时)', cursor: 6 })
  // 紧跟在字段后面插入时补一个空格；运算符后面不补。
  assert.deepEqual(insertSnippet('max(工时)', 6, 6, '*'), { text: 'max(工时 *)', cursor: 8 })
  assert.deepEqual(insertSnippet('工时 * ', 5, 5, '被引张数'), { text: '工时 * 被引张数', cursor: 9 })
  // 替换选中的文字。
  assert.deepEqual(insertSnippet('工时 * 20', 0, 2, '拍摄时长'), { text: '拍摄时长 * 20', cursor: 4 })
  // 选区越界时收回到文本范围内。
  assert.deepEqual(insertSnippet('1 + ', 99, 120, 'min()'), { text: '1 + min()', cursor: 8 })
})

test('keeps whole projects whose title matches and only matching members elsewhere', () => {
  assert.equal(filterProjects(projects, '  '), projects)
  assert.deepEqual(filterProjects(projects, '运动').map(item => item.lines.length), [2])
  const byMember = filterProjects(projects, '张三')
  assert.deepEqual(byMember.map(item => item.projectTitle), ['运动会', '校报'])
  assert.deepEqual(byMember.map(item => item.lines.map(entry => entry.memberName)), [['张三'], ['张三']])
  assert.deepEqual(filterProjects(projects, 's2').map(item => item.lines.map(entry => entry.memberName)), [['李四']])
  assert.deepEqual(filterProjects(projects, '没有这个人'), [])
})

test('collects one member across projects in project order', () => {
  const detail = { calculation: {} as SalaryCalculationDetail['calculation'], projects, members: [] }
  assert.deepEqual(linesOfMember(detail, 'id:S1').map(item => [item.projectId, item.salaryAmount]), [['1', 40], ['2', 10]])
  assert.deepEqual(linesOfMember(detail, 'name:老成员').map(item => item.projectId), ['2'])
})
