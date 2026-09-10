import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('选题入口和路由认两条查看权限中的任意一条', async () => {
  const [app, permissions] = await Promise.all([read('App.tsx'), read('permissions.ts')])

  // 拆分后「无条件查看全部选题」是独立权限码，只勾它的账号照样要能进选题模块。
  assert.match(permissions, /canViewProjects[\s\S]*?'PROJECT_VIEW',\s*'PROJECT_VIEW_ALL'/)

  // 入口和两条路由都不能再退回单看 PROJECT_VIEW。侧栏入口的判断和 push 的内容分在两行，
  // 所以这里按「守卫紧跟着 /projects 这一项」来断言。
  assert.match(app, /if \(canViewProjects\(user\)\) common\.push\(\s*\{ key: '\/projects'/)
  for (const path of ['/projects', '/projects/:projectId']) {
    const routeLine = app.split('\n').find(line => line.includes(`path="${path}"`)) || ''
    assert.match(routeLine, /canViewProjects\(user\)/)
    assert.doesNotMatch(routeLine, /hasPermission\(user, 'PROJECT_VIEW'\)/)
  }
})

test('选题详情页在没有需求访问权限时照样打得开', async () => {
  const detail = await read('pages/ProjectDetailPage.tsx')

  // 这是回归点：GET /requests 硬性要求 REQUEST_VIEW，而它和项目详情、图片、被引记录
  // 同在一个 Promise.all 里。裸调用的话，一个只有选题权限的权限组会因为这一条 403
  // 而整页打不开——「能看选题」和「能看需求」是两条权限，不能互相绑架。
  assert.match(detail, /const canViewRequests = hasPermission\(user, 'REQUEST_VIEW'\)/)
  const requestFetch = detail.split('\n')
    .find(line => line.includes("url: '/requests'") && line.includes('pageSize: 100')) || ''
  assert.notEqual(requestFetch, '')
  const fetchIndex = detail.indexOf(requestFetch)
  const guardIndex = detail.lastIndexOf('canViewRequests', fetchIndex)
  assert.ok(guardIndex !== -1 && fetchIndex - guardIndex < 200,
    '/requests 的首页取数必须被 canViewRequests 挡住')

  // 权限变化后要重新取数，否则刷新前还停在旧结果上。
  assert.match(detail, /\[projectId, user\?\.dataScope, canViewRequests\]/)

  // 空态不能谎称「还没有图片需求」——那是权限问题，不是数据问题。
  assert.match(detail, /canViewRequests \? '这个项目还没有图片需求'/)
})
