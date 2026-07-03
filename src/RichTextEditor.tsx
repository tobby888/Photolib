import { App, Button, Space, Tooltip } from 'antd'
import {
  BoldOutlined, ItalicOutlined, OrderedListOutlined, PictureOutlined, UnorderedListOutlined,
} from '@ant-design/icons'
import { useEffect, useRef } from 'react'
import { api } from './api'

export default function RichTextEditor({ value, onChange }: {
  value: string
  onChange: (value: string) => void
}) {
  const { message } = App.useApp()
  const editor = useRef<HTMLDivElement>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (!value && editor.current) editor.current.innerHTML = ''
  }, [value])
  const command = (name: string) => {
    editor.current?.focus()
    document.execCommand(name)
    onChange(editor.current?.innerHTML || '')
  }
  const uploadImage = async (file?: File) => {
    if (!file) return
    try {
      const data = new FormData()
      data.append('file', file)
      const result = await api<{ url: string }>({ method: 'POST', url: '/notifications/images', data })
      editor.current?.focus()
      document.execCommand('insertHTML', false,
        `<p><img src="${result.url}" alt="${file.name.replace(/[<>"]/g, '')}"></p>`)
      onChange(editor.current?.innerHTML || '')
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      if (fileInput.current) fileInput.current.value = ''
    }
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
      data-placeholder="输入消息正文……"
      onInput={(event) => onChange(event.currentTarget.innerHTML)} />
  </div>
}
