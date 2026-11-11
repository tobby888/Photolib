import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  campusNameOf, canFileWithoutRequest, membersForRequest, memberOptionsAnyCampus, requestSelectOptions,
} from '../src/worklogForm.ts'
import type { Campus, CampusMember, PhotoRequest } from '../src/types.ts'

const base = { createdAt: '', updatedAt: '', version: 1 }
const campus = (id: string, name: string): Campus => ({ ...base, id, code: `c${id}`, name, enabled: true })
const member = (id: string, campusId: string, name: string, studentId: string): CampusMember =>
  ({ ...base, id, campusId, name, studentId, enabled: true })
const request = (id: string, campusId: string, title: string): PhotoRequest => ({
  ...base, id, campusId, title, projectId: 'p1', description: '', deadline: '', status: 'ACCEPTED', createdBy: 'u1',
})

const campuses = [campus('1', '主校区'), campus('2', '东校区')]
// 同一个学生在两个校区的通讯录里各有一行：学号相同、id 不同。
const directory = [
  member('11', '1', '张三', '2023001'),
  member('21', '2', '张三', '2023001'),
  member('22', '2', '李四', '2023002'),
]

test('选了需求就只列出需求所属校区的通讯录成员', () => {
  assert.deepEqual(membersForRequest(directory, request('r2', '2', '迎新晚会')).map(item => item.id), ['21', '22'])
  // 同时属于两个校区的人只剩需求校区那一行，后端 getForWorklog 认的正是这一行。
  assert.deepEqual(membersForRequest(directory, request('r1', '1', '开学典礼')).map(item => item.id), ['11'])
})

test('没选需求时不给候选人', () => {
  assert.deepEqual(membersForRequest(directory, undefined), [])
  assert.deepEqual(membersForRequest(directory, null), [])
})

test('校区名找不到时退回编号，不留空', () => {
  assert.equal(campusNameOf(campuses, '2'), '东校区')
  assert.equal(campusNameOf(campuses, '9'), '校区 #9')
})

test('候选需求跨校区时标题后带校区名，只有一个校区时不带', () => {
  // 多校区发布会给每个校区各建一条同名需求，只写标题分不清。
  assert.deepEqual(requestSelectOptions([request('r1', '1', '迎新晚会'), request('r2', '2', '迎新晚会')], campuses), [
    { value: 'r1', label: '迎新晚会 · 主校区' },
    { value: 'r2', label: '迎新晚会 · 东校区' },
  ])
  assert.deepEqual(requestSelectOptions([request('r1', '1', '迎新晚会'), request('r3', '1', '开学典礼')], campuses), [
    { value: 'r1', label: '迎新晚会' },
    { value: 'r3', label: '开学典礼' },
  ])
})

test('工时表单的工作人员下拉按所选需求的校区取数', async () => {
  const page = await readFile(new URL('../src/pages/WorklogsPage.tsx', import.meta.url), 'utf8')
  // 回归点：之前直接把整个通讯录塞进下拉，管多个校区的负责人会看到重复的人并可能选错校区。
  assert.match(page, /: memberOptions\.map\(/)
  assert.doesNotMatch(page, /options=\{directory\.map\(/)
  assert.match(page, /const memberOptions = membersForRequest\(directory, selectedRequest\)/)
  // 需求所属校区要显示出来，换需求时要清掉不属于新校区的已选成员。
  assert.match(page, /需求所属校区：/)
  assert.match(page, /onValuesChange=\{onFormValuesChange\}/)
  assert.match(page, /form\.setFieldValue\('memberContactId', undefined\)/)
})

test('不关联需求的工作人员候选是全部校区的通讯录，标签带校区名', () => {
  assert.deepEqual(memberOptionsAnyCampus(directory, campuses), [
    { value: '22', label: '李四 · 2023002 · 东校区' },
    { value: '21', label: '张三 · 2023001 · 东校区' },
    { value: '11', label: '张三 · 2023001 · 主校区' },
  ])
})

test('只有能向任意需求申报、且不限校区的账号能不关联需求填报', () => {
  assert.equal(canFileWithoutRequest({ dataScope: 'GLOBAL', permissions: ['WORKLOG_SUBMIT', 'WORKLOG_SUBMIT_ANY'] }), true)
  assert.equal(canFileWithoutRequest({ dataScope: 'CAMPUS', permissions: ['WORKLOG_SUBMIT', 'WORKLOG_SUBMIT_ANY'] }), false)
  assert.equal(canFileWithoutRequest({ dataScope: 'GLOBAL', permissions: ['WORKLOG_SUBMIT'] }), false)
  assert.equal(canFileWithoutRequest(null), false)
})
