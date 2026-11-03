/**
 * 清算单（统计面板「发送清算单」：邮件和 / 或站内信；管理员面板「邮件模板」；站内信里的清算单）的类型与纯函数。
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
  /** 按姓名匹配到的、所属选题在期内结束的工时条数（含待审核、已退回，不含草稿）。 */
  worklogCount: number
  confirmedMinutes: number
  pendingCount: number
  rejectedCount: number
  adoptedPhotos: number
  adoptionCount: number
  /** 另有几个在用账号与他同名；大于 0 时无法按姓名区分记录，不能收清算单。 */
  sameNameCount: number
  /** 期内结束、他上传过图（拍摄者是他、已传完）的选题数和其中的图片张数。 */
  uploadedPhotos?: number
  projectCount?: number
  /** 截至期末还没结束、他上传过图的选题数和期末前上传的张数（清算单里单独列出，不计入本期）。 */
  ongoingPhotos?: number
  ongoingProjectCount?: number
}

/** 这次发哪几种。两种都发时没邮箱的人只收站内信。 */
export interface SettlementChannels {
  email: boolean
  inbox: boolean
}

export interface SettlementStatus {
  /** 能不能发邮件（SMTP 配齐了）。站内信不需要。 */
  configured: boolean
  fromAddress?: string | null
  activeBatchId?: number | null
  siteUrlConfigured: boolean
}

export type SettlementBatchStatus = 'RUNNING' | 'COMPLETED'
export type SettlementDeliveryStatus = 'PENDING' | 'SENDING' | 'SENT' | 'FAILED'
/** 单个渠道的结果；邮件另有 SKIPPED（两种都发、但这个人没有邮箱）。 */
export type SettlementChannelStatus = 'PENDING' | 'SENT' | 'FAILED' | 'SKIPPED'

export interface SettlementDelivery {
  id: number
  userId: EntityId | number
  recipientName: string
  recipientEmail?: string | null
  status: SettlementDeliveryStatus
  worklogCount: number
  adoptionCount: number
  errorMessage?: string | null
  sentAt?: string | null
  /** null：这次没发这一种。 */
  emailStatus?: SettlementChannelStatus | null
  inboxStatus?: SettlementChannelStatus | null
  /** 站内信里那份清算单。 */
  statementId?: EntityId | number | null
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
  sendEmail?: boolean
  sendInbox?: boolean
  createdAt: string
  finishedAt?: string | null
  deliveries?: SettlementDelivery[] | null
}

export interface SettlementPreview {
  subject: string
  html: string
}

/** 站内信里的清算单（发送那一刻的快照）。 */
export interface SettlementStatement {
  id: EntityId | number
  recipientName: string
  from: string
  to: string
  subject: string
  html: string
  createdByName: string
  createdAt?: string | null
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

export const EMAIL_ONLY: SettlementChannels = { email: true, inbox: false }

/** 为什么这个人不能选；能选时返回 null。没有邮箱只在「只发邮件」时才不能选。 */
export function unavailableReason(recipient: SettlementRecipient, channels: SettlementChannels = EMAIL_ONLY): string | null {
  if (recipient.sameNameCount > 0) return `另有 ${recipient.sameNameCount} 个账号与他同名，无法按姓名区分记录`
  if (channels.email && !channels.inbox && !recipient.email) return '账号没有填写邮箱，可以改发站内信'
  return null
}

export const isSelectable = (recipient: SettlementRecipient, channels: SettlementChannels = EMAIL_ONLY) =>
  unavailableReason(recipient, channels) === null

export const hasRecords = (recipient: SettlementRecipient) =>
  recipient.worklogCount > 0 || recipient.adoptionCount > 0 || (recipient.uploadedPhotos ?? 0) > 0
  || (recipient.ongoingPhotos ?? 0) > 0

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
 * 名单刷新或换了发送方式后，勾选只保留仍然可选的人（不在当前筛选结果里的照样保留，和列表跨页勾选一致），
 * 防止还留着一个已经没有邮箱、却要发邮件的人。
 */
export function keepSelectable(selected: string[], recipients: SettlementRecipient[],
  channels: SettlementChannels = EMAIL_ONLY) {
  const selectable = new Set(recipients.filter(recipient => isSelectable(recipient, channels))
    .map(recipient => userKey(recipient.userId)))
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

export const CHANNEL_STATUS_LABELS: Record<SettlementChannelStatus, string> = {
  PENDING: '排队中', SENT: '已发送', FAILED: '失败', SKIPPED: '无邮箱',
}

export const CHANNEL_STATUS_COLORS: Record<SettlementChannelStatus, string> = {
  PENDING: 'default', SENT: 'success', FAILED: 'error', SKIPPED: 'default',
}

/** 批次发了哪几种，给发送记录列表看。V72 之前的批次只有邮件。 */
export function channelsLabel(batch: Pick<SettlementBatch, 'sendEmail' | 'sendInbox'>) {
  const email = batch.sendEmail ?? true
  const inbox = batch.sendInbox ?? false
  return email && inbox ? '站内信 + 邮件' : inbox ? '站内信' : '邮件'
}

export const batchProgress = (batch: Pick<SettlementBatch, 'total' | 'sent' | 'failed'>) =>
  batch.total <= 0 ? 100 : Math.round((batch.sent + batch.failed) / batch.total * 100)

/** 一个批次里发送失败的人，用来「重发失败的」。 */
export const failedUserIds = (batch: SettlementBatch) =>
  (batch.deliveries ?? []).filter(delivery => delivery.status === 'FAILED').map(delivery => userKey(delivery.userId))

/**
 * 「重发失败的」该发哪几种：只补失败的那一种，已经收到站内信的人不再收第二条。
 * 老批次（没有分渠道结果）失败的就是邮件。
 */
export function failedChannels(batch: SettlementBatch): SettlementChannels {
  const failed = (batch.deliveries ?? []).filter(delivery => delivery.status === 'FAILED')
  const email = failed.some(delivery => delivery.emailStatus === 'FAILED'
    || (delivery.emailStatus == null && delivery.inboxStatus == null))
  const inbox = failed.some(delivery => delivery.inboxStatus === 'FAILED')
  return { email, inbox }
}

/** 下载用的 PDF 文件名：去掉 Windows 不允许的字符。 */
export function statementFileName(name: string, from: string, to: string, extension = 'pdf') {
  const safe = `${name}-清算单-${from}至${to}`.replace(/[\\/:*?"<>|]+/g, '_').replace(/\s+/g, ' ').trim()
  return `${safe || '清算单'}.${extension}`
}

/** 打包进 ZIP 的文件名去重：同名时加上序号。 */
export function uniqueNames(names: string[]) {
  const seen = new Map<string, number>()
  return names.map(name => {
    const count = seen.get(name) ?? 0
    seen.set(name, count + 1)
    if (!count) return name
    const dot = name.lastIndexOf('.')
    return dot > 0 ? `${name.slice(0, dot)} (${count + 1})${name.slice(dot)}` : `${name} (${count + 1})`
  })
}

/** 站内信里的清算单地址：{@code /settlements/{id}}，从通知的 actionUrl 里认出来。 */
export function statementIdFromActionUrl(actionUrl?: string | null) {
  const match = /^\/settlements\/(\d+)$/.exec(actionUrl ?? '')
  return match ? match[1] : null
}

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
