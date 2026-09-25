import { Button, Tooltip, Typography } from 'antd'
import {
  ColumnWidthOutlined, OneToOneOutlined, ZoomInOutlined, ZoomOutOutlined,
} from '@ant-design/icons'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { PointerEvent as ReactPointerEvent } from 'react'
import {
  FIT_ZOOM, MAX_SCALE, MIN_SCALE, ZOOM_STEP, containSize, isZoomed, panBy, scaleForActualPixels, zoomAt,
} from './photoZoom'
import type { Size, ZoomState } from './photoZoom'
import { PreviewPhotoImg } from './PreviewPhoto'
import type { PreviewUrlRefresher } from './previewImage'

interface ZoomableImageProps {
  src: string
  alt: string
  refresh?: PreviewUrlRefresher
  /** 换一张图（或换一次画质）就回到适应窗口；传 photo id + 画质即可。 */
  resetKey: string
  /** 放大到超过预览图本身的分辨率时给一句提醒，不传就不提醒。 */
  previewHint?: string
}

/**
 * 可缩放、可拖动的大图（issue #94 追加）。
 *
 * 选片要回答的问题里有一半是「这张糊没糊、眼睛有没有睁开」，`object-fit: contain`
 * 把一张 6000px 的图缩到 800px 宽之后，这些一概看不出来。所以大图区需要能放大到
 * 像素级，并且放大之后能拖着看。
 *
 * 几何全部在 `src/photoZoom.ts` 里（纯函数，有测试），这里只负责把手势接上去：
 * - 滚轮以**光标**为锚点缩放（不是以中心——放大到三四倍时两者差得很远）；
 * - 放大后按住拖动平移，边界由 `clampPan` 收住，拖不出空白；
 * - 双击在「适应窗口」和「1:1」之间来回；
 * - 换图、换画质一律回到适应窗口，否则上一张拖到的角落会套到下一张上。
 *
 * 滚轮监听必须用 `addEventListener(..., { passive: false })` 手挂：React 的
 * `onWheel` 是被动监听，里面调 `preventDefault` 不生效，页面会跟着一起滚。
 */
