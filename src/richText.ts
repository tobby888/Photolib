/**
 * 富文本正文是否算「空」：剥掉标签后没有可见文本，且不含图片。
 *
 * 管理消息发送、反馈提交、反馈回复三处共用同一条判断，抽出来免得各写一遍走样。
 */
export function richTextIsEmpty(html: string) {
  return !html.replace(/<[^>]+>/g, '').trim() && !html.includes('<img')
}
