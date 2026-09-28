import { useEffect, useState } from 'react'
import { api } from './api'
import { DEFAULT_UPLOAD_LIMITS, normalizeUploadLimits, type UploadLimits } from './uploadLimits'

/** 管理员改了限额，别的标签页最多这么久之后跟上；本页保存时立即生效（见 publishUploadLimits）。 */
const CACHE_TTL_MS = 5 * 60 * 1000

let cached: { limits: UploadLimits; loadedAt: number } | null = null
let pending: Promise<UploadLimits> | null = null
const listeners = new Set<(limits: UploadLimits) => void>()

/**
 * 取当前生效的上传限额。整页共用一次请求；取失败时退回内置默认值但不缓存，
 * 下一次用到时再试——宁可提示得旧一点，也不能因为这条请求失败就挡住上传。
 */
export function loadUploadLimits(): Promise<UploadLimits> {
  if (cached && Date.now() - cached.loadedAt < CACHE_TTL_MS) return Promise.resolve(cached.limits)
  pending ??= api<unknown>({ url: '/upload-limits' })
    .then(raw => publishUploadLimits(normalizeUploadLimits(raw)))
    .catch(() => cached?.limits ?? DEFAULT_UPLOAD_LIMITS)
    .finally(() => { pending = null })
  return pending
}

/** 管理员保存之后调用，让同一页里正在显示的提示立刻换成新值。 */
export function publishUploadLimits(limits: UploadLimits) {
  cached = { limits, loadedAt: Date.now() }
  listeners.forEach(listener => listener(limits))
  return limits
}

/** 组件里用：先给缓存值（没有就是默认值），接口回来后自动刷新。 */
export function useUploadLimits(): UploadLimits {
  const [limits, setLimits] = useState<UploadLimits>(() => cached?.limits ?? DEFAULT_UPLOAD_LIMITS)
  useEffect(() => {
    let active = true
    const listener = (next: UploadLimits) => { if (active) setLimits(next) }
    listeners.add(listener)
    void loadUploadLimits().then(listener)
    return () => {
      active = false
      listeners.delete(listener)
    }
  }, [])
  return limits
}
