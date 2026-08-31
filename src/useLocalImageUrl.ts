import { useEffect, useState } from 'react'

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
  // `mode: 'cors'` is what makes the pixels readable (an <img> without it taints
  // the histogram's canvas), and it is why every other element rendering the same
  // preview URL must request it as `crossOrigin="anonymous"` — a no-CORS response
  // for this URL sitting in the HTTP cache is served back here without
  // Access-Control-Allow-Origin and fails. See src/previewImage.ts.
  const promise = fetch(remoteUrl, { signal: controller.signal, mode: 'cors' })
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

export function useLocalImageUrl(remoteUrl?: string): LocalImageState {
  const [state, setState] = useState<LocalImageState>({ status: 'idle' })

  useEffect(() => {
    if (!remoteUrl) {
      setState({ status: 'idle' })
      return
    }

    let active = true
    setState({ status: 'loading' })
    const resource = acquireLocalImage(remoteUrl)
    void resource.promise.then(url => {
      if (active) setState({ status: 'ready', url })
    }).catch(reason => {
      if (!active || (reason as Error).name === 'AbortError') return
      setState({ status: 'error', message: (reason as Error).message || '预览图没能加载出来' })
    })

    return () => {
      active = false
      resource.release()
    }
  }, [remoteUrl])

  return state
}
