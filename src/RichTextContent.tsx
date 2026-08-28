import { createElement, useEffect, useState, type ReactNode } from 'react'
import { http } from './api'

const DESCRIPTION_IMAGE_PREFIX = '/api/v1/description-images/'

/** 允许渲染的标签，与服务端清洗用的安全列表对应。 */
const ALLOWED_TAGS = new Set([
  'p', 'div', 'br', 'span', 'b', 'strong', 'i', 'em', 'u', 'h1', 'h2', 'h3',
  'ul', 'ol', 'li', 'blockquote', 'code', 'pre',
])

/**
 * 受控富文本的只读渲染。
 *
 * <p>正文虽然在入库前已由服务端清洗，这里仍然重新解析并按标签白名单重建节点，
 * 而不是 <code>dangerouslySetInnerHTML</code>：历史数据、将来放宽的安全列表或某个
 * 绕过 Service 的写入路径，都不该直接变成页面上的可执行内容。</p>
 *
 * <p>图片必须带鉴权头才读得到，普通 <code>&lt;img src&gt;</code> 不会带上访问令牌，
 * 所以站内说明图片统一下载成 Blob 再渲染，卸载时回收 URL。</p>
 */
export default function RichTextContent({ value, className = '' }: {
  value?: string | null
  className?: string
}) {
  if (!value?.trim()) return null
  const parsed = new DOMParser().parseFromString(value, 'text/html')
  const children = Array.from(parsed.body.childNodes).map((node, index) => render(node, `n${index}`))
  return <div className={`rich-text-content ${className}`.trim()}>{children}</div>
}

function render(node: Node, key: string): ReactNode {
  if (node.nodeType === Node.TEXT_NODE) return node.textContent
  if (node.nodeType !== Node.ELEMENT_NODE) return null
  const element = node as Element
  const tag = element.tagName.toLowerCase()
  if (tag === 'img') {
    const src = element.getAttribute('src') || ''
    if (!src.startsWith(DESCRIPTION_IMAGE_PREFIX)) {
      return <span key={key} className="markdown-image-error">仅支持通过编辑器上传的图片</span>
    }
    return <ProtectedImage key={key} src={src} alt={element.getAttribute('alt') || ''} />
  }
  if (!ALLOWED_TAGS.has(tag)) {
    // 不认识的标签只保留它的文本，避免连带丢掉正文内容。
    return <span key={key}>{element.textContent}</span>
  }
  if (tag === 'br') return <br key={key} />
  const children = Array.from(element.childNodes).map((child, index) => render(child, `${key}-${index}`))
  return createElement(tag, { key }, children.length ? children : undefined)
}

function ProtectedImage({ src, alt }: { src: string; alt: string }) {
  const [objectUrl, setObjectUrl] = useState<string>()
  const [failed, setFailed] = useState(false)

  useEffect(() => {
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
  }, [src])

  if (failed) return <span className="markdown-image-error">图片加载失败：{alt || '未命名图片'}</span>
  return <img src={objectUrl} alt={alt} />
}

/** 富文本摘要，用于列表卡片。服务端已经存了纯文本投影，这里只做兜底。 */
export function richTextExcerpt(html?: string | null, limit = 120) {
  if (!html) return ''
  const text = new DOMParser().parseFromString(html, 'text/html').body.textContent || ''
  const collapsed = text.replace(/\s+/g, ' ').trim()
  return collapsed.length > limit ? `${collapsed.slice(0, limit)}…` : collapsed
}
