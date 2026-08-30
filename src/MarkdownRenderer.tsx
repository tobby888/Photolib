import { useEffect, useState, type ComponentPropsWithoutRef } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { http } from './api'

/**
 * 正文里允许出现的图片来源，只有这两处，都是本站上传接口回吐的地址。
 * 任意外链一律拒绝渲染：Markdown 正文由人手写，放行外链等于允许在页面上
 * 引用第三方资源，既泄漏访客 IP，也让内容随对方站点变化。
 */
const IMAGE_PREFIXES = ['/api/v1/description-images/', '/api/v1/public/docs/assets/']

/**
 * 图片一律通过 axios 取 blob，而不是直接交给 <img src>。
 * 说明图片需要 Authorization 头；文档插图虽然挂在 /public/ 下，
 * 但"仅限成员"的文档里的插图同样要带令牌才拿得到——
 * <img> 标签不会带上 localStorage 里的令牌，直接用会让成员看到一片碎图。
 */
function MarkdownImage({ src, alt, ...props }: ComponentPropsWithoutRef<'img'>) {
  const [objectUrl, setObjectUrl] = useState<string>()
  const [failed, setFailed] = useState(false)
  const protectedImage = typeof src === 'string' && IMAGE_PREFIXES.some(prefix => src.startsWith(prefix))
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

  if (unsupportedImage) return <span className="markdown-image-error">仅支持通过编辑器上传的图片</span>
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
