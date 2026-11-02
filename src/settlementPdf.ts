/**
 * 把一份清算单（邮件 HTML）在浏览器里转成 A4 PDF。
 *
 * <p>为什么在前端做：服务器上不一定装了中文字体，后端出 PDF 要么方块字、要么得把十几 MB 的字体打进包里；
 * 浏览器本来就能正确排出这份 HTML。做法是放进一个离屏 iframe 排版，按 A4 比例分页截图，每页一张图，
 * 再把正文里的链接（图片详情、选题）按位置补成 PDF 里可点的链接。文字因此不能选中复制，换来的是
 * 和邮件里看到的一模一样。</p>
 *
 * <p>分页不硬切：先找出所有「不能从中间切开」的区域（每一行文字、图片，以及不超过半页高的小块，
 * 例如一条工时卡片、表格的一行），每页尽量在页尾往上找一条不穿过这些区域的线来断页。</p>
 *
 * <p>html2canvas / jsPDF 体积不小，只在真正导出时才加载。上面的分页计算是纯函数，node 单测直接测。</p>
 */

/** 排版宽度（CSS 像素）：内置模板正文 680 宽，两边留出背景。 */
export const PAGE_WIDTH_PX = 760
const PAGE_WIDTH_MM = 210
const PAGE_HEIGHT_MM = 297
const MM_PER_PX = PAGE_WIDTH_MM / PAGE_WIDTH_PX
export const PAGE_HEIGHT_PX = Math.floor(PAGE_HEIGHT_MM / MM_PER_PX)
/** 高于这个比例的块（整张卡片、外层布局表格）允许从中间分页，否则会整页留白。 */
const UNBREAKABLE_MAX = 0.5
/** 断页线最多往上找到页面的这个位置，再往上宁可切开。 */
const MIN_FILL = 0.55
/** 一次截多高再切成几页：太高会超出浏览器 canvas 的尺寸上限。 */
const CHUNK_PX = 7000
const SCALE = 2

export interface Band {
  top: number
  bottom: number
}

/**
 * 每一条水平线 y 能不能断页：返回的数组里 {@code blocked[y] > 0} 表示 y 会穿过某个不能切开的区域
 * （区域的上下边沿本身可以断）。比 {@code maxHeight} 高的区域不算。
 */
export function blockedRows(bands: Band[], total: number, maxHeight: number) {
  const diff = new Int32Array(total + 2)
  for (const band of bands) {
    const top = Math.max(0, Math.floor(band.top))
    const bottom = Math.min(total, Math.ceil(band.bottom))
    if (bottom - top <= 1 || bottom - top > maxHeight) continue
    diff[top + 1] += 1
    diff[bottom] -= 1
  }
  const blocked = new Int32Array(total + 1)
  let running = 0
  for (let y = 0; y <= total; y++) {
    running += diff[y]
    blocked[y] = running
  }
  return blocked
}

/**
 * 分页：返回每一页的起点（第一页是 0）。每页从页尾往上找第一条能断的线，
 * 找到页面 {@code minFill} 处还没有就硬切在页尾。
 */
export function pageStarts(blocked: ArrayLike<number>, total: number, pageHeight: number, minFill = MIN_FILL) {
  const starts = [0]
  let start = 0
  while (start + pageHeight < total) {
    const end = start + pageHeight
    const floor = start + Math.ceil(pageHeight * minFill)
    let next = end
    for (let y = end; y >= floor; y--) {
      if (!blocked[y]) { next = y; break }
    }
    starts.push(next)
    start = next
  }
  return starts
}

/** 截图分段：把相邻的几页合成一段一起截，每段不超过 {@code limit} 高（至少一页）。 */
export function chunkPages(starts: number[], total: number, limit = CHUNK_PX) {
  const chunks: { from: number; to: number; pages: number[] }[] = []
  let current: { from: number; to: number; pages: number[] } | null = null
  starts.forEach((pageStart, index) => {
    const pageEnd = index + 1 < starts.length ? starts[index + 1] : total
    if (current && pageEnd - current.from <= limit) {
      current.to = pageEnd
      current.pages.push(index)
    } else {
      current = { from: pageStart, to: pageEnd, pages: [index] }
      chunks.push(current)
    }
  })
  return chunks
}

export interface PdfOptions {
  title?: string
  /** 已渲染页数 / 总页数。 */
  onProgress?: (done: number, total: number) => void
}

