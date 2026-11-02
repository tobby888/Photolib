import { Typography } from 'antd'

/**
 * 邮件预览：正文放进不带任何权限的沙箱 iframe（`sandbox=""`：不跑脚本、不同源）。
 * 模板是管理员手写的 HTML，不能让它碰到站点的登录态。
 */
export default function MailPreviewFrame({ subject, html, height = 620 }: {
  subject: string
  html: string
  height?: number
}) {
  return <div className="mail-preview">
    <div className="mail-preview-subject"><Typography.Text type="secondary">主题</Typography.Text>
      <strong>{subject}</strong></div>
    <iframe className="mail-preview-frame" title="邮件预览" sandbox="" srcDoc={html} style={{ height }} />
  </div>
}
