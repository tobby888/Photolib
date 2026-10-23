/**
 * 富文本编辑器的粘贴、拖放与长度规则。
 *
 * 判断逻辑写成纯函数放在这里，方便脱离浏览器单测；真正动 DOM 的几个函数也集中在这里，
 * 编辑器组件只负责把事件接进来。
 */

/**
 * 正文 HTML 的长度上限。后端四个入口都是 `@Size(max = 20000)`：反馈、反馈回复、
 * 管理消息、好图精选的征集要求。算的是 HTML 长度，标签和图片地址都占字数。
 */
export const RICH_TEXT_MAX_CHARS = 20000

export const RICH_TEXT_TOO_LONG =
  `正文内容过长：含格式和图片地址在内最多 ${RICH_TEXT_MAX_CHARS} 个字符，请删减部分文字或图片`

/** 正文插图允许的类型，与后端 InlineImageUpload、MessageImageController 的白名单一致。 */
export const INLINE_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const
export const INLINE_IMAGE_ACCEPT = INLINE_IMAGE_TYPES.join(',')
export const INLINE_IMAGE_TYPE_HINT = '仅支持 JPEG、PNG 或 WebP 图片'

export function isInlineImageType(type: string) {
  return (INLINE_IMAGE_TYPES as readonly string[]).includes(type)
}

/** 服务端生成的图片 ID：26 位 Crockford Base32，和后端清洗用的正则一致。 */
const IMAGE_ID = /^[0-9A-HJKMNP-TV-Z]{26}$/

/** HTML 片段里有没有看得见的文字。注释、样式表、空格和 &nbsp; 都不算。 */
export function htmlHasVisibleText(html: string) {
  const text = html
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<(script|style|head|title|xml)\b[\s\S]*?<\/\1\s*>/gi, '')
    .replace(/<[^>]*>/g, '')
    .replace(/&nbsp;|&#160;|&#xa0;/gi, ' ')
  return text.trim() !== ''
}

export type PastePlan<F> =
  | { kind: 'native' }
  | { kind: 'html' }
  | { kind: 'upload'; images: F[]; skipped: number }
  | { kind: 'unsupported' }

/**
 * 决定一次粘贴或拖放怎么处理。
 *
 * 剪贴板里有文字时一律按文字处理，即使同时带着图片：Windows 上从 Excel、Word、WPS 复制，
 * 剪贴板会顺带放一张选区截图，Chrome 会把它当成 image/png 文件交给网页。
 * 只有「纯图片」才上传，比如截图、浏览器里「复制图片」、聊天软件里复制的图。
 *
 * 有 HTML 时只看 HTML 里有没有文字：Firefox「复制图片」会在 text/plain 里放图片地址，
 * 不能因此把它当成文字。
 *
 * @param files 剪贴板里的图片文件（type 以 image/ 开头的那些）
 */
export function planPaste<F extends { type: string }>({ html, text, files }: {
  html: string
  text: string
  files: F[]
}): PastePlan<F> {
  const hasText = html ? htmlHasVisibleText(html) : text.trim() !== ''
  if (!hasText && files.length) {
    const images = files.filter(file => isInlineImageType(file.type))
    if (!images.length) return { kind: 'unsupported' }
    return { kind: 'upload', images, skipped: files.length - images.length }
  }
  if (html) return { kind: 'html' }
  return { kind: 'native' }
}

/** 插入正文的图片标签。alt 里去掉会被转义的字符，免得实际长度比预估的长。 */
export function imageHtml(src: string, fileName: string) {
  return `<p><img src="${src}" alt="${fileName.replace(/[<>"&]/g, '')}"></p>`
}

/** 上传前预估这张图插进正文要占多少字符，地址按真实长度的占位 ID 算。 */
export function estimateImageHtmlLength(uploadUrl: string, fileName: string) {
  return imageHtml(`/api/v1${uploadUrl}/${'0'.repeat(26)}`, fileName).length
}

