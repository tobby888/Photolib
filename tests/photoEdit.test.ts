import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  FULL_CROP, MIN_CROP_SIZE, NO_EDITS, hasEdits, nextRotation, normalizeCrop, outputSize, rotateCrop,
  rotatedSize,
} from '../src/photoEdit.ts'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('90° 和 270° 交换宽高，0° 和 180° 不变', () => {
  assert.deepEqual(rotatedSize(6000, 4000, 0), { width: 6000, height: 4000 })
  assert.deepEqual(rotatedSize(6000, 4000, 180), { width: 6000, height: 4000 })
  assert.deepEqual(rotatedSize(6000, 4000, 90), { width: 4000, height: 6000 })
  assert.deepEqual(rotatedSize(6000, 4000, 270), { width: 4000, height: 6000 })
})

test('旋转角只在 0/90/180/270 之间循环，负数也要绕回去', () => {
  assert.equal(nextRotation(0, 1), 90)
  assert.equal(nextRotation(270, 1), 0)
  assert.equal(nextRotation(0, -1), 270)
  assert.equal(nextRotation(90, -3), 180)
  // 转满一圈等于没转，不能累积成 360。
  assert.equal(nextRotation(0, 4), 0)
})

test('裁切框收进 0~1，并且不会小到看不见', () => {
  assert.deepEqual(normalizeCrop({ x: -0.5, y: -0.2, width: 0.4, height: 0.3 }),
    { x: 0, y: 0, width: 0.4, height: 0.3 })
  // 超出右下边界时把位置往回推，而不是把框裁小——用户拖出去的意思是「要到边」。
  assert.deepEqual(normalizeCrop({ x: 0.9, y: 0.9, width: 0.4, height: 0.4 }),
    { x: 0.6, y: 0.6, width: 0.4, height: 0.4 })
  const tiny = normalizeCrop({ x: 0.5, y: 0.5, width: 0, height: 0 })
  assert.equal(tiny.width, MIN_CROP_SIZE)
  assert.equal(tiny.height, MIN_CROP_SIZE)
  // 比整张图还大的框收敛成整张图。
  assert.deepEqual(normalizeCrop({ x: 0, y: 0, width: 2, height: 2 }), FULL_CROP)
})

test('旋转时裁切框跟着转，左上角会变成右上角而不是留在原地', () => {
  // 左上角一小块：x/y 都在 0，宽高各占 1/4。
  const topLeft = { x: 0, y: 0, width: 0.25, height: 0.25 }
  const turned = rotateCrop(topLeft, 1)
  // 顺时针转 90° 之后它应该在右上角：x 靠右（1 - 0 - 0.25），y 回到 0。
  assert.deepEqual(turned, { x: 0.75, y: 0, width: 0.25, height: 0.25 })
  // 转满四次回到原处，说明这套变换是可逆的（用户来回转不会把构图磨掉）。
  assert.deepEqual(rotateCrop(topLeft, 4), topLeft)
  assert.deepEqual(rotateCrop(topLeft, -1), rotateCrop(topLeft, 3))
})

test('旋转会交换裁切框的宽高', () => {
  const wide = { x: 0.1, y: 0.4, width: 0.8, height: 0.2 }
  const turned = rotateCrop(wide, 1)
  assert.equal(turned.width, 0.2)
  assert.equal(turned.height, 0.8)
})

test('没动过就是没动过，浮点误差不算改动', () => {
  assert.equal(hasEdits(NO_EDITS), false)
  assert.equal(hasEdits({ rotation: 90, crop: FULL_CROP }), true)
  assert.equal(hasEdits({ rotation: 0, crop: { x: 0, y: 0, width: 0.5, height: 1 } }), true)
  assert.equal(hasEdits({ rotation: 0, crop: { x: 1e-9, y: 0, width: 1, height: 1 } }), false)
})

test('输出尺寸按旋转后的画面算，并且至少 1×1', () => {
  assert.deepEqual(outputSize(6000, 4000, NO_EDITS), { width: 6000, height: 4000 })
  // 竖过来之后裁掉一半高度：宽 4000、高 6000 × 0.5。
  assert.deepEqual(
    outputSize(6000, 4000, { rotation: 90, crop: { x: 0, y: 0, width: 1, height: 0.5 } }),
    { width: 4000, height: 3000 })
  // 极小的框也要给出合法的画布尺寸，否则 canvas 会直接抛错。
  const tiny = outputSize(10, 10, { rotation: 0, crop: { x: 0, y: 0, width: 0.01, height: 0.01 } })
  assert.ok(tiny.width >= 1 && tiny.height >= 1)
})

test('编辑器必须从 Blob URL 取像素，不能直接渲染签名地址', async () => {
  const source = await read('PhotoCropEditor.tsx')
  // 站内一律以 no-cors 请求签名预览地址（src/previewImage.ts），那样拿到的图片会
  // 污染画布、让 toBlob 抛 SecurityError。唯一的出口是 useLocalImageUrl。
  assert.match(source, /useLocalImageUrl/)
  assert.doesNotMatch(source, /<img[^>]*src=\{\s*imageUrl/)
})

test('裁切预览画在 canvas 上，而不是 CSS 旋转过的 img', async () => {
  const source = await read('PhotoCropEditor.tsx')
  // CSS 旋转会让元素盒子和它显示出来的画面对不上（90° 时宽高互换，但百分比
  // 定位仍按原盒子算），叠在上面的选框就会歪。
  assert.doesNotMatch(source, /transform:\s*`?rotate\(/)
  assert.match(source, /<canvas ref=\{canvasRef\}/)
})
