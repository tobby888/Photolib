import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  DEFAULT_PHOTO_LIBRARY_FILTERS,
  isTagTooLong,
  photoLibraryRequestParams,
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
    tags: [],
    uploadedBy: '',
  })
})

test('library filters keep only numeric uploader ids from the URL', () => {
  const read = (uploadedBy: string) =>
    readPhotoLibraryFilters(new URLSearchParams({ uploadedBy })).uploadedBy

  assert.equal(read('42'), '42')
  // 后端的 uploadedBy 是 Long，非数字的值会 400 让整页变成加载出错。
  assert.equal(read('or 1=1'), '')
  assert.equal(read('-1'), '')
  assert.equal(read('1.5'), '')
  assert.equal(read(''), '')
})

test('uploader filter travels through the URL and the list request', () => {
  const filters = {
    ...DEFAULT_PHOTO_LIBRARY_FILTERS,
    uploadedBy: '7',
  }

  assert.equal(writePhotoLibraryFilters(filters).toString(), 'uploadedBy=7')
  assert.deepEqual(readPhotoLibraryFilters(writePhotoLibraryFilters(filters)), filters)
  assert.equal(photoLibraryRequestParams(filters).get('uploadedBy'), '7')
  // 详情页的「上一张 / 下一张」必须带上同一个上传者，否则定位会错位。
  assert.equal(photoLibraryRequestParams(filters, { page: 2 }).get('uploadedBy'), '7')
  assert.equal(photoLibraryRequestParams(DEFAULT_PHOTO_LIBRARY_FILTERS).has('uploadedBy'), false)
})

test('photo library filters round-trip Unicode and reserved characters', () => {
  const filters = {
    page: 3,
    status: 'ARCHIVED' as const,
    keyword: '毕业典礼 A&B / 夜景',
    tags: ['合影', '颁奖/闭幕'],
    uploadedBy: '12',
  }
  const searchParams = writePhotoLibraryFilters(filters)

  assert.deepEqual(readPhotoLibraryFilters(searchParams), filters)
  assert.equal(searchParams.get('keyword'), filters.keyword)
  assert.deepEqual(searchParams.getAll('tags'), filters.tags)
})

test('default filter values stay out of the URL', () => {
  assert.equal(writePhotoLibraryFilters(DEFAULT_PHOTO_LIBRARY_FILTERS).toString(), '')
  assert.equal(writePhotoLibraryFilters({
    ...DEFAULT_PHOTO_LIBRARY_FILTERS,
    keyword: '运动会',
  }).toString(), 'keyword=%E8%BF%90%E5%8A%A8%E4%BC%9A')
  assert.equal(writePhotoLibraryFilters({
    ...DEFAULT_PHOTO_LIBRARY_FILTERS,
    tags: ['合影', '开幕式'],
  }).toString(), 'tags=%E5%90%88%E5%BD%B1&tags=%E5%BC%80%E5%B9%95%E5%BC%8F')
})

test('photo detail and list paths preserve the complete library query', () => {
  const search = `?${writePhotoLibraryFilters({
    page: 2,
    status: 'PROCESSING',
    keyword: '新闻 图',
    tags: [],
    uploadedBy: '',
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
  assert.equal(
    withPhotoLibrarySearch('/favorites/photo-1', search),
    '/favorites/photo-1?keyword=%E6%96%B0%E9%97%BB+%E5%9B%BE&status=PROCESSING&page=2',
  )
  assert.equal(
    withPhotoLibrarySearch('/favorites', search),
    '/favorites?keyword=%E6%96%B0%E9%97%BB+%E5%9B%BE&status=PROCESSING&page=2',
  )
})

test('photo and favorites pages wire the controlled search field and preserved return query', async () => {
  const [librarySource, detailSource, appSource] = await Promise.all([
    readFile(new URL('../src/pages/PhotosPage.tsx', import.meta.url), 'utf8'),
    readFile(new URL('../src/pages/PhotoDetailPage.tsx', import.meta.url), 'utf8'),
    readFile(new URL('../src/App.tsx', import.meta.url), 'utf8'),
  ])

  assert.match(librarySource, /value=\{searchText\} onChange=\{event => setSearchText\(event\.target\.value\)\}/)
  // 候选人只能来自按可见范围算过的专用接口，不能拿通讯录或用户列表凑。
  assert.match(librarySource, /url: '\/photos\/uploaders'/)
  assert.match(librarySource, /filters\.page, filters\.keyword, filters\.status, filters\.uploadedBy/)
  assert.match(librarySource, /const libraryRoot = favoritesOnly \? '\/favorites' : '\/photos'/)
  assert.match(librarySource, /`\$\{libraryRoot\}\/\$\{photo\.id\}`/)
  assert.match(librarySource, /const operationViewKey = currentViewKeyRef\.current/)
  assert.match(librarySource, /currentViewKeyRef\.current === operationViewKey/)
  assert.match(detailSource, /withPhotoLibrarySearch\(libraryRoot, location\.search\)/)
  assert.match(detailSource, /\{favoritesOnly \? '返回收藏图片' : '返回图片库'\}/)
  assert.match(appSource, /key: '\/favorites'.*label: '收藏图片'/)
  assert.match(appSource, /path="\/favorites".*<PhotosPage favoritesOnly \/>/)
  assert.match(appSource, /path="\/favorites\/:photoId".*<PhotoDetailPage favoritesOnly \/>/)
})

test('library request params repeat tags and match between the list and detail pages', () => {
  const filters = {
    page: 3,
    status: 'AVAILABLE' as const,
    keyword: '',
    tags: ['合影', '颁奖/闭幕'],
    uploadedBy: '',
  }

  assert.equal(
    photoLibraryRequestParams(filters).toString(),
    'page=3&pageSize=24&status=AVAILABLE&tags=%E5%90%88%E5%BD%B1&tags=%E9%A2%81%E5%A5%96%2F%E9%97%AD%E5%B9%95',
  )
  const neighbor = photoLibraryRequestParams(filters, { page: 4, favoritesOnly: true })
  assert.equal(neighbor.get('page'), '4')
  assert.deepEqual(neighbor.getAll('tags'), filters.tags)
  assert.equal(neighbor.get('favoritesOnly'), 'true')
  assert.equal(photoLibraryRequestParams(filters, { favoritesOnly: false }).has('favoritesOnly'), false)
})

test('library filters read from the URL drop blank, duplicate and over-long tags', () => {
  const tooLong = '长'.repeat(51)
  const searchParams = new URLSearchParams()
  for (const tag of [' 合影 ', '合影', '', tooLong, '😀'.repeat(50)]) searchParams.append('tags', tag)

  assert.deepEqual(readPhotoLibraryFilters(searchParams).tags, ['合影', '😀'.repeat(50)])
  assert.equal(isTagTooLong('😀'.repeat(50)), false)
  assert.equal(isTagTooLong(tooLong), true)
})
