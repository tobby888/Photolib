/**
 * 统计面板「工时分析」的条件树和接口类型，与后端 `cn.photolib.statistics.analysis` 一一对应。
 *
 * 字段和运算符都是后端的枚举名：前端只是把它们组合成一棵树发过去，后端按白名单把每个枚举
 * 换成写死的 SQL 片段、取值一律绑定成参数（见 `WorklogConditionCompiler`）。所以这里不需要、
 * 也不应该做任何转义——前端的校验只为了少发一次注定被拒的请求，边界在后端。
 */

export type ConditionField =
  | 'STATUS' | 'SOURCE' | 'CAMPUS' | 'PROJECT' | 'PROJECT_STATUS' | 'REQUEST' | 'SUBMITTER'
  | 'MEMBER_STUDENT_ID' | 'MEMBER_NAME' | 'REMARK' | 'WORK_DATE'
  | 'SHOOTING_MINUTES' | 'RETOUCHING_MINUTES' | 'TOTAL_MINUTES'

export type ConditionOperator =
  | 'EQ' | 'NE' | 'IN' | 'NOT_IN' | 'GT' | 'GE' | 'LT' | 'LE' | 'BETWEEN'
  | 'CONTAINS' | 'NOT_CONTAINS' | 'STARTS_WITH' | 'IS_EMPTY' | 'IS_NOT_EMPTY'

export type ValueKind = 'enum' | 'option' | 'text' | 'date' | 'number'

/** 下拉候选从哪里来：枚举是写死的，其余来自 `GET /statistics/worklog-analysis/options`。 */
export type OptionSource = 'projects' | 'campuses' | 'members' | 'submitters'

export interface FieldMeta {
  label: string
  kind: ValueKind
  operators: ConditionOperator[]
  enumOptions?: { value: string; label: string }[]
  optionSource?: OptionSource
}

const EQUALITY: ConditionOperator[] = ['IN', 'NOT_IN', 'EQ', 'NE']
const ORDERED: ConditionOperator[] = ['GE', 'LE', 'GT', 'LT', 'BETWEEN', 'EQ', 'NE']
const TEXTUAL: ConditionOperator[] = ['CONTAINS', 'NOT_CONTAINS', 'STARTS_WITH', 'EQ', 'NE', 'IN', 'NOT_IN']

export const STATUS_LABELS: Record<string, string> = {
  CONFIRMED: '已确认', SUBMITTED: '待确认', REJECTED: '已退回',
}

/** 与后端 `WorklogConditionCompiler.operatorsFor` 对齐：可空列才有「为空 / 不为空」。 */
export const FIELDS: Record<ConditionField, FieldMeta> = {
  STATUS: { label: '审核状态', kind: 'enum', operators: EQUALITY,
    enumOptions: Object.entries(STATUS_LABELS).map(([value, label]) => ({ value, label })) },
  SOURCE: { label: '来源', kind: 'enum', operators: EQUALITY, enumOptions: [
    { value: 'IN_APP', label: '站内填报' }, { value: 'SHARE_LINK', label: '上传链接申报' }] },
  CAMPUS: { label: '校区', kind: 'option', optionSource: 'campuses',
    operators: [...EQUALITY, 'IS_EMPTY', 'IS_NOT_EMPTY'] },
  PROJECT: { label: '所属选题', kind: 'option', optionSource: 'projects', operators: EQUALITY },
  PROJECT_STATUS: { label: '选题状态', kind: 'enum', operators: EQUALITY, enumOptions: [
    { value: 'ACTIVE', label: '进行中' }, { value: 'COMPLETED', label: '已完成' },
    { value: 'CANCELLED', label: '已取消' }, { value: 'DRAFT', label: '草稿' }] },
  REQUEST: { label: '需求编号', kind: 'text', operators: ['IS_EMPTY', 'IS_NOT_EMPTY', 'EQ', 'NE'] },
  SUBMITTER: { label: '填报账号', kind: 'option', optionSource: 'submitters', operators: EQUALITY },
  MEMBER_STUDENT_ID: { label: '成员（按学号）', kind: 'option', optionSource: 'members', operators: EQUALITY },
  MEMBER_NAME: { label: '成员姓名', kind: 'text', operators: TEXTUAL },
  REMARK: { label: '工作说明', kind: 'text', operators: [...TEXTUAL, 'IS_EMPTY', 'IS_NOT_EMPTY'] },
  WORK_DATE: { label: '工作日期', kind: 'date', operators: ORDERED },
  SHOOTING_MINUTES: { label: '拍摄时长（分钟）', kind: 'number', operators: ORDERED },
  RETOUCHING_MINUTES: { label: '修图时长（分钟）', kind: 'number', operators: ORDERED },
  TOTAL_MINUTES: { label: '单条总时长（分钟）', kind: 'number', operators: ORDERED },
}

