import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { findPhotoNeighbors, pickEdgePhotoId } from '../src/photoNeighbors.ts'
import type { PageData } from '../src/types.ts'

const page = (ids: string[], current: number, totalPages: number, pageSize = 3): PageData<{ id: string }> => ({
  items: ids.map(id => ({ id })),
  page: current,
  pageSize,
  total: (totalPages - 1) * pageSize + ids.length,
  totalPages,
})

test('neighbours inside one page follow the gallery order', () => {
  assert.deepEqual(findPhotoNeighbors(page(['a', 'b', 'c'], 1, 1), 'b'), {
    positioned: true,
    previous: { page: 1, id: 'a' },
    next: { page: 1, id: 'c' },
  })
})

test('the first and last photo of the whole library have no neighbour on that side', () => {
  const only = page(['a', 'b', 'c'], 1, 1)

  assert.deepEqual(findPhotoNeighbors(only, 'a').previous, null)
  assert.deepEqual(findPhotoNeighbors(only, 'a').next, { page: 1, id: 'b' })
  assert.deepEqual(findPhotoNeighbors(only, 'c').next, null)
  assert.deepEqual(findPhotoNeighbors(only, 'c').previous, { page: 1, id: 'b' })

  const single = page(['solo'], 1, 1)
  assert.deepEqual(findPhotoNeighbors(single, 'solo'), { positioned: true, previous: null, next: null })
})

test('page edges point at the adjacent page and leave the photo ID to be fetched', () => {
  const middlePage = page(['d', 'e', 'f'], 2, 3)

  assert.deepEqual(findPhotoNeighbors(middlePage, 'd').previous, { page: 1, id: null })
  assert.deepEqual(findPhotoNeighbors(middlePage, 'd').next, { page: 2, id: 'e' })
  assert.deepEqual(findPhotoNeighbors(middlePage, 'f').next, { page: 3, id: null })
  assert.deepEqual(findPhotoNeighbors(middlePage, 'f').previous, { page: 2, id: 'e' })

  // 最后一页的最后一张仍然是整个图库的最后一张。
  assert.deepEqual(findPhotoNeighbors(page(['g'], 3, 3), 'g'), {
    positioned: true,
    previous: { page: 2, id: null },
    next: null,
  })
})

test('a photo missing from the page disables both directions instead of guessing', () => {
  // 直链进来、图片刚被删掉、或筛选条件已经变了都会走到这里。
  assert.deepEqual(findPhotoNeighbors(page(['a', 'b', 'c'], 2, 3), 'zzz'), {
    positioned: false,
    previous: null,
    next: null,
  })
  assert.deepEqual(findPhotoNeighbors(page([], 1, 0), 'a'), { positioned: false, previous: null, next: null })
  assert.deepEqual(findPhotoNeighbors(page(['a'], 1, 1), undefined), {
    positioned: false,
    previous: null,
    next: null,
  })
})

test('page count falls back to total/pageSize when the response omits totalPages', () => {
  const withoutTotalPages: PageData<{ id: string }> = {
    items: [{ id: 'a' }, { id: 'b' }],
    page: 1,
    pageSize: 2,
    total: 5,
    totalPages: 0,
  }

  assert.deepEqual(findPhotoNeighbors(withoutTotalPages, 'b').next, { page: 2, id: null })
  assert.deepEqual(findPhotoNeighbors({ ...withoutTotalPages, total: 2 }, 'b').next, null)
})

test('the edge photo of a fetched page is picked from the right end', () => {
  const fetched = page(['a', 'b', 'c'], 1, 2)

  assert.equal(pickEdgePhotoId(fetched, 'first'), 'a')
  assert.equal(pickEdgePhotoId(fetched, 'last'), 'c')
  // 隔壁页在这次请求之间被删空了，就不要跳过去。
  assert.equal(pickEdgePhotoId(page([], 2, 2), 'first'), null)
  assert.equal(pickEdgePhotoId(page([], 2, 2), 'last'), null)
})

test('the photo detail page wires the previous/next buttons to the resolved neighbours', async () => {
  const [detailSource, librarySource, searchSource] = await Promise.all([
    readFile(new URL('../src/pages/PhotoDetailPage.tsx', import.meta.url), 'utf8'),
    readFile(new URL('../src/pages/PhotosPage.tsx', import.meta.url), 'utf8'),
    readFile(new URL('../src/photoLibrarySearch.ts', import.meta.url), 'utf8'),
  ])

  assert.match(detailSource, /const neighbors = findPhotoNeighbors\(libraryPage, photoId\)/)
  assert.match(detailSource, /disabled=\{!neighbors\.previous\}[\s\S]*?>上一张<\/Button>/)
  assert.match(detailSource, /disabled=\{!neighbors\.next\}[\s\S]*?>下一张<\/Button>/)
  // 跨页跳转必须把新页码写回 URL，否则回到图库会落在旧的一页上。
  assert.match(detailSource, /writePhotoLibraryFilters\(\{ \.\.\.libraryFilters, page: neighbor\.page \}\)/)

  // 详情页定位用的分页必须和图库列表严格一致，否则翻页会跳号。
  assert.match(searchSource, /export const PHOTO_LIBRARY_PAGE_SIZE = 24/)
  for (const source of [detailSource, librarySource]) {
    assert.match(source, /pageSize: PHOTO_LIBRARY_PAGE_SIZE/)
    assert.doesNotMatch(source, /pageSize: 24/)
  }
  assert.match(librarySource, /pageSize=\{PHOTO_LIBRARY_PAGE_SIZE\}/)
})
