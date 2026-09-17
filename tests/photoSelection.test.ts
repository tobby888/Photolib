import assert from 'node:assert/strict'
import test from 'node:test'
import { selectPhotoRange } from '../src/photoSelection.ts'

const ordered = ['a', 'b', 'c', 'd', 'e']

test('Shift 连选把锚点到目标的中间图片并入已有选择', () => {
  const result = selectPhotoRange(['a'], ordered, 'a', 'd')

  assert.deepEqual(result.selected, ['a', 'b', 'c', 'd'])
  assert.equal(result.anchorId, 'd')
  assert.equal(result.truncated, false)
})

test('反向连选从较后的锚点选回目标，顺序仍按列表顺序', () => {
  const result = selectPhotoRange(['e'], ordered, 'e', 'b')

  assert.deepEqual(result.selected, ['e', 'b', 'c', 'd'])
  assert.equal(result.anchorId, 'b')
})

test('已经在选择里的图片不会重复加入', () => {
  const result = selectPhotoRange(['a', 'c'], ordered, 'a', 'd')

  assert.deepEqual(result.selected, ['a', 'c', 'b', 'd'])
})

test('到 200 张上限时截断并报告，不悄悄少选', () => {
  const many = Array.from({ length: 205 }, (_, index) => `p${index + 1}`)
  const result = selectPhotoRange(
    Array.from({ length: 198 }, (_, index) => `p${index + 1}`),
    many,
    'p198',
    'p205',
    200,
  )

  assert.equal(result.selected.length, 200)
  assert.equal(result.truncated, true)
})

test('锚点不在当前列表时保持原选择，并让调用方回退到普通点选', () => {
  const result = selectPhotoRange(['a'], ordered, 'missing', 'd')

  assert.deepEqual(result.selected, ['a'])
  assert.equal(result.anchorId, 'd')
  assert.equal(result.truncated, false)
})
