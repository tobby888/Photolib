import assert from 'node:assert/strict'
import { readdir, readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  GRID_PAGE_SIZES, TABLE_PAGE_SIZES, clientTablePagination, normalizePageSize, serverTablePagination, turnPage,
} from '../src/pagination.ts'

const sourceRoot = new URL('../src/', import.meta.url)

async function frontendSources(): Promise<{ path: string; code: string }[]> {
  const names = await readdir(sourceRoot, { recursive: true })
  // pagination.ts 自己的注释里写着反面教材，扫描时跳过。
  const files = names.filter(name => /\.tsx?$/.test(name) && !/(^|[\\/])pagination\.ts$/.test(name))
  return Promise.all(files.map(async name => ({
    path: name.replace(/\\/g, '/'),
    code: (await readFile(new URL(name.replace(/\\/g, '/'), sourceRoot), 'utf8')).replace(/\r\n/g, '\n'),
  })))
}

test('改每页条数会回到第 1 页，只翻页则停在原来的条数上', () => {
  const filters = { page: 9, pageSize: 12, keyword: '校庆' }

  assert.deepEqual(turnPage(filters, 3, 12), { page: 3, pageSize: 12, keyword: '校庆' })
  // 第 9 页在 96 条一页时多半根本不存在，留在那里只会得到一屏空列表。
  assert.deepEqual(turnPage(filters, 9, 96), { page: 1, pageSize: 96, keyword: '校庆' })
})

test('每页条数的候选值都在后端允许的范围内，越界的值被收回默认档', () => {
  // 后端每个分页接口都是 @Min(1) @Max(100)，传更大的值直接 400、整页变成加载出错。
  for (const size of [...GRID_PAGE_SIZES, ...TABLE_PAGE_SIZES]) {
    assert.ok(size >= 1 && size <= 100, `${size} 超出后端允许的 pageSize 范围`)
  }
  assert.equal(normalizePageSize('48', GRID_PAGE_SIZES), 48)
  assert.equal(normalizePageSize('1000', GRID_PAGE_SIZES), GRID_PAGE_SIZES[0])
  assert.equal(normalizePageSize(null, TABLE_PAGE_SIZES, 20), 20)
  assert.equal(normalizePageSize('not a number', TABLE_PAGE_SIZES, 20), 20)
})

test('本地分页的表格只给 defaultPageSize，否则下拉框选完会被盖回去', () => {
  const config = clientTablePagination(120, { defaultPageSize: 12, sizes: GRID_PAGE_SIZES })

  // antd 的 usePagination 让传进来的 pagination 覆盖它自己记着的状态，固定的 pageSize
  // 因此会在下一次渲染把用户选的值抹掉——下拉框看得见、点得动，选完什么也没发生。
  assert.equal('pageSize' in config, false)
  assert.equal(config.defaultPageSize, 12)
  assert.deepEqual(config.pageSizeOptions, GRID_PAGE_SIZES)
  assert.equal(config.showSizeChanger, true)
  assert.equal(config.hideOnSinglePage, false)
})

test('只有在最小档位下也只有一页时才藏起分页条', () => {
  // 藏起来会连下拉框一起藏掉：选了 100 条一页、数据凑不满一页，就再也没有地方调回去。
  assert.equal(clientTablePagination(60, { sizes: TABLE_PAGE_SIZES }).hideOnSinglePage, false)
  assert.equal(clientTablePagination(9, { sizes: TABLE_PAGE_SIZES }).hideOnSinglePage, true)
})

test('服务端分页的表格把页码和条数都交给调用方', () => {
  const changes: [number, number][] = []
  const config = serverTablePagination({ page: 2, pageSize: 50 }, 480,
    (page, pageSize) => changes.push([page, pageSize]))

  assert.equal(config.current, 2)
  assert.equal(config.pageSize, 50)
  assert.equal(config.total, 480)
  assert.equal(config.showSizeChanger, true)
  config.onChange?.(1, 100)
  assert.deepEqual(changes, [[1, 100]])
})

/** 抽出 `pagination={{ ... }}` 里那个对象字面量的文本（按花括号配平，内层回调也在里面）。 */
function inlinePaginationObjects(code: string): string[] {
  const objects: string[] = []
  const marker = 'pagination={{'
  for (let at = code.indexOf(marker); at !== -1; at = code.indexOf(marker, at + 1)) {
    let depth = 0
    for (let index = at + marker.length - 1; index < code.length; index += 1) {
      if (code[index] === '{') depth += 1
      if (code[index] === '}') {
        depth -= 1
        if (depth === 0) {
          objects.push(code.slice(at, index + 1))
          break
        }
      }
    }
  }
  return objects
}

test('写死的 pageSize 只出现在明确关掉了下拉框的分页上', async () => {
  const offenders = (await frontendSources()).flatMap(source => inlinePaginationObjects(source.code)
    // 固定的 pageSize 会在每次渲染把用户选的条数盖回去，所以要么条数是真的状态
    //（clientTablePagination / serverTablePagination），要么就别把下拉框摆出来。
    .filter(object => /\bpageSize:/.test(object) && !object.includes('showSizeChanger'))
    .map(() => source.path))


  assert.deepEqual(offenders, [],
    '表格分页要用 clientTablePagination / serverTablePagination（见 src/pagination.ts）')
})

test('分页条不用 hideOnSinglePage 藏掉每页条数的下拉框', async () => {
  const pager = await readFile(new URL('../src/ListPagination.tsx', import.meta.url), 'utf8')

  assert.doesNotMatch(pager, /hideOnSinglePage(\s*[=:])/)
  assert.match(pager, /showSizeChanger pageSizeOptions=\{sizes\}/)
  // 最小档位下也只有一页时才不摆——那时怎么选都是这一屏。
  assert.match(pager, /if \(total <= Math\.min\(\.\.\.sizes\)\) return null/)
})

test('每个列表的分页条都收两个参数，翻页和改条数走同一个回调', async () => {
  const lists = (await frontendSources()).filter(source => source.code.includes('<ListPagination'))

  assert.ok(lists.length >= 8, `只找到 ${lists.length} 个列表用了公共分页条`)
  for (const list of lists) {
    assert.match(list.code, /onChange=\{(\(page, pageSize\)|changePhotoPage)/,
      `${list.path} 的分页回调丢掉了每页条数`)
  }
})
