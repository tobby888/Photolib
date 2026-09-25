import axios, { type AxiosRequestConfig } from 'axios'
import { isEnvelope, noCacheHeaders } from './apiEnvelope'
import type { PageData, User } from './types'

export interface ApiErrorDetail {
  field?: string
  message: string
}

interface Envelope<T> {
  code: string
  message: string
  data: T
  details?: ApiErrorDetail[]
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly code: string,
    readonly status?: number,
    readonly details: ApiErrorDetail[] = [],
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  withCredentials: true,
  timeout: 20000,
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('photolib_access_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// Broadcast that the session is unrecoverable (refresh failed) so AuthProvider can
// clear React state. Clearing only localStorage here leaves the in-memory `user`
// stale, which makes the /login guard bounce straight back to the shell.
export const SESSION_EXPIRED_EVENT = 'photolib:session-expired'

/**
 * 被踢回登录页时要给人看的一句话。两步验证对账号生效后，之前签发、没过第二步的会话
 * 会被后端作废——不说一声的话，用户只会觉得"莫名其妙被登出了"。登录页读一次就清掉。
 */
export const LOGIN_NOTICE_KEY = 'photolib_login_notice'

function clearSession(notice?: string) {
  localStorage.removeItem('photolib_access_token')
  localStorage.removeItem('photolib_user')
  if (notice) {
    try { sessionStorage.setItem(LOGIN_NOTICE_KEY, notice) } catch { /* 隐私模式等：提示丢了也不影响登出 */ }
  }
  window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT))
}

/**
 * 被强制两步验证却还没绑定时，后端对几乎所有接口回 `MFA_ENROLLMENT_REQUIRED`。
 * 典型场景是管理员刚打开全站开关、成员手上还开着页面：`AuthProvider` 听到这个事件后
 * 重新取一次身份，外壳据此把人带去绑定页。
 */
export const MFA_ENROLLMENT_REQUIRED_EVENT = 'photolib:mfa-enrollment-required'

/**
 * 敏感操作（删除图片 / 选题 / 需求、系统管理面板）要求先再验证一次两步验证。
 * 外壳注册一个处理器弹出验证框；验证成功就把原请求原样重发，调用方完全无感。
 */
type StepUpHandler = () => Promise<boolean>
let stepUpHandler: StepUpHandler | null = null
let steppingUp: Promise<boolean> | null = null

export function setStepUpHandler(handler: StepUpHandler) {
  stepUpHandler = handler
  return () => {
    if (stepUpHandler === handler) stepUpHandler = null
  }
}

/** 下载接口的错误体也是 Blob，要读出来才知道错误码。 */
async function errorCode(data: unknown): Promise<string | undefined> {
  if (data instanceof Blob) {
    try { return (JSON.parse(await data.text()) as Envelope<unknown>).code } catch { return undefined }
  }
  return (data as Envelope<unknown> | undefined)?.code
}

let refreshing: Promise<string> | null = null
http.interceptors.response.use(
  (response) => response,
  async (error) => {
    const original = error.config as AxiosRequestConfig & { _retry?: boolean; _stepUp?: boolean }
    if (error.response?.status === 403) {
      const code = await errorCode(error.response.data)
      if (code === 'STEP_UP_REQUIRED' && stepUpHandler && !original._stepUp) {
        original._stepUp = true
        // 同一时刻的几个请求（比如管理面板一进来的几次加载）共用一个验证框。
        steppingUp ??= stepUpHandler().finally(() => { steppingUp = null })
        if (await steppingUp) return http(original)
      }
      if (code === 'MFA_ENROLLMENT_REQUIRED') window.dispatchEvent(new Event(MFA_ENROLLMENT_REQUIRED_EVENT))
      return Promise.reject(error)
    }
    if (error.response?.status !== 401 || original._retry || original.url?.includes('/auth/')) {
      return Promise.reject(error)
    }
    original._retry = true
    try {
      refreshing ??= http.post<Envelope<{ accessToken: string }>>('/auth/refresh')
        .then(({ data }) => {
          localStorage.setItem('photolib_access_token', data.data.accessToken)
          return data.data.accessToken
        }).finally(() => { refreshing = null })
      const token = await refreshing
      original.headers = { ...original.headers, Authorization: `Bearer ${token}` }
      return http(original)
    } catch (refreshError) {
      const body = axios.isAxiosError(refreshError) ? refreshError.response?.data as Envelope<unknown> : undefined
      clearSession(body?.code === 'MFA_SESSION_UNVERIFIED' ? body.message : undefined)
      return Promise.reject(error)
    }
  },
)

export async function api<T>(config: AxiosRequestConfig): Promise<T> {
  let response
  try {
    response = await http.request<Envelope<T>>({
      ...config,
      headers: { ...noCacheHeaders(config.method), ...config.headers },
    })
  } catch (error) {
    if (axios.isAxiosError(error)) {
      const body = error.response?.data as Envelope<unknown> | undefined
      const detail = body?.details?.map((item) => item.message).join('；')
      throw new ApiError(
        detail || body?.message || (error.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : '网络连接失败'),
        body?.code || (error.code === 'ECONNABORTED' ? 'REQUEST_TIMEOUT' : 'NETWORK_ERROR'),
        error.response?.status,
        body?.details || [],
      )
    }
    throw error
  }
  // 见 apiEnvelope.ts：拿到的不是本系统的信封时报一个可重试的错误，不把 undefined 交给页面。
  if (!isEnvelope(response.status, response.data)) {
    throw new ApiError('服务器返回的数据无法识别，请重试', 'INVALID_RESPONSE', response.status)
  }
  return response.data?.data as T
}

export const qs = (values: Record<string, unknown>) => Object.fromEntries(
  Object.entries(values).filter(([, value]) => value !== undefined && value !== null && value !== ''),
)

export const emptyPage = <T>(): PageData<T> => ({ items: [], page: 1, pageSize: 20, total: 0, totalPages: 0 })

export interface LoginResult {
  accessToken: string
  expiresIn: number
  mustChangePassword: boolean
  user: User
}

/**
 * 密码登录的应答。`mfaRequired` 为真时还没有会话：只有一张票据，要拿它去完成第二步
 * （`mfaMethods` 是这个账号绑过的验证方式）。
 */
export type PasswordLoginResult =
  | (LoginResult & { mfaRequired: false })
  | { mfaRequired: true; mfaTicket: string; mfaMethods: ('TOTP' | 'WEBAUTHN')[]; mfaExpiresIn: number }
