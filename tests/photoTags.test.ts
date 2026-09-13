import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  applyTagChanges, collectPhotographers, collectTagOptions, emptyProjectPhotoFilters, filterPhotos,
  hasActiveFilters, normalizeTags, tagInputError, tagsOnPhotos, tagsOutsidePresets,
} from '../src/photoTags.ts'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')

const photos = [
  { id: '1', tags: ['合影', '开幕式'], takenAt: '2026-06-01T09:30:00', photographerName: '张三' },
  { id: '2', tags: ['合影'], takenAt: '2026-06-02T23:59:59', photographerName: '李四' },
  { id: '3', tags: [], takenAt: '2026-06-03T00:00:00', photographerName: '张三' },
  { id: '4', tags: ['颁奖', '合影'], takenAt: '2026-06-05T12:00:00', photographerName: '王五' },
]

test('标签规范化与后端一致：去空白、丢空值、按首次出现去重', () => {
  assert.deepEqual(normalizeTags([' 合影 ', '合影', '', null, '  ', '开幕式']), ['合影', '开幕式'])
  assert.deepEqual(normalizeTags(undefined), [])
})

test('批量改标签先删后加，新增的追加在末尾', () => {
  assert.deepEqual(applyTagChanges(['旧标签', '合影'], ['颁奖', '合影'], ['旧标签']), ['合影', '颁奖'])
  assert.deepEqual(applyTagChanges(undefined, [' 合影 '], []), ['合影'])
  // 同一个标签同时加和删：结果与后端一致，保留（后端先删后加）。
  assert.deepEqual(applyTagChanges(['合影'], ['合影'], ['合影']), ['合影'])
})

test('标签筛选要求同时包含所选全部标签', () => {
  const ids = (filters = emptyProjectPhotoFilters) => filterPhotos(photos, filters).map(photo => photo.id)
  assert.deepEqual(ids(), ['1', '2', '3', '4'])
  assert.deepEqual(ids({ ...emptyProjectPhotoFilters, tags: ['合影'] }), ['1', '2', '4'])
  assert.deepEqual(ids({ ...emptyProjectPhotoFilters, tags: ['合影', '开幕式'] }), ['1'])
})

test('拍摄时间按当地日期筛选，起止两端都包含整天', () => {
  const ids = (takenFrom: string | null, takenTo: string | null) =>
    filterPhotos(photos, { ...emptyProjectPhotoFilters, takenFrom, takenTo }).map(photo => photo.id)
  // 23:59:59 仍属于 6 月 2 日；次日零点属于 6 月 3 日。
  assert.deepEqual(ids('2026-06-02', '2026-06-02'), ['2'])
  assert.deepEqual(ids('2026-06-02', '2026-06-03'), ['2', '3'])
  assert.deepEqual(ids(null, '2026-06-01'), ['1'])
  assert.deepEqual(ids('2026-06-04', null), ['4'])
})

test('拍摄者筛选是任意其一，且能与标签、时间叠加', () => {
  const filters = { tags: ['合影'], photographers: ['张三', '王五'], takenFrom: '2026-06-01', takenTo: '2026-06-04' }
  assert.deepEqual(filterPhotos(photos, filters).map(photo => photo.id), ['1'])
  assert.equal(hasActiveFilters(filters), true)
  assert.equal(hasActiveFilters(emptyProjectPhotoFilters), false)
})

