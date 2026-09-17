import { useCallback, useEffect, useRef, type SyntheticEvent } from 'react'

/**
 * React 事件会顺着组件树穿过 Portal 冒泡：封面里 antd 大图预览弹层上的点击、双击、按键，
 * 也会跑到封面的处理函数里。这类事件的 DOM 目标不在封面节点内，封面要忽略它们，
 * 否则点预览里的关闭 / 缩放按钮会把图片勾上或取消。
 */
export const isPortalEvent = (event: SyntheticEvent<HTMLElement>) =>
  !event.currentTarget.contains(event.target as Node)

/**
 * 单击用来选图时，大图预览改成受控：单击缩略图不再弹出（否则一下既选中又打开预览），
 * 只由双击、Enter 或「查看大图」按钮打开；关闭仍交给预览自己。`cover: false` 去掉悬停时的「预览」遮罩。
 */
export const selectablePreview = (open: boolean, onClose: () => void) => ({
  open,
  cover: false as const,
  onOpenChange: (next: boolean) => { if (!next) onClose() },
})

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
