import assert from 'node:assert/strict'
import test from 'node:test'
import { describeUserAgent, formatDuration, isFlatRecord, parseAuditDetail } from '../src/auditLogs.ts'

test('常见浏览器和系统翻成一行中文', () => {
  assert.equal(describeUserAgent(
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
  ).label, 'Chrome 130 · Windows')
  assert.equal(describeUserAgent(
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36 Edg/130.0.2849.68',
  ).label, 'Edge 130 · Windows')
  assert.equal(describeUserAgent(
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15',
  ).label, 'Safari 17 · macOS')
})

test('微信、企业微信里打开的页面先认出内置浏览器，不被当成 Safari', () => {
  assert.equal(describeUserAgent(
    'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 MicroMessenger/8.0.50',
  ).label, '微信 · iOS')
  assert.equal(describeUserAgent(
    'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36 wxwork/4.1.20 MicroMessenger/7.0.1',
  ).label, '企业微信 · Android')
})

test('不是浏览器的客户端显示第一段，空 UA 显示未知设备', () => {
  assert.equal(describeUserAgent('curl/8.4.0').label, 'curl 8.4.0')
  assert.equal(describeUserAgent('').label, '未知设备')
  assert.equal(describeUserAgent(null).label, '未知设备')
})

test('详情 JSON 拆成参数、文件和附加信息', () => {
  const detail = parseAuditDetail(JSON.stringify({
    path: '/api/v1/doc-files', status: 200,
    params: { title: '期末总结' },
    body: { name: 'x' },
    files: [{ field: 'file', name: 'a.pdf', size: 10 }],
    identifier: 'admin',
  }))
  assert.equal(detail.path, '/api/v1/doc-files')
  assert.equal(detail.status, 200)
  assert.deepEqual(detail.params, { title: '期末总结' })
  assert.deepEqual(detail.body, { name: 'x' })
  assert.equal(detail.files[0].name, 'a.pdf')
  assert.deepEqual(detail.extra, { identifier: 'admin' })
})

test('坏掉或为空的详情不报错', () => {
  assert.deepEqual(parseAuditDetail('{oops'), { files: [], extra: {} })
  assert.deepEqual(parseAuditDetail(null), { files: [], extra: {} })
  assert.equal(parseAuditDetail('{"params":{}}').params, undefined)
})

test('平对象用键值表，嵌套的用 JSON；耗时换算成毫秒或秒', () => {
  assert.equal(isFlatRecord({ a: 1, b: 'x', c: null }), true)
  assert.equal(isFlatRecord({ a: { b: 1 } }), false)
  assert.equal(isFlatRecord([1, 2]), false)
  assert.equal(formatDuration(35), '35 毫秒')
  assert.equal(formatDuration(1530), '1.53 秒')
  assert.equal(formatDuration(null), '-')
})
