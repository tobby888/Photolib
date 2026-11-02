import { Typography } from 'antd'
import { useState } from 'react'

/**
 * 清算单 / 邮件的显示框：正文放进沙箱 iframe，永远不给 allow-scripts。模板是管理员手写的 HTML，
 * 不能让它碰到站点的登录态。
 *
 * - 默认（发送前预览、模板编辑器）：`sandbox=""`，不同源、链接点不动，固定高度。
 * - `interactive`（站内信里的清算单）：允许点链接在新标签页打开图片详情和选题；允许同源只是为了
 *   量出正文高度、让 iframe 跟着内容撑开，没有脚本权限时它什么也做不了。
 */
export default function MailPreviewFrame({ subject, html, height = 620, interactive = false }: {
  subject?: string
  html: string
  height?: number
  interactive?: boolean
}) {
  const [contentHeight, setContentHeight] = useState<number | null>(null)
  return <div className="mail-preview">
    {subject !== undefined && <div className="mail-preview-subject">
      <Typography.Text type="secondary">主题</Typography.Text><strong>{subject}</strong></div>}
    <iframe className="mail-preview-frame" title={subject ? `清算单：${subject}` : '清算单'} srcDoc={html}
      sandbox={interactive ? 'allow-same-origin allow-popups allow-popups-to-escape-sandbox' : ''}
      style={{ height: interactive && contentHeight ? contentHeight : height }}
      onLoad={interactive ? event => {
        const doc = event.currentTarget.contentDocument
        if (doc) setContentHeight(Math.max(200, doc.documentElement.scrollHeight))
      } : undefined} />
  </div>
}
