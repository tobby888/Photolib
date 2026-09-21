import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { keepSelectedRows } from '../src/rowSelection.ts'

const read = async (path: string) =>
  (await readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')).replace(/\r\n/g, '\n')

interface Row { id: string; status: string; version: number }

const row = (id: string, status = 'SUBMITTED', version = 1): Row => ({ id, status, version })

test('别的页上勾中的行不会因为不在当前页而丢掉', () => {
  const selected = [row('1'), row('2')]
  const currentPage = [row('3'), row('4')]

  // 用户在第 1 页勾了 1、2，翻到第 2 页又勾了 3。
  const next = keepSelectedRows(['1', '2', '3'], [selected, currentPage], item => item.id)

  assert.deepEqual(next.map(item => item.id), ['1', '2', '3'])
})

test('靠后的来源优先，勾着的行跟着列表刷新换成新数据', () => {
  const stale = [row('1', 'SUBMITTED', 1)]
  const fresh = [row('1', 'CONFIRMED', 2)]

  // 批准和删除都带版本号，拿着旧快照去提交会被乐观锁拒掉。
  assert.deepEqual(keepSelectedRows(['1'], [stale, fresh], item => item.id), fresh)
})

test('认不出来的 key 直接丢掉，重复的 key 只留一份', () => {
  assert.deepEqual(keepSelectedRows(['1', '1', '404'], [[row('1')]], item => item.id), [row('1')])
  assert.deepEqual(keepSelectedRows(['404'], [undefined, []], item => (item as Row).id), [])
})

test('数字和字符串 id 算同一行（后端的 Long 在两处可能是两种类型）', () => {
  const rows = [{ id: 7, label: '七' }]
  assert.deepEqual(keepSelectedRows(['7'], [rows], item => item.id), rows)
})

test('工时表格跨页保留勾选：preserveSelectedRowKeys + 翻页不清空', async () => {
  const worklogs = await read('pages/WorklogsPage.tsx')

  // 没有 preserveSelectedRowKeys，antd 会在每次勾选时把不在当前 dataSource 里的 key 滤掉，
  // 于是在第 2 页勾一条就把第 1 页的勾选整批丢了（那些行此刻根本不在屏幕上）。
  assert.match(worklogs, /preserveSelectedRowKeys: true/)
  assert.match(worklogs, /keepSelectedRows\(keys, \[selectedItems, data\.items\], item => item\.id\)/)
  // 勾选存的是整行：批量批准要读状态和版本号，而翻页之后那几行已经不在 data.items 里。
  assert.match(worklogs, /const \[selectedItems, setSelectedItems\] = useState<Worklog\[\]>\(\[\]\)/)
  assert.doesNotMatch(worklogs, /data\.items\.filter\(item => selectedIds\.includes/)
  // 翻页回调里不许再出现清空勾选。
  const pager = worklogs.slice(worklogs.indexOf('<ListPagination'))
  assert.doesNotMatch(pager, /setSelectedItems\(\[\]\)/)
  // 看不见的勾选必须有个说法：计数 + 一个清空入口。
  assert.match(worklogs, /已选择 \{selectedItems\.length\} 条/)
  assert.match(worklogs, />清空选择</)
})

test('图库和选题相册的勾选按 id 累加，翻页不清空', async () => {
  const [library, projectDetail] = await Promise.all([
    read('pages/PhotosPage.tsx'),
    read('pages/ProjectDetailPage.tsx'),
  ])

  for (const [name, source] of [['图库', library], ['选题相册', projectDetail]] as const) {
    const pager = source.slice(source.indexOf('<ListPagination'))
    assert.doesNotMatch(pager, /setSelected\w*\(\[\]\)/, `${name}的分页条不该清空勾选`)
  }
})