export const OPERATOR_LABELS: Record<ConditionOperator, string> = {
  EQ: '等于', NE: '不等于', IN: '属于', NOT_IN: '不属于', GT: '大于', GE: '不小于', LT: '小于', LE: '不大于',
  BETWEEN: '介于', CONTAINS: '包含', NOT_CONTAINS: '不包含', STARTS_WITH: '开头是',
  IS_EMPTY: '为空', IS_NOT_EMPTY: '不为空',
}

export type Logic = 'AND' | 'OR'

export interface ConditionLeaf {
  key: string
  field: ConditionField
  operator: ConditionOperator
  values: string[]
}

export interface ConditionGroup {
  key: string
  logic: Logic
  not?: boolean
  children: ConditionNode[]
}

export type ConditionNode = ConditionLeaf | ConditionGroup

export const isGroup = (node: ConditionNode): node is ConditionGroup => 'children' in node

/** 界面上允许嵌套几层分组（后端上限是 5 层、50 个节点，留出余量）。 */
export const MAX_UI_DEPTH = 3
export const MAX_UI_NODES = 40

let counter = 0
export const nextKey = () => `c${Date.now().toString(36)}${(counter++).toString(36)}`

export function newLeaf(field: ConditionField = 'STATUS'): ConditionLeaf {
  return { key: nextKey(), field, operator: FIELDS[field].operators[0], values: [] }
}

export function newGroup(logic: Logic = 'AND', children: ConditionNode[] = []): ConditionGroup {
  return { key: nextKey(), logic, children }
}

/** 默认条件：只看已确认的工时——与成员统计、工资核算是同一个口径。 */
export function defaultCondition(projectId?: string): ConditionGroup {
  const children: ConditionNode[] = [{ ...newLeaf('STATUS'), operator: 'IN', values: ['CONFIRMED'] }]
  if (projectId) children.push({ ...newLeaf('PROJECT'), operator: 'IN', values: [projectId] })
  return newGroup('AND', children)
}

export function needsValues(operator: ConditionOperator): number {
  if (operator === 'IS_EMPTY' || operator === 'IS_NOT_EMPTY') return 0
  if (operator === 'BETWEEN') return 2
  if (operator === 'IN' || operator === 'NOT_IN') return -1
  return 1
}

/** 一条比较是否填完整了：没填完的条件不发给后端（等于还没加上），而不是让整次分析报错。 */
export function isComplete(leaf: ConditionLeaf): boolean {
  const need = needsValues(leaf.operator)
  const filled = leaf.values.filter(value => value.trim() !== '')
  if (need === 0) return true
  if (need === -1) return filled.length > 0
  return filled.length === need
}

/** 换字段时运算符和取值跟着重置：上一个字段的取值对新字段多半没有意义（选题 id 不是学号）。 */
export function changeField(leaf: ConditionLeaf, field: ConditionField): ConditionLeaf {
  return { ...leaf, field, operator: FIELDS[field].operators[0], values: [] }
}

/** 换运算符时尽量保留已填的值：从「属于」换到「不属于」不该把选好的五个人清掉。 */
export function changeOperator(leaf: ConditionLeaf, operator: ConditionOperator): ConditionLeaf {
  const need = needsValues(operator)
  const values = need === 0 ? [] : need === -1 ? leaf.values : leaf.values.slice(0, need)
  return { ...leaf, operator, values }
}

export function countNodes(node: ConditionNode): number {
  return isGroup(node) ? 1 + node.children.reduce((sum, child) => sum + countNodes(child), 0) : 1
}

