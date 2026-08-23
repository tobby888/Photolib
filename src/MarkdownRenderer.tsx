import { useEffect, useState, type ComponentPropsWithoutRef } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { http } from './api'

const DESCRIPTION_IMAGE_PREFIX = '/api/v1/description-images/'

function MarkdownImage({ src, alt, ...props }: ComponentPropsWithoutRef<'img'>) {
  const [objectUrl, setObjectUrl] = useState<string>()
  const [failed, setFailed] = useState(false)
  const protectedImage = typeof src === 'string' && src.startsWith(DESCRIPTION_IMAGE_PREFIX)
  const unsupportedImage = !!src && !protectedImage

  useEffect(() => {
    if (!protectedImage || !src) return
    let active = true
    let currentUrl: string | undefined
    setFailed(false)
    void http.get<Blob>(src.slice('/api/v1'.length), { responseType: 'blob' })
      .then(response => {
        if (!active) return
        currentUrl = URL.createObjectURL(response.data)
        setObjectUrl(currentUrl)
      })
      .catch(() => { if (active) setFailed(true) })
    return () => {
      active = false
      if (currentUrl) URL.revokeObjectURL(currentUrl)
    }
  }, [protectedImage, src])

  if (unsupportedImage) return <span className="markdown-image-error">仅支持通过编辑器上传的说明图片</span>
  if (failed) return <span className="markdown-image-error">图片加载失败：{alt || '未命名图片'}</span>
  return <img {...props} src={objectUrl} alt={alt || ''} />
}

function PlainMarkdownLink({ children }: ComponentPropsWithoutRef<'a'>) {
  return <>{children}</>
}

export default function MarkdownRenderer({ value, className = '', allowLinks = true }: {
  value?: string | null
  className?: string
  allowLinks?: boolean
}) {
  if (!value?.trim()) return null
  return <div className={`markdown-body ${className}`.trim()}>
    <ReactMarkdown remarkPlugins={[remarkGfm]} components={allowLinks
      ? { img: MarkdownImage }
      : { img: MarkdownImage, a: PlainMarkdownLink }}>
      {value}
    </ReactMarkdown>
  </div>
}

export function markdownExcerpt(value?: string | null) {
  if (!value) return ''
  return value
    .replace(/!\[[^\]]*]\([^)]*\)/g, '[图片]')
    .replace(/\[([^\]]+)]\([^)]*\)/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/[*_~`>|-]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}
