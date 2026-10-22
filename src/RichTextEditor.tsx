import { App, Button, Space, Tooltip } from 'antd'
import {
  BoldOutlined, ItalicOutlined, LoadingOutlined, OrderedListOutlined, PictureOutlined, UnorderedListOutlined,
} from '@ant-design/icons'
import { useEffect, useRef, useState, type ClipboardEvent, type DragEvent, type FormEvent } from 'react'
import { api, http } from './api'
import {
  EDITOR_IMAGE_SOURCE_ATTR, INLINE_IMAGE_ACCEPT, INLINE_IMAGE_TYPE_HINT, RICH_TEXT_MAX_CHARS, RICH_TEXT_TOO_LONG,
  caretTextOffset, estimateImageHtmlLength, estimateInputGrowth, imageHtml, isInlineImageType,
  placeCaretAtTextOffset, planPaste, sanitizePastedHtml, serializeEditorHtml,
} from './richTextPaste'
import { describeBytes } from './uploadLimits'
import { useUploadLimits } from './useUploadLimits'

/**
 * 受控富文本编辑器。
 *
 * 图片上传地址可切换，因为不同业务的图片鉴权模型不同：管理消息的图片只对收件人可读，
 * 而说明图片（`/description-images`）对任何登录用户可读。选错地址会让读者看到裂图，
 * 所以调用方必须按内容的可见范围挑选，默认保持消息图片不变。
 *
 * 正文 HTML 不会超过后端的 20000 字符上限：能预估的输入在发生前就挡住，
 * 估不准的（输入法、拖放等）在输入后退回上一版并恢复光标。
 * 图片上传期间通过 `onUploadingChange` 通知调用方，调用方应暂时禁用提交。
 */
