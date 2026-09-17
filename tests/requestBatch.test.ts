import assert from 'node:assert/strict'
import test from 'node:test'
import { batchStatusCounts, groupPhotoRequests, selectedRequestFor } from '../src/requestBatch.ts'
import type { PhotoRequest } from '../src/types.ts'

const request = (id: string, batchId: string | null): PhotoRequest => ({
  id,
  batchId,
  projectId: 'p1',
  title: '毕业季拍摄',
  description: '说明',
  campusId: `campus-${id}`,
  deadline: '2026-10-01T10:00:00',
  status: 'PUBLISHED',
  createdBy: 'u1',
}) as PhotoRequest

test('同一 batchId 的需求合并成一行，并保留首次出现顺序', () => {
  const rows = groupPhotoRequests([
    request('r1', 'batch-a'),
    request('r2', null),
    request('r3', 'batch-a'),
    request('r4', 'batch-b'),
  ])

  assert.deepEqual(rows.map(row => row.key), ['batch-batch-a', 'request-r2', 'batch-batch-b'])
  assert.deepEqual(rows[0].requests.map(item => item.id), ['r1', 'r3'])
  assert.equal(rows[1].requests.length, 1)
})

test('单校区需求继续各自成行，不与同批次混淆', () => {
  const rows = groupPhotoRequests([request('r1', null), request('r2', null)])

  assert.deepEqual(rows.map(row => row.key), ['request-r1', 'request-r2'])
})

test('没有保存的校区选择时回退到批次的第一个需求，保存后按请求 id 取回', () => {
  const row = groupPhotoRequests([
    request('r1', 'batch-a'),
    request('r2', 'batch-a'),
  ])[0]

  assert.equal(selectedRequestFor(row, {}).id, 'r1')
  assert.equal(selectedRequestFor(row, { 'batch-batch-a': 'r2' }).id, 'r2')
})

test('批次内状态不一致时按首次出现顺序统计各状态数量', () => {
  const row = groupPhotoRequests([
    { ...request('r1', 'batch-a'), status: 'PUBLISHED' },
    { ...request('r2', 'batch-a'), status: 'SUBMITTED' },
    { ...request('r3', 'batch-a'), status: 'PUBLISHED' },
  ])[0]

  assert.deepEqual(batchStatusCounts(row), [
    { status: 'PUBLISHED', count: 2 },
    { status: 'SUBMITTED', count: 1 },
  ])
  assert.equal(batchStatusCounts(groupPhotoRequests([request('r4', 'batch-b')])[0]).length, 1)
})
