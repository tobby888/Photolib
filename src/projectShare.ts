import { api } from './api'
import type { EntityId, PageData, ShareGuestAccess, ShareGuestSession, SharePhoto } from './types'

/** 与后端 `ProjectSharePublicController.SESSION_HEADER` 必须一致。 */
export const SHARE_SESSION_HEADER = 'X-Share-Session'

const storageKey = (token: string) => `photolib_share_session_${token}`

interface StoredSession {
  sessionToken: string
  expiresAt: string
}

/**
 * 会话存在 localStorage 而不是内存：访客点开一张图、按了返回、或者手机上把页面
 * 切走再回来，都不该重新输一次密码。存的只是一把随机凭据，权限每次请求由服务端
 * 按链接现读（见 `ProjectShareService` 类注释第 2 条），所以这里缓存不了权限。
 */
export function readStoredShareSession(token: string): string | null {
  try {
    const raw = localStorage.getItem(storageKey(token))
    if (!raw) return null
    const stored = JSON.parse(raw) as StoredSession
    if (!stored?.sessionToken) return null
    if (stored.expiresAt && new Date(stored.expiresAt).getTime() <= Date.now()) {
      localStorage.removeItem(storageKey(token))
      return null
    }
    return stored.sessionToken
  } catch {
    // 隐私模式或损坏的值：当成没有会话，让访客重新输密码即可。
    return null
  }
}

export function storeShareSession(token: string, session: ShareGuestSession) {
  try {
    localStorage.setItem(storageKey(token), JSON.stringify({
      sessionToken: session.sessionToken,
      expiresAt: session.expiresAt,
    } satisfies StoredSession))
  } catch {
    // 存不下也能用完这一次，只是刷新后要重新输密码。
  }
}

export function clearShareSession(token: string) {
  try {
    localStorage.removeItem(storageKey(token))
  } catch {
    // 同上：清不掉也不影响这次请求，服务端仍会拒绝失效会话。
  }
}

const guest = (session: string) => ({ headers: { [SHARE_SESSION_HEADER]: session } })

export const shareApi = {
  greet: (token: string) =>
    api<{ requiresPassword: boolean }>({ url: `/public/shares/${token}` }),

  openSession: (token: string, password: string) =>
    api<ShareGuestSession>({ method: 'POST', url: `/public/shares/${token}/sessions`, data: { password } }),

  access: (token: string, session: string) =>
    api<ShareGuestAccess>({ url: `/public/shares/${token}/access`, ...guest(session) }),

  photos: (token: string, session: string, params: { page: number; pageSize: number; keyword?: string }) =>
    api<PageData<SharePhoto>>({ url: `/public/shares/${token}/photos`, params, ...guest(session) }),

  downloadUrl: (token: string, session: string, photoId: EntityId) =>
    api<{ downloadUrl: string }>({
      method: 'POST', url: `/public/shares/${token}/photos/${photoId}/download-url`, ...guest(session),
    }),

  adopt: (token: string, session: string, photoId: EntityId) =>
    api<{ photoId: EntityId; adopted: boolean }>({
      method: 'POST', url: `/public/shares/${token}/photos/${photoId}/adoption`, ...guest(session),
    }),

  cancelAdoption: (token: string, session: string, photoId: EntityId) =>
    api<{ photoId: EntityId; adopted: boolean }>({
      method: 'DELETE', url: `/public/shares/${token}/photos/${photoId}/adoption`, ...guest(session),
    }),
}

interface ShareExportJobView {
  job: {
    status: 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED'
    errorMessage?: string
  }
  downloadUrl?: string
}

const wait = (milliseconds: number) => new Promise(resolve => window.setTimeout(resolve, milliseconds))

/**
 * 分享链接的打包下载，和站内的 `preparePhotoBatchDownload` 是同一套任务与轮询，
 * 只是走访客通道：任务归属认的是分享链接而不是账号。
 */
export async function prepareSharedBatchDownload(
  token: string, session: string, photoIds: EntityId[],
): Promise<string | null> {
  const job = await api<{ id: string }>({
    method: 'POST',
    url: `/public/shares/${token}/batch-downloads`,
    data: { photoIds },
    ...guest(session),
  })
  for (let attempt = 0; attempt < 60; attempt += 1) {
    const result = await api<ShareExportJobView>({
      url: `/public/shares/${token}/batch-downloads/${job.id}`, ...guest(session),
    })
    if (result.job.status === 'SUCCEEDED' && result.downloadUrl) return result.downloadUrl
    if (result.job.status === 'FAILED') {
      throw new Error(result.job.errorMessage
        || '这批图片没能打包成 ZIP。可能其中有图片已被移出项目或删除，重新选一次再试。')
    }
    await wait(1000)
  }
  return null
}