test('被引筛选按本选题的采用记录判断，能与其他条件叠加', () => {
  const adopted = new Set(['2', '4'])
  const isAdopted = (photo: { id: string }) => adopted.has(photo.id)
  const ids = (filters: typeof emptyProjectPhotoFilters) =>
    filterPhotos(photos, filters, isAdopted).map(photo => photo.id)

  assert.deepEqual(ids({ ...emptyProjectPhotoFilters, adoption: 'ADOPTED' }), ['2', '4'])
  assert.deepEqual(ids({ ...emptyProjectPhotoFilters, adoption: 'NOT_ADOPTED' }), ['1', '3'])
  assert.deepEqual(ids({ ...emptyProjectPhotoFilters, adoption: null }), ['1', '2', '3', '4'])
  assert.deepEqual(ids({ ...emptyProjectPhotoFilters, tags: ['合影'], adoption: 'NOT_ADOPTED' }), ['1'])
  assert.equal(hasActiveFilters({ ...emptyProjectPhotoFilters, adoption: 'ADOPTED' }), true)
  // 不传判断函数时一律当作未被引，不会因为漏传而把「未被引」筛成空。
  assert.deepEqual(filterPhotos(photos, { ...emptyProjectPhotoFilters, adoption: 'NOT_ADOPTED' }).length, 4)
})

test('筛选候选：预设标签按定义顺序在前，其余按出现次数排序', () => {
  assert.deepEqual(collectTagOptions(['颁奖', '未使用'], photos), ['颁奖', '未使用', '合影', '开幕式'])
  assert.deepEqual(tagsOnPhotos(photos.slice(0, 2)), ['合影', '开幕式'])
  assert.deepEqual(collectPhotographers(photos), ['李四', '王五', '张三'].sort((a, b) => a.localeCompare(b, 'zh-CN')))
})

test('预设为空时不限制，否则找出预设之外的标签', () => {
  assert.deepEqual(tagsOutsidePresets(['自定义'], []), [])
  assert.deepEqual(tagsOutsidePresets(['合影', ' 自定义 '], ['合影']), ['自定义'])
})

test('表单校验与后端上限一致：按字计长度，最多 30 个', () => {
  assert.equal(tagInputError(['😀'.repeat(50)]), null)
  assert.match(tagInputError(['长'.repeat(51)]) || '', /不能超过 50 个字/)
  assert.match(tagInputError(Array.from({ length: 31 }, (_, index) => `标签${index}`)) || '', /最多 30 个/)
  assert.equal(tagInputError(undefined), null)
})

test('需求上传入口按选题预设切换成只选模式', async () => {
  const [delivery, batch, select] = await Promise.all([
    read('pages/RequestDeliveryPage.tsx'), read('pages/BatchUploadPage.tsx'), read('TagSelect.tsx'),
  ])
  // 上传者不一定能看选题详情，预设标签必须从需求自己的接口取，而不是 /projects/{id}。
  assert.match(delivery, /\/requests\/\$\{request\.id\}\/tag-options/)
  assert.match(delivery, /<TagSelect restricted=\{tagOptions\.restricted\} presets=\{tagOptions\.tags\}/)
  assert.match(batch, /\/requests\/\$\{requestId\}\/tag-options/)
  assert.match(batch, /restricted=\{context\.data\.tagOptions\.restricted\}/)
  // 只选模式不能是 tags 模式加提示：那样照样能敲回车造出新标签。
  assert.match(select, /mode=\{restricted \? 'multiple' : 'tags'\}/)
})

test('选题详情页的批量改标签带上选题 id，筛选只作用于已取回的项目图片', async () => {
  const detail = await read('pages/ProjectDetailPage.tsx')
  assert.match(detail, /<BatchTagModal mode=\{tagMode\} photos=\{selectedAlbumPhotos\} projectId=\{projectId\}/)
  assert.match(detail, /presets=\{presetTags\}/)
  assert.match(detail, /filterPhotos\(data\.photos, photoFilters, isAdoptedHere\)/)
  // 被引是本选题里的状态，按本选题的采用记录判断，不能用图片上跨选题的 adoptionCount。
  assert.match(detail, /const isAdoptedHere = useCallback\(\(photo: Photo\) => adoptedPhotoIds\.has\(/)
  // 相册分页只切展示：当前页取自筛选结果。
  assert.match(detail, /filteredPhotos\.slice\(/)
  assert.match(detail, /\{pagedPhotos\.map\(photo =>/)
})
