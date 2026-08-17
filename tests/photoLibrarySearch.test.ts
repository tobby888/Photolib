import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  DEFAULT_PHOTO_LIBRARY_FILTERS,
  readPhotoLibraryFilters,
  withPhotoLibrarySearch,
  writePhotoLibraryFilters,
} from '../src/photoLibrarySearch.ts'

test('photo library filters use safe defaults for empty or invalid query parameters', () => {
  assert.deepEqual(readPhotoLibraryFilters(new URLSearchParams()), DEFAULT_PHOTO_LIBRARY_FILTERS)
  assert.deepEqual(readPhotoLibraryFilters(new URLSearchParams({
    page: '-2',
    status: 'DELETED',
    keyword: '校庆',
  })), {
    page: 1,
    status: 'AVAILABLE',
    keyword: '校庆',
  })
})

test('photo library filters round-trip Unicode and reserved characters', () => {
  const filters = {
    page: 3,
    status: 'ARCHIVED' as const,
    keyword: '毕业典礼 A&B / 夜景',
  }
  const searchParams = writePhotoLibraryFilters(filters)

  assert.deepEqual(readPhotoLibraryFilters(searchParams), filters)
  assert.equal(searchParams.get('keyword'), filters.keyword)
})

test('default filter values stay out of the URL', () => {
  assert.equal(writePhotoLibraryFilters(DEFAULT_PHOTO_LIBRARY_FILTERS).toString(), '')
  assert.equal(writePhotoLibraryFilters({
    ...DEFAULT_PHOTO_LIBRARY_FILTERS,
    keyword: '运动会',
  }).toString(), 'keyword=%E8%BF%90%E5%8A%A8%E4%BC%9A')
})

test('photo detail and list paths preserve the complete library query', () => {
  const search = `?${writePhotoLibraryFilters({
    page: 2,
    status: 'PROCESSING',
    keyword: '新闻 图',
  })}`

  assert.equal(
    withPhotoLibrarySearch('/photos/photo-1', search),
    '/photos/photo-1?keyword=%E6%96%B0%E9%97%BB+%E5%9B%BE&status=PROCESSING&page=2',
  )
  assert.equal(
    withPhotoLibrarySearch('/photos', search),
    '/photos?keyword=%E6%96%B0%E9%97%BB+%E5%9B%BE&status=PROCESSING&page=2',
  )
  assert.equal(withPhotoLibrarySearch('/photos', ''), '/photos')
})

test('photo pages wire the controlled search field and preserved return query', async () => {
  const [librarySource, detailSource] = await Promise.all([
    readFile(new URL('../src/pages/PhotosPage.tsx', import.meta.url), 'utf8'),
    readFile(new URL('../src/pages/PhotoDetailPage.tsx', import.meta.url), 'utf8'),
  ])

  assert.match(librarySource, /value=\{searchText\} onChange=\{event => setSearchText\(event\.target\.value\)\}/)
  assert.match(librarySource, /navigate\(withPhotoLibrarySearch\(`\/photos\/\$\{photo\.id\}`, location\.search\)\)/)
  assert.match(detailSource, /navigate\(withPhotoLibrarySearch\('\/photos', location\.search\)/)
})
