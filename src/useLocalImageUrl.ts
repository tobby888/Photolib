import { useEffect, useRef, useState } from 'react'
import type { PreviewUrlRefresher } from './previewImage'

interface SharedImageResource {
  controller: AbortController
  promise: Promise<string>
  objectUrl?: string
  references: number
  releaseTimer?: ReturnType<typeof setTimeout>
}

export type LocalImageState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; url: string }
  | { status: 'error'; message: string }

const resources = new Map<string, SharedImageResource>()

function createResource(remoteUrl: string): SharedImageResource {
  const controller = new AbortController()
  // `mode: 'cors'` is what makes the pixels readable — an <img> without it taints
  // the histogram's canvas. This is the *only* consumer of a preview URL that needs
  // CORS; everything else renders previews as plain no-cors <img> (see
  // src/previewImage.ts for why that is the invariant rather than the opposite one).
  //
  // `cache: 'reload'` is what keeps those two worlds apart, and it is not optional.
  // The HTTP cache is keyed by URL alone, so the entry sitting under this URL is
  // almost always the gallery's no-cors response — stored without
  // Access-Control-Allow-Origin, and rejected here as a CORS failure if we read it.
  // Going straight to the network sends an `Origin` and always gets the header back;
  // the response it writes back into the cache carries the header, so it stays
  // perfectly usable for the no-cors <img> elements too.
  //
  // The cost is one extra download per photo detail view (a preview is ~20 KB,
  // up to imageTargetBytes for photos falling back to the finished image).
  const promise = fetch(remoteUrl, { signal: controller.signal, mode: 'cors', cache: 'reload' })
    .then(response => {
      if (!response.ok) {
        console.error('[preview] 预览图请求失败', { url: remoteUrl, status: response.status })
        throw new Error('预览图没能加载出来')
      }
      return response.blob()
    })
    .then(blob => {
      if (!blob.type.startsWith('image/')) {
        console.error('[preview] 预览图响应不是图片', { url: remoteUrl, type: blob.type })
        throw new Error('预览图文件好像损坏了')
      }
      const objectUrl = URL.createObjectURL(blob)
      const current = resources.get(remoteUrl)
      if (current?.promise === promise) current.objectUrl = objectUrl
      return objectUrl
    })

  return { controller, promise, references: 0 }
}

export function acquireLocalImage(remoteUrl: string) {
  let resource = resources.get(remoteUrl)
  if (!resource) {
    resource = createResource(remoteUrl)
    resources.set(remoteUrl, resource)
  }
  if (resource.releaseTimer) {
    clearTimeout(resource.releaseTimer)
    resource.releaseTimer = undefined
  }
  resource.references += 1
  let released = false

  return {
    promise: resource.promise,
    release() {
      if (released) return
      released = true
      resource.references = Math.max(0, resource.references - 1)
      if (resource.references > 0) return

      // React StrictMode immediately mounts the effect again in development.
      // Deferring cleanup by one task lets that second mount reuse the same
      // request while still releasing the Blob as soon as the page is gone.
      resource.releaseTimer = setTimeout(() => {
        if (resource.references > 0 || resources.get(remoteUrl) !== resource) return
        resource.controller.abort()
        if (resource.objectUrl) URL.revokeObjectURL(resource.objectUrl)
        resources.delete(remoteUrl)
      }, 0)
    },
  }
}

/**
 * @param refresh 取新签名地址的办法。预览图取不回来最常见的原因是签名过期，
 *   所以先重取一次地址再试，真的取不回来才报错——理由见 `src/previewRetry.ts`。
 *   和那边一样只重试一次。
 */
export function useLocalImageUrl(remoteUrl?: string, refresh?: PreviewUrlRefresher): LocalImageState {
  const [state, setState] = useState<LocalImageState>({ status: 'idle' })
  // 用 ref 拿 refresh：调用方通常是内联箭头函数，放进依赖里会让整个 effect 每次
  // 渲染都重来一遍，等于把刚下好的 Blob 扔掉重下。
  const refreshRef = useRef(refresh)
  refreshRef.current = refresh

  useEffect(() => {
    if (!remoteUrl) {
      setState({ status: 'idle' })
      return
    }

    let active = true
    let url = remoteUrl
    let resource = acquireLocalImage(url)
    setState({ status: 'loading' })

    const consume = (retried: boolean) => {
      const attemptedUrl = url
      const attempted = resource
      void attempted.promise.then(value => {
        if (active) setState({ status: 'ready', url: value })
      }).catch(async reason => {
        if (!active || (reason as Error).name === 'AbortError') return
        const message = (reason as Error).message || '预览图没能加载出来'
        if (retried || !refreshRef.current) {
          setState({ status: 'error', message })
          return
        }

        let fresh: string | undefined
        try {
          fresh = (await refreshRef.current()) ?? undefined
        } catch {
          fresh = undefined
        }
        if (!active) return
        // 地址没变说明这次失败与签名过期无关（同一个签名窗口内后端对同一张图签出
        // 的地址是逐字节相同的），再取一次也是同样的结果。
        if (!fresh || fresh === attemptedUrl) {
          setState({ status: 'error', message })
          return
        }
        attempted.release()
        url = fresh
        resource = acquireLocalImage(url)
        consume(true)
      })
    }
    consume(false)

    return () => {
      active = false
      resource.release()
    }
  }, [remoteUrl])

  return state
}
