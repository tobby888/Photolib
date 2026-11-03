import type { SalaryBasis, SalaryCalculationDetail, SalaryLine, SalaryProjectGroup } from './types'

/**
 * 统计面板「工资计算」的展示辅助。金额和口径都由后端算好、存成快照（见 SalaryCalculationService），
 * 前端只负责格式化和在明细里筛选，不重新计算。
 */

const yuanFormat = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/** 金额：千分位、两位小数，例如 `1,234.50`。 */
export function formatYuan(value: number | null | undefined): string {
  return yuanFormat.format(Number(value) || 0)
}

/** 分钟换成小时，最多两位小数、去掉末尾的 0，例如 90 → `1.5`。 */
export function minutesToHours(minutes: number | null | undefined): string {
  return String(Number(((Number(minutes) || 0) / 60).toFixed(2)))
}

export const BASIS_LABELS: Record<SalaryBasis, string> = {
  HOURS: '按工时',
  ADOPTIONS: '按被引',
  EQUAL: '两者相同',
  NONE: '无已确认记录',
}

/** 计算规则的一句话说明，和导出表、后端注释是同一句。 */
export function salaryRule(hourlyRate: number, adoptionRate: number): string {
  return `每位成员在每个选题的工资 = max(已确认工时（小时）× ${Number(hourlyRate)} 元，被引张数 × ${Number(adoptionRate)} 元)`
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
