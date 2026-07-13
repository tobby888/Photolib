import { createContext, useContext, useEffect, useState, type PropsWithChildren } from 'react'
import { api, SESSION_EXPIRED_EVENT, type LoginResult } from './api'
import type { User } from './types'

interface AuthContextValue {
  user: User | null
  login: (identifier: string, password: string) => Promise<LoginResult>
  updateSession: (result: LoginResult) => void
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

function readUser(): User | null {
  try {
    const raw = localStorage.getItem('photolib_user')
    return raw ? JSON.parse(raw) as User : null
  } catch {
    return null
  }
}

export function AuthProvider({ children }: PropsWithChildren) {
  const [user, setUser] = useState<User | null>(readUser)
  useEffect(() => {
    // A failed token refresh (interceptor) means the session is unrecoverable.
    // Clear React state so route guards send the user to /login and keep them there
    // instead of bouncing between the shell and the login page.
    const onExpired = () => setUser(null)
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired)
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired)
  }, [])
  useEffect(() => {
    if (!localStorage.getItem('photolib_access_token')) return
    void api<User>({ url: '/auth/me' }).then(current => {
      localStorage.setItem('photolib_user', JSON.stringify(current))
      setUser(current)
    }).catch(() => undefined)
  }, [])
  const updateSession = (result: LoginResult) => {
    localStorage.setItem('photolib_access_token', result.accessToken)
    localStorage.setItem('photolib_user', JSON.stringify(result.user))
    setUser(result.user)
  }
  const login = async (identifier: string, password: string) => {
    const result = await api<LoginResult>({ method: 'POST', url: '/auth/login', data: { username: identifier, password } })
    updateSession(result)
    return result
  }
  const logout = async () => {
    try { await api({ method: 'POST', url: '/auth/logout' }) } finally {
      localStorage.removeItem('photolib_access_token')
      localStorage.removeItem('photolib_user')
      setUser(null)
    }
  }
  return <AuthContext.Provider value={{ user, login, updateSession, logout }}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) throw new Error('AuthProvider missing')
  return value
}
