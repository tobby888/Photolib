import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  PHOTO_METADATA_LIMITS, batchMetadataPayload, describeBatchMetadataResult, photographerChanged,
} from '../src/photoMetadata.ts'

test('批量编辑只把勾选了的项放进请求体，没勾的项后端一概不动', () => {
  assert.deepEqual(batchMetadataPayload(['1', '2'], {
    fields: ['photographer'],
    photographerName: ' 张三 ', photographerStudentId: ' 2024001 ',
    title: '不会发出去', description: '也不会', campusId: '9',
  }), { photoIds: ['1', '2'], syncAdoptions: true, photographer: { name: '张三', studentId: '2024001' } })

  assert.deepEqual(batchMetadataPayload(['1'], { fields: [] }), { photoIds: ['1'], syncAdoptions: true })
  assert.equal(batchMetadataPayload(['1'], { fields: ['photographer'], photographerName: 'a', syncAdoptions: false })
    .syncAdoptions, false)
})

test('校区清空是「改成无校区」，说明留空是「清空说明」——都要发出去', () => {
  const payload = batchMetadataPayload(['1'], { fields: ['campus', 'description', 'title'], title: ' 开学典礼 ' })
  assert.deepEqual(payload.campus, { campusId: null })
  assert.equal(payload.description, '')
  assert.equal(payload.title, '开学典礼')
  assert.deepEqual(batchMetadataPayload(['1'], { fields: ['campus'], campusId: '7' }).campus, { campusId: '7' })
  // 不勾校区时整个字段缺省，后端据此区分「不改」。
  assert.equal('campus' in batchMetadataPayload(['1'], { fields: ['title'], title: 'x' }), false)
})

test('只有拍摄者真的改了才问要不要同步被引记录', () => {
  const photo = { photographerName: '张三', photographerStudentId: '2024001' }
  assert.equal(photographerChanged(photo, ' 张三 ', '2024001 '), false)
  assert.equal(photographerChanged(photo, '李四', '2024001'), true)
  assert.equal(photographerChanged(photo, '张三', ''), true)
})

test('批量结果按批累加，写清改了几张、几张原本就一样、同步了几条被引', () => {
  assert.equal(describeBatchMetadataResult([
    { photoCount: 200, changedCount: 198, adoptionsUpdated: 3 },
    { photoCount: 10, changedCount: 10, adoptionsUpdated: 0 },
  ]), '已修改 208 张图片，2 张本来就是这些信息，没有改动，同步更正了 3 条被引记录')
  assert.equal(describeBatchMetadataResult([{ photoCount: 1, changedCount: 1, adoptionsUpdated: 0 }]),
    '已修改 1 张图片')
})

test('前端长度上限与 PhotoController 的注解一致', async () => {
  const controller = await readFile(new URL('../backend/src/main/java/cn/photolib/photo/PhotoController.java',
    import.meta.url), 'utf8')
  const admin = controller.slice(controller.indexOf('record AdminMetadataRequest'))
  assert.match(admin, new RegExp(`@Size\\(max = ${PHOTO_METADATA_LIMITS.title}\\) String title`))
  assert.match(admin, new RegExp(`@Size\\(max = ${PHOTO_METADATA_LIMITS.description}\\) String description`))
  assert.match(admin, new RegExp(`@Size\\(max = ${PHOTO_METADATA_LIMITS.photographerName}\\) String photographerName`))
  assert.match(admin,
    new RegExp(`@Size\\(max = ${PHOTO_METADATA_LIMITS.photographerStudentId}\\) String photographerStudentId`))
  assert.match(controller, /BatchMetadataRequest\(@NotEmpty @Size\(max = 200\)/)
})
