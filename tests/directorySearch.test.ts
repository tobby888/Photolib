import assert from 'node:assert/strict'
import test from 'node:test'
import { filterMembers, matchesMember, searchAcrossCampuses } from '../src/directorySearch.ts'
import type { Campus, CampusMember } from '../src/types.ts'

const base = { createdAt: '', updatedAt: '', version: 1 }
const campus = (id: string, name: string): Campus => ({ ...base, id, code: `c${id}`, name, enabled: true })
const member = (id: string, campusId: string, name: string, studentId: string, enabled = true): CampusMember =>
  ({ ...base, id, campusId, name, studentId, enabled })

const campuses = [campus('1', '主校区'), campus('2', '东校区'), campus('3', '南校区')]

test('按姓名或学号的片段匹配，忽略大小写、首尾空白和全角字符', () => {
  const zhang = member('1', '1', '张三', 'S2024001')
  assert.equal(matchesMember(zhang, '张'), true)
  assert.equal(matchesMember(zhang, '2024'), true)
  assert.equal(matchesMember(zhang, '  s2024  '), true)
  assert.equal(matchesMember(zhang, 'Ｓ２０２４'), true)
  assert.equal(matchesMember(zhang, '李'), false)
})

test('单校区搜索：关键词为空时原样返回，否则只留下匹配的成员', () => {
  const members = [member('1', '1', '张三', '001'), member('2', '1', '李四', '002')]
  assert.equal(filterMembers(members, ''), members)
  assert.equal(filterMembers(members, '   '), members)
  assert.deepEqual(filterMembers(members, '李').map(m => m.id), ['2'])
  assert.deepEqual(filterMembers(members, '00').map(m => m.id), ['1', '2'])
})

test('全局搜索：同一学号在多个校区归并成一个人，并按校区下拉的顺序列出所在校区', () => {
  const members = [
    member('10', '2', '张三', '001'),
    member('11', '1', '张三', '001', false),
    member('12', '1', '李四', '002'),
  ]
  const hits = searchAcrossCampuses(members, campuses, '张三')
  assert.equal(hits.length, 1)
  assert.equal(hits[0].studentId, '001')
  assert.deepEqual(hits[0].names, ['张三'])
  assert.deepEqual(hits[0].campuses, [
    { campusId: '1', campusName: '主校区', memberId: '11', enabled: false },
    { campusId: '2', campusName: '东校区', memberId: '10', enabled: true },
  ])
})

test('全局搜索：一个校区的记录命中，就带出这个人在其他校区的记录和不同写法的姓名', () => {
  const members = [
    member('1', '1', '张三 ', 'S001'),
    member('2', '2', '张叁', 's001'),
    member('3', '3', '王五', 'S003'),
  ]
  const hits = searchAcrossCampuses(members, campuses, '张叁')
  assert.equal(hits.length, 1)
  assert.deepEqual(hits[0].names, ['张三', '张叁'])
  assert.deepEqual(hits[0].campuses.map(c => c.campusName), ['主校区', '东校区'])
})

test('全局搜索：不在可见校区（如已停用校区）里的记录不出现', () => {
  const members = [member('1', '9', '张三', '001'), member('2', '1', '张三', '001')]
  const hits = searchAcrossCampuses(members, campuses, '张三')
  assert.deepEqual(hits[0].campuses.map(c => c.campusId), ['1'])
  assert.deepEqual(searchAcrossCampuses([member('1', '9', '张三', '001')], campuses, '张三'), [])
})

test('全局搜索：关键词为空时不返回结果，结果保持后端排好的顺序', () => {
  const members = [member('1', '1', '李明', '002'), member('2', '2', '李华', '001')]
  assert.deepEqual(searchAcrossCampuses(members, campuses, ' '), [])
  assert.deepEqual(searchAcrossCampuses(members, campuses, '李').map(hit => hit.studentId), ['002', '001'])
})