export default function RichTextEditor({
  value, onChange, uploadUrl = '/notifications/images', placeholder = '输入消息正文……', onUploadingChange,
}: {
  value: string
  onChange: (value: string) => void
  uploadUrl?: string
  placeholder?: string
  onUploadingChange?: (uploading: boolean) => void
}) {
  const { message } = App.useApp()
  const inlineImageMaxBytes = useUploadLimits().INLINE_IMAGE_MAX_BYTES
  const editor = useRef<HTMLDivElement>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  /** 最近一次交给调用方的 HTML，也就是超长时退回的版本。 */
  const lastHtml = useRef('')
  /** 最近一次改动之前的光标位置，退回时放回这里。 */
  const lastCaret = useRef<number | null>(null)
  /** 正在上传的图片预留的字符数，避免图片传完插进去时才发现超长。 */
  const reservedChars = useRef(0)
  const pendingUploads = useRef(0)
  /** 编辑器内部拖动文字是「移动」，交给浏览器处理，不走外部拖放的清洗插入。 */
  const draggingInside = useRef(false)
  const [uploading, setUploading] = useState(false)
  const uploadingListener = useRef(onUploadingChange)
  uploadingListener.current = onUploadingChange

  // 调用方给的值和编辑器里的不同时（打开已有内容编辑、提交后清空），以调用方为准。
  useEffect(() => {
    if (!editor.current || value === lastHtml.current) return
    editor.current.innerHTML = value
    lastHtml.current = value
  }, [value])

  // 站内图片要带鉴权头才读得到，直接用 <img src> 会是裂图；Chrome 还会让只含裂图的段落
  // 放不进光标，图片下面打字、粘贴都失效。所以编辑器里换成下载好的 Blob 显示，
  // 真实地址留在 data 属性上，交出去的 HTML 由 serializeEditorHtml 换回来。
  useEffect(() => {
    const root = editor.current
    if (!root) return
    const objectUrls = new Map<string, string>()
    // 按这一轮 effect 自己记，不看 data 属性：StrictMode 下 effect 会先清理再重跑，
    // 上一轮打过标记但下载结果已被丢弃的图，这一轮还要重新下。
    const handled = new WeakSet<HTMLImageElement>()
    let active = true
    const show = (image: HTMLImageElement) => {
      if (handled.has(image)) return
      handled.add(image)
      const path = image.getAttribute(EDITOR_IMAGE_SOURCE_ATTR) ?? image.getAttribute('src') ?? ''
      if (!path.startsWith('/api/v1/')) return
      image.setAttribute(EDITOR_IMAGE_SOURCE_ATTR, path)
      const cached = objectUrls.get(path)
      if (cached) {
        image.src = cached
        return
      }
      void http.get<Blob>(path.slice('/api/v1'.length), { responseType: 'blob' })
        .then(response => {
          if (!active) return
          const url = objectUrls.get(path) ?? URL.createObjectURL(response.data)
          objectUrls.set(path, url)
          if (image.getAttribute(EDITOR_IMAGE_SOURCE_ATTR) === path) image.src = url
        })
        .catch(() => undefined)
    }
    const scan = () => root.querySelectorAll('img').forEach(show)
    scan()
    const observer = new MutationObserver(scan)
    observer.observe(root, { childList: true, subtree: true })
    return () => {
      active = false
      observer.disconnect()
      objectUrls.forEach(url => URL.revokeObjectURL(url))
    }
  }, [])

  const warnTooLong = () => message.warning({ key: 'rich-text-too-long', content: RICH_TEXT_TOO_LONG })
  const fits = (growth: number) =>
    (editor.current ? serializeEditorHtml(editor.current).length : 0) + reservedChars.current + growth
      <= RICH_TEXT_MAX_CHARS
  const rememberCaret = () => {
    if (editor.current) lastCaret.current = caretTextOffset(editor.current)
  }
  const selectedChars = () => window.getSelection()?.toString().length ?? 0

  const emit = () => {
    const root = editor.current
    if (!root) return
    const html = serializeEditorHtml(root)
    if (html.length + reservedChars.current > RICH_TEXT_MAX_CHARS) {
      root.innerHTML = lastHtml.current
      if (lastCaret.current !== null) placeCaretAtTextOffset(root, lastCaret.current)
      warnTooLong()
      return
    }
    if (html === lastHtml.current) return
    lastHtml.current = html
    onChange(html)
  }

  // React 的 onBeforeInput 不带 inputType，挡输入要用原生事件。
  const beforeInput = useRef<(event: InputEvent) => void>(() => undefined)
  beforeInput.current = (event) => {
    // 输入法组合中的 beforeinput 取消不了，组合结束后由 emit 兜底。
    if (event.isComposing || event.inputType === 'insertCompositionText') return
    rememberCaret()
    const text = event.data ?? event.dataTransfer?.getData('text/plain') ?? null
    const growth = estimateInputGrowth(event.inputType, text, selectedChars())
    if (growth > 0 && !fits(growth)) {
      event.preventDefault()
      warnTooLong()
    }
  }
  useEffect(() => {
    const root = editor.current
    if (!root) return
    const listener = (event: Event) => beforeInput.current(event as InputEvent)
    root.addEventListener('beforeinput', listener)
    return () => root.removeEventListener('beforeinput', listener)
  }, [])

  const command = (name: string) => {
    editor.current?.focus()
    rememberCaret()
    document.execCommand(name)
    emit()
  }

  // 上传中途编辑器被卸载（比如弹窗关了），别让调用方的提交按钮一直禁用。
  useEffect(() => () => {
    if (pendingUploads.current > 0) uploadingListener.current?.(false)
  }, [])

  const changePending = (delta: number) => {
    pendingUploads.current += delta
    const next = pendingUploads.current > 0
    setUploading(next)
    uploadingListener.current?.(next)
  }

  const uploadImage = async (file: File) => {
    if (file.size > inlineImageMaxBytes) {
      message.error(`图片不能超过 ${describeBytes(inlineImageMaxBytes)}`)
      return
    }
    if (!isInlineImageType(file.type)) {
      message.error(INLINE_IMAGE_TYPE_HINT)
      return
    }
    const reserve = estimateImageHtmlLength(uploadUrl, file.name)
    if (!fits(reserve)) {
      warnTooLong()
      return
    }
    reservedChars.current += reserve
    changePending(1)
    let reserved = true
    try {
      const data = new FormData()
      data.append('file', file)
      const result = await api<{ url: string }>({ method: 'POST', url: uploadUrl, data })
      reservedChars.current -= reserve
      reserved = false
      const root = editor.current
      if (!root) return
      root.focus()
      rememberCaret()
      document.execCommand('insertHTML', false, imageHtml(result.url, file.name))
      emit()
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      if (reserved) reservedChars.current -= reserve
      changePending(-1)
    }
  }

  // 多张图按顺序传、按顺序插，保证正文里的顺序和粘贴时一致。
  const uploadImages = async (files: File[]) => {
    for (const file of files) await uploadImage(file)
  }

  const pickFile = async (file?: File) => {
    if (fileInput.current) fileInput.current.value = ''
    if (file) await uploadImage(file)
  }

  /** 粘贴和拖放共用：纯图片上传，带文字的按后端白名单清洗后插入，纯文本交给浏览器。 */
  const insertTransfer = (event: ClipboardEvent | DragEvent, transfer: DataTransfer | null) => {
    const root = editor.current
    if (!root || !transfer || draggingInside.current) return
    const html = transfer.getData('text/html')
    const files = Array.from(transfer.files).filter(file => file.type.startsWith('image/'))
    const plan = planPaste({ html, text: transfer.getData('text/plain'), files })
    if (plan.kind === 'native') return
    event.preventDefault()
    if ('clientX' in event) moveCaretToPoint(event.clientX, event.clientY)
    rememberCaret()
    if (plan.kind === 'unsupported') {
      message.error(INLINE_IMAGE_TYPE_HINT)
      return
    }
    if (plan.kind === 'upload') {
      if (plan.skipped) message.warning(`已跳过 ${plan.skipped} 张图片：${INLINE_IMAGE_TYPE_HINT}`)
      void uploadImages(plan.images)
      return
    }
    const cleaned = sanitizePastedHtml(html, `/api/v1${uploadUrl}/`)
    if (cleaned.removedImages) {
      message.warning(`已去掉 ${cleaned.removedImages} 张图片：网页或文档里的图片带不过来，`
        + '请单独复制图片后粘贴，或用「插入图片」上传')
    }
    if (!cleaned.html) return
    if (!fits(cleaned.html.length - selectedChars())) {
      warnTooLong()
      return
    }
    root.focus()
    document.execCommand('insertHTML', false, cleaned.html)
    emit()
  }

  const onInput = (event: FormEvent<HTMLDivElement>) => {
    // 输入法组合还没结束时不检查，避免把正在拼的字退回去。
    if ((event.nativeEvent as InputEvent).isComposing) return
    emit()
  }

  return <div className="rich-editor">
    <Space className="rich-editor-toolbar" wrap>
      <Tooltip title="加粗"><Button type="text" icon={<BoldOutlined />} onClick={() => command('bold')} /></Tooltip>
      <Tooltip title="斜体"><Button type="text" icon={<ItalicOutlined />} onClick={() => command('italic')} /></Tooltip>
      <Tooltip title="无序列表"><Button type="text" icon={<UnorderedListOutlined />} onClick={() => command('insertUnorderedList')} /></Tooltip>
      <Tooltip title="有序列表"><Button type="text" icon={<OrderedListOutlined />} onClick={() => command('insertOrderedList')} /></Tooltip>
      <Tooltip title="插入图片"><Button type="text" icon={<PictureOutlined />} onClick={() => fileInput.current?.click()} /></Tooltip>
      {/* 只写 hidden 不够：antd 的 .ant-form input[type="file"] { display: block } 会盖过它，
          编辑器放在 Form 里（好图精选）时原生文件选择框会露在工具栏上。 */}
      <input ref={fileInput} hidden style={{ display: 'none' }} type="file" accept={INLINE_IMAGE_ACCEPT}
        onChange={(event) => void pickFile(event.target.files?.[0])} />
      {uploading && <span className="rich-editor-status"><LoadingOutlined /> 图片上传中…</span>}
    </Space>
    <div ref={editor} className="rich-editor-content" contentEditable suppressContentEditableWarning
      data-placeholder={placeholder}
      onInput={onInput}
      onCompositionStart={rememberCaret}
      onCompositionEnd={emit}
      onPaste={(event) => insertTransfer(event, event.clipboardData)}
      onDragStart={() => { draggingInside.current = true }}
      onDragEnd={() => { draggingInside.current = false }}
      onDrop={(event) => insertTransfer(event, event.dataTransfer)} />
  </div>
}

/** 拖放时浏览器还没把光标移到落点，自己移过去，插入位置才对。 */
function moveCaretToPoint(x: number, y: number) {
  const range = document.caretRangeFromPoint?.(x, y)
  if (!range) return
  const selection = window.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(range)
}
