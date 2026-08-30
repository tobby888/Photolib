import {
  BoldOutlined, EyeOutlined, ItalicOutlined, LinkOutlined, OrderedListOutlined,
  PictureOutlined, UnorderedListOutlined,
} from '@ant-design/icons'
import { App, Button, Input, Segmented, Space, Tooltip } from 'antd'
import { useRef, useState } from 'react'
import { api } from './api'
import MarkdownRenderer from './MarkdownRenderer'

export default function MarkdownEditor({
  value = '', onChange, placeholder = '使用 Markdown 编写说明……', allowImageUpload = true,
  uploadUrl = '/description-images', maxLength = 5000,
}: {
  value?: string
  onChange?: (value: string) => void
  placeholder?: string
  allowImageUpload?: boolean
  /**
   * 插图上传接口。默认是全站通用的说明图片；文档中心传自己的
   * /docs/{id}/assets，因为文档插图的可见范围要跟着所属文档走，
   * 而说明图片一律要求登录才能读。
   */
  uploadUrl?: string
  maxLength?: number
}) {
  const { message } = App.useApp()
  const root = useRef<HTMLDivElement>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const [mode, setMode] = useState<'edit' | 'preview'>('edit')
  const [uploading, setUploading] = useState(false)

  const insert = (before: string, after = '', fallback = '') => {
    const textarea = root.current?.querySelector('textarea')
    if (!textarea) return
    const start = textarea.selectionStart
    const end = textarea.selectionEnd
    const selected = value.slice(start, end) || fallback
    const next = `${value.slice(0, start)}${before}${selected}${after}${value.slice(end)}`
    onChange?.(next)
    requestAnimationFrame(() => {
      textarea.focus()
      const cursor = start + before.length + selected.length + after.length
      textarea.setSelectionRange(cursor, cursor)
    })
  }

  const uploadImage = async (file?: File) => {
    if (!file) return
    setUploading(true)
    try {
      const data = new FormData()
      data.append('file', file)
      const result = await api<{ url: string }>({ method: 'POST', url: uploadUrl, data })
      const safeName = file.name.replace(/[\]\\]/g, '') || '说明图片'
      insert(`![${safeName}](${result.url})\n`)
      message.success('图片已上传到对象存储并插入正文')
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setUploading(false)
      if (fileInput.current) fileInput.current.value = ''
    }
  }

  return <div className="markdown-editor" ref={root}>
    <div className="markdown-editor-toolbar">
      <Space size={2} wrap>
        <Tooltip title="加粗"><Button type="text" disabled={mode !== 'edit'} icon={<BoldOutlined />} onClick={() => insert('**', '**', '加粗文字')} /></Tooltip>
        <Tooltip title="斜体"><Button type="text" disabled={mode !== 'edit'} icon={<ItalicOutlined />} onClick={() => insert('*', '*', '斜体文字')} /></Tooltip>
        <Tooltip title="无序列表"><Button type="text" disabled={mode !== 'edit'} icon={<UnorderedListOutlined />} onClick={() => insert('- ', '', '列表项')} /></Tooltip>
        <Tooltip title="有序列表"><Button type="text" disabled={mode !== 'edit'} icon={<OrderedListOutlined />} onClick={() => insert('1. ', '', '列表项')} /></Tooltip>
        <Tooltip title="链接"><Button type="text" disabled={mode !== 'edit'} icon={<LinkOutlined />} onClick={() => insert('[', '](https://)', '链接文字')} /></Tooltip>
        {allowImageUpload && <>
          <Tooltip title="上传图片（JPEG、PNG、WebP，最大 5 MiB）">
            <Button type="text" disabled={mode !== 'edit'} loading={uploading} icon={<PictureOutlined />} onClick={() => fileInput.current?.click()} />
          </Tooltip>
          <input ref={fileInput} hidden type="file" accept="image/jpeg,image/png,image/webp"
            onChange={event => void uploadImage(event.target.files?.[0])} />
        </>}
      </Space>
      <Segmented size="small" value={mode} onChange={next => setMode(next as 'edit' | 'preview')}
        options={[{ value: 'edit', label: '编辑' }, { value: 'preview', label: <Space size={4}><EyeOutlined />预览</Space> }]} />
    </div>
    {mode === 'edit'
      ? <Input.TextArea value={value} onChange={event => onChange?.(event.target.value)}
          placeholder={placeholder} autoSize={{ minRows: 8, maxRows: 18 }} maxLength={maxLength} showCount />
      : <div className="markdown-editor-preview">
          {value.trim() ? <MarkdownRenderer value={value} /> : <span>暂无可预览内容</span>}
        </div>}
  </div>
}
