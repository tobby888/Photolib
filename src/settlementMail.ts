/**
 * 邮件清算单（统计面板「发送清算单」、管理员面板「邮件模板」）的类型与纯函数。
 * 不依赖 React 和请求层，node 单测直接测（tests/settlementMail.test.ts）。
 */

import type { EntityId } from './types'

/**
 * 账号 id 一律按字符串处理：雪花 id 超出 JS 安全整数，后端会以字符串返回（见 JacksonConfig），
 * 转成 number 会丢精度，发出去就成了另一个（不存在的）账号。
 */
export const userKey = (id: EntityId | number) => String(id)

export interface SettlementRecipient {
  userId: EntityId | number
  username: string
  displayName: string
  email?: string | null
  campus?: string | null
  groupName?: string | null
  /** 期内按姓名匹配到的工时条数（含待审核、已退回，不含草稿）。 */
  worklogCount: number
  confirmedMinutes: number
  pendingCount: number
  rejectedCount: number
  adoptedPhotos: number
  adoptionCount: number
  /** 另有几个在用账号与他同名；大于 0 时无法按姓名区分记录，不能收清算单。 */
  sameNameCount: number
}

export interface SettlementStatus {
  configured: boolean
  fromAddress?: string | null
  activeBatchId?: number | null
  siteUrlConfigured: boolean
}

export type SettlementBatchStatus = 'RUNNING' | 'COMPLETED'
export type SettlementDeliveryStatus = 'PENDING' | 'SENDING' | 'SENT' | 'FAILED'

export interface SettlementDelivery {
  id: number
  userId: EntityId | number
  recipientName: string
  recipientEmail: string
  status: SettlementDeliveryStatus
  worklogCount: number
  adoptionCount: number
  errorMessage?: string | null
  sentAt?: string | null
}

export interface SettlementBatch {
  id: number
  status: SettlementBatchStatus
  from: string
  to: string
  createdByName: string
  total: number
  sent: number
  failed: number
  createdAt: string
  finishedAt?: string | null
  deliveries?: SettlementDelivery[] | null
}

export interface SettlementPreview {
  subject: string
  html: string
}

export interface MailTemplate {
  subject: string
  html: string
  customized: boolean
  updatedAt?: string | null
  updatedByName?: string | null
  version: number
}

export interface MailTemplateView {
  template: MailTemplate
  defaultSubject: string
  defaultHtml: string
  /** 变量名 → 说明，顺序即展示顺序。 */
  variables: Record<string, string>
}

export type RecordPresence = 'all' | 'with' | 'without'

export interface RecipientFilter {
  keyword: string
  campus?: string
  group?: string
  records: RecordPresence
  /** 只看有被退回工时的人。 */
  onlyRejected: boolean
}

export const EMPTY_RECIPIENT_FILTER: RecipientFilter = { keyword: '', records: 'with', onlyRejected: false }

/** 为什么这个人不能选；能选时返回 null。 */
export function unavailableReason(recipient: SettlementRecipient): string | null {
  if (!recipient.email) return '账号没有填写邮箱'
  if (recipient.sameNameCount > 0) return `另有 ${recipient.sameNameCount} 个账号与他同名，无法按姓名区分记录`
  return null
}

export const isSelectable = (recipient: SettlementRecipient) => unavailableReason(recipient) === null

export const hasRecords = (recipient: SettlementRecipient) =>
  recipient.worklogCount > 0 || recipient.adoptionCount > 0

export function filterRecipients(recipients: SettlementRecipient[], filter: RecipientFilter) {
  const keyword = filter.keyword.trim().toLowerCase()
  return recipients.filter(recipient => {
    if (keyword && ![recipient.displayName, recipient.username, recipient.email ?? '']
      .some(value => value.toLowerCase().includes(keyword))) return false
    if (filter.campus && recipient.campus !== filter.campus) return false
    if (filter.group && recipient.groupName !== filter.group) return false
    if (filter.records === 'with' && !hasRecords(recipient)) return false
    if (filter.records === 'without' && hasRecords(recipient)) return false
    if (filter.onlyRejected && recipient.rejectedCount <= 0) return false
    return true
  })
}

/** 下拉候选：出现过的校区 / 权限组，去重排序，空值不算。 */
export function distinctValues(recipients: SettlementRecipient[], key: 'campus' | 'groupName') {
  return [...new Set(recipients.map(recipient => recipient[key]).filter((value): value is string => !!value))]
    .sort((a, b) => a.localeCompare(b, 'zh-CN'))
}

/**
 * 换了筛选条件后，勾选只保留仍然可选的人（不在当前筛选结果里的照样保留，和列表跨页勾选一致），
 * 防止名单刷新后（例如换了时间范围）还留着一个已经没有邮箱的人。
 */
export function keepSelectable(selected: string[], recipients: SettlementRecipient[]) {
  const selectable = new Set(recipients.filter(isSelectable).map(recipient => userKey(recipient.userId)))
  return selected.filter(id => selectable.has(id))
}

export const formatMinutes = (minutes = 0) => {
  if (minutes <= 0) return '0'
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  if (!hours) return `${rest} 分钟`
  return rest ? `${hours} 小时 ${rest} 分` : `${hours} 小时`
}

export const DELIVERY_STATUS_LABELS: Record<SettlementDeliveryStatus, string> = {
  PENDING: '排队中', SENDING: '发送中', SENT: '已发送', FAILED: '失败',
}

export const DELIVERY_STATUS_COLORS: Record<SettlementDeliveryStatus, string> = {
  PENDING: 'default', SENDING: 'processing', SENT: 'success', FAILED: 'error',
}

export const batchProgress = (batch: Pick<SettlementBatch, 'total' | 'sent' | 'failed'>) =>
  batch.total <= 0 ? 100 : Math.round((batch.sent + batch.failed) / batch.total * 100)

/** 一个批次里发送失败的人，用来「重发失败的」。 */
export const failedUserIds = (batch: SettlementBatch) =>
  (batch.deliveries ?? []).filter(delivery => delivery.status === 'FAILED').map(delivery => userKey(delivery.userId))

/** 在模板正文的光标处插入一个变量，返回新正文和插入后的光标位置。 */
export function insertVariable(text: string, start: number, end: number, name: string) {
  const token = `{{${name}}}`
  const safeStart = Math.max(0, Math.min(start, text.length))
  const safeEnd = Math.max(safeStart, Math.min(end, text.length))
  return { text: text.slice(0, safeStart) + token + text.slice(safeEnd), cursor: safeStart + token.length }
}

/** 模板里引用了、但系统不认识的变量（多半是拼错了），保存前提示。 */
export function unknownVariables(template: string, variables: Record<string, string>) {
  const names = new Set<string>()
  for (const match of template.matchAll(/\{\{\s*[#/]?\s*([A-Za-z]+)\s*}}/g)) {
    if (!(match[1] in variables)) names.add(match[1])
  }
  return [...names]
}
