import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const read = async (path: string) =>
  (await readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')).replace(/\r\n/g, '\n')

test('图库把单击选图、双击详情和 Shift 连选接到图片封面上', async () => {
  const page = await read('pages/PhotosPage.tsx')

  assert.match(page, /usePhotoCardClick<Photo>/)
  assert.match(page, /onDoubleClick=\{event => \{[\s\S]*?openPhotoDetail\(photo\)/)
  assert.match(page, /event\.shiftKey[\s\S]*?selectRangeTo\(photo\.id\)/)
  assert.match(page, /selectPhotoRange\(selectedIds, orderedIds, selectionAnchor, photoId, 200\)/)
})

test('选题详情页和分享页也支持单击 / Shift 连选，并且保留 memo 的稳定回调', async () => {
  const [detail, shared] = await Promise.all([
    read('pages/ProjectDetailPage.tsx'),
    read('pages/SharedProjectPage.tsx'),
  ])

  assert.match(detail, /usePhotoCardClick<Photo>/)
  assert.match(detail, /onSelectRange=\{onSelectAlbumRange\}/)
  assert.match(detail, /const onSelectAlbumRange = useStableCallback\(selectAlbumRange\)/)
  assert.match(shared, /usePhotoCardClick<SharePhoto>/)
  assert.match(shared, /selectPhotoRange\(selected, pageIds, selectionAnchor, photoId, MAX_SHARE_BATCH\)/)
})

test('需求交付页的图片也一样单击选取', async () => {
  const page = await read('pages/RequestDeliveryPage.tsx')

  assert.match(page, /usePhotoCardClick<Photo>/)
  assert.match(page, /event\.shiftKey[\s\S]*?selectDeliveryRange\(photo\.id\)/)
})

test('封面上的下载 / 勾选按钮不会把双击冒泡成打开详情或再次选取', async () => {
  const pages = await Promise.all([
    read('pages/PhotosPage.tsx'),
    read('pages/ProjectDetailPage.tsx'),
    read('pages/SharedProjectPage.tsx'),
  ])

  for (const page of pages) {
    assert.match(page, /className="photo-overlay"[\s\S]*?onDoubleClick=\{event => event\.stopPropagation\(\)\}/)
  }
  const delivery = await read('pages/RequestDeliveryPage.tsx')
  assert.match(delivery, /closest\('\.ant-checkbox-wrapper'\)/)
})
