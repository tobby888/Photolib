import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

// Windows 上 git 会把源码检出成 CRLF，统一成 LF 再断言。
const read = async (path: string) =>
  (await readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')).replace(/\r\n/g, '\n')

/**
 * 选题详情页的性能回归点。400 张图的选题上实测：改动前首屏要卡 3.7 秒、每勾一张图卡 1.5 秒，
 * 原因是全部图片一次挂成卡片、每次状态变化整页几百张卡片一起重渲染、并且每张卡片都在
 * 数组里线性查找采纳/需求/勾选状态。下面几条任何一条被改回去，那种卡顿就会回来。
 */

function cardSource(detail: string) {
  const start = detail.indexOf('const ProjectPhotoCard = memo(')
  const end = detail.indexOf('\n})\n', start)
  assert.ok(start !== -1 && end !== -1, '找不到 memo 过的 ProjectPhotoCard')
  return detail.slice(start, end)
}

test('相册分页展示，而不是把全部图片一次挂成卡片', async () => {
  const detail = await read('pages/ProjectDetailPage.tsx')
  assert.match(detail, /const pagedPhotos = useMemo\(\s*\(\) => filteredPhotos\.slice\(/)
  assert.match(detail, /\{pagedPhotos\.map\(photo =>/)
  assert.doesNotMatch(detail, /\{filteredPhotos\.map\(/)
  assert.doesNotMatch(detail, /\{data\.photos\.map\(/)
  assert.match(detail, /<Pagination className="project-photo-pagination"/)
  // 筛选一变就回第 1 页，否则可能停在一个已经不存在的页码上。
  assert.match(detail, /setPhotoPage\(current => \(\{ \.\.\.current, current: 1 \}\)\)/)
})

test('图片卡片是 memo 组件，回调引用稳定，勾选一张只重渲染一张', async () => {
  const detail = await read('pages/ProjectDetailPage.tsx')
  cardSource(detail)
  for (const handler of ['onToggleAlbumPhoto', 'onDownloadPhoto', 'onToggleAdoption', 'onPhotoTagClick']) {
    assert.match(detail, new RegExp(`const ${handler} = useStableCallback\\(`), `${handler} 必须是稳定回调`)
  }
  // 卡片上的回调只能传这些稳定引用，传内联箭头函数会让 memo 失效。
  const usage = detail.slice(detail.indexOf('<ProjectPhotoCard\n'))
  assert.match(usage, /onToggleSelect=\{onToggleAlbumPhoto\}/)
  assert.match(usage, /onDownload=\{onDownloadPhoto\}/)
  assert.match(usage, /onToggleAdoption=\{onToggleAdoption\}/)
  assert.match(usage, /onTagClick=\{onPhotoTagClick\}/)
})

test('卡片状态靠索引查，不再逐张线性扫描采纳、需求和勾选列表', async () => {
  const detail = await read('pages/ProjectDetailPage.tsx')
  assert.match(detail, /const adoptedPhotoIds = useMemo\(\(\) => new Set\(/)
  assert.match(detail, /const requestTitles = useMemo\(\s*\(\) => new Map\(/)
  assert.match(detail, /const selectedAlbumIdSet = useMemo\(\(\) => new Set\(/)
  const grid = detail.slice(detail.indexOf('{pagedPhotos.map(photo =>'), detail.indexOf('<Pagination className="project-photo-pagination"'))
  assert.doesNotMatch(grid, /data\.adoptions\.some\(|data\.requests\.find\(|selectedAlbumPhotoIds\.includes\(/)
})

test('卡片里不用 Typography 的 ellipsis，缩略图懒加载', async () => {
  const card = cardSource(await read('pages/ProjectDetailPage.tsx'))
  // ellipsis 会在每个实例上量一次布局，几十张卡片就是一串强制重排；单行省略交给 CSS。
  assert.doesNotMatch(card, /ellipsis/)
  assert.match(card, /className="photo-card-line"/)
  assert.match(card, /<PreviewPhoto [^>]*loading="lazy"/)
  const styles = await read('styles.css')
  assert.match(styles, /\.photo-card \.photo-card-line \{[^}]*text-overflow: ellipsis/)
})

test('Markdown 正文不变就不重新解析', async () => {
  const renderer = await read('MarkdownRenderer.tsx')
  assert.match(renderer, /export default memo\(MarkdownRenderer\)/)
})
