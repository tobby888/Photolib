/**
 * 管理员面板的页面清单。
 *
 * 以前这些功能是「系统管理」里的一排页签（`/admin?tab=users`），现在每项各占一页
 * （`/admin/users`），数据面板在 `/admin`。清单放在这里而不是外壳组件里，是为了
 * 让 node 单测能直接核对：侧栏、路由和旧链接的改写用的是同一份 key。
 *
 * 不依赖 React 和请求层；图标由 `AdminShell` 按 key 配。
 */

export interface AdminSection {
  /** 地址里的那一段：`/admin/<key>`。数据面板的 key 为空串，地址就是 `/admin`。 */
  key: string
  label: string
  /** 侧栏分组。 */
  group: AdminSectionGroup
  description: string
}

export type AdminSectionGroup = '概览' | '站点外观' | '成员与权限' | '安全与运维'

export const ADMIN_SECTIONS: readonly AdminSection[] = [
  { key: '', label: '数据面板', group: '概览', description: '注册用户、系统流量、QPS 与运行状态，可自行增减小面板。' },
  { key: 'branding', label: '面板品牌', group: '站点外观', description: '品牌名称、Slogan、图标与定时图标。' },
  { key: 'site', label: '页面定制', group: '站点外观', description: '登录页文案、全站页脚与缺图占位图。' },
  { key: 'users', label: '账号管理', group: '成员与权限', description: '创建、停用、重置和删除成员账号。' },
  { key: 'registration-codes', label: '注册码', group: '成员与权限', description: '生成注册码，让同学自助申请账号。' },
  { key: 'registration-review', label: '注册审核', group: '成员与权限', description: '审核持注册码提交的注册申请。' },
  { key: 'permissions', label: '权限管理', group: '成员与权限', description: '权限组、数据范围与图库可见范围。' },
  { key: 'campuses', label: '校区管理', group: '成员与权限', description: '校区资源与启用状态。' },
  { key: 'two-factor', label: '两步验证', group: '安全与运维', description: '全站两步验证开关与各组策略。' },
  { key: 'audit-logs', label: '操作日志', group: '安全与运维', description: '查询与导出写操作审计记录。' },
  { key: 'backups', label: '数据备份', group: '安全与运维', description: '数据库备份、导入与回滚。' },
  { key: 'upload-limits', label: '上传限额', group: '安全与运维', description: '各类上传的大小与数量上限。' },
]

export const ADMIN_GROUPS: readonly AdminSectionGroup[] = ['概览', '站点外观', '成员与权限', '安全与运维']

export const adminPath = (key: string) => key ? `/admin/${key}` : '/admin'

/** 当前地址对应哪一项；认不出的子路径返回 undefined（交给 404）。 */
export function adminSectionOf(pathname: string): AdminSection | undefined {
  const match = /^\/admin(?:\/([^/]+))?\/?$/.exec(pathname)
  if (!match) return undefined
  return ADMIN_SECTIONS.find(section => section.key === (match[1] ?? ''))
}

/**
 * 旧地址 `/admin?tab=<key>` 改写到新页面。站内信里存着的「有新的注册申请待审核」
 * 还指向 `/admin?tab=registration-review`，已经发出去的那些不能因为改版就失效。
 * 不是旧地址（或 tab 认不出）时返回 null，照常显示数据面板。
 */
export function legacyAdminRedirect(pathname: string, search: string): string | null {
  if (pathname !== '/admin' && pathname !== '/admin/') return null
  const tab = new URLSearchParams(search).get('tab')
  if (!tab) return null
  // 旧面板的默认页签是「面板品牌」，老书签里的 ?tab=branding 也落到那一页。
  const section = ADMIN_SECTIONS.find(item => item.key && item.key === tab)
  return section ? adminPath(section.key) : null
}
