/**
 * 后台动态多 SMTP 发信邮箱的类型与纯函数。
 * 不依赖 React 和请求层，node 单测直接测（tests/smtpAccounts.test.ts）。
 */

export type SmtpSecurity = 'SSL' | 'STARTTLS' | 'NONE'

export interface SmtpAccount {
  id: number
  name: string
  host: string
  port: number
  username: string
  /** 单独填写的发件地址；为空时使用 username。 */
  fromAddress?: string | null
  /** 实际写入 From 头和 SMTP MAIL FROM 的发件地址。 */
  sender: string
  fromName?: string | null
  security: SmtpSecurity
  enabled: boolean
  sortOrder: number
  passwordEncrypted: boolean
  lastSuccessAt?: string | null
  lastFailureAt?: string | null
  lastError?: string | null
  updatedAt?: string | null
}

export interface SmtpEnvFallback {
  host: string
  port: number
  username: string
  sender: string
  security: SmtpSecurity
}

export interface SmtpOverview {
  accounts: SmtpAccount[]
  envFallback?: SmtpEnvFallback | null
  encrypting: boolean
}

export interface SmtpAccountPayload {
  name: string
  host: string
  port: number
  username: string
  password?: string
  fromAddress?: string
  fromName?: string
  security: SmtpSecurity
  enabled: boolean
}

export const SECURITY_LABELS: Record<SmtpSecurity, string> = {
  SSL: 'SSL / TLS（推荐，常用于 465）',
  STARTTLS: 'STARTTLS（常用于 587）',
  NONE: '明文（不推荐，仅限内网中继）',
}

export const SECURITY_SHORT_LABELS: Record<SmtpSecurity, string> = {
  SSL: 'SSL',
  STARTTLS: 'STARTTLS',
  NONE: '明文',
}

export function defaultPortForSecurity(security: SmtpSecurity): number {
  if (security === 'SSL') return 465
  if (security === 'STARTTLS') return 587
  return 25
}

/** 切换加密方式时，若端口仍是原加密方式的默认端口，则自动切到新的默认端口。 */
export function portAfterSecurityChange(currentPort: number | undefined, prev: SmtpSecurity, next: SmtpSecurity): number {
  if (!currentPort || currentPort === defaultPortForSecurity(prev)) {
    return defaultPortForSecurity(next)
  }
  return currentPort
}

/** 将第 index 项上下移动 delta 位，返回重排后的 ID 列表；越界返回 null。 */
export function moveAccountIds(accounts: Pick<SmtpAccount, 'id'>[], index: number, delta: -1 | 1): number[] | null {
  const target = index + delta
  if (index < 0 || index >= accounts.length || target < 0 || target >= accounts.length) return null
  const ids = accounts.map(item => item.id)
  const [moved] = ids.splice(index, 1)
  ids.splice(target, 0, moved)
  return ids
}

/** 判断值看起来是否像邮箱地址（粗略用于表单提醒，最终校验在服务端）。 */
export function looksLikeEmail(value?: string | null): boolean {
  const text = (value ?? '').trim()
  const at = text.indexOf('@')
  return at > 0 && at < text.length - 1 && !/\s/.test(text)
}

/** 当 SMTP 登录名不是邮箱地址（如 SendGrid 的 apikey、SES 的 IAM 账号）且未填发件地址时提醒填写。 */
export function needsSeparateFromAddress(username?: string | null, fromAddress?: string | null): boolean {
  const user = (username ?? '').trim()
  const from = (fromAddress ?? '').trim()
  return user.length > 0 && !looksLikeEmail(user) && from.length === 0
}

export type SmtpHealthKind = 'disabled' | 'healthy' | 'failed' | 'idle'

/** 根据启用状态与最近成功 / 失败时间计算发信邮箱当前状态。 */
export function accountHealth(account: Pick<SmtpAccount, 'enabled' | 'lastSuccessAt' | 'lastFailureAt'>): SmtpHealthKind {
  if (!account.enabled) return 'disabled'
  if (!account.lastSuccessAt && !account.lastFailureAt) return 'idle'
  if (account.lastFailureAt && (!account.lastSuccessAt || account.lastFailureAt >= account.lastSuccessAt)) {
    return 'failed'
  }
  return 'healthy'
}
