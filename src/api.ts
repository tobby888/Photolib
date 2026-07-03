import axios, { type AxiosRequestConfig } from 'axios'
import type { PageData, User } from './types'

interface Envelope<T> {
  code: string
  message: string
  data: T
  details?: { field?: string; message: string }[]
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

let refreshing: Promise<string> | null = null
http.interceptors.response.use(
  (response) => response,
  async (error) => {
    const original = error.config as AxiosRequestConfig & { _retry?: boolean }
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
    } catch {
      localStorage.removeItem('photolib_access_token')
      localStorage.removeItem('photolib_user')
      window.location.href = '/#/login'
      return Promise.reject(error)
    }
  },
)

export async function api<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const { data } = await http.request<Envelope<T>>(config)
    return data.data
  } catch (error) {
    if (axios.isAxiosError(error)) {
      const body = error.response?.data as Envelope<unknown> | undefined
      const detail = body?.details?.map((item) => item.message).join('；')
      throw new Error(detail || body?.message || (error.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : '网络连接失败'))
    }
    throw error
  }
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
