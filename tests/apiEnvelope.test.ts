import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { isEnvelope, noCacheHeaders } from '../src/apiEnvelope.ts'

// Windows 上 git 会把源码检出成 CRLF，统一成 LF 再断言。
const read = async (path: string) =>
  (await readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')).replace(/\r\n/g, '\n')

test('读接口带上 no-cache，跳过浏览器缓存里的旧条目', () => {
  assert.deepEqual(noCacheHeaders(undefined), { 'Cache-Control': 'no-cache' })
  assert.deepEqual(noCacheHeaders('get'), { 'Cache-Control': 'no-cache' })
  assert.deepEqual(noCacheHeaders('GET'), { 'Cache-Control': 'no-cache' })
})

test('写接口不加缓存头', () => {
  for (const method of ['post', 'PUT', 'patch', 'delete']) assert.deepEqual(noCacheHeaders(method), {})
})

test('本系统的信封照常放行，包括 data 为 null 的 void 应答', () => {
  assert.equal(isEnvelope(200, { code: 'OK', message: 'success', data: [] }), true)
  assert.equal(isEnvelope(200, { code: 'OK', message: 'success', data: null }), true)
  assert.equal(isEnvelope(204, ''), true)
})

test('缓存或代理塞进来的外来应答不当成数据', () => {
  // 现场就是这种：HTML 被当成成功应答，.data 是 undefined，消息中心读 .length 崩掉。
  assert.equal(isEnvelope(200, '<!doctype html><html><body>old site</body></html>'), false)
  assert.equal(isEnvelope(200, ''), false)
  assert.equal(isEnvelope(200, undefined), false)
  assert.equal(isEnvelope(200, null), false)
  assert.equal(isEnvelope(200, [{ id: 1 }]), false)
  assert.equal(isEnvelope(200, { items: [], total: 0 }), false)
})

test('api() 用上了这两道防线，不再直接返回 data.data', async () => {
  const api = await read('api.ts')
  const body = api.slice(api.indexOf('export async function api<T>'), api.indexOf('export const qs'))
  assert.match(body, /headers: \{ \.\.\.noCacheHeaders\(config\.method\), \.\.\.config\.headers \}/)
  assert.match(body, /if \(!isEnvelope\(response\.status, response\.data\)\) \{\s*throw new ApiError\(/)
  assert.doesNotMatch(body, /return data\.data/)
})
