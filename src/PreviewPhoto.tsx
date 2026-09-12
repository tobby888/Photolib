import { Image, type ImageProps } from 'antd'
import { useEffect, useRef, useState, type ImgHTMLAttributes } from 'react'
import { PREVIEW_CROSS_ORIGIN, type PreviewUrlRefresher } from './previewImage'
import { attemptFor, loadFailed, refreshed, startAttempt, type PreviewAttempt } from './previewRetry'

/**
 * 渲染签名预览地址的统一入口：CORS 模式由它统一决定（`PREVIEW_CROSS_ORIGIN`，
 * 现在是"不用 CORS"，原因见 `src/previewImage.ts`），加载失败时重取一次地址再试
 * （原因见 `src/previewRetry.ts`），两次都不成才让占位图顶上。
 *
 * 页面不要再直接写 `<Image src={photo.thumbnailUrl}>`——`tests/previewCorsMode.test.ts`
 * 会拦下这种写法。
 */

export interface PreviewRetryState {
  src: string
  onError: () => void
  /**
   * 是否已经放弃。占位图只在这之后才允许露面：重试期间就把 `fallback` 交出去，
   * 会先闪一下不相干的占位画面，再闪回真图。
   */
  failed: boolean
}

export function usePreviewRetry(src: string, refresh?: PreviewUrlRefresher): PreviewRetryState {
  const [stored, setStored] = useState<PreviewAttempt>(() => startAttempt(src))
  // 在渲染里对齐，比放进 useEffect 少一帧"拿旧地址渲染"的中间态。
  const attempt = attemptFor(stored, src)
  if (attempt !== stored) setStored(attempt)

  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])

  const onError = () => {
    const next = loadFailed(attempt, !!refresh)
    if (next === attempt) return
    setStored(next)
    if (next.phase !== 'retrying' || !refresh) return
    const settle = (fresh: string | null | undefined) => {
      if (!mounted.current) return
      // 期间页面可能已经换了新数据，那一份地址更新，别用旧结果把它盖掉。
      setStored(current => (current.origin === next.origin ? refreshed(current, fresh) : current))
    }
    void refresh().then(settle, () => settle(undefined))
  }

  return { src: attempt.src, onError, failed: attempt.phase === 'failed' }
}

export interface PreviewPhotoProps
  extends Omit<ImageProps, 'src' | 'crossOrigin' | 'onError' | 'fallback'> {
  src: string
  /** 取新签名地址的办法；不传就退回"失败即占位图"的老行为。 */
  refresh?: PreviewUrlRefresher
  /** 两次都取不回来之后顶上的占位图。 */
  fallback?: string
}

export default function PreviewPhoto({ src, refresh, fallback, ...rest }: PreviewPhotoProps) {
  const retry = usePreviewRetry(src, refresh)
  return <Image {...rest} src={retry.src} crossOrigin={PREVIEW_CROSS_ORIGIN}
    onError={retry.onError} fallback={retry.failed ? fallback : undefined} />
}

export interface PreviewPhotoImgProps
  extends Omit<ImgHTMLAttributes<HTMLImageElement>, 'src' | 'crossOrigin' | 'onError'> {
  src: string
  refresh?: PreviewUrlRefresher
  fallback?: string
}

/** 裸 `<img>` 版本，给不能接受 antd 多包一层 DOM 的位置用。 */
export function PreviewPhotoImg({ src, refresh, fallback, ...rest }: PreviewPhotoImgProps) {
  const retry = usePreviewRetry(src, refresh)
  const showFallback = retry.failed && !!fallback
  return <img {...rest} src={showFallback ? fallback : retry.src}
    crossOrigin={PREVIEW_CROSS_ORIGIN}
    // 占位图自己再失败时不能绕回来，否则就是一个死循环。
    onError={showFallback ? undefined : retry.onError} />
}
