import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  INLINE_IMAGE_ACCEPT, INLINE_IMAGE_TYPES, RICH_TEXT_MAX_CHARS, estimateImageHtmlLength, estimateInputGrowth,
  htmlHasVisibleText, imageHtml, isInlineImageType, planPaste,
} from '../src/richTextPaste.ts'

const readBackend = (path: string) =>
  readFile(new URL(`../backend/src/main/java/cn/photolib/${path}`, import.meta.url), 'utf8')
const readSource = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')

const png = { type: 'image/png', name: 'image.png' }
const gif = { type: 'image/gif', name: 'anim.gif' }

test('截图这类纯图片剪贴板走上传', () => {
  assert.deepEqual(planPaste({ html: '', text: '', files: [png] }), { kind: 'upload', images: [png], skipped: 0 })
})

test('从 Excel 复制单元格：带文字的 HTML 加一张选区截图，按文字粘贴而不是上传截图', () => {
  const html = '<html><body><!--StartFragment--><table><tr><td>姓名</td><td>张三</td></tr></table>'
    + '<!--EndFragment--></body></html>'
  assert.deepEqual(planPaste({ html, text: '姓名\t张三\r\n', files: [png] }), { kind: 'html' })
})

test('从 Word 复制文字：样式表和注释不算文字，正文算', () => {
  const html = '<html><head><style>p.MsoNormal{margin:0}</style></head><body><!--StartFragment-->'
    + '<p class=MsoNormal>第一段<o:p></o:p></p><!--EndFragment--></body></html>'
  assert.deepEqual(planPaste({ html, text: '第一段', files: [png] }), { kind: 'html' })
})

test('浏览器「复制图片」：HTML 里只有 <img>，即使 text/plain 是图片地址也按图片上传', () => {
  const html = '<meta charset="utf-8"><img src="https://example.com/a.png" alt="">'
  assert.deepEqual(
    planPaste({ html, text: 'https://example.com/a.png', files: [png] }),
    { kind: 'upload', images: [png], skipped: 0 },
  )
})

test('Word 里只复制一张图：HTML 里只有 &nbsp; 和图片，按图片上传', () => {
  const html = '<p class=MsoNormal><img src="file:///C:/Users/a/clip_image001.png">&nbsp;<o:p></o:p></p>'
  assert.equal(planPaste({ html, text: '', files: [png] }).kind, 'upload')
})

test('只有后端不收的图片格式时提示不支持，混着的只跳过不支持的', () => {
  assert.deepEqual(planPaste({ html: '', text: '', files: [gif] }), { kind: 'unsupported' })
  assert.deepEqual(planPaste({ html: '', text: '', files: [gif, png] }), { kind: 'upload', images: [png], skipped: 1 })
})

test('纯文本交给浏览器原生粘贴；有 HTML 无图片时走清洗插入', () => {
  assert.deepEqual(planPaste({ html: '', text: 'hello', files: [] }), { kind: 'native' })
  assert.deepEqual(planPaste({ html: '', text: '', files: [] }), { kind: 'native' })
  assert.deepEqual(planPaste({ html: '<b>hi</b>', text: 'hi', files: [] }), { kind: 'html' })
  assert.deepEqual(planPaste({ html: '<img src="data:image/png;base64,AAAA">', text: '', files: [] }), { kind: 'html' })
})

test('htmlHasVisibleText 忽略标签、注释、样式和各种空格', () => {
  assert.equal(htmlHasVisibleText('<p>&nbsp;&#160; <br></p><!-- x --><style>a{}</style>'), false)
  assert.equal(htmlHasVisibleText('<p> a </p>'), true)
})

test('图片标签长度预估与真实插入的一致，alt 里会转义的字符被去掉', () => {
  const realUrl = '/api/v1/notifications/images/01HZX3K7Q8M9N0P1R2S3T4V5W6'
  assert.equal(estimateImageHtmlLength('/notifications/images', 'a&b<c>"d".png'),
    imageHtml(realUrl, 'a&b<c>"d".png').length)
  assert.equal(imageHtml(realUrl, 'a&b<c>"d".png'), `<p><img src="${realUrl}" alt="abcd.png"></p>`)
})

test('输入增长预估：文字按转义后长度算，减掉被替换的选中文字，删除不增长', () => {
  assert.equal(estimateInputGrowth('insertText', 'a<b', 0), 6)
  assert.equal(estimateInputGrowth('insertText', '&', 0), 5)
  assert.equal(estimateInputGrowth('insertText', 'abc', 2), 1)
  assert.equal(estimateInputGrowth('insertFromPaste', 'xy', 0), 2)
  assert.equal(estimateInputGrowth('insertParagraph', null, 0), 15)
  assert.equal(estimateInputGrowth('deleteContentBackward', null, 0), 0)
  assert.equal(estimateInputGrowth('formatBold', null, 0), 0)
})

test('正文长度上限与后端四个入口的 @Size 一致', async () => {
  const sources = await Promise.all([
    readBackend('feedback/FeedbackController.java'),
    readBackend('notification/UserNotificationController.java'),
    readBackend('featured/FeaturedCollectionController.java'),
  ])
  const limits = sources.flatMap(source =>
    Array.from(source.matchAll(/@Size\(max = ([\d_]+)\) String (?:contentHtml|requirementHtml)/g),
      match => Number(match[1].replaceAll('_', ''))))
  // 反馈提交、反馈回复、管理消息、征集要求新建、征集要求编辑
  assert.equal(limits.length, 5)
  assert.ok(limits.every(limit => limit === RICH_TEXT_MAX_CHARS))
})

test('插图类型白名单与后端两处上传入口一致，编辑器的文件选择框也用它', async () => {
  for (const path of ['common/upload/InlineImageUpload.java', 'notification/MessageImageController.java']) {
    const source = await readBackend(path)
    assert.match(source, /MediaType\.IMAGE_JPEG_VALUE, MediaType\.IMAGE_PNG_VALUE, "image\/webp"\)/, path)
  }
  assert.deepEqual([...INLINE_IMAGE_TYPES], ['image/jpeg', 'image/png', 'image/webp'])
  assert.equal(isInlineImageType('image/gif'), false)
  assert.match(await readSource('RichTextEditor.tsx'), /accept=\{INLINE_IMAGE_ACCEPT\}/)
  assert.equal(INLINE_IMAGE_ACCEPT, 'image/jpeg,image/png,image/webp')
})
