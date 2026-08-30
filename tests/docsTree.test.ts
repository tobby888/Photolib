import assert from 'node:assert/strict'
import test from 'node:test'

import {
  ancestorKeysOf, childKeysOf, documentKeysInOrder, findManageNode, isDescendant,
  manageNodesToTree, parentKeyOf, readerNodesToTree, relativeDropPosition, resolveDrop,
  type DocTreeNodeLike,
} from '../src/docsTree.ts'
import type { DocManageNode, DocReaderNode } from '../src/types.ts'

/**
 * 树的形状（key 用字母便于阅读）：
 *   guide(FOLDER)
 *     ├─ a(DOCUMENT)
 *     ├─ b(DOCUMENT)
 *     └─ nested(FOLDER)
 *          └─ c(DOCUMENT)
 *   faq(DOCUMENT)
 */
const tree: DocTreeNodeLike[] = [
  {
    key: 'guide', nodeType: 'FOLDER', children: [
      { key: 'a', nodeType: 'DOCUMENT', children: [] },
      { key: 'b', nodeType: 'DOCUMENT', children: [] },
      { key: 'nested', nodeType: 'FOLDER', children: [{ key: 'c', nodeType: 'DOCUMENT', children: [] }] },
    ],
  },
  { key: 'faq', nodeType: 'DOCUMENT', children: [] },
]

test('父节点、子节点和祖先链', () => {
  assert.equal(parentKeyOf(tree, 'guide'), null)
  assert.equal(parentKeyOf(tree, 'c'), 'nested')
  assert.equal(parentKeyOf(tree, '不存在'), undefined)
  assert.deepEqual(childKeysOf(tree, null), ['guide', 'faq'])
  assert.deepEqual(childKeysOf(tree, 'guide'), ['a', 'b', 'nested'])
  assert.deepEqual(ancestorKeysOf(tree, 'c'), ['guide', 'nested'])
  assert.deepEqual(ancestorKeysOf(tree, 'faq'), [])
})

test('文档按目录顺序展开，文件夹本身不算文档', () => {
  assert.deepEqual(documentKeysInOrder(tree), ['a', 'b', 'c', 'faq'])
})

test('isDescendant 不把节点自己算作自己的子孙', () => {
  assert.equal(isDescendant(tree, 'guide', 'c'), true)
  assert.equal(isDescendant(tree, 'guide', 'guide'), false)
  assert.equal(isDescendant(tree, 'nested', 'a'), false)
})

test('拖到文件夹内部时追加到末尾', () => {
  assert.deepEqual(resolveDrop(tree, 'faq', 'guide', false, 0), { parentKey: 'guide', index: 3 })
  // 目标文件夹里已经有被拖动的节点时，它不占位置——否则会多出一位。
  assert.deepEqual(resolveDrop(tree, 'a', 'guide', false, 0), { parentKey: 'guide', index: 2 })
})

test('拖到文档内部是无效落点', () => {
  assert.equal(resolveDrop(tree, 'faq', 'a', false, 0), null)
})

test('拖到同级缝隙里按前后插入，序号已排除被拖动的节点自身', () => {
  // faq 从根目录拖到 a 之前：a 所在的兄弟列表是 [a, b, nested]，落点 0。
  assert.deepEqual(resolveDrop(tree, 'faq', 'a', true, -1), { parentKey: 'guide', index: 0 })
  assert.deepEqual(resolveDrop(tree, 'faq', 'a', true, 1), { parentKey: 'guide', index: 1 })
  // 同一层里 a 往后拖到 b 之后：摘掉 a 之后兄弟是 [b, nested]，b 的序号是 0，
  // 所以落点是 1。若不先摘掉 a，这里会算成 2，文档就会跳过 nested 排到最后。
  assert.deepEqual(resolveDrop(tree, 'a', 'b', true, 1), { parentKey: 'guide', index: 1 })
  assert.deepEqual(resolveDrop(tree, 'a', 'b', true, -1), { parentKey: 'guide', index: 0 })
})

test('拖到根目录的缝隙里落到根下', () => {
  assert.deepEqual(resolveDrop(tree, 'a', 'faq', true, 1), { parentKey: null, index: 2 })
  assert.deepEqual(resolveDrop(tree, 'a', 'guide', true, -1), { parentKey: null, index: 0 })
})

test('文件夹不能被拖进自己的子树，也不能拖到自己身上', () => {
  assert.equal(resolveDrop(tree, 'guide', 'nested', false, 0), null)
  assert.equal(resolveDrop(tree, 'guide', 'c', true, 1), null)
  assert.equal(resolveDrop(tree, 'guide', 'guide', false, 0), null)
})

test('antd 的绝对落点换算成 -1/0/1', () => {
  // pos "0-1-2" 表示该节点在其父节点下排第 2 位。
  assert.equal(relativeDropPosition('0-1-2', 1), -1)
  assert.equal(relativeDropPosition('0-1-2', 2), 0)
  assert.equal(relativeDropPosition('0-1-2', 3), 1)
})

test('编辑树用 id 当 key，读者树用 publicId 当 key', () => {
  const manage: DocManageNode[] = [{
    id: '7', publicId: 'PUB7', nodeType: 'FOLDER', title: '指南', sortOrder: 0,
    published: false, visibility: 'MEMBERS', hasContent: false, version: 1,
    children: [{
      id: '8', publicId: 'PUB8', parentId: '7', nodeType: 'DOCUMENT', title: '入门', sortOrder: 0,
      published: true, visibility: 'PUBLIC', hasContent: true, version: 3, children: [],
    }],
  }]
  assert.deepEqual(manageNodesToTree(manage)[0].children.map(node => node.key), ['8'])
  assert.equal(findManageNode(manage, '8')?.title, '入门')
  assert.equal(findManageNode(manage, '404'), undefined)

  const reader: DocReaderNode[] = [{
    publicId: 'PUB7', nodeType: 'FOLDER', title: '指南', requiresLogin: false,
    children: [{ publicId: 'PUB8', nodeType: 'DOCUMENT', title: '入门', requiresLogin: false, children: [] }],
  }]
  assert.deepEqual(readerNodesToTree(reader)[0].children.map(node => node.key), ['PUB8'])
})
