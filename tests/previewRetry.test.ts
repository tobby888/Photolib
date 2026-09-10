import assert from 'node:assert/strict'
import test from 'node:test'
import { attemptFor, loadFailed, refreshed, startAttempt } from '../src/previewRetry.ts'

const FIRST = 'https://oss.test/preview.jpg?Expires=1&Signature=a'
const FRESH = 'https://oss.test/preview.jpg?Expires=2&Signature=b'

test('先用页面给的地址，没出错就不打扰后端', () => {
  const attempt = startAttempt(FIRST)

  assert.deepEqual(attempt, { origin: FIRST, src: FIRST, phase: 'fresh' })
})

test('加载失败先去重取地址，换上新地址再试一次', () => {
  const failed = loadFailed(startAttempt(FIRST), true)
  assert.equal(failed.phase, 'retrying')
  assert.equal(failed.src, FIRST, '新地址还没到手之前不能先把 src 换掉')

  const retried = refreshed(failed, FRESH)
  assert.deepEqual(retried, { origin: FIRST, src: FRESH, phase: 'retried' })
})

test('重取回来的地址和刚失败的那条一样，就不必再试一次', () => {
  // 后端在同一个签名窗口内签出的地址逐字节相同，所以这说明失败与签名过期无关。
  const settled = refreshed(loadFailed(startAttempt(FIRST), true), FIRST)

  assert.equal(settled.phase, 'failed')
  assert.equal(settled.src, FIRST)
})

test('重取不到地址（图片已删、后端出错）直接认输', () => {
  assert.equal(refreshed(loadFailed(startAttempt(FIRST), true), undefined).phase, 'failed')
  assert.equal(refreshed(loadFailed(startAttempt(FIRST), true), null).phase, 'failed')
  assert.equal(refreshed(loadFailed(startAttempt(FIRST), true), '').phase, 'failed')
})

test('只重试一次：换上新地址之后再失败就交给占位图', () => {
  const retried = refreshed(loadFailed(startAttempt(FIRST), true), FRESH)
  const givenUp = loadFailed(retried, true)

  assert.equal(givenUp.phase, 'failed')
  assert.equal(loadFailed(givenUp, true).phase, 'failed', '认输之后不再重来')
})

test('没有重取地址的办法时，失败就等于原来的"直接占位图"', () => {
  assert.equal(loadFailed(startAttempt(FIRST), false).phase, 'failed')
})

test('重取还在路上时又来一次 error 事件，不会重复发请求', () => {
  const pending = loadFailed(startAttempt(FIRST), true)

  assert.equal(loadFailed(pending, true), pending, '状态没变，调用方据此判断不必再发请求')
})

test('页面换了新数据就从头来过，重试次数跟着归零', () => {
  const givenUp = loadFailed(refreshed(loadFailed(startAttempt(FIRST), true), FRESH), true)
  assert.equal(givenUp.phase, 'failed')

  // 列表重取之后 props 里是一条新地址：那是一次新的机会，不是上一轮的延续。
  const reset = attemptFor(givenUp, FRESH)
  assert.deepEqual(reset, { origin: FRESH, src: FRESH, phase: 'fresh' })
})

test('地址没变时保持原来的对象，渲染里对齐状态不会引起额外的重渲染', () => {
  const attempt = loadFailed(startAttempt(FIRST), true)

  assert.equal(attemptFor(attempt, FIRST), attempt)
})

test('迟到的重取结果不会把已经翻篇的状态改回去', () => {
  const givenUp = refreshed(loadFailed(startAttempt(FIRST), true), FIRST)

  assert.equal(refreshed(givenUp, FRESH), givenUp)
})
