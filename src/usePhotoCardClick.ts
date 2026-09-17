import { useCallback, useEffect, useRef } from 'react'

/**
 * 区分图片卡片的单击和双击。单击延迟执行，双击时取消待执行的单击，
 * 避免浏览器先派发 click、再派发 dblclick 导致双击顺手把图片选中或取消。
 */
export function usePhotoCardClick<T>(
  onSingleClick: (target: T) => void,
  onDoubleClick?: (target: T) => void,
  delay = 250,
) {
  const singleRef = useRef(onSingleClick)
  const doubleRef = useRef(onDoubleClick)
  singleRef.current = onSingleClick
  doubleRef.current = onDoubleClick
  const timerRef = useRef<number | null>(null)
  const pendingRef = useRef<T | null>(null)

  const cancel = useCallback(() => {
    if (timerRef.current !== null) window.clearTimeout(timerRef.current)
    timerRef.current = null
    pendingRef.current = null
  }, [])

  useEffect(() => cancel, [cancel])

  const flushPending = useCallback(() => {
    if (timerRef.current === null) return
    window.clearTimeout(timerRef.current)
    timerRef.current = null
    const pending = pendingRef.current
    pendingRef.current = null
    if (pending !== null) singleRef.current(pending)
  }, [])

  const click = useCallback((target: T) => {
    if (timerRef.current !== null) {
      if (pendingRef.current === target) {
        window.clearTimeout(timerRef.current)
        timerRef.current = null
      } else {
        // 快速连续点选不同图片时，先把上一张落实，避免两次单击互相取消。
        flushPending()
      }
    }
    pendingRef.current = target
    timerRef.current = window.setTimeout(() => {
      timerRef.current = null
      pendingRef.current = null
      singleRef.current(target)
    }, delay)
  }, [delay, flushPending])

  const doubleClick = useCallback((target: T) => {
    if (pendingRef.current === target) cancel()
    const handler = doubleRef.current
    if (handler) handler(target)
    else singleRef.current(target)
  }, [cancel])

  return { click, doubleClick, cancel }
}
