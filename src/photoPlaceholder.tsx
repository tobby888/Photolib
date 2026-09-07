import { useMemo, type ReactNode } from 'react'
import { useBranding } from './branding'
import { pickPlaceholderImage } from './placeholderImages'

export { pickPlaceholderImage }

/**
 * 缺图占位图：管理员在系统管理里传若干张，前端在图片取不到时任取一张顶上。
 * 三种"取不到"都走这里——图片压根没有预览地址、预览地址加载失败（对象存储
 * 故障或签名过期），以及图片已被软删除。一张都没配时退回原来的灰底占位，
 * 所以这个能力可以随时清空，不会把界面弄坏。
 */
export function usePlaceholderImages(): string[] {
  const branding = useBranding()
  return branding.placeholderImageUrls ?? []
}

export function usePlaceholderImage(seed?: string | number) {
  const images = usePlaceholderImages()
  return useMemo(() => pickPlaceholderImage(images, seed), [images, seed])
}

/**
 * 占位块。children 是没有配置占位图时的兜底内容（原来的首字圆点、图标等），
 * note 则是配了占位图之后压在图上的说明文字——"图片已从图库删除"这类信息
 * 不能因为换成好看的占位图就丢掉。
 */
export function PhotoPlaceholder({ seed, note, className = 'image-placeholder', children }: {
  seed?: string | number
  note?: ReactNode
  className?: string
  children?: ReactNode
}) {
  const imageUrl = usePlaceholderImage(seed)
  if (!imageUrl) return <div className={className}>{children}</div>
  return <div className={`${className} placeholder-with-image`}>
    <img src={imageUrl} alt="" />
    {note && <span className="placeholder-note">{note}</span>}
  </div>
}
