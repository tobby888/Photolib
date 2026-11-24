import type { WorklogMemberPair } from './types'
import type { AnalysisOption } from './worklogAnalysis'

/**
 * 工时统计与工时记录页的「姓名 + 学号」配对搜索提示逻辑。
 *
 * 提示数据来自后端全量有工时记录的成员配对（不随当前所选日期范围截断）：
 * - 空输入时展示全部有工时记录的（姓名，学号）配对，方便直接浏览点选；
 * - 输入姓名、学号或「姓名 学号」组合时实时筛选，前缀命中排在包含命中之前；
 * - 选中某一条时可按场景回填学号（精确唯一、区分同名）或姓名（用于仅按姓名匹配的筛选条件）。
 */

export type MemberSuggestionPick = 'studentId' | 'displayName'

export const formatMemberPair = (pair: Pick<WorklogMemberPair, 'displayName' | 'studentId'>) =>
  `${pair.displayName}（${pair.studentId}）`

/** 清洗首尾空白、按（学号，姓名）去重，并按中文拼音 + 学号稳定排序。 */
export function normalizeMemberPairs(pairs: readonly WorklogMemberPair[]): WorklogMemberPair[] {
  const seen = new Set<string>()
  const result: WorklogMemberPair[] = []
  for (const item of pairs) {
    const studentId = item.studentId?.trim() ?? ''
    const displayName = item.displayName?.trim() ?? ''
    if (!studentId || !displayName) continue
    const key = `${studentId}\0${displayName}`
    if (seen.has(key)) continue
    seen.add(key)
    result.push({ studentId, displayName })
  }
  return result.sort((a, b) =>
    a.displayName.localeCompare(b.displayName, 'zh-CN') || a.studentId.localeCompare(b.studentId))
}

function matchRank(pair: WorklogMemberPair, query: string): number {
  const name = pair.displayName.toLowerCase()
  const id = pair.studentId.toLowerCase()
  if (name === query || id === query) return 0
  if (name.startsWith(query) || id.startsWith(query)) return 1
  if (name.includes(query) || id.includes(query)) return 2
  const combined = `${name} ${id} ${name}（${id}） ${name}(${id})`
  if (combined.includes(query)) return 3
  const tokens = query.split(/\s+/).filter(Boolean)
  if (tokens.length > 1 && tokens.every(token => name.includes(token) || id.includes(token))) return 3
  return -1
}

/**
 * 按用户输入筛选配对提示：
 * - 输入为空时返回全部已规范化的有工时成员配对；
 * - 有输入时按姓名、学号或组合匹配，精确/前缀命中排在前面。
 */
export function filterMemberPairs(
  pairs: readonly WorklogMemberPair[],
  query: string,
): WorklogMemberPair[] {
  const normalized = normalizeMemberPairs(pairs)
  const trimmed = query.trim().toLowerCase()
  if (!trimmed) return normalized
  return normalized
    .map(pair => ({ pair, rank: matchRank(pair, trimmed) }))
    .filter(item => item.rank >= 0)
    .sort((a, b) => a.rank - b.rank
      || a.pair.displayName.localeCompare(b.pair.displayName, 'zh-CN')
      || a.pair.studentId.localeCompare(b.pair.studentId))
    .map(item => item.pair)
}

/**
 * 将下拉选项的唯一展示键（`姓名（学号）`）解析为回填给搜索框的值；
 * 普通手输文本原样返回。
 */
export function resolveMemberSelection(
  rawValue: string,
  pairs: readonly WorklogMemberPair[],
  pick: MemberSuggestionPick = 'studentId',
): { value: string; pair: WorklogMemberPair | null } {
  const normalized = normalizeMemberPairs(pairs)
  const matched = normalized.find(pair => formatMemberPair(pair) === rawValue)
  if (!matched) return { value: rawValue, pair: null }
  return {
    value: pick === 'displayName' ? matched.displayName : matched.studentId,
    pair: matched,
  }
}

/**
 * 工时分析页「成员（按学号）」下拉选项合并：把全量有工时记录的成员配对补进来，
 * 按学号去重并保留中文排序，确保切换日期范围前也能搜到全部有工时记录的人。
 */
export function mergeAnalysisMemberOptions(
  rangeOptions: readonly AnalysisOption[],
  allPairs: readonly WorklogMemberPair[],
): AnalysisOption[] {
  const byStudentId = new Map<string, string>()
  for (const option of rangeOptions) {
    const id = option.value?.trim() ?? ''
    const name = option.label?.trim() ?? ''
    if (id && name && !byStudentId.has(id)) byStudentId.set(id, name)
  }
  for (const pair of normalizeMemberPairs(allPairs)) {
    if (!byStudentId.has(pair.studentId)) byStudentId.set(pair.studentId, pair.displayName)
  }
  return [...byStudentId.entries()]
    .map(([value, label]) => ({ value, label }))
    .sort((a, b) => a.label.localeCompare(b.label, 'zh-CN') || a.value.localeCompare(b.value))
}
