import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  BATCH_REQUEST_LIMIT, MAX_LIBRARY_SELECTION, SELECT_ALL_PAGE_SIZE, chunk, collectAllPages, invertPage, mergeById,
  runInChunks,
} from '../src/librarySelection.ts'
import {
  DEFAULT_PHOTO_LIBRARY_FILTERS, photoLibraryRequestParams, readPhotoLibraryFilters, writePhotoLibraryFilters,
} from '../src/photoLibrarySearch.ts'

const photo = (id: number, version = 1) => ({ id: String(id), version })

test('批量请求按后端的 200 张一批拆开，并且与后端的 @Size(max = 200) 对齐', async () => {
  assert.equal(BATCH_REQUEST_LIMIT, 200)
  assert.deepEqual(chunk([1, 2, 3, 4, 5], 2), [[1, 2], [3, 4], [5]])
  assert.deepEqual(chunk([], 2), [])
  const controller = await readFile(new URL('../backend/src/main/java/cn/photolib/photo/PhotoController.java',
    import.meta.url), 'utf8')
  assert.match(controller, /BatchDeleteRequest\(@NotEmpty @Size\(max = 200\)/)
  // 「全选」逐页取数的页长就是 GET /photos 的 pageSize 上限。
  assert.equal(SELECT_ALL_PAGE_SIZE, 100)
  assert.match(controller, /@Min\(1\) @Max\(100\) int pageSize/)
})

test('按批顺序提交：中途失败就停下，并如实报告已经成功了几条', async () => {
  const sent: number[][] = []
  const ids = Array.from({ length: 450 }, (_, index) => index)
  const ok = await runInChunks(ids, async batch => { sent.push(batch) })
  assert.deepEqual(ok, { done: 450 })
  assert.deepEqual(sent.map(batch => batch.length), [200, 200, 50])

  let calls = 0
  const failed = await runInChunks(ids, async () => {
    calls += 1
    if (calls === 2) throw new Error('已采纳图片不能直接删除')
  })
  assert.equal(failed.done, 200)
  assert.equal(failed.error?.message, '已采纳图片不能直接删除')
  // 失败之后不再发后面的批次。
  assert.equal(calls, 2)
})

test('并入勾选：保留原有顺序、按 id 去重、换上新取回的版本、到上限为止', () => {
  const merged = mergeById([photo(1), photo(2)], [photo(2, 5), photo(3), photo(4)], 3)
  assert.deepEqual(merged.selected.map(item => item.id), ['1', '2', '3'])
  assert.equal(merged.selected[1].version, 5)
  assert.equal(merged.added, 1)
  assert.equal(merged.truncated, true)
  assert.equal(mergeById([photo(1)], [photo(1)]).truncated, false)
})

test('反选本页只动本页，其他页的勾选原样保留', () => {
  const result = invertPage([photo(1), photo(9)], [photo(1), photo(2), photo(3)])
  assert.deepEqual(result.selected.map(item => item.id).sort(), ['2', '3', '9'])
})

test('筛选结果全选按总页数取，最多取到上限那么多页', async () => {
  const requested: number[] = []
  const total = 1234
  const result = await collectAllPages(async (page, pageSize) => {
    requested.push(page)
    const start = (page - 1) * pageSize
    return {
      items: Array.from({ length: Math.max(0, Math.min(pageSize, total - start)) }, (_, index) => photo(start + index)),
      page, pageSize, total, totalPages: Math.ceil(total / pageSize),
    }
  })
  assert.equal(result.total, total)
  assert.equal(result.items.length, MAX_LIBRARY_SELECTION)
  assert.deepEqual(requested.sort((a, b) => a - b), Array.from({ length: 10 }, (_, index) => index + 1))

  const small = await collectAllPages(async () => ({ items: [photo(1)], page: 1, pageSize: 100, total: 1, totalPages: 1 }))
  assert.deepEqual(small, { items: [photo(1)], total: 1 })
})

test('新增的图库筛选进 URL、进请求，旧链接不受影响', () => {
  const filters = {
    ...DEFAULT_PHOTO_LIBRARY_FILTERS, tags: ['合影', '颁奖'],
    takenFrom: '2026-05-01', takenTo: '2026-05-03', adopted: 'no' as const, inProject: 'yes' as const,
    tagMatch: 'any' as const,
  }
  const url = writePhotoLibraryFilters(filters)
  assert.deepEqual(readPhotoLibraryFilters(url), filters)
  const request = photoLibraryRequestParams(filters)
  assert.equal(request.get('takenFrom'), '2026-05-01')
  assert.equal(request.get('takenTo'), '2026-05-03')
  assert.equal(request.get('adopted'), 'false')
  assert.equal(request.get('inProject'), 'true')
  assert.equal(request.get('tagMatch'), 'ANY')
  // 只有一个标签时「任意其一」没有意义，不发。
  assert.equal(photoLibraryRequestParams({ ...filters, tags: ['合影'] }).has('tagMatch'), false)
  // 默认值不进 URL，旧书签读出来还是默认筛选。
  assert.equal(writePhotoLibraryFilters(DEFAULT_PHOTO_LIBRARY_FILTERS).toString(), '')
})

test('手改的 URL：不存在的日期丢掉、起止颠倒就换过来、不认识的取值当没填', () => {
  const read = (query: Record<string, string>) => readPhotoLibraryFilters(new URLSearchParams(query))
  assert.equal(read({ takenFrom: '2026-02-30' }).takenFrom, undefined)
  assert.equal(read({ takenFrom: '2026/05/01' }).takenFrom, undefined)
  const swapped = read({ takenFrom: '2026-05-09', takenTo: '2026-05-01' })
  assert.deepEqual([swapped.takenFrom, swapped.takenTo], ['2026-05-01', '2026-05-09'])
  assert.equal(read({ adopted: 'maybe' }).adopted, undefined)
  assert.equal(read({ tagMatch: 'ANY; DROP' }).tagMatch, undefined)
  assert.deepEqual(read({ adopted: 'maybe', inProject: '1' }), DEFAULT_PHOTO_LIBRARY_FILTERS)
})

test('图库页和选题的「从图库添加图片」都接了筛选结果全选和分批提交', async () => {
  const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')
  const [library, picker, tagModal] = await Promise.all([
    read('pages/PhotosPage.tsx'), read('LibraryPickerModal.tsx'), read('BatchTagModal.tsx')])
  for (const source of [library, picker]) {
    assert.match(source, /collectAllPages\(/)
    assert.match(source, /全选筛选结果/)
    assert.match(source, /runInChunks\(/)
  }
  assert.match(tagModal, /runInChunks\(/)
  // 打包下载是一个 ZIP 一个任务，超过 200 张要提示收窄，而不是悄悄只打前 200 张。
  assert.match(library, /selectedIds\.length > BATCH_REQUEST_LIMIT/)
})
