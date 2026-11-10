import { App } from 'antd'
import { useCallback, useEffect, useState, type RefObject } from 'react'

/**
 * 「弹窗选图」的外围行为，站内选片页（PhotoSelectionPage）和分享链接的选图台
 * （SharedSelectionWorkbench）共用：
 *
 * - 弹窗开着时锁住底下页面的滚动，并把焦点交给弹窗，读屏和键盘都从这里开始。
 * - 浏览器全屏是弹窗里的可选项。用户也可能自己按 F11 或 Esc 退出，状态以事件为准。
 *   全屏的是整个 documentElement 而不是弹窗那个元素——antd 的提示和气泡都挂在 body 上，
 *   只全屏弹窗的话，操作失败的报错会被挡在全屏层后面看不见。
 */
export function useSelectionFocus(open: boolean, focusRef: RefObject<HTMLElement | null>) {
  const { message } = App.useApp()
  const [browserFullscreen, setBrowserFullscreen] = useState(false)

  useEffect(() => {
    if (!open) return
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    focusRef.current?.focus()
    return () => { document.body.style.overflow = previous }
  }, [focusRef, open])

  useEffect(() => {
    const sync = () => setBrowserFullscreen(!!document.fullscreenElement)
    document.addEventListener('fullscreenchange', sync)
    return () => document.removeEventListener('fullscreenchange', sync)
  }, [])

  const toggleBrowserFullscreen = useCallback(() => {
    const request = document.fullscreenElement
      ? document.exitFullscreen()
      : document.documentElement.requestFullscreen?.()
    void request?.catch(() => message.warning('浏览器没有允许全屏，可以按 F11 手动切换'))
  }, [message])

  /** 关弹窗时顺手退出浏览器全屏，否则用户回到页面还困在全屏里。 */
  const exitBrowserFullscreen = useCallback(() => {
    if (document.fullscreenElement) void document.exitFullscreen().catch(() => undefined)
  }, [])

  return { browserFullscreen, toggleBrowserFullscreen, exitBrowserFullscreen }
}
