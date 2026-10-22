import { App, Button, Space, Tooltip } from 'antd'
import {
  BoldOutlined, ItalicOutlined, OrderedListOutlined, PictureOutlined, UnorderedListOutlined,
} from '@ant-design/icons'
import { useEffect, useRef, type ClipboardEvent } from 'react'
import { api } from './api'
import { describeBytes } from './uploadLimits'
import { useUploadLimits } from './useUploadLimits'

const MAX_CONTENT_CHARS = 20000

/**
 * 受控富文本编辑器。
 *
 * 图片上传地址可切换，因为不同业务的图片鉴权模型不同：管理消息的图片只对收件人可读，
 * 而说明图片（`/description-images`）对任何登录用户可读。选错地址会让读者看到裂图，
 * 所以调用方必须按内容的可见范围挑选，默认保持消息图片不变。
 */
export default function RichTextEditor({
  value, onChange, uploadUrl = '/notifications/images', placeholder = '输入消息正文……',
}: {
  value: string
  onChange: (value: string) => void
  uploadUrl?: string
  placeholder?: string
}) {
  const { message } = App.useApp()
  const inlineImageMaxBytes = useUploadLimits().INLINE_IMAGE_MAX_BYTES
  const editor = useRef<HTMLDivElement>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const lastHtml = useRef(value)
  useEffect(() => {
    lastHtml.current = value
    if (!value && editor.current) editor.current.innerHTML = ''
  }, [value])
  const emit = (html: string) => {
    if (html.length > MAX_CONTENT_CHARS) {
      message.warning(`正文最多 ${MAX_CONTENT_CHARS} 个字符`)
      if (editor.current) editor.current.innerHTML = lastHtml.current
      return
    }
    lastHtml.current = html
    onChange(html)
  }
  const command = (name: string) => {
    editor.current?.focus()
    document.execCommand(name)
    emit(editor.current?.innerHTML || '')
  }
  const uploadImage = async (file?: File) => {
    if (!file) return
    if (file.size > inlineImageMaxBytes) {
      message.error(`图片不能超过 ${describeBytes(inlineImageMaxBytes)}`)
      if (fileInput.current) fileInput.current.value = ''
      return
    }
    try {
      const data = new FormData()
      data.append('file', file)
      const result = await api<{ url: string }>({ method: 'POST', url: uploadUrl, data })
      editor.current?.focus()
      document.execCommand('insertHTML', false,
        `<p><img src="${result.url}" alt="${file.name.replace(/[<>"]/g, '')}"></p>`)
      emit(editor.current?.innerHTML || '')
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      if (fileInput.current) fileInput.current.value = ''
    }
  }
  const onPaste = (event: ClipboardEvent<HTMLDivElement>) => {
    const items = event.clipboardData?.items
    const files: File[] = []
    if (items) {
      for (let i = 0; i < items.length; i++) {
        const item = items[i]
        if (item.kind === 'file' && item.type.startsWith('image/')) {
          const file = item.getAsFile()
          if (file) files.push(file)
        }
      }
    }
    if (files.length === 0) return
    event.preventDefault()
    files.forEach((file) => void uploadImage(file))
  }
  return <div className="rich-editor">
    <Space className="rich-editor-toolbar" wrap>
      <Tooltip title="加粗"><Button type="text" icon={<BoldOutlined />} onClick={() => command('bold')} /></Tooltip>
      <Tooltip title="斜体"><Button type="text" icon={<ItalicOutlined />} onClick={() => command('italic')} /></Tooltip>
      <Tooltip title="无序列表"><Button type="text" icon={<UnorderedListOutlined />} onClick={() => command('insertUnorderedList')} /></Tooltip>
      <Tooltip title="有序列表"><Button type="text" icon={<OrderedListOutlined />} onClick={() => command('insertOrderedList')} /></Tooltip>
      <Tooltip title="插入图片"><Button type="text" icon={<PictureOutlined />} onClick={() => fileInput.current?.click()} /></Tooltip>
      <input ref={fileInput} hidden type="file" accept="image/jpeg,image/png,image/webp"
        onChange={(event) => void uploadImage(event.target.files?.[0])} />
    </Space>
    <div ref={editor} className="rich-editor-content" contentEditable suppressContentEditableWarning
      data-placeholder={placeholder}
      onInput={(event) => emit(event.currentTarget.innerHTML)}
      onPaste={onPaste} />
  </div>
}
