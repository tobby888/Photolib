import type { SalaryCalculationDetail, SalaryLine, SalaryProjectGroup } from './types'

/**
 * 统计面板「工资计算」的展示辅助。金额由后端按公式算好、存成快照（见 SalaryCalculationService），
 * 公式的解析和校验也只在后端做（SalaryFormula）；前端只负责格式化、编辑框插入和明细筛选。
 */

/** 和后端 `SalaryFormula.DEFAULT` 一致；接口没取到时的兜底。 */
export const DEFAULT_SALARY_FORMULA = 'max(工时 * 20, 被引张数 * 10)'
/** 和后端 `SalaryFormula.MAX_LENGTH` 一致。 */
export const SALARY_FORMULA_MAX_LENGTH = 500

const yuanFormat = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/** 金额：千分位、两位小数，例如 `1,234.50`。 */
export function formatYuan(value: number | null | undefined): string {
  return yuanFormat.format(Number(value) || 0)
}

/** 分钟换成小时，最多两位小数、去掉末尾的 0，例如 90 → `1.5`。 */
export function minutesToHours(minutes: number | null | undefined): string {
  return String(Number(((Number(minutes) || 0) / 60).toFixed(2)))
}

/** 计算规则的一句话说明。 */
export function salaryRule(formula: string): string {
  return `每位成员在每个选题的工资 = ${formula}（单位元，小于 0 按 0 计，四舍五入到分）`
}

/**
 * 在编辑框的选区 `[start, end)` 处插入一段字段名或函数，返回新文本和光标位置。
 * 函数片段（以 `()` 结尾）把光标放进括号里，接着就能写参数；插入点紧挨着前面的字段 / 数字时补一个空格。
 */
export function insertSnippet(text: string, start: number, end: number, snippet: string): { text: string; cursor: number } {
  const from = Math.max(0, Math.min(start, text.length))
  const to = Math.max(from, Math.min(end, text.length))
  const before = text.slice(0, from)
  const after = text.slice(to)
  const needsSpace = /[\p{L}\p{N}_)]$/u.test(before)
  const piece = (needsSpace ? ' ' : '') + snippet
  const cursor = before.length + piece.length - (snippet.endsWith('()') ? 1 : 0)
  return { text: before + piece + after, cursor }
}

const normalize = (value: string) => value.trim().toLowerCase()

function lineMatches(line: SalaryLine, keyword: string) {
  return normalize(line.memberName).includes(keyword) || normalize(line.studentId ?? '').includes(keyword)
}

/**
 * 按选题看时的筛选：关键词同时匹配选题标题和成员（姓名、学号）。标题命中的选题整组保留；
 * 只命中成员的选题只留下命中的那几行。没有关键词时原样返回。
 */
export function filterProjects(projects: SalaryProjectGroup[], keyword: string): SalaryProjectGroup[] {
  const key = normalize(keyword)
  if (!key) return projects
  const result: SalaryProjectGroup[] = []
  for (const project of projects) {
    if (normalize(project.projectTitle).includes(key)) {
      result.push(project)
      continue
    }
    const lines = project.lines.filter(line => lineMatches(line, key))
    if (lines.length) result.push({ ...project, lines })
  }
  return result
}

/** 某位成员在各个选题里的明细行（按成员看时展开用），顺序沿用选题顺序。 */
export function linesOfMember(detail: SalaryCalculationDetail, memberKey: string): SalaryLine[] {
  return detail.projects.flatMap(project => project.lines.filter(line => line.memberKey === memberKey))
}
