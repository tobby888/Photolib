import { createContext, useContext, useEffect, useState, type PropsWithChildren } from 'react'
import { api, type LoginResult } from './api'
import type { User } from './types'

interface AuthContextValue {
  user: User | null
  login: (username: string, password: string) => Promise<LoginResult>
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
  const login = async (username: string, password: string) => {
    const result = await api<LoginResult>({ method: 'POST', url: '/auth/login', data: { username, password } })
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
