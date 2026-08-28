import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  buildAssignmentTree, campusKey, checkedKeysToSelection, coveredUserIds, describeSelection,
  pruneCoveredUsers, selectionToCheckedKeys, userKey,
} from '../src/featuredAssignmentTree.ts'
import type { Campus, CampusAssignmentUser } from '../src/types.ts'

const campuses = [campus('1', '东校区'), campus('2', '西校区'), campus('3', '空校区')]
const managers = [
  manager('11', '东负责人', ['1']),
  manager('12', '两校区负责人', ['1', '2']),
  manager('13', '西负责人', ['2']),
  manager('14', '还没授权的人', []),
]

test('每个校区挂自己的负责人，空校区仍然保留可指派的节点', () => {
  const tree = buildAssignmentTree(campuses, managers)
  assert.deepEqual(tree.map(node => node.label), ['东校区', '西校区', '空校区', '未分配校区'])
  assert.deepEqual(tree[0].children?.map(child => child.label), ['东负责人', '两校区负责人'])
  assert.deepEqual(tree[1].children?.map(child => child.label), ['两校区负责人', '西负责人'])
  // 还没有成员的校区照样能被指派：之后加入的人会自动落进这个范围。
  assert.equal(tree[2].kind, 'CAMPUS')
  assert.deepEqual(tree[2].children, [])
})

test('没有任何现存授权校区的负责人集中在不可勾选的分组里', () => {
  const tree = buildAssignmentTree(campuses, managers)
  const group = tree[3]
  assert.equal(group.kind, 'GROUP')
  assert.deepEqual(group.children?.map(child => child.label), ['还没授权的人'])
  // 授权指向一个已不存在的校区，也应该落到这里，而不是从界面上消失。
  const stale = buildAssignmentTree([campus('1', '东校区')], [manager('99', '孤儿', ['404'])])
  assert.equal(stale[1].kind, 'GROUP')
  assert.deepEqual(stale[1].children?.map(child => child.userId), ['99'])
})

test('同一个人出现在多个校区下时节点 key 仍然唯一', () => {
  const tree = buildAssignmentTree(campuses, managers)
  const keys = tree.flatMap(node => [node.key, ...(node.children ?? []).map(child => child.key)])
  assert.equal(new Set(keys).size, keys.length)
})

test('勾选状态与后端的 campusIds / userIds 双向一致', () => {
  const tree = buildAssignmentTree(campuses, managers)
  const keys = selectionToCheckedKeys({ campusIds: ['2'], userIds: ['11'] }, tree)
  assert.ok(keys.includes(campusKey('2')))
  assert.ok(keys.includes(userKey('1', '11')))
  assert.deepEqual(checkedKeysToSelection(keys), { campusIds: ['2'], userIds: ['11'] })
})

test('多校区负责人在任意一处被勾选，其余位置同步显示为已选且只回传一次', () => {
  const tree = buildAssignmentTree(campuses, managers)
  const keys = selectionToCheckedKeys({ campusIds: [], userIds: ['12'] }, tree)
  // 12 号同时挂在东、西两个校区下，两个节点都要显示为勾选。
  assert.deepEqual(keys.sort(), [userKey('1', '12'), userKey('2', '12')].sort())
  assert.deepEqual(checkedKeysToSelection(keys), { campusIds: [], userIds: ['12'] })
})

test('校区覆盖的人不会重复留在点名列表里', () => {
  assert.deepEqual([...coveredUserIds(['1'], managers)].sort(), ['11', '12'])
  const pruned = pruneCoveredUsers({ campusIds: ['1'], userIds: ['11', '12', '13'] }, managers)
  assert.deepEqual(pruned, { campusIds: ['1'], userIds: ['13'] })
  // 取消校区后不该悄悄把人补回来——覆盖只影响"点名"这一份数据。
  assert.deepEqual(pruneCoveredUsers({ campusIds: [], userIds: ['13'] }, managers),
    { campusIds: [], userIds: ['13'] })
})

test('摘要区分校区带进来的人和额外点名的人', () => {
  assert.equal(describeSelection({ campusIds: [], userIds: [] }, managers), '还没有选择任何提交对象')
  assert.equal(describeSelection({ campusIds: ['1'], userIds: [] }, managers),
    '已选：1 个校区的全部负责人')
  assert.equal(describeSelection({ campusIds: ['1'], userIds: ['13'] }, managers),
    '已选：1 个校区的全部负责人，另外点名 1 位负责人')
  // 已被校区覆盖的人不计入"另外点名"，否则数字会重复计一遍。
  assert.equal(describeSelection({ campusIds: ['1'], userIds: ['11'] }, managers),
    '已选：1 个校区的全部负责人')
})

test('指派树不级联，校区与个人各自独立勾选', async () => {
  const source = await readFile(
    new URL('../src/pages/FeaturedCollectionsPage.tsx', import.meta.url), 'utf8')
  // checkStrictly 是这套语义的关键：父子联动会把"整个校区"退化成"当时的这些人"。
  assert.match(source, /checkStrictly/)
  assert.match(source, /<Tree checkable/)
  // 旧的两个多选框不该再回来（只匹配表单项本身，说明文字里提到"点名"是正常的）。
  assert.doesNotMatch(source, /label="按校区指派"/)
  assert.doesNotMatch(source, /label="单独点名"/)
  assert.doesNotMatch(source, /name="campusIds"[^>]*>\s*<Select/)
})

function campus(id: string, name: string): Campus {
  return { id, code: id, name, enabled: true, createdAt: '', updatedAt: '', version: 1 }
}

function manager(id: string, displayName: string, campusIds: string[]): CampusAssignmentUser {
  return { id, displayName, permissionGroupName: '校区负责人', campusIds, version: 1 }
}
