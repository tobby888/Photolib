import { App, Button, Space, Typography } from 'antd'
import {
  CheckOutlined, CloseOutlined, RedoOutlined, RotateLeftOutlined, RotateRightOutlined,
} from '@ant-design/icons'
import { useEffect, useRef, useState } from 'react'
import type { PointerEvent as ReactPointerEvent } from 'react'
import {
  FULL_CROP, MIN_CROP_SIZE, NO_EDITS, hasEdits, nextRotation, normalizeCrop, outputSize, renderEdited,
  rotateCrop, rotatedSize,
} from './photoEdit'
import type { CropRect, EditState } from './photoEdit'
import { useLocalImageUrl } from './useLocalImageUrl'
import type { PreviewUrlRefresher } from './previewImage'

/** 预览画布的最长边。再大只是白白解码像素——屏幕上根本放不下。 */
const PREVIEW_MAX_EDGE = 1600

interface PhotoCropEditorProps {
  /** 成品图的签名地址。必须是原图而不是预览图——裁切按它的像素出图。 */
  imageUrl: string
  /** 地址过期时重取一条；与图库其余位置同一套重试语义。 */
  refresh?: PreviewUrlRefresher
  contentType: string
  saving: boolean
  onCancel: () => void
  onSave: (blob: Blob) => void
}

/**
 * 选片页的裁切 / 旋转编辑器（issue #94）。
 *
 * <p>图片走 {@link useLocalImageUrl} 取成 Blob URL 再画进 canvas。这一步不是可选的：
 * 站内其余位置一律以 no-cors 渲染签名地址（理由见 `src/previewImage.ts`），
 * 而 no-cors 拿回来的图片会污染画布，`toBlob` 会直接抛 `SecurityError`。
 * `useLocalImageUrl` 用 `mode: 'cors'` + `cache: 'reload'` 绕开那条被污染的缓存条目，
 * 拿到的 Blob URL 是同源的，画布因此干净。</p>
 *
 * <p><b>预览画的是 canvas，不是 CSS 旋转过的 `&lt;img&gt;`。</b>裁切框存的是
 * 「旋转之后那张图」的归一化坐标，而 CSS 旋转会让 `&lt;img&gt;` 的盒子和它显示出来的
 * 画面对不上（90° 时宽高互换，但百分比定位仍按原盒子算），叠在上面的选框就会歪。
 * 画进 canvas 之后，元素的盒子**就是**用户看到的那个方向和比例，选框、指针坐标和
 * 最终输出用的是同一套坐标系，不需要任何换算。</p>
 *
 * <p>旋转时用 {@link rotateCrop} 把选框跟着转，而不是重置——用户刚框好构图，
 * 转一下就被清空是最惹人烦的那种「贴心」。</p>
 */
