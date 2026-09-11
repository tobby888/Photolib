import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createResumeRefresh } from './previewFreshness'

export function useLoad<T>(loader: () => Promise<T>, initial: T, deps: unknown[] = []) {
  const [data, setData] = useState(initial)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const loaderRef = useRef(loader)
  const requestSequence = useRef(0)
  loaderRef.current = loader
  // `quiet` 是后台刷新：不亮加载态、失败也不接管画面。用户没有主动要求这次
  // 请求，把已经渲染好的一屏换成骨架屏或错误页只会让人莫名其妙——息屏期间
  // 断网就会走到失败分支，而那时原来那一屏仍然是他想看的东西。
  const run = useCallback(async (quiet: boolean) => {
    const requestId = ++requestSequence.current
    if (!quiet) {
      setLoading(true)
      setError('')
    }
    try {
      const next = await loaderRef.current()
      if (requestId !== requestSequence.current) return
      setData(next)
      setError('')
    } catch (reason) {
      if (requestId === requestSequence.current && !quiet) setError((reason as Error).message)
    } finally {
      if (requestId === requestSequence.current && !quiet) setLoading(false)
    }
  }, [])
  const load = useCallback(() => run(false), [run])
  const refresh = useCallback(() => run(true), [run])
  useEffect(() => {
    void load()
    return () => { requestSequence.current += 1 }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)
  return { data, setData, loading, error, reload: load, refresh }
}

/**
 * 页面回到前台时重取一次数据，把过期的签名预览地址换掉。
 *
 * 每个会渲染 `thumbnailUrl` 的页面都要挂上，理由见 `src/previewFreshness.ts`：
 * 预签名地址只活 10 分钟，而 iOS Safari 恢复挂起的标签页时不重跑 effect，页面
 * 会拿着一批过期地址去请求图片，整屏变成占位图。
 *
 * 传进来的 `refresh` 应当是 `useLoad` 的 `refresh`（后台刷新）而不是 `reload`：
 * 用户切回来看到的应该是原来那一屏被悄悄换新，而不是先闪一下骨架屏。
 */
export function useRefreshOnResume(refresh: () => void) {
  useEffect(() => {
    const resume = createResumeRefresh(refresh, {
      initiallyHidden: document.visibilityState === 'hidden',
    })
    const onVisibilityChange = () => resume.visibilityChanged(
      document.visibilityState === 'hidden' ? 'hidden' : 'visible')
    const onPageShow = (event: PageTransitionEvent) => resume.pageShown(event.persisted)
    document.addEventListener('visibilitychange', onVisibilityChange)
    window.addEventListener('pageshow', onPageShow)
    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange)
      window.removeEventListener('pageshow', onPageShow)
    }
  }, [refresh])
}

/**
 * 引用恒定、但每次调用都执行最新实现的回调。传给 `memo` 过的子组件（例如选题相册里
 * 的图片卡片）时用它：普通闭包每次渲染都是新函数，会让 memo 形同虚设，而 `useCallback`
 * 又得把读到的状态全列进依赖，照样每次都变。只能在事件处理里调用，不要在渲染期间调用。
 */
export function useStableCallback<Args extends unknown[], Result>(
  callback: (...args: Args) => Result,
): (...args: Args) => Result {
  const latest = useRef(callback)
  useLayoutEffect(() => {
    latest.current = callback
  })
  return useCallback((...args: Args) => latest.current(...args), [])
}