export default function ZoomableImage({ src, alt, refresh, resetKey, previewHint }: ZoomableImageProps) {
  const [zoom, setZoom] = useState<ZoomState>(FIT_ZOOM)
  const [natural, setNatural] = useState<Size | null>(null)
  const [dragging, setDragging] = useState(false)
  const frameRef = useRef<HTMLDivElement>(null)
  const dragFrom = useRef<{ x: number; y: number } | null>(null)
  // 滚轮回调挂在原生监听上，拿不到最新的 state；用 ref 兜住当前值。
  const zoomRef = useRef(zoom)
  zoomRef.current = zoom
  const naturalRef = useRef(natural)
  naturalRef.current = natural

  useEffect(() => {
    setZoom(FIT_ZOOM)
    setNatural(null)
  }, [resetKey])

  const measure = useCallback(() => {
    const frame = frameRef.current
    const size = naturalRef.current
    if (!frame || !size) return null
    const rect = frame.getBoundingClientRect()
    const viewport = { width: rect.width, height: rect.height }
    return { rect, viewport, content: containSize(size, viewport) }
  }, [])

  const zoomTo = useCallback((nextScale: number, clientPoint?: { x: number; y: number }) => {
    const measured = measure()
    if (!measured) return
    const { rect, viewport, content } = measured
    // 锚点相对容器中心。不给点就以中心缩放（按钮走这条）。
    const anchor = clientPoint
      ? { x: clientPoint.x - (rect.left + rect.width / 2), y: clientPoint.y - (rect.top + rect.height / 2) }
      : { x: 0, y: 0 }
    setZoom(current => zoomAt(current, nextScale, anchor, viewport, content))
  }, [measure])

  useEffect(() => {
    const frame = frameRef.current
    if (!frame) return
    const onWheel = (event: WheelEvent) => {
      if (!naturalRef.current) return
      event.preventDefault()
      const factor = event.deltaY < 0 ? ZOOM_STEP : 1 / ZOOM_STEP
      zoomTo(zoomRef.current.scale * factor, { x: event.clientX, y: event.clientY })
    }
    frame.addEventListener('wheel', onWheel, { passive: false })
    return () => frame.removeEventListener('wheel', onWheel)
  }, [zoomTo])

  // 容器尺寸变了（收起侧边栏、切换视图、转屏）要把偏移重新收进边界，
  // 否则原来贴着右边缘的画面会露出一条空白。
  useEffect(() => {
    const frame = frameRef.current
    if (!frame) return
    const observer = new ResizeObserver(() => {
      const measured = measure()
      if (!measured) return
      setZoom(current => zoomAt(current, current.scale, { x: 0, y: 0 },
        measured.viewport, measured.content))
    })
    observer.observe(frame)
    return () => observer.disconnect()
  }, [measure])

  /*
    缩放快捷键留在这个组件里，因为缩放状态也在这里。数字 0 和 +/- 与选片页的
    快捷键不冲突：那边只吃 1–9（套预设标签）和空格（标记不可用）。
  */
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (target && /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName)) return
      if (event.metaKey || event.ctrlKey || event.altKey) return
      if (event.key === '0') {
        event.preventDefault()
        setZoom(FIT_ZOOM)
      } else if (event.key === '+' || event.key === '=') {
        event.preventDefault()
        zoomTo(zoomRef.current.scale * ZOOM_STEP)
      } else if (event.key === '-') {
        event.preventDefault()
        zoomTo(zoomRef.current.scale / ZOOM_STEP)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [zoomTo])

  const startDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!isZoomed(zoom)) return
    try {
      event.currentTarget.setPointerCapture(event.pointerId)
    } catch {
      // 合成事件下可能没有这个 pointerId，拖动照常继续。
    }
    dragFrom.current = { x: event.clientX, y: event.clientY }
    setDragging(true)
  }

  const moveDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    const from = dragFrom.current
    if (!from) return
    const measured = measure()
    if (!measured) return
    const delta = { x: event.clientX - from.x, y: event.clientY - from.y }
    dragFrom.current = { x: event.clientX, y: event.clientY }
    setZoom(current => panBy(current, delta, measured.viewport, measured.content))
  }

  const endDrag = () => {
    dragFrom.current = null
    setDragging(false)
  }

  /** 「1:1」要放大到几倍。渲染期读一次布局——只有一个元素，比塞进 state 再同步简单。 */
  const actualScale = (() => {
    const measured = measure()
    return measured && natural ? scaleForActualPixels(natural, measured.content) : MIN_SCALE
  })()
  const zoomed = isZoomed(zoom)
  // 预览图只有 480px 长边，放大过头就只是在放大压缩块。提示语由调用方给，
  // 它才知道当前是不是预览画质。
  const beyondPreview = !!previewHint && zoom.scale > 1.5

  return <div className="selection-viewer">
    <div className={`selection-viewer-frame${dragging ? ' is-dragging' : ''}${zoomed ? ' is-zoomed' : ''}`}
      ref={frameRef}
      onPointerDown={startDrag} onPointerMove={moveDrag} onPointerUp={endDrag} onPointerCancel={endDrag}
      onDoubleClick={event => zoomTo(zoomed ? MIN_SCALE : actualScale, { x: event.clientX, y: event.clientY })}>
      <PreviewPhotoImg src={src} alt={alt} loading="eager" draggable={false} refresh={refresh}
        style={{ transform: `translate(${zoom.x}px, ${zoom.y}px) scale(${zoom.scale})` }}
        onLoad={event => setNatural({
          width: event.currentTarget.naturalWidth,
          height: event.currentTarget.naturalHeight,
        })} />
    </div>
    <div className="selection-viewer-tools">
      <Tooltip title="缩小">
        <Button size="small" icon={<ZoomOutOutlined />} disabled={zoom.scale <= MIN_SCALE}
          aria-label="缩小" onClick={() => zoomTo(zoom.scale / ZOOM_STEP)} />
      </Tooltip>
      <span className="selection-viewer-scale">{Math.round(zoom.scale * 100)}%</span>
      <Tooltip title="放大">
        <Button size="small" icon={<ZoomInOutlined />} disabled={zoom.scale >= MAX_SCALE}
          aria-label="放大" onClick={() => zoomTo(zoom.scale * ZOOM_STEP)} />
      </Tooltip>
      <Tooltip title="适应窗口（按 0）">
        <Button size="small" icon={<ColumnWidthOutlined />} disabled={!zoomed}
          aria-label="适应窗口" onClick={() => setZoom(FIT_ZOOM)} />
      </Tooltip>
      <Tooltip title="按原始像素显示">
        <Button size="small" icon={<OneToOneOutlined />} aria-label="原始像素"
          onClick={() => zoomTo(actualScale)} />
      </Tooltip>
    </div>
    {beyondPreview && <Typography.Text className="selection-viewer-hint" type="warning">
      {previewHint}
    </Typography.Text>}
  </div>
}
