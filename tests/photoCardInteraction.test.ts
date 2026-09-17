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

test('带大图预览的页面：单击选图时预览改受控，并忽略从预览弹层冒泡上来的事件', async () => {
  const pages = await Promise.all([
    read('pages/ProjectDetailPage.tsx'),
    read('pages/SharedProjectPage.tsx'),
    read('pages/RequestDeliveryPage.tsx'),
  ])

  for (const page of pages) {
    assert.match(page, /selectablePreview\(/)
    assert.match(page, /onClick=\{event => \{\n\s*if \([^\n]*isPortalEvent\(event\)\) return/)
    assert.match(page, /onDoubleClick=\{event => \{\n\s*if \([^\n]*isPortalEvent\(event\)\) return/)
  }
})

test('Shift 连选只在可勾选的图片里取范围', async () => {
  const [library, detail, delivery] = await Promise.all([
    read('pages/PhotosPage.tsx'),
    read('pages/ProjectDetailPage.tsx'),
    read('pages/RequestDeliveryPage.tsx'),
  ])

  assert.match(library, /const orderedIds = data\.items\.filter\(canSelectPhoto\)/)
  assert.match(detail, /const orderedIds = pagedPhotos\.filter\(photo => isDownloadableStatus\(photo\.status\)\)/)
  assert.match(delivery, /const orderedIds = photosState\.data\.items\.filter\(canSelectPhoto\)/)
})

test('键盘和触屏也能打开详情 / 大图：Enter 打开，卡片上有查看按钮', async () => {
  const pages = await Promise.all([
    read('pages/PhotosPage.tsx'),
    read('pages/ProjectDetailPage.tsx'),
    read('pages/SharedProjectPage.tsx'),
    read('pages/RequestDeliveryPage.tsx'),
  ])

  for (const page of pages) {
    assert.match(page, /event\.key === 'Enter'/)
    assert.match(page, /className="(photo|delivery)-view-button"/)
  }
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
  assert.match(delivery, /closest\('\.ant-checkbox-wrapper, \.delivery-view-button'\)/)
})
