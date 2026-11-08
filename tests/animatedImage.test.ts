import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  HOVER_JITTER_PX, HOVER_PLAY_DELAY_MS, frameDelayMs, movedBeyondJitter, sniffImageType,
} from '../src/animatedImage.ts'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('鼠标停住 3 秒才播放，手抖不算挪动', () => {
  assert.equal(HOVER_PLAY_DELAY_MS, 3000)
  const anchor = { x: 100, y: 100 }
  assert.equal(movedBeyondJitter(anchor, { x: 100 + HOVER_JITTER_PX, y: 100 }), false)
  assert.equal(movedBeyondJitter(anchor, { x: 103, y: 103 }), true)
  assert.equal(movedBeyondJitter(anchor, { x: 100, y: 100 }), false)
})

test('帧时长按浏览器的规矩：0 或 ≤10ms 当 100ms 播放', () => {
  assert.equal(frameDelayMs(null), 100)
  assert.equal(frameDelayMs(0), 100)
  assert.equal(frameDelayMs(10_000), 100)
  assert.equal(frameDelayMs(20_000), 20)
  assert.equal(frameDelayMs(80_000), 80)
  assert.equal(frameDelayMs(Number.NaN), 100)
})

test('按文件头认出动图格式，不信响应里的 Content-Type', () => {
  assert.equal(sniffImageType(new TextEncoder().encode('GIF89a......')), 'image/gif')
  assert.equal(sniffImageType(new TextEncoder().encode('GIF87a......')), 'image/gif')
  assert.equal(sniffImageType(Uint8Array.of(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0)), 'image/png')
  assert.equal(sniffImageType(new TextEncoder().encode('RIFF\0\0\0\0WEBP')), 'image/webp')
  assert.equal(sniffImageType(new TextEncoder().encode('GIF90a......')), null)
  assert.equal(sniffImageType(Uint8Array.of(0xff, 0xd8, 0xff)), null)
})

test('动图不能裁切，也不画直方图', async () => {
  const [selection, detail] = await Promise.all([
    read('pages/PhotoSelectionPage.tsx'),
    read('pages/PhotoDetailPage.tsx'),
  ])
  // 两个「裁切 / 旋转」入口都要挡住动图，编辑器本身也不能为动图打开。
  assert.equal(selection.match(/disabled=\{!editable \|\| !active\.imageUrl \|\| !!active\.animated\}/g)?.length, 2)
  assert.match(selection, /editing && active\.imageUrl && !active\.animated && <Suspense/)
  assert.match(detail, /\{!photo\.animated && <Card title="曝光直方图"/)
  assert.match(detail, /<AnimatedPhotoStage/)
})

test('三个图片网格都给动图挂上悬停播放', async () => {
  for (const page of ['pages/PhotosPage.tsx', 'pages/ProjectDetailPage.tsx', 'pages/RequestDeliveryPage.tsx']) {
    const source = await read(page)
    assert.match(source, /hoverPlayback\.bind\(String\(photo\.id\), !!photo\.animated && !!photo\.animationUrl\)/, page)
    assert.match(source, /<AnimatedHoverLayer src=\{photo\.animationUrl\}/, page)
  }
})