function escapedLength(text: string) {
  return text.length + (text.match(/&/g)?.length ?? 0) * 4 + (text.match(/[<>]/g)?.length ?? 0) * 3
}

/**
 * 预估一次输入会让正文 HTML 变长多少，用来在 beforeinput 阶段就挡住超长输入。
 * 只是估算：输入法组合、粘贴进来的换行等估不准的情况，由输入后的兜底检查处理。
 *
 * @param selectedChars 当前选中的文字长度，输入会替换掉它们
 */
export function estimateInputGrowth(inputType: string, text: string | null, selectedChars: number) {
  switch (inputType) {
    case 'insertText':
    case 'insertReplacementText':
    case 'insertFromPaste':
    case 'insertFromDrop':
      return escapedLength(text ?? '') - selectedChars
    case 'insertParagraph':
      return '<div><br></div>'.length - selectedChars
    case 'insertLineBreak':
      return '<br>'.length - selectedChars
    default:
      return 0
  }
}

/* ---------- 以下需要浏览器 DOM ---------- */

/**
 * 编辑器里站内图片的真实地址。图片要带鉴权头才读得到，编辑器里显示的是下载好的 Blob 地址，
 * 真实地址存在这个属性上；交给调用方和计算长度时用 {@link serializeEditorHtml} 换回来。
 */
export const EDITOR_IMAGE_SOURCE_ATTR = 'data-photolib-src'

/** 编辑器内容的「保存形态」：图片地址换回站内路径，去掉只在编辑器里用的属性。 */
export function serializeEditorHtml(root: HTMLElement) {
  if (!root.querySelector(`img[${EDITOR_IMAGE_SOURCE_ATTR}]`)) return root.innerHTML
  const copy = root.cloneNode(true) as HTMLElement
  for (const image of Array.from(copy.querySelectorAll(`img[${EDITOR_IMAGE_SOURCE_ATTR}]`))) {
    image.setAttribute('src', image.getAttribute(EDITOR_IMAGE_SOURCE_ATTR) ?? '')
    image.removeAttribute(EDITOR_IMAGE_SOURCE_ATTR)
  }
  return copy.innerHTML
}

/** 与后端 Jsoup Safelist.basic() + p/div/h1-h3/img 对应的标签白名单。 */
const ALLOWED_TAGS = new Set([
  'a', 'b', 'blockquote', 'br', 'cite', 'code', 'dd', 'dl', 'dt', 'em', 'i', 'li', 'ol', 'p', 'pre', 'q',
  'small', 'span', 'strike', 'strong', 'sub', 'sup', 'u', 'ul', 'div', 'h1', 'h2', 'h3', 'img',
])
/** 连同内容一起丢掉的标签。 */
const DROPPED_TAGS = new Set([
  'script', 'style', 'head', 'title', 'meta', 'link', 'template', 'xml', 'iframe', 'object', 'embed',
  'noscript', 'svg', 'math', 'button', 'input', 'select', 'textarea',
])
/** 后端不收、但丢掉标签会把前后文字粘在一起的块级元素，换成 div / p 保留分行。 */
const BLOCK_REPLACEMENTS: Record<string, string> = {
  table: 'div', tr: 'div', caption: 'div', section: 'div', article: 'div', header: 'div', footer: 'div',
  aside: 'div', nav: 'div', main: 'div', figure: 'div', figcaption: 'div', address: 'div',
  h4: 'p', h5: 'p', h6: 'p',
}

/** 把同站图片的绝对地址还原成相对路径；外站、data:、file:、blob: 地址返回 null。 */
function sameSitePath(src: string | null, origin: string) {
  if (!src) return null
  try {
    const url = new URL(src, origin)
    return url.origin === origin ? url.pathname : null
  } catch {
    return null
  }
}

