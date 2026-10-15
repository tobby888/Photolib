import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

// 模块在调用时才碰 window.sessionStorage，先给 node 装一个最小的实现。
const store = new Map<string, string>()
;(globalThis as { window?: unknown }).window = {
  sessionStorage: {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => { store.set(key, value) },
  },
}

const {
  PROJECT_ALBUM_PAGE_SIZE, loadProjectAlbumView, neighborsInList, pageContaining, projectPhotoDetailPath,
  readProjectContext, readProjectNavState, rememberProjectAlbumFocus, saveProjectAlbumView,
} = await import('../src/projectPhotoContext.ts')

test('the detail URL carries the project so a reload still knows where to go back', () => {
  const path = projectPhotoDetailPath('42', '7')
  assert.equal(path, '/photos/42?project=7')
  assert.equal(readProjectContext(path.slice(path.indexOf('?'))), '7')
})

test('a hand-edited project param that is not an id is treated as no origin', () => {
  assert.equal(readProjectContext(''), null)
  assert.equal(readProjectContext('?project='), null)
  assert.equal(readProjectContext('?project=../admin'), null)
  assert.equal(readProjectContext('?keyword=a&project=12'), '12')
})

test('previous / next follow the album order that was on screen', () => {
  const ids = ['3', '9', '5']
  assert.deepEqual(neighborsInList(ids, '9'), { positioned: true, previous: '3', next: '5' })
  assert.deepEqual(neighborsInList(ids, '3'), { positioned: true, previous: null, next: '9' })
  assert.deepEqual(neighborsInList(ids, '5'), { positioned: true, previous: '9', next: null })
  // 不在这份顺序里（被筛掉、刚删掉）就两边都不能翻，而不是跳到别处。
  assert.deepEqual(neighborsInList(ids, '100'), { positioned: false, previous: null, next: null })
  assert.deepEqual(neighborsInList(null, '9'), { positioned: false, previous: null, next: null })
})

test('only a list of string ids is accepted as router state', () => {
  assert.deepEqual(readProjectNavState({ projectPhotoIds: ['1', '2'] }), ['1', '2'])
  assert.equal(readProjectNavState(null), null)
  assert.equal(readProjectNavState({ projectPhotoIds: [1, 2] }), null)
  assert.equal(readProjectNavState({ usr: 'x' }), null)
})

test('the album view survives the round trip to the detail page', () => {
  store.clear()
  saveProjectAlbumView('7', {
    filters: { tags: ['合影'], takenFrom: '2026-10-01', takenTo: null, photographers: ['张三'], adoption: 'ADOPTED' },
    page: 3, pageSize: 48, focusPhotoId: null,
  })
  rememberProjectAlbumFocus('7', '42')
  assert.deepEqual(loadProjectAlbumView('7'), {
    filters: { tags: ['合影'], takenFrom: '2026-10-01', takenTo: null, photographers: ['张三'], adoption: 'ADOPTED' },
    page: 3, pageSize: 48, focusPhotoId: '42',
  })
  // 每个选题各记各的。
  assert.equal(loadProjectAlbumView('8'), null)
})

test('a corrupted or outdated stored view falls back field by field', () => {
  store.clear()
  store.set('photolib_project_album_7', JSON.stringify({
    filters: { tags: 'oops', takenFrom: 'yesterday', photographers: [1, '李四'], adoption: 'MAYBE' },
    page: -2, pageSize: 1000, focusPhotoId: 5,
  }))
  assert.deepEqual(loadProjectAlbumView('7'), {
    filters: { tags: [], takenFrom: null, takenTo: null, photographers: ['李四'], adoption: null },
    page: 1, pageSize: PROJECT_ALBUM_PAGE_SIZE, focusPhotoId: null,
  })
  store.set('photolib_project_album_7', '{not json')
  assert.equal(loadProjectAlbumView('7'), null)
})

test('coming back turns to the page that holds the last photo viewed', () => {
  const ids = Array.from({ length: 50 }, (_, index) => String(index + 1))
  assert.equal(pageContaining(ids, '1', 24, 2), 1)
  assert.equal(pageContaining(ids, '25', 24, 1), 2)
  assert.equal(pageContaining(ids, '50', 24, 1), 3)
  assert.equal(pageContaining(ids, 'gone', 24, 2), 2)
  assert.equal(pageContaining(ids, null, 24, 2), 2)
})

test('the project page opens photo details with its origin and the detail page goes back to it', async () => {
  const detail = await readFile(new URL('../src/pages/ProjectDetailPage.tsx', import.meta.url), 'utf8')
  assert.match(detail, /navigate\(projectPhotoDetailPath\(photo\.id, projectId\), \{ state \}\)/)
  assert.doesNotMatch(detail, /navigate\(`\/photos\/\$\{photo\.id\}`\)/)
  const photo = await readFile(new URL('../src/pages/PhotoDetailPage.tsx', import.meta.url), 'utf8')
  assert.match(photo, /navigate\(`\/projects\/\$\{projectContext\}`/)
  assert.match(photo, /返回选题/)
})
