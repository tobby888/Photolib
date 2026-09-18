import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('工时详情按学号取，并带上当前统计的时间范围', async () => {
  const page = await read('pages/StatisticsPage.tsx')

  const request = page.split('\n').find(line => line.includes("url: '/statistics/members/worklogs'")) || ''
  assert.notEqual(request, '', '统计面板要有取工时明细的请求')
  const params = page.slice(page.indexOf(request), page.indexOf(request) + 400)
  // 明细必须和表格那一行同口径：同一个时间范围、按学号取（表格行本来就是按学号归并的）。
  assert.match(params, /studentId: member\.studentId/)
  assert.match(params, /from: range\[0\], to: range\[1\]/)
})

test('成员统计表按学号作行键，校区不再参与标识', async () => {
  const page = await read('pages/StatisticsPage.tsx')

  // 同一个学号在多个校区的工时已经在后端归并成一行，行键再拼校区名只会掩盖这件事。
  assert.doesNotMatch(page, /rowKey=\{member => `\$\{member\.studentId\}-\$\{member\.campus\}`\}/)
  assert.match(page, /rowKey=\{member => member\.studentId\}/)
})

test('明细弹窗把需求、选题和校区都摆出来', async () => {
  const page = await read('pages/StatisticsPage.tsx')

  const modal = page.slice(page.indexOf('<Modal'))
  for (const column of ['requestTitle', 'projectTitle', 'campus', 'workDate']) {
    assert.ok(modal.includes(`dataIndex: '${column}'`), `明细表缺少 ${column} 列`)
  }
})
