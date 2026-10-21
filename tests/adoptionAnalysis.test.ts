import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { adoptionRowsToCsv, foldRanking, formatDateTime, toChartBuckets, toParams } from '../src/adoptionAnalysis.ts'
import type { AdoptionRow } from '../src/adoptionAnalysis.ts'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('查询串只带有值的条件，自动粒度不传', () => {
  assert.deepEqual(toParams({ from: '2026-01-01', to: '2026-01-31', basis: 'ADOPTED', granularity: 'AUTO' }),
    { from: '2026-01-01', to: '2026-01-31', basis: 'ADOPTED' })
  assert.deepEqual(toParams({
    from: '2026-01-01', to: '2026-01-31', basis: 'PROJECT_COMPLETED', projectId: '7', campusId: '', studentId: '2023001',
    granularity: 'MONTH',
  }), { from: '2026-01-01', to: '2026-01-31', basis: 'PROJECT_COMPLETED', projectId: '7', studentId: '2023001', granularity: 'MONTH' })
})

test('排行超过上限时把长尾合成一行，合计不丢', () => {
  const items = Array.from({ length: 5 }, (_, index) => ({ key: String(index), count: 5 - index }))
  const folded = foldRanking(items, 3, rest => ({ key: '__rest', count: rest.reduce((sum, item) => sum + item.count, 0) }))
  assert.deepEqual(folded.map(item => item.key), ['0', '1', '__rest'])
  assert.equal(folded.reduce((sum, item) => sum + item.count, 0), 15)
  assert.equal(foldRanking(items, 5, () => ({ key: 'x', count: 0 })).length, 5)
})

test('借工时图表画计数：要画的数放进 worklogCount，其余时长清零', () => {
  const [bucket] = toChartBuckets([{ key: '2026-03', label: '2026 年 3 月', n: 4 }], item => item.n)
  assert.equal(bucket.worklogCount, 4)
  assert.equal(bucket.totalMinutes, 0)
  assert.equal(bucket.label, '2026 年 3 月')
})

test('被引明细 CSV 带 BOM、时间到分钟、用户填的内容防公式注入', () => {
  const row: AdoptionRow = {
    adoptionId: 1, adoptedAt: '2026-03-02T10:05:33', projectId: 2, projectTitle: '校运会', projectStatus: 'COMPLETED',
    projectCompletedAt: '2026-03-20T18:00:00', photoId: 3, photoTitle: null, takenAt: null, campusId: null,
    campusName: null, photographerStudentId: '2023001', photographerName: '张三', adoptedByName: '编辑',
    remark: '=HYPERLINK("x")',
  }
  const csv = adoptionRowsToCsv([row])
  assert.equal(csv.charCodeAt(0), 0xfeff)
  const line = csv.split('\r\n')[1]
  assert.ok(line.startsWith('2026-03-02 10:05,张三,2023001,,3,—,,校运会,已完成,2026-03-20 18:00,编辑,'))
  assert.ok(line.endsWith(`"'=HYPERLINK(""x"")"`))
  assert.equal(formatDateTime(null), '—')
})

test('成员核算点进被引详情用选题结束时间口径，和表里的被引张数对得上', async () => {
  const page = await read('pages/StatisticsPage.tsx')
  assert.match(page, /<MemberAdoptionModal member=\{adoptionMember\} range=\{range\} basis="PROJECT_COMPLETED"/)
  // 页签进 URL，被引分析可以直接链接过来。
  assert.match(page, /\{ key: 'adoptions', label: '被引分析' \}/)
  const modal = await read('MemberAdoptionModal.tsx')
  assert.match(modal, /studentId: member\.studentId/)
  assert.match(modal, /url: '\/statistics\/adoption-analysis'/)
})