/**
 * 按后端白名单清洗粘贴进来的 HTML，结果和保存后看到的一致。
 *
 * 不清洗的话，网页、Word 里的 base64 图片和大段内联样式会直接撑爆 20000 字符，
 * 而这些内容后端反正也会剥掉。图片只保留本编辑器上传地址下的站内图，其余计数后去掉，
 * 由调用方提示用户单独复制图片。
 *
 * @param imagePrefix 允许保留的图片路径前缀，比如 `/api/v1/notifications/images/`
 */
export function sanitizePastedHtml(html: string, imagePrefix: string, origin = window.location.origin) {
  const source = new DOMParser().parseFromString(html, 'text/html')
  const container = source.createElement('div')
  let removedImages = 0

  const copy = (from: Node, to: Node, inPre: boolean) => {
    for (const child of Array.from(from.childNodes)) {
      if (child.nodeType === Node.TEXT_NODE) {
        const text = child.textContent ?? ''
        // 只有空白且带换行的文本是 HTML 源码里的缩进（Excel、Word 都有），留着会变成行首行尾的空格。
        if (!inPre && /^\s*$/.test(text) && /[\r\n]/.test(text)) continue
        to.appendChild(source.createTextNode(inPre ? text : text.replace(/[\t\n\r ]+/g, ' ')))
        continue
      }
      if (child.nodeType !== Node.ELEMENT_NODE) continue
      const element = child as Element
      const tag = element.tagName.toLowerCase()
      if (DROPPED_TAGS.has(tag)) continue
      if (tag === 'img') {
        // 从本编辑器里复制出来的图，src 是 Blob 地址，真实地址在 data 属性上。
        const path = sameSitePath(
          element.getAttribute(EDITOR_IMAGE_SOURCE_ATTR) ?? element.getAttribute('src'), origin)
        if (!path || !path.startsWith(imagePrefix) || !IMAGE_ID.test(path.slice(imagePrefix.length))) {
          removedImages++
          continue
        }
        const image = source.createElement('img')
        image.setAttribute('src', path)
        for (const name of ['alt', 'title']) {
          const value = element.getAttribute(name)
          if (value) image.setAttribute(name, value)
        }
        to.appendChild(image)
        continue
      }
      // 表格单元格只留文字，同一行的格子用竖线隔开，免得 A1B1 粘成一串。
      if (tag === 'td' || tag === 'th') {
        if (element.previousElementSibling) to.appendChild(source.createTextNode(' | '))
        copy(element, to, inPre)
        continue
      }
      const target = BLOCK_REPLACEMENTS[tag] ?? (ALLOWED_TAGS.has(tag) ? tag : null)
      if (!target) {
        copy(element, to, inPre)
        continue
      }
      const clean = source.createElement(target)
      if (tag === 'a') {
        const href = element.getAttribute('href')?.trim()
        if (href && /^(https?:|mailto:)/i.test(href)) clean.setAttribute('href', href)
      }
      copy(element, clean, inPre || tag === 'pre')
      to.appendChild(clean)
    }
  }

  copy(source.body, container, false)
  return { html: container.innerHTML.trim(), removedImages }
}

/** 光标前面有多少个文字字符；光标不在 root 里时返回 null。 */
export function caretTextOffset(root: HTMLElement) {
  const selection = window.getSelection()
  if (!selection?.rangeCount) return null
  const range = selection.getRangeAt(0)
  if (!root.contains(range.startContainer)) return null
  const before = document.createRange()
  before.selectNodeContents(root)
  before.setEnd(range.startContainer, range.startOffset)
  return before.toString().length
}

/** 把光标放回第 offset 个文字字符之后；超出时放到末尾。 */
export function placeCaretAtTextOffset(root: HTMLElement, offset: number) {
  const range = document.createRange()
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  let remaining = offset
  let placed = false
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const length = node.textContent?.length ?? 0
    if (remaining <= length) {
      range.setStart(node, remaining)
      placed = true
      break
    }
    remaining -= length
  }
  if (!placed) range.selectNodeContents(root)
  range.collapse(placed)
  const selection = window.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(range)
}