export default function PhotoCropEditor({
  imageUrl, refresh, contentType, saving, onCancel, onSave,
}: PhotoCropEditorProps) {
  const { message } = App.useApp()
  const local = useLocalImageUrl(imageUrl, refresh)
  const [edits, setEdits] = useState<EditState>(NO_EDITS)
  const [natural, setNatural] = useState<{ width: number; height: number } | null>(null)
  const [dragging, setDragging] = useState<CropRect | null>(null)
  const [box, setBox] = useState<{ left: number; top: number; width: number; height: number } | null>(null)
  const sourceRef = useRef<HTMLImageElement | null>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const frameRef = useRef<HTMLDivElement>(null)
  const dragStart = useRef<{ x: number; y: number } | null>(null)

  // 换一张图就从「没有编辑」重新开始，否则上一张的裁切框会套到这一张上。
  useEffect(() => {
    setEdits(NO_EDITS)
    setNatural(null)
    setDragging(null)
    setBox(null)
    sourceRef.current = null
  }, [imageUrl])

  // 解码一次原图留着：预览重画和最终出图都用它，避免每次旋转都重新解码几十 MB。
  useEffect(() => {
    if (local.status !== 'ready') return
    let active = true
    const image = new Image()
    image.onload = () => {
      if (!active) return
      sourceRef.current = image
      setNatural({ width: image.naturalWidth, height: image.naturalHeight })
    }
    image.onerror = () => {
      if (active) message.error('原图没能解码，请刷新后重试')
    }
    image.src = local.url
    return () => { active = false }
  }, [local, message])

  // 按当前旋转角把整张图画进预览画布。裁切不在这里做——选框是叠在上面的遮罩，
  // 用户随时能改，画进画布只会让「重新框一次」变成「先撤销再框」。
  useEffect(() => {
    const canvas = canvasRef.current
    const source = sourceRef.current
    if (!canvas || !source || !natural) return
    const rotated = rotatedSize(natural.width, natural.height, edits.rotation)
    const scale = Math.min(1, PREVIEW_MAX_EDGE / Math.max(rotated.width, rotated.height))
    canvas.width = Math.max(1, Math.round(rotated.width * scale))
    canvas.height = Math.max(1, Math.round(rotated.height * scale))
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.clearRect(0, 0, canvas.width, canvas.height)
    ctx.save()
    ctx.scale(scale, scale)
    switch (edits.rotation) {
      case 90:
        ctx.translate(rotated.width, 0)
        ctx.rotate(Math.PI / 2)
        break
      case 180:
        ctx.translate(rotated.width, rotated.height)
        ctx.rotate(Math.PI)
        break
      case 270:
        ctx.translate(0, rotated.height)
        ctx.rotate(-Math.PI / 2)
        break
      default:
        break
    }
    ctx.drawImage(source, 0, 0)
    ctx.restore()
  }, [natural, edits.rotation])

  /*
    选框按像素叠在画布上，而不是按百分比叠在画布的父元素上。
    百分比要求父元素刚好包住画布，而画布自己是靠 `max-width/max-height: 100%`
    等比缩下来的——那两条百分比又要求父元素有确定的尺寸，两边互为前提，
    结果是画布按原始像素撑出容器（横图看不出来，竖过来立刻溢出）。
    改成父元素填满可用区域、画布在其中居中缩放，选框则按量出来的画布盒子定位。
  */
  useEffect(() => {
    const canvas = canvasRef.current
    const frame = frameRef.current
    if (!canvas || !frame) return
    const measure = () => setBox({
      left: canvas.offsetLeft,
      top: canvas.offsetTop,
      width: canvas.offsetWidth,
      height: canvas.offsetHeight,
    })
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(canvas)
    observer.observe(frame)
    return () => observer.disconnect()
  }, [natural, edits.rotation])

  const crop = dragging ?? edits.crop
  const result = natural ? outputSize(natural.width, natural.height, { ...edits, crop }) : null

  /** 指针位置 → 归一化裁切坐标。量的是画布本身，所以它在容器里怎么居中都不影响。 */
  const pointToCrop = (event: ReactPointerEvent<HTMLDivElement>) => {
    const rect = canvasRef.current?.getBoundingClientRect()
    if (!rect || !rect.width || !rect.height) return null
    return {
      x: Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width)),
      y: Math.min(1, Math.max(0, (event.clientY - rect.top) / rect.height)),
    }
  }

  const startDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (saving || !natural) return
    const point = pointToCrop(event)
    if (!point) return
    // 指针捕获只是让指针滑出画布之后还能继续收到 move；拿不到就照常拖，
    // 不要让整个裁切功能因为它抛异常而失效。
    try {
      event.currentTarget.setPointerCapture(event.pointerId)
    } catch {
      // 某些浏览器/合成事件下没有这个 pointerId，忽略即可。
    }
    dragStart.current = point
    setDragging({ x: point.x, y: point.y, width: 0, height: 0 })
  }

  const moveDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    const origin = dragStart.current
    if (!origin) return
    const point = pointToCrop(event)
    if (!point) return
    setDragging({
      x: Math.min(origin.x, point.x),
      y: Math.min(origin.y, point.y),
      width: Math.abs(point.x - origin.x),
      height: Math.abs(point.y - origin.y),
    })
  }

  const endDrag = () => {
    const pending = dragging
    dragStart.current = null
    setDragging(null)
    if (!pending) return
    // 只是点了一下（没拖出可用的框）就当成「取消裁切」，不要留下一个看不见的小方块。
    if (pending.width < MIN_CROP_SIZE || pending.height < MIN_CROP_SIZE) {
      setEdits(current => ({ ...current, crop: FULL_CROP }))
      return
    }
    setEdits(current => ({ ...current, crop: normalizeCrop(pending) }))
  }

  const rotate = (quarterTurns: number) => setEdits(current => ({
    rotation: nextRotation(current.rotation, quarterTurns),
    crop: rotateCrop(current.crop, quarterTurns),
  }))

  const save = async () => {
    const source = sourceRef.current
    if (!source) return
    try {
      onSave(await renderEdited(source, edits, contentType))
    } catch (reason) {
      message.error((reason as Error).message)
    }
  }

  const ready = local.status === 'ready' && !!natural
  return <div className="selection-editor">
    <div className="selection-editor-toolbar">
      <Space wrap>
        <Button icon={<RotateLeftOutlined />} disabled={saving || !ready}
          onClick={() => rotate(-1)}>左转 90°</Button>
        <Button icon={<RotateRightOutlined />} disabled={saving || !ready}
          onClick={() => rotate(1)}>右转 90°</Button>
        <Button icon={<RedoOutlined />} disabled={saving || !hasEdits(edits)}
          onClick={() => setEdits(NO_EDITS)}>重置</Button>
      </Space>
      <Space wrap>
        <Typography.Text type="secondary">
          在图上拖动框出要保留的部分{result ? `；将输出 ${result.width} × ${result.height}` : ''}
        </Typography.Text>
        <Button icon={<CloseOutlined />} disabled={saving} onClick={onCancel}>取消</Button>
        <Button type="primary" icon={<CheckOutlined />} loading={saving}
          disabled={!ready || !hasEdits(edits)}
          onClick={() => void save()}>保存并替换原图</Button>
      </Space>
    </div>
    <div className="selection-editor-canvas">
      {local.status === 'error' && <div className="empty-state">{local.message}</div>}
      {local.status !== 'error' && !ready && <div className="empty-state">正在加载原图…</div>}
      <div ref={frameRef} className={`selection-editor-frame${ready ? '' : ' is-hidden'}`}
        onPointerDown={startDrag} onPointerMove={moveDrag} onPointerUp={endDrag} onPointerCancel={endDrag}>
        <canvas ref={canvasRef} className="selection-editor-preview" />
        {box && isPartialCrop(crop) && <div className="selection-editor-crop" style={{
          left: box.left + crop.x * box.width,
          top: box.top + crop.y * box.height,
          width: crop.width * box.width,
          height: crop.height * box.height,
        }} />}
      </div>
    </div>
  </div>
}

function isPartialCrop(crop: CropRect) {
  return crop.width > 0 && crop.height > 0 && (crop.width < 1 || crop.height < 1)
}
