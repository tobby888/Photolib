import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { describeRemovalResult, planPhotoRemoval } from '../src/projectPhotoRemoval.ts'

test('勾选的图片按能否移出本选题分开', () => {
  const plan = planPhotoRemoval([
    { id: '1', projectId: null },
    { id: '2', projectId: '9' },
    { id: '3', projectId: 7 },
    { id: '4', projectId: '7' },
    { id: '5' },
  ], '7', new Set(['2', '5']))

  // 来源项目就是本选题的优先归到「本选题上传」，哪怕它也被采纳了。
  assert.deepEqual(plan, { removable: ['1'], adopted: ['2', '5'], uploadedHere: ['3', '4'] })
})

test('移出结果的提示写明没移出的原因', () => {
  assert.equal(describeRemovalResult({ removed: 3, skippedAdopted: 0, skippedUploadedHere: 0 }),
    '已将 3 张图片移出选题')
  assert.equal(describeRemovalResult({ removed: 1, skippedAdopted: 2, skippedUploadedHere: 1 }),
    '已将 1 张图片移出选题；2 张已采纳、1 张是在本选题上传的，未移出')
  assert.equal(describeRemovalResult({ removed: 0, skippedAdopted: 1, skippedUploadedHere: 0 }),
    '没有图片被移出选题；1 张已采纳，未移出')
})

test('选题详情页的移出入口与后端同一道门槛', async () => {
  const detail = await readFile(new URL('../src/pages/ProjectDetailPage.tsx', import.meta.url), 'utf8')

  // 与 POST /projects/{id}/photos/remove 的 PROJECT_ADOPT + 仅进行中选题一致。
  assert.match(detail, /const canRemovePhotos = canAdopt && project\?\.status === 'ACTIVE'/)
  assert.match(detail, /url: `\/projects\/\$\{projectId\}\/photos\/remove`/)
  // 能移出就得能勾选，否则按钮永远是灰的。
  assert.match(detail, /const canSelectPhotos = canBatchDownload \|\| canTag \|\| canRemovePhotos/)
})
