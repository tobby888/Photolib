import assert from 'node:assert/strict'
import test from 'node:test'
import {
  FIT_ZOOM, MAX_SCALE, MIN_SCALE, ZOOM_STEP, clampPan, clampScale, containSize, isZoomed, panBy,
  scaleForActualPixels, zoomAt,
} from '../src/photoZoom.ts'

/** 一个 800×600 的容器，里面铺着一张 4:3 的图——1 倍时正好铺满。 */
const viewport = { width: 800, height: 600 }
const content = { width: 800, height: 600 }

test('缩放倍率夹在适应窗口和上限之间', () => {
  assert.equal(clampScale(0.2), MIN_SCALE)
  assert.equal(clampScale(100), MAX_SCALE)
  assert.equal(clampScale(2.5), 2.5)
  // 非法输入退回 1 倍，而不是让 NaN 顺着 transform 传进 DOM。
  assert.equal(clampScale(Number.NaN), MIN_SCALE)
})

test('适应窗口不算「已放大」，浮点误差也不算', () => {
  assert.equal(isZoomed(FIT_ZOOM), false)
  assert.equal(isZoomed({ scale: 1 + 1e-9, x: 0, y: 0 }), false)
  assert.equal(isZoomed({ scale: 1.2, x: 0, y: 0 }), true)
})

test('1 倍时平移范围是 0，所以永远居中', () => {
  const panned = clampPan({ scale: 1, x: 300, y: -200 }, viewport, content)
  assert.deepEqual(panned, { scale: 1, x: 0, y: 0 })
})

test('放大后只能拖到图片边缘，不能把图拖出容器', () => {
  // 2 倍时内容是 1600×1200，两边各多出 400 / 300。
  const limit = clampPan({ scale: 2, x: 9999, y: -9999 }, viewport, content)
  assert.deepEqual(limit, { scale: 2, x: 400, y: -300 })
  const inside = clampPan({ scale: 2, x: 100, y: 50 }, viewport, content)
  assert.deepEqual(inside, { scale: 2, x: 100, y: 50 })
})

test('以光标为锚点缩放时，锚点下的那个点不动', () => {
  // 锚点取容器中心右上方 200, -100（坐标相对中心）。
  const anchor = { x: 200, y: -100 }
  const zoomed = zoomAt(FIT_ZOOM, 2, anchor, viewport, content)
  // 锚点在未缩放内容里的位置：u = (anchor - x) / s = anchor。
  // 放大后要求 anchor = x' + s'·u，于是 x' = anchor - 2·anchor = -anchor。
  assert.equal(zoomed.scale, 2)
  assert.equal(zoomed.x, -200)
  assert.equal(zoomed.y, 100)

  // 再验证一次不变式：把锚点换算回内容坐标，缩放前后必须相同。
  const before = (anchor.x - FIT_ZOOM.x) / FIT_ZOOM.scale
  const after = (anchor.x - zoomed.x) / zoomed.scale
  assert.ok(Math.abs(before - after) < 1e-9)
})

test('中心缩放是锚点缩放的特例，不产生偏移', () => {
  const zoomed = zoomAt(FIT_ZOOM, 3, { x: 0, y: 0 }, viewport, content)
  assert.deepEqual(zoomed, { scale: 3, x: 0, y: 0 })
})

test('缩回 1 倍时偏移被边界清零，不留下跑偏的画面', () => {
  const zoomed = zoomAt(FIT_ZOOM, 4, { x: 300, y: 200 }, viewport, content)
  assert.ok(isZoomed(zoomed))
  const back = zoomAt(zoomed, 1, { x: 300, y: 200 }, viewport, content)
  assert.deepEqual(back, FIT_ZOOM)
})

test('连续滚动缩放不会越过上下限', () => {
  let state = FIT_ZOOM
  for (let i = 0; i < 40; i += 1) state = zoomAt(state, state.scale * ZOOM_STEP, { x: 0, y: 0 }, viewport, content)
  assert.equal(state.scale, MAX_SCALE)
  for (let i = 0; i < 40; i += 1) state = zoomAt(state, state.scale / ZOOM_STEP, { x: 0, y: 0 }, viewport, content)
  assert.equal(state.scale, MIN_SCALE)
})

test('拖动同样收在边界里', () => {
  const state = { scale: 2, x: 0, y: 0 }
  assert.deepEqual(panBy(state, { x: 50, y: 20 }, viewport, content), { scale: 2, x: 50, y: 20 })
  assert.deepEqual(panBy(state, { x: 5000, y: 5000 }, viewport, content), { scale: 2, x: 400, y: 300 })
})

test('contain 之后的实际尺寸按较紧的那一边算', () => {
  // 竖图放进横容器：高度吃满，宽度按比例缩。
  assert.deepEqual(containSize({ width: 600, height: 1200 }, viewport), { width: 300, height: 600 })
  // 横图放进横容器：宽度吃满。
  assert.deepEqual(containSize({ width: 1600, height: 800 }, viewport), { width: 800, height: 400 })
})

test('平移边界按 contain 之后的尺寸算，而不是容器尺寸', () => {
  // 竖图 contain 后是 300×600，2 倍后 600×1200：横向 600 < 800 容器宽，所以横向不能拖。
  const portrait = containSize({ width: 600, height: 1200 }, viewport)
  const panned = clampPan({ scale: 2, x: 999, y: 999 }, viewport, portrait)
  assert.equal(panned.x, 0)
  assert.equal(panned.y, 300)
})

test('1:1 是「原始宽度 ÷ 当前显示宽度」，图比容器小时退回适应窗口', () => {
  assert.equal(scaleForActualPixels({ width: 4000, height: 3000 }, content), 5)
  assert.equal(scaleForActualPixels({ width: 400, height: 300 }, content), MIN_SCALE)
  // 超大原图会被夹到上限，而不是算出一个 30 倍。
  assert.equal(scaleForActualPixels({ width: 40000, height: 30000 }, content), MAX_SCALE)
})
