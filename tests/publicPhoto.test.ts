import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  PUBLIC_PHOTOGRAPHER, PUBLIC_PHOTOGRAPHER_VALUE, isPublicPhoto, photographerOptionsFor, photographerPayload,
  publicPhotographerFirst, withPublicPhotographer,
} from '../src/publicPhoto.ts'
import { PUBLIC_UPLOADER_KEY, canRetag, uploaderKey } from '../src/shareUpload.ts'
import type { ShareUploadItem } from '../src/shareUpload.ts'

const read = (path: string) => readFile(new URL(`../${path}`, import.meta.url), 'utf8')

test('公共照片按拍摄者姓名判定，与后端 PublicPhoto 用同一个名字', async () => {
  assert.equal(isPublicPhoto('公共照片'), true)
  assert.equal(isPublicPhoto(' 公共照片 '), true)
  assert.equal(isPublicPhoto('张三'), false)
  assert.equal(isPublicPhoto(null), false)
  const backend = await read('backend/src/main/java/cn/photolib/photo/PublicPhoto.java')
  assert.match(backend, new RegExp(`PHOTOGRAPHER_NAME = "${PUBLIC_PHOTOGRAPHER}"`))
})

test('拍摄者下拉框：公共照片排第一；选题只收公共照片时只剩它一项', () => {
  const members = [{ value: 1, label: '张三 · 2023' }, { value: 2, label: '李四 · 2024' }]
  assert.deepEqual(withPublicPhotographer(members).map(option => option.value),
    [PUBLIC_PHOTOGRAPHER_VALUE, 1, 2])
  assert.deepEqual(photographerOptionsFor(members, false).map(option => option.value),
    [PUBLIC_PHOTOGRAPHER_VALUE, 1, 2])
  assert.deepEqual(photographerOptionsFor(members, true),
    [{ value: PUBLIC_PHOTOGRAPHER_VALUE, label: PUBLIC_PHOTOGRAPHER }])
})

test('提交时公共照片发 publicPhoto，通讯录成员发 photographerContactId，两者只出现一个', () => {
  assert.deepEqual(photographerPayload(PUBLIC_PHOTOGRAPHER_VALUE), { publicPhoto: true })
  assert.deepEqual(photographerPayload('42'), { photographerContactId: '42' })
  assert.deepEqual(photographerPayload(7), { photographerContactId: 7 })
})

test('图库拍摄者筛选候选把公共照片挪到最前面，没有就不凭空加', () => {
  assert.deepEqual(publicPhotographerFirst(['张三', '公共照片', '李四']), ['公共照片', '张三', '李四'])
  assert.deepEqual(publicPhotographerFirst(['张三', '李四']), ['张三', '李四'])
})

test('上传链接：公共照片没有学号，归属键用固定值，传完的图照样能改标签', () => {
  assert.equal(uploaderKey({ uploaderPublic: true, uploaderStudentId: '' }), PUBLIC_UPLOADER_KEY)
  assert.equal(uploaderKey({ uploaderPublic: false, uploaderStudentId: '20230001' }), '20230001')
  assert.equal(uploaderKey({ uploaderStudentId: '' }), undefined)
  assert.equal(uploaderKey(null), undefined)

  const item = { key: 'k', stage: 'done', photoId: '9', photographerStudentId: PUBLIC_UPLOADER_KEY } as ShareUploadItem
  assert.equal(canRetag(item, PUBLIC_UPLOADER_KEY), true)
  // 切换成具体的人之后，公共照片那几张就是「别人名下」的了。
  assert.equal(canRetag(item, '20230001'), false)
})
