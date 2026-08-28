import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  featuredStatusDisplay, groupEntriesByCampus, remainingEntryQuota,
} from '../src/featuredCollections.ts'
import type { FeaturedEntry } from '../src/types.ts'

const window = { startsAt: '2026-05-01T09:00:00', endsAt: '2026-05-10T18:00:00' }

test('published collections are labelled by where "now" falls in the submission window', () => {
  assert.deepEqual(
    featuredStatusDisplay({ status: 'DRAFT', ...window }, new Date('2026-05-05T12:00:00')),
    { label: '草稿', color: 'default' })
  assert.equal(
    featuredStatusDisplay({ status: 'PUBLISHED', ...window }, new Date('2026-04-30T12:00:00')).label,
    '待开始')
  assert.equal(
    featuredStatusDisplay({ status: 'PUBLISHED', ...window }, new Date('2026-05-05T12:00:00')).label,
    '征集中')
  assert.equal(
    featuredStatusDisplay({ status: 'CLOSED', ...window }, new Date('2026-05-20T12:00:00')).label,
    '已截止')
})

test('the deadline is exclusive and the start is inclusive, matching the backend window check', () => {
  // 恰好到开始时间就能填。
  assert.equal(
    featuredStatusDisplay({ status: 'PUBLISHED', ...window }, new Date('2026-05-01T09:00:00')).label,
    '征集中')
  // 恰好到截止时间就不能再填了；定时任务还没扫到之前不能显示成"征集中"。
  assert.equal(
    featuredStatusDisplay({ status: 'PUBLISHED', ...window }, new Date('2026-05-10T18:00:00')).label,
    '待生成文档')
})

test('entries are chaptered by campus in the order the server returned them', () => {
  const entries = [
    entry('1', '9', '东校区'), entry('2', '9', '东校区'),
    entry('3', '7', '西校区'), entry('4', null, null),
  ]
  const chapters = groupEntriesByCampus(entries)
  assert.deepEqual(chapters.map(chapter => chapter.campusName), ['东校区', '西校区', '未分配校区'])
  assert.deepEqual(chapters[0].entries.map(item => item.id), ['1', '2'])
  assert.equal(chapters[2].campusId, null)
})

test('grouping does not merge campuses that the server separated', () => {
  // 服务端保证同校区连续。若真出现交替顺序，宁可分成两章，也不要静默重排——
  // 那会让页面与 Word 文档的章节顺序对不上。
  const chapters = groupEntriesByCampus([
    entry('1', '9', '东校区'), entry('2', '7', '西校区'), entry('3', '9', '东校区'),
  ])
  assert.equal(chapters.length, 3)
})

test('the per-manager quota never goes negative', () => {
  assert.equal(remainingEntryQuota({ entryLimit: 10, myEntryCount: 3 }), 7)
  assert.equal(remainingEntryQuota({ entryLimit: 2, myEntryCount: 5 }), 0)
})

test('the featured routes are wired and gated the way the module intends', async () => {
  const app = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8')
  // 查看不设限：路由不能挂任何权限判断。
  assert.match(app, /path="\/featured"\s+element=\{<FeaturedCollectionsPage \/>\}/)
  assert.match(app, /path="\/featured\/:collectionId"\s+element=\{<FeaturedCollectionDetailPage \/>\}/)
  assert.match(app, /key: '\/featured'/)
})

test('the requirement editor uploads to the description-image endpoint, not the message one', async () => {
  const source = await readFile(
    new URL('../src/pages/FeaturedCollectionsPage.tsx', import.meta.url), 'utf8')
  // 消息图片只对收件人可读，用它当征集要求的配图会让负责人看到裂图。
  assert.match(source, /uploadUrl="\/description-images"/)
  assert.doesNotMatch(source, /notifications\/images/)
})

function entry(id: string, campusId: string | null, campusName: string | null): FeaturedEntry {
  return {
    id, collectionId: '1', photoId: id, campusId, campusName,
    photoTitle: '图', previewUrl: null, photoAvailable: true,
    idea: '思路', location: '地点', photographerName: '拍摄者', photographerStudentId: 'S1',
    takenAt: null, submittedBy: '1', submitterDisplayName: '负责人', sortOrder: 0,
    mine: false, version: 1,
  }
}
