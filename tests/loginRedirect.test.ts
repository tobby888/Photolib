import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { afterLoginRoute, twoFactorNext } from '../src/loginRedirect.ts'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')
const member = { mustChangePassword: false }

test('没有来路时回工作台首页', () => {
  assert.equal(afterLoginRoute(member, null), '/')
  assert.equal(afterLoginRoute(member, undefined), '/')
  assert.equal(afterLoginRoute(member, {}), '/')
  assert.equal(afterLoginRoute(null, null), '/')
})

test('回到被弹走的那一页，query 一起带回去', () => {
  const state = { from: { pathname: '/mcp/authorize', search: '?request=abc123' } }

  assert.equal(afterLoginRoute(member, state), '/mcp/authorize?request=abc123')
})

test('没有 query 的来路不会多出一个问号', () => {
  assert.equal(afterLoginRoute(member, { from: { pathname: '/projects/42' } }), '/projects/42')
})

test('首次改密优先于任何来路', () => {
  const state = { from: { pathname: '/mcp/authorize', search: '?request=abc123' } }

  assert.equal(afterLoginRoute({ mustChangePassword: true }, state), '/initial-password')
})

test('被强制两步验证却没绑定的，先去绑定，连首次改密都排在后面', () => {
  const state = { from: { pathname: '/mcp/authorize', search: '?request=abc123' } }
  const user = { mustChangePassword: true, mfa: { enrollmentRequired: true, suggested: false } }

  assert.equal(afterLoginRoute(user, state), '/two-factor?next=%2Fmcp%2Fauthorize%3Frequest%3Dabc123')
  assert.equal(afterLoginRoute(user, null), '/two-factor')
})

test('被建议两步验证的，先看建议页，但排在首次改密之后', () => {
  const state = { from: { pathname: '/projects/42' } }

  assert.equal(afterLoginRoute({ mfa: { suggested: true } }, state), '/two-factor?next=%2Fprojects%2F42')
  assert.equal(afterLoginRoute({ mustChangePassword: true, mfa: { suggested: true } }, state), '/initial-password')
})

test('两步验证页只回站内地址', () => {
  assert.equal(twoFactorNext('?next=%2Fmcp%2Fauthorize%3Frequest%3Dabc123'), '/mcp/authorize?request=abc123')
  assert.equal(twoFactorNext(''), '/')
  assert.equal(twoFactorNext('?next=https%3A%2F%2Fevil.example'), '/')
  assert.equal(twoFactorNext('?next=%2F%2Fevil.example'), '/')
  assert.equal(twoFactorNext('?next=%2F%5Cevil.example'), '/')
  assert.equal(twoFactorNext('?next=%2Ftwo-factor%3Fnext%3D%252F'), '/')
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
