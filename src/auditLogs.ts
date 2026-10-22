/**
 * 操作日志页面用的纯函数：把 User-Agent 翻成「Chrome 130 · Windows」，把详情 JSON 拆成
 * 请求参数、上传文件和处理器附加的信息。不依赖 React 和请求层，node 单测可以直接跑。
 */

export interface DeviceInfo {
  /** 浏览器或客户端，如「Chrome 130」「微信」；认不出时是 UA 的第一段。 */
  client?: string
  /** 系统，如「Windows」「iOS」。 */
  os?: string
  /** 合起来的一行，空 UA 时为「未知设备」。 */
  label: string
}

const CLIENTS: Array<[RegExp, string, boolean]> = [
  // [匹配, 名称, 是否带主版本号]。顺序有讲究：内置浏览器的 UA 里也带 Chrome/Safari 字样，要先认它们。
  [/wxwork\/([\d.]+)/i, '企业微信', false],
  [/MicroMessenger\/([\d.]+)/i, '微信', false],
  [/DingTalk\/([\d.]+)/i, '钉钉', false],
  [/QQBrowser\/([\d.]+)/i, 'QQ 浏览器', false],
  [/\bQQ\/([\d.]+)/i, 'QQ', false],
  [/EdgA?\/([\d.]+)/, 'Edge', true],
  [/OPR\/([\d.]+)/, 'Opera', true],
  [/Firefox\/([\d.]+)/, 'Firefox', true],
  [/CriOS\/([\d.]+)/, 'Chrome', true],
  [/Chrome\/([\d.]+)/, 'Chrome', true],
  [/Version\/([\d.]+).*Safari\//, 'Safari', true],
]

const SYSTEMS: Array<[RegExp, string]> = [
  [/HarmonyOS|OpenHarmony/i, '鸿蒙'],
  [/iPad/, 'iPadOS'],
  [/iPhone|iPod/, 'iOS'],
  [/Android/, 'Android'],
  [/Windows NT/, 'Windows'],
  [/Mac OS X|Macintosh/, 'macOS'],
  [/CrOS/, 'ChromeOS'],
  [/Linux/, 'Linux'],
]

export function describeUserAgent(userAgent?: string | null): DeviceInfo {
  const ua = userAgent?.trim()
  if (!ua) return { label: '未知设备' }
  let client: string | undefined
  for (const [pattern, name, versioned] of CLIENTS) {
    const match = pattern.exec(ua)
    if (match) {
      client = versioned ? `${name} ${match[1].split('.')[0]}` : name
      break
    }
  }
  const os = SYSTEMS.find(([pattern]) => pattern.test(ua))?.[1]
  if (!client && !os) {
    // 不是浏览器：curl、脚本、MCP 客户端……显示第一段「产品/版本」。
    client = ua.split(/\s+/)[0].replace('/', ' ').slice(0, 40)
  }
  return { client, os, label: [client, os].filter(Boolean).join(' · ') }
}

export interface AuditFile {
  field?: string
  name?: string
  size?: number
  contentType?: string
}

export interface AuditDetail {
  path?: string
  status?: number
  params?: Record<string, unknown>
  body?: unknown
  files: AuditFile[]
  /** 处理器自己附加的上下文（登录的目标账号、改了哪些字段……）。 */
  extra: Record<string, unknown>
}

const KNOWN_KEYS = new Set(['path', 'status', 'params', 'body', 'files'])

export function parseAuditDetail(detailJson?: string | null): AuditDetail {
  const empty: AuditDetail = { files: [], extra: {} }
  if (!detailJson) return empty
  let raw: unknown
  try {
    raw = JSON.parse(detailJson)
  } catch {
    return empty
  }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return empty
  const record = raw as Record<string, unknown>
  const extra: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(record)) {
    if (!KNOWN_KEYS.has(key)) extra[key] = value
  }
  const params = record.params && typeof record.params === 'object' && !Array.isArray(record.params)
    ? record.params as Record<string, unknown> : undefined
  return {
    path: typeof record.path === 'string' ? record.path : undefined,
    status: typeof record.status === 'number' ? record.status : undefined,
    params: params && Object.keys(params).length > 0 ? params : undefined,
    body: record.body ?? undefined,
    files: Array.isArray(record.files) ? record.files as AuditFile[] : [],
    extra,
  }
}

/** 一个对象是不是"平"的（值都是标量），平的用键值表展示，嵌套的用缩进 JSON。 */
export function isFlatRecord(value: unknown): value is Record<string, string | number | boolean | null> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  return Object.values(value).every(item => item === null || ['string', 'number', 'boolean'].includes(typeof item))
}

export function formatDuration(ms?: number | null) {
  if (ms === undefined || ms === null) return '-'
  if (ms < 1000) return `${ms} 毫秒`
  return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)} 秒`
}
