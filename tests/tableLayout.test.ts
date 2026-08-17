import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  calculateActionColumnWidth,
  fallbackTableTextWidth,
  measureTableText,
} from '../src/tableLayout.ts'
import {
  CONTENT_FIT_TABLE_LAYOUT,
  CONTENT_FIT_TABLE_SCROLL,
  contentFitTableClassName,
  contentFitTableScroll,
} from '../src/contentFitTableLayout.ts'

const tenPixelsPerCharacter = (text: string) => Array.from(text).length * 10

test('content-fit table contract forces automatic max-content layout', () => {
  assert.equal(CONTENT_FIT_TABLE_LAYOUT, 'auto')
  assert.deepEqual(CONTENT_FIT_TABLE_SCROLL, { x: 'max-content' })
  assert.deepEqual(contentFitTableScroll({ x: 900, y: 480 }), { x: 'max-content', y: 480 })
  assert.equal(contentFitTableClassName('compact'), 'content-fit-table compact')
})

test('the React wrapper cannot silently restore fixed layout', async () => {
  const source = await readFile(new URL('../src/ContentFitTable.tsx', import.meta.url), 'utf8')
  assert.match(source, /tableLayout=\{CONTENT_FIT_TABLE_LAYOUT\}/)
  assert.match(source, /Omit<TableProps<RecordType>, 'tableLayout'>/)
})

test('critical fixed columns consume their shared measured widths', async () => {
  const [widths, requests, worklogs] = await Promise.all([
    readFile(new URL('../src/tableActionWidths.ts', import.meta.url), 'utf8'),
    readFile(new URL('../src/pages/RequestsPage.tsx', import.meta.url), 'utf8'),
    readFile(new URL('../src/pages/WorklogsPage.tsx', import.meta.url), 'utf8'),
  ])

  assert.match(widths, /REQUEST_ACTION_MIN_WIDTH[\s\S]*交付图片[\s\S]*确认完成/)
  assert.match(requests, /minWidth: REQUEST_ACTION_MIN_WIDTH/)
  assert.match(worklogs, /minWidth: reviewer \? WORKLOG_REVIEW_ACTION_MIN_WIDTH : WORKLOG_OWNER_ACTION_MIN_WIDTH/)
})

test('mobile tables release oversized sticky action columns into horizontal scroll', async () => {
  const styles = await readFile(new URL('../src/styles.css', import.meta.url), 'utf8')
  assert.match(styles, /@media \(max-width: 900px\)[\s\S]*?\.content-fit-table \.ant-table-cell-fix-end\s*\{[\s\S]*?position: static !important;[\s\S]*?inset-inline-end: auto !important;/)
})

test('action column width uses the widest control variant and all component chrome', () => {
  const width = calculateActionColumnWidth([
    [{ label: '提交' }, { label: '删除', icon: true }],
    [{ label: '批准', icon: true }, { label: '退回', icon: true }, { label: '删除', icon: true }],
  ], { measureText: tenPixelsPerCharacter })

  // 3 * (2 chars * 10 + 32 button chrome + 22 icon chrome)
  // + 2 * 8 Space gaps + 32 cell padding + 8 safety margin.
  assert.equal(width, 278)
})

test('owner worklog actions cannot regress to the former 120px column', () => {
  const width = calculateActionColumnWidth([[
    { label: '提交' },
    { label: '删除', icon: true },
  ]], { measureText: tenPixelsPerCharacter })

  assert.equal(width, 174)
  assert.ok(width > 120)
})

test('fixed-width controls and long labels both contribute to the action width', () => {
  const shortVariant = calculateActionColumnWidth([
    [{ fixedWidth: 28 }, { label: '修改邮箱', icon: true }],
  ], { measureText: tenPixelsPerCharacter })
  const longVariant = calculateActionColumnWidth([
    [{ label: '确认完成这个需求' }],
  ], { measureText: tenPixelsPerCharacter })

  assert.equal(shortVariant, 170)
  assert.equal(longVariant, 152)
  assert.ok(shortVariant > 96)
})

test('empty variants retain a safe header minimum and fractional widths round up', () => {
  assert.equal(calculateActionColumnWidth([], { measureText: () => 1.2 }), 96)
  assert.equal(calculateActionColumnWidth([[{ label: 'A' }]], {
    measureText: () => 20.25,
    minWidth: 0,
  }), 93)
})

test('text measurement has a deterministic no-Canvas fallback for Node tests', () => {
  assert.ok(fallbackTableTextWidth('长姓名 A-01') > fallbackTableTextWidth('A-01'))
  assert.ok(measureTableText('长姓名 A-01') > 0)
})
