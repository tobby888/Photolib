import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  MAX_SHARE_BATCH, dropFromSelection, isFullySelected, mergeSelection,
} from '../src/shareSelection.ts'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')

const ids = (prefix: string, count: number) =>
  Array.from({ length: count }, (_, index) => `${prefix}-${index + 1}`)

test('全选本页是并入而不是替换：翻到下一页再全选，上一页的勾选还在', () => {
  const firstPage = ids('a', 60)
  const secondPage = ids('b', 60)

  const afterFirst = mergeSelection([], firstPage)
  const afterSecond = mergeSelection(afterFirst.selected, secondPage)

  assert.equal(afterSecond.selected.length, 120)
  assert.deepEqual(afterSecond.selected.slice(0, 60), firstPage)
  assert.deepEqual(afterSecond.selected.slice(60), secondPage)
  assert.equal(afterSecond.truncated, false)
})

test('重复并入同一页不会产生重复 id，也不会打乱已有顺序', () => {
  const page = ids('a', 3)
  const merged = mergeSelection(['a-2'], page)

  assert.deepEqual(merged.selected, ['a-2', 'a-1', 'a-3'])
})

test('到了打包上限就截断并报告，不会悄悄少选', () => {
  const existing = ids('a', MAX_SHARE_BATCH - 2)
  const merged = mergeSelection(existing, ids('b', 60))

  assert.equal(merged.selected.length, MAX_SHARE_BATCH)
  assert.equal(merged.truncated, true)
  assert.deepEqual(merged.selected.slice(-2), ['b-1', 'b-2'])
})

test('取消本页只摘掉这一页，别的页的勾选原样保留', () => {
  const selected = [...ids('a', 3), ...ids('b', 2)]

  assert.deepEqual(dropFromSelection(selected, ids('b', 2)), ids('a', 3))
})

test('整页勾满才算"取消本页"，空页不算', () => {
  assert.equal(isFullySelected(['a', 'b'], new Set(['a', 'b', 'c'])), true)
  assert.equal(isFullySelected(['a', 'b'], new Set(['a'])), false)
  assert.equal(isFullySelected([], new Set(['a'])), false)
})

test('上限与后端 @Size(max = 200) 对齐，全选全部按后端最大页长取数', async () => {
  const [controller, page] = await Promise.all([
    readFile(new URL('../backend/src/main/java/cn/photolib/share/ProjectSharePublicController.java',
      import.meta.url), 'utf8'),
    read('pages/SharedProjectPage.tsx'),
  ])

  assert.equal(MAX_SHARE_BATCH, 200)
  assert.match(controller, /@Size\(max = 200\) List<@NotNull Long> photoIds/)
  // 列表接口的 pageSize 上限是 100，"全选全部"按这个页长翻页，多了会被参数校验拒掉。
  assert.match(controller, /@Max\(100\) int pageSize/)
  assert.match(page, /const SELECT_ALL_PAGE_SIZE = 100/)
})
