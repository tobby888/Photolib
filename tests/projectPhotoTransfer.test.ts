import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { describeTransferResult } from '../src/projectPhotoTransfer.ts'
import type { TransferPhotosResult } from '../src/projectPhotoTransfer.ts'

const empty: TransferPhotosResult = {
  copied: 0, alreadyInTarget: 0, skipped: 0, removed: 0, keptAdopted: 0, keptUploadedHere: 0,
}

test('复制只说进了目标几张、几张本来就在', () => {
  assert.equal(
    describeTransferResult({ ...empty, copied: 3, alreadyInTarget: 1 }, 'COPY', '校庆'),
    '已复制 3 张图片到「校庆」；1 张本来就在目标选题里')
  assert.equal(describeTransferResult({ ...empty, alreadyInTarget: 2 }, 'COPY', '校庆'),
    '没有新图片进入「校庆」；2 张本来就在目标选题里')
})

test('移动按真正离开本选题的张数说，移不走的单独交代', () => {
  // copied 含移不走的那几张：它们复制过去了，但还留在本选题，不能算「已移动」。
  assert.equal(
    describeTransferResult({ ...empty, copied: 4, removed: 2, keptAdopted: 1, keptUploadedHere: 1 }, 'MOVE', '校庆'),
    '已将 2 张图片移动到「校庆」；1 张在本选题已采纳、1 张是在本选题上传的，已复制过去但仍保留在本选题')
  assert.equal(describeTransferResult({ ...empty, copied: 1, keptUploadedHere: 1 }, 'MOVE', '校庆'),
    '没有图片从本选题移走；1 张是在本选题上传的，已复制过去但仍保留在本选题')
})

test('过期的勾选按跳过计数', () => {
  assert.equal(describeTransferResult({ ...empty, copied: 1, skipped: 2 }, 'COPY', 'A'),
    '已复制 1 张图片到「A」；2 张已不在本选题或尚未处理完，未处理')
})

test('前后端用同一组字段：接口路径、请求体和分享范围', async () => {
  const [modal, controller, shareModal, shareController] = await Promise.all([
    readFile(new URL('../src/ProjectPhotoTransferModal.tsx', import.meta.url), 'utf8'),
    readFile(new URL('../backend/src/main/java/cn/photolib/project/ProjectController.java', import.meta.url), 'utf8'),
    readFile(new URL('../src/ProjectShareLinksModal.tsx', import.meta.url), 'utf8'),
    readFile(new URL('../backend/src/main/java/cn/photolib/share/ProjectShareController.java', import.meta.url), 'utf8'),
  ])
  assert.match(modal, /url: `\/projects\/\$\{projectId\}\/photos\/transfer`/)
  assert.match(controller, /@PostMapping\("\/\{id\}\/photos\/transfer"\)/)
  assert.match(modal, /targetProjectId: target\.id, mode, photoIds:/)
  assert.match(controller, /record TransferPhotosRequest\(@NotNull Long targetProjectId, ProjectService\.TransferMode mode,/)
  // 只有浏览链接带范围；上传链接发 null，服务端对上传链接带范围会直接拒绝。
  assert.match(shareModal, /photoIds: purpose === 'BROWSE' && values\.photoScope === 'SELECTED' \? selectedPhotoIds : null/)
  assert.match(shareController, /List<@NotNull Long> photoIds\)/)
})
