import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { afterLoginRoute } from '../src/loginRedirect.ts'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('没有来路时回工作台首页', () => {
  assert.equal(afterLoginRoute(false, null), '/')
  assert.equal(afterLoginRoute(false, undefined), '/')
  assert.equal(afterLoginRoute(false, {}), '/')
})

test('回到被弹走的那一页，query 一起带回去', () => {
  const state = { from: { pathname: '/mcp/authorize', search: '?request=abc123' } }

  assert.equal(afterLoginRoute(false, state), '/mcp/authorize?request=abc123')
})

test('没有 query 的来路不会多出一个问号', () => {
  assert.equal(afterLoginRoute(false, { from: { pathname: '/projects/42' } }), '/projects/42')
})

test('首次改密优先于任何来路', () => {
  const state = { from: { pathname: '/mcp/authorize', search: '?request=abc123' } }

  assert.equal(afterLoginRoute(true, state), '/initial-password')
})

/**
 * 判定必须只有一处。两个调用方是在赛跑（LoginPage 自己 navigate，`/login` 路由上的
 * `<Navigate replace>` 也可能后到并覆盖它），谁复制一份出去，两边就会在某些时序下
 * 给出不同答案——而那种 bug 只在"带 query 的深链 + 登录"这条路径上偶发。
 */
test('登录去向的判定没有被复制回调用方', async () => {
  for (const file of ['App.tsx', 'pages/LoginPage.tsx']) {
    const source = await read(file)
    assert.match(source, /afterLoginRoute/, `${file} 应当复用 afterLoginRoute`)
    assert.doesNotMatch(source, /from\.pathname/,
      `${file} 不应自己拼来路，改用 afterLoginRoute`)
  }
})
