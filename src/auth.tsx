import { createContext, useCallback, useContext, useEffect, useState, type PropsWithChildren } from 'react'
import {
  api, MFA_ENROLLMENT_REQUIRED_EVENT, SESSION_EXPIRED_EVENT, type LoginResult, type PasswordLoginResult,
} from './api'
import type { User } from './types'

interface AuthContextValue {
  user: User | null
  /**
   * 后端确认过这个会话（`/auth/me` 应答了，或者刚刚登录成功）才为 true。
   *
   * `user` 是乐观的：它从 localStorage 直接读出来，好让成员刷新工作台时不闪一下
   * 登录页。但缓存里的身份可能早就失效了——令牌过期、会话被清、账号被删都不会
   * 顺手把 localStorage 擦干净。所以"把已登录的人弹去别处"这种判断必须用这个标志，
   * 不能只看 `user`：公开页（报名页）曾经因此把没有账号的访客也弹去了登录页。
   */
  sessionVerified: boolean
  /**
   * 密码登录。两步验证对账号生效时返回的是票据而不是会话：此时什么都不写入，
   * 由登录页完成第二步后再调 `updateSession`。
   */
  login: (identifier: string, password: string) => Promise<PasswordLoginResult>
  updateSession: (result: LoginResult) => void
  updateUser: (updates: Partial<User>) => void
  /** 重新向后端取一次登录身份（两步验证状态变了之后调用）。 */
  refreshUser: () => Promise<User | null>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

function readUser(): User | null {
  try {
    // 没有令牌就不算有身份：这种状态下每个接口都会 401，把缓存里的用户当成"已登录"
    // 只会让路由做出错误的跳转。两项一起写入、一起清除，落单的那份就是脏数据。
    if (!localStorage.getItem('photolib_access_token')) return null
    const raw = localStorage.getItem('photolib_user')
    return raw ? JSON.parse(raw) as User : null
  } catch {
    return null
  }
}

export function AuthProvider({ children }: PropsWithChildren) {
  const [user, setUser] = useState<User | null>(readUser)
  const [sessionVerified, setSessionVerified] = useState(false)
  const storeUser = useCallback((current: User) => {
    localStorage.setItem('photolib_user', JSON.stringify(current))
    setUser(current)
  }, [])
  useEffect(() => {
    // A failed token refresh (interceptor) means the session is unrecoverable.
    // Clear React state so route guards send the user to /login and keep them there
    // instead of bouncing between the shell and the login page.
    const onExpired = () => {
      setUser(null)
      setSessionVerified(false)
    }
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired)
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired)
  }, [])
  const refreshUser = useCallback(async () => {
    if (!localStorage.getItem('photolib_access_token')) return null
    try {
      const current = await api<User>({ url: '/auth/me' })
      storeUser(current)
      setSessionVerified(true)
      return current
    } catch {
      return null
    }
  }, [storeUser])
  useEffect(() => {
    void refreshUser()
  }, [refreshUser])
  useEffect(() => {
    // 会话被后端关进"先绑定两步验证"时，取一次最新身份，外壳会据此跳去绑定页。
    const onEnrollmentRequired = () => void refreshUser()
    window.addEventListener(MFA_ENROLLMENT_REQUIRED_EVENT, onEnrollmentRequired)
    return () => window.removeEventListener(MFA_ENROLLMENT_REQUIRED_EVENT, onEnrollmentRequired)
  }, [refreshUser])
  const updateSession = (result: LoginResult) => {
    localStorage.setItem('photolib_access_token', result.accessToken)
    storeUser(result.user)
    setSessionVerified(true)
  }
  // 保持引用稳定：弹窗会把它放进 effect 的依赖里，每次渲染换一个新函数会让 effect 反复执行。
  const updateUser = useCallback((updates: Partial<User>) => {
    setUser(current => {
      if (!current) return current
      const next = { ...current, ...updates }
      localStorage.setItem('photolib_user', JSON.stringify(next))
      return next
    })
  }, [])
  const login = async (identifier: string, password: string) => {
    const result = await api<PasswordLoginResult>({
      method: 'POST', url: '/auth/login', data: { username: identifier, password },
    })
    if (!result.mfaRequired) updateSession(result)
    return result
  }
  const logout = async () => {
    try { await api({ method: 'POST', url: '/auth/logout' }) } finally {
      localStorage.removeItem('photolib_access_token')
      localStorage.removeItem('photolib_user')
      setUser(null)
      setSessionVerified(false)
    }
  }
  return <AuthContext.Provider value={{ user, sessionVerified, login, updateSession, updateUser, refreshUser, logout }}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) throw new Error('AuthProvider missing')
  return value
}