/** 把清算单 HTML 转成 PDF。必须在浏览器里调用。 */
export async function statementPdf(html: string, options: PdfOptions = {}): Promise<Blob> {
  const [{ default: html2canvas }, { jsPDF }] = await Promise.all([import('html2canvas'), import('jspdf')])
  const frame = document.createElement('iframe')
  // 只给同源（要读里面的排版结果），不给脚本：模板是管理员手写的 HTML。
  frame.setAttribute('sandbox', 'allow-same-origin')
  frame.setAttribute('aria-hidden', 'true')
  frame.style.cssText = `position:fixed;left:-20000px;top:0;width:${PAGE_WIDTH_PX}px;height:${PAGE_HEIGHT_PX}px;border:0;`
  try {
    const loaded = new Promise<void>(resolve => { frame.onload = () => resolve() })
    frame.srcdoc = html
    document.body.appendChild(frame)
    await loaded
    const doc = frame.contentDocument
    if (!doc?.body) throw new Error('清算单内容无法排版')
    await Promise.all([...doc.images].map(image => image.complete ? undefined
      : new Promise(resolve => { image.onload = resolve; image.onerror = resolve })))
    await doc.fonts?.ready
    const total = Math.max(1, Math.ceil(doc.documentElement.scrollHeight))
    frame.style.height = `${total}px`

    const starts = pageStarts(blockedRows(collectBands(doc), total, PAGE_HEIGHT_PX * UNBREAKABLE_MAX),
      total, PAGE_HEIGHT_PX)
    const links = collectLinks(doc)
    const background = pageBackground(doc)
    const pdf = new jsPDF({ unit: 'mm', format: 'a4', orientation: 'portrait', compress: true })
    if (options.title) pdf.setProperties({ title: options.title })
    let done = 0
    for (const chunk of chunkPages(starts, total)) {
      const canvas = await html2canvas(doc.body, {
        x: 0, y: chunk.from, width: PAGE_WIDTH_PX, height: chunk.to - chunk.from,
        windowWidth: PAGE_WIDTH_PX, windowHeight: total, scale: SCALE,
        backgroundColor: background, logging: false, useCORS: false,
      })
      for (const index of chunk.pages) {
        const top = starts[index]
        const bottom = index + 1 < starts.length ? starts[index + 1] : total
        const page = document.createElement('canvas')
        page.width = Math.round(PAGE_WIDTH_PX * SCALE)
        page.height = Math.max(1, Math.round((bottom - top) * SCALE))
        const context = page.getContext('2d')
        if (!context) throw new Error('浏览器不支持生成 PDF')
        context.drawImage(canvas, 0, Math.round((top - chunk.from) * SCALE), page.width, page.height,
          0, 0, page.width, page.height)
        if (index > 0) pdf.addPage()
        pdf.setFillColor(background)
        pdf.rect(0, 0, PAGE_WIDTH_MM, PAGE_HEIGHT_MM, 'F')
        pdf.addImage(page.toDataURL('image/jpeg', 0.9), 'JPEG', 0, 0, PAGE_WIDTH_MM, (bottom - top) * MM_PER_PX)
        for (const link of links) {
          if (link.top < top || link.top >= bottom) continue
          pdf.link(link.left * MM_PER_PX, (link.top - top) * MM_PER_PX, link.width * MM_PER_PX,
            Math.min(link.height, bottom - link.top) * MM_PER_PX, { url: link.url })
        }
        options.onProgress?.(++done, starts.length)
      }
    }
    return pdf.output('blob')
  } finally {
    frame.remove()
  }
}

/** 不能切开的区域：每一行文字、每张图，以及所有元素（太高的在 {@link blockedRows} 里会被跳过）。 */
function collectBands(doc: Document): Band[] {
  const bands: Band[] = []
  const add = (rect: DOMRect) => {
    if (rect.height > 0 && rect.width > 0) bands.push({ top: rect.top, bottom: rect.bottom })
  }
  for (const element of doc.body.querySelectorAll('*')) add(element.getBoundingClientRect())
  const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT)
  const range = doc.createRange()
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    if (!node.textContent?.trim()) continue
    range.selectNodeContents(node)
    for (const rect of range.getClientRects()) add(rect)
  }
  return bands
}

function collectLinks(doc: Document) {
  const links: { url: string; left: number; top: number; width: number; height: number }[] = []
  for (const anchor of doc.querySelectorAll<HTMLAnchorElement>('a[href]')) {
    // srcdoc 的相对地址按外层页面解析，这里拿到的已经是完整地址。
    const url = anchor.href
    if (!/^https?:\/\//i.test(url)) continue
    for (const rect of anchor.getClientRects()) {
      if (rect.width > 0 && rect.height > 0) {
        links.push({ url, left: rect.left, top: rect.top, width: rect.width, height: rect.height })
      }
    }
  }
  return links
}

/** 页面底色：分页处没铺满的那一截用它补齐，不会露出一条白边。 */
function pageBackground(doc: Document) {
  const view = doc.defaultView
  for (const element of [doc.body, doc.documentElement]) {
    const color = view?.getComputedStyle(element).backgroundColor
    const hex = color ? cssColorToHex(color) : null
    if (hex) return hex
  }
  return '#ffffff'
}

/** {@code rgb(…)} / {@code rgba(…)} 转 #rrggbb；透明返回 null。 */
export function cssColorToHex(color: string) {
  const match = /^rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)(?:[\s,/]+([\d.]+%?))?\s*\)$/i.exec(color.trim())
  if (!match) return null
  if (match[4] !== undefined && parseFloat(match[4]) === 0) return null
  return '#' + [match[1], match[2], match[3]]
    .map(part => Math.min(255, Number(part)).toString(16).padStart(2, '0')).join('')
}

/** 触发浏览器下载。 */
export function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  // 立刻回收在部分浏览器里会让下载拿不到数据，留一点时间。
  window.setTimeout(() => URL.revokeObjectURL(url), 10_000)
}

/** 多份 PDF 打成一个 ZIP（PDF 里已经是 JPEG，不再压缩）。 */
export async function zipFiles(files: { name: string; data: Blob }[]) {
  const { zipSync } = await import('fflate')
  const entries: Record<string, [Uint8Array, { level: 0 }]> = {}
  for (const file of files) entries[file.name] = [new Uint8Array(await file.data.arrayBuffer()), { level: 0 }]
  const zipped = zipSync(entries)
  return new Blob([zipped.slice().buffer], { type: 'application/zip' })
}
