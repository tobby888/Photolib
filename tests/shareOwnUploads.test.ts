import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  MAX_OWN_DELETE, describeOwnDeleteOutcome, emptyOwnUploadFilters, hasOwnUploadFilters, pageAfterDelete,
  pageFullySelected, toggleOwnSelection, togglePageSelection,
} from '../src/shareOwnUploads.ts'
import type { ShareOwnUpload } from '../src/types.ts'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')
const readBackend = (path: string) =>
  readFile(new URL(`../backend/src/main/java/cn/photolib/${path}`, import.meta.url), 'utf8')

const photo = (id: number, deletable = true): ShareOwnUpload =>
  ({ id, tags: [], deletable, lockedReason: deletable ? null : '已被采用，不能删除' })

test('不能删的图片勾不上，取消勾选照常', () => {
  assert.deepEqual(toggleOwnSelection([], photo(1), true), [1])
  assert.deepEqual(toggleOwnSelection([], photo(2, false), true), [])
  assert.deepEqual(toggleOwnSelection([1, 2], photo(1), false), [2])
  const same = [1]
  assert.equal(toggleOwnSelection(same, photo(1), true), same)
})

test('选满上限后勾不上新的', () => {
  const full = Array.from({ length: MAX_OWN_DELETE }, (_, index) => index + 1)
  assert.equal(toggleOwnSelection(full, photo(9_999), true).length, MAX_OWN_DELETE)
})

test('全选本页只勾能删的，跨页勾选保留，再点一次只取消本页', () => {
  const page = [photo(1), photo(2, false), photo(3)]
  const selected = togglePageSelection([99], page)
  assert.deepEqual(selected, [99, 1, 3])
  assert.equal(pageFullySelected(selected, page), true)
  assert.deepEqual(togglePageSelection(selected, page), [99])
  // 本页一张能删的都没有时不算「已全选」。
  assert.equal(pageFullySelected([], [photo(5, false)]), false)
})

test('全选本页按上限截断', () => {
  const nearlyFull = Array.from({ length: MAX_OWN_DELETE - 1 }, (_, index) => index + 1_000)
  const next = togglePageSelection(nearlyFull, [photo(1), photo(2)])
  assert.equal(next.length, MAX_OWN_DELETE)
  assert.ok(next.includes(1))
  assert.ok(!next.includes(2))
})

test('删除结果的文案说清跳过的原因', () => {
  assert.deepEqual(describeOwnDeleteOutcome({ deleted: 3, skipped: 0, alreadyDeleted: 0 }),
    { tone: 'success', message: '已删除 3 张' })
  const partial = describeOwnDeleteOutcome({ deleted: 1, skipped: 2, alreadyDeleted: 1 })
  assert.equal(partial.tone, 'warning')
  assert.match(partial.message, /已删除 1 张/)
  assert.match(partial.message, /1 张之前已经删掉了/)
  assert.match(partial.message, /2 张没有删：已被采用/)
  assert.equal(describeOwnDeleteOutcome({ deleted: 0, skipped: 0, alreadyDeleted: 0 }).tone, 'warning')
})

test('最后一页删空时退回上一页', () => {
  assert.equal(pageAfterDelete(2, 25, 1, 24), 1)
  assert.equal(pageAfterDelete(2, 30, 1, 24), 2)
  assert.equal(pageAfterDelete(1, 3, 3, 24), 1)
})

test('有筛选条件才算在筛选', () => {
  assert.equal(hasOwnUploadFilters(emptyOwnUploadFilters), false)
  assert.equal(hasOwnUploadFilters({ ...emptyOwnUploadFilters, keyword: '  ' }), false)
  assert.equal(hasOwnUploadFilters({ ...emptyOwnUploadFilters, tags: ['舞台'] }), true)
  assert.equal(hasOwnUploadFilters({ ...emptyOwnUploadFilters, takenTo: '2026-10-01' }), true)
})

test('前后端的删除上限一致', async () => {
  const service = await readBackend('share/ProjectShareUploadService.java')
  assert.match(service, new RegExp(`MAX_DELETE_PER_REQUEST = ${MAX_OWN_DELETE};`))
})

test('「我传的图片」要等确认是上传链接才挂上：浏览链接调它会 403，而上传页对 403 的处理是清会话', async () => {
  const page = await read('pages/SharedUploadPage.tsx')
  assert.match(page, /access\?\.purpose === 'UPLOAD' && session &&\s*<SharedOwnUploads/)
})

test('多选标签按 tags=a&tags=b 发，Spring 的 List 参数才绑得上', async () => {
  const api = await read('projectShare.ts')
  const block = api.slice(api.indexOf('ownUploads:'), api.indexOf('ownUploadFilterOptions:'))
  assert.match(block, /paramsSerializer: \{ indexes: null \}/)
})
