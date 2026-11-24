import assert from 'node:assert/strict'
import test from 'node:test'
import {
  filterMemberPairs, formatMemberPair, mergeAnalysisMemberOptions, normalizeMemberPairs, resolveMemberSelection,
} from '../src/worklogMemberSuggest.ts'
import type { WorklogMemberPair } from '../src/types.ts'

const pairs: WorklogMemberPair[] = [
  { studentId: '20230002', displayName: '张三' },
  { studentId: '20230001', displayName: ' 张三 ' },
  { studentId: '20230001', displayName: '张三' },
  { studentId: '20230003', displayName: '张小三' },
  { studentId: '20230004', displayName: '李四' },
  { studentId: '20240010', displayName: '王张明' },
  { studentId: '  ', displayName: '无效空学号' },
  { studentId: '20239999', displayName: '   ' },
]

test('规范化成员配对：去空白、按（学号，姓名）去重、保留同名不同学号、过滤空项', () => {
  const normalized = normalizeMemberPairs(pairs)
  assert.equal(normalized.length, 5)
  assert.deepEqual(
    normalized.filter(item => item.displayName === '张三'),
    [
      { studentId: '20230001', displayName: '张三' },
      { studentId: '20230002', displayName: '张三' },
    ],
  )
})

test('空输入展示全部有工时记录的学号和姓名配对，输入姓名或学号实时筛选并优先前缀命中', () => {
  const all = filterMemberPairs(pairs, '   ')
  assert.equal(all.length, 5)

  // 输入名字「张」：前缀命中（张三 0001/0002、张小三）排在中间包含（王张明）之前。
  const byName = filterMemberPairs(pairs, '张')
  assert.deepEqual(
    byName.map(item => `${item.displayName}:${item.studentId}`),
    ['张三:20230001', '张三:20230002', '张小三:20230003', '王张明:20240010'],
  )

  // 精确输入姓名「张三」：精确命中排最前。
  const exactName = filterMemberPairs(pairs, '张三')
  assert.deepEqual(exactName.map(item => item.studentId), ['20230001', '20230002'])

  // 输入学号片段与「姓名 学号」组合都能筛出对应配对。
  assert.deepEqual(filterMemberPairs(pairs, '2024').map(item => item.studentId), ['20240010'])
  assert.deepEqual(filterMemberPairs(pairs, '张三 0002').map(item => item.studentId), ['20230002'])
  assert.deepEqual(filterMemberPairs(pairs, '李四（20230004）').map(item => item.studentId), ['20230004'])
})

test('点选配对项按场景回填学号或姓名，手输文本原样保留', () => {
  const selected = formatMemberPair({ displayName: '张三', studentId: '20230002' })
  assert.deepEqual(resolveMemberSelection(selected, pairs, 'studentId'), {
    value: '20230002',
    pair: { displayName: '张三', studentId: '20230002' },
  })
  assert.deepEqual(resolveMemberSelection(selected, pairs, 'displayName'), {
    value: '张三',
    pair: { displayName: '张三', studentId: '20230002' },
  })
  assert.deepEqual(resolveMemberSelection('张', pairs, 'studentId'), {
    value: '张',
    pair: null,
  })
})

test('工时分析成员下拉合并全量有工时记录的配对并按学号去重', () => {
  const merged = mergeAnalysisMemberOptions(
    [{ value: '20230001', label: '张三' }],
    [
      { studentId: '20230001', displayName: '张三' },
      { studentId: '20230004', displayName: '李四' },
      { studentId: '20240010', displayName: '王张明' },
    ],
  )
  assert.deepEqual(
    merged.map(item => item.value).sort(),
    ['20230001', '20230004', '20240010'],
  )
})