/** 发给后端的形状（去掉界面用的 key）；没填完的比较和因此变空的分组一并去掉，整棵树空了就是 null。 */
export interface ConditionPayload {
  logic?: Logic
  not?: boolean
  children?: ConditionPayload[]
  field?: ConditionField
  operator?: ConditionOperator
  values?: string[]
}

export function toPayload(node: ConditionNode): ConditionPayload | null {
  if (!isGroup(node)) {
    if (!isComplete(node)) return null
    return { field: node.field, operator: node.operator, values: node.values.map(value => value.trim()) }
  }
  const children = node.children.map(toPayload).filter((child): child is ConditionPayload => child !== null)
  if (!children.length) return null
  return { logic: node.logic, ...(node.not ? { not: true } : {}), children }
}

/** 汇总维度，与后端 `WorklogAnalysisService.Dimension` 对齐（DAY/WEEK/MONTH 走趋势图，这里不列）。 */
export type GroupDimension =
  | 'MEMBER' | 'CAMPUS' | 'PROJECT' | 'SUBMITTER' | 'STATUS' | 'SOURCE' | 'WEEKDAY' | 'MONTH' | 'WEEK' | 'DAY'

export const GROUP_LABELS: Partial<Record<GroupDimension, string>> = {
  MEMBER: '按成员', CAMPUS: '按校区', PROJECT: '按选题', SUBMITTER: '按填报账号',
  STATUS: '按审核状态', SOURCE: '按来源', MONTH: '按月份', WEEKDAY: '按星期',
}

export type Granularity = 'AUTO' | 'DAY' | 'WEEK' | 'MONTH'

export interface AnalysisBucket {
  key: string
  label: string
  worklogCount: number
  shootingMinutes: number
  retouchingMinutes: number
  totalMinutes: number
  memberCount: number
}

export interface AnalysisRow {
  worklogId: string | number
  workDate: string
  memberStudentId: string
  memberName: string
  shootingMinutes: number
  retouchingMinutes: number
  status: string
  requestId?: string | number | null
  requestTitle?: string | null
  projectId: string | number
  projectTitle?: string | null
  campusId?: string | number | null
  campusName?: string | null
  userId: string | number
  userDisplayName?: string | null
  remark?: string | null
}

export interface AnalysisResult {
  summary: {
    worklogCount: number
    shootingMinutes: number
    retouchingMinutes: number
    totalMinutes: number
    memberCount: number
    projectCount: number
    activeDays: number
  }
  groups: AnalysisBucket[]
  groupBy: GroupDimension
  trend: AnalysisBucket[]
  granularity: Granularity
  weekdays: AnalysisBucket[]
  statuses: AnalysisBucket[]
  sources: AnalysisBucket[]
  durations: AnalysisBucket[]
  daily: AnalysisBucket[]
  rows: AnalysisRow[]
  rowsTruncated: boolean
}

export interface AnalysisOption { value: string; label: string }
export type AnalysisOptions = Record<OptionSource, AnalysisOption[]>

/** 分钟 → 小时，保留一位小数；图表和表格都用它，免得两处四舍五入得不一样。 */
export const toHours = (minutes: number) => Math.round(minutes / 6) / 10

/** 长尾折成「其他」：排行图只画前 `limit` 名，剩下的合成一根，合计照样对得上。 */
export function foldTail(buckets: readonly AnalysisBucket[], limit: number): AnalysisBucket[] {
  if (buckets.length <= limit) return [...buckets]
  const head = buckets.slice(0, limit - 1)
  const tail = buckets.slice(limit - 1)
  const other = tail.reduce<AnalysisBucket>((sum, bucket) => ({
    ...sum,
    worklogCount: sum.worklogCount + bucket.worklogCount,
    shootingMinutes: sum.shootingMinutes + bucket.shootingMinutes,
    retouchingMinutes: sum.retouchingMinutes + bucket.retouchingMinutes,
    totalMinutes: sum.totalMinutes + bucket.totalMinutes,
    memberCount: sum.memberCount + bucket.memberCount,
  }), { key: '__other', label: `其他 ${tail.length} 项`, worklogCount: 0, shootingMinutes: 0,
    retouchingMinutes: 0, totalMinutes: 0, memberCount: 0 })
  return [...head, other]
}
