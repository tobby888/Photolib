import { useBranding } from './branding'

/**
 * 全站页脚。文案和链接都由管理员在系统管理里配置，对所有角色（以及登录页、
 * 公开招募页这类未登录场景）显示同一份内容；两项都为空时整块不渲染，
 * 免得给没配过的部署留一条空白横条。
 *
 * 文案按纯文本渲染（换行靠 CSS 的 white-space），链接的协议在后端按白名单
 * 校验过——页脚是每个页面都挂着的东西，这里既不接受 HTML 也不接受伪协议。
 */
export default function SiteFooter({ className = '' }: { className?: string }) {
  const branding = useBranding()
  const text = branding.footerText?.trim()
  const links = branding.footerLinks ?? []
  if (!text && !links.length) return null
  return <footer className={`site-footer ${className}`.trim()}>
    {text && <span className="site-footer-text">{text}</span>}
    {!!links.length && <span className="site-footer-links">
      {links.map(link => <a key={`${link.label}-${link.url}`} href={link.url}
        target={link.url.startsWith('#/') ? undefined : '_blank'}
        rel="noreferrer noopener">{link.label}</a>)}
    </span>}
  </footer>
}
