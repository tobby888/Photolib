import {
  App as AntApp, Button, Card, Dropdown, Grid, Layout, Menu, Space, Typography,
} from 'antd'
import type { MenuProps } from 'antd'
import {
  AppstoreOutlined, LogoutOutlined, MenuFoldOutlined, MenuUnfoldOutlined, SafetyCertificateOutlined,
} from '@ant-design/icons'
import { lazy, Suspense, useEffect, useState, type ReactNode } from 'react'
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from './auth'
import { BrandGlyph, useBranding } from './branding'
import { DataState, NotFound, PageTitle } from './components'
import { canManageTwoFactor } from './permissions'
import { loadMfaOverview, loadStepUpStatus, stepUpWithCode, stepUpWithSecurityKey } from './mfa'
import {
  ADMIN_GROUPS, ADMIN_SECTIONS, adminPath, adminSectionOf, legacyAdminRedirect, type AdminSection,
} from './adminNav'
import { ADMIN_SECTION_ICONS as ICONS } from './adminIcons'
import RouteErrorBoundary from './RouteErrorBoundary'
import TwoFactorVerify from './TwoFactorVerify'
import UserAvatar from './UserAvatar'

const AdminDashboardPage = lazy(() => import('./pages/admin/AdminDashboardPage'))
const BrandingSettingsPage = lazy(() => import('./pages/admin/BrandingSettingsPage'))
const SiteContentPage = lazy(() => import('./pages/admin/SiteContentPage'))
const UsersPage = lazy(() => import('./pages/admin/UsersPage'))
const RegistrationCodesPage = lazy(() => import('./pages/admin/RegistrationCodesPage'))
const RegistrationReviewPanel = lazy(() => import('./RegistrationReviewPanel'))
const PermissionGroupsPanel = lazy(() => import('./PermissionGroupsPanel'))
const CampusesPage = lazy(() => import('./pages/admin/CampusesPage'))
const MfaSettingsPanel = lazy(() => import('./MfaSettingsPanel'))
const AuditLogsPanel = lazy(() => import('./AuditLogsPanel'))
const DatabaseBackupPanel = lazy(() => import('./DatabaseBackupPanel'))
const UploadLimitsPanel = lazy(() => import('./UploadLimitsPanel'))
const StepUpHost = lazy(() => import('./StepUpHost'))

const { Header, Sider, Content } = Layout


/** 各项管理页的外框：标题 + 卡片。数据面板自己排版，不套这一层。 */
function SectionPage({ section, children }: { section: AdminSection; children: ReactNode }) {
  return <>
    <PageTitle eyebrow="ADMINISTRATION" title={section.label} description={section.description} />
    <Card className="admin-section-card">{children}</Card>
  </>
}

const section = (key: string) => ADMIN_SECTIONS.find(item => item.key === key)!

/**
 * 进入管理员面板前先验证两步验证（对管理员生效时）。验证一次 15 分钟内有效；
 * 过期后面板里的下一次请求会被后端拦下，由 {@link StepUpHost} 的再验证框接住，不用重新进来。
 * 门挡在各页外面，是为了让一进来就发出的那一串请求（数据面板每几秒还要轮询一次）
 * 不会各自弹一次验证框。
 */
function StepUpGate({ children }: { children: ReactNode }) {
  const [state, setState] = useState<'checking' | 'locked' | 'open'>('checking')
  const [methods, setMethods] = useState<('TOTP' | 'WEBAUTHN')[]>()
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false
    void loadStepUpStatus().then(async status => {
      if (cancelled) return
      if (!status.required || status.verified) { setState('open'); return }
      const overview = await loadMfaOverview().catch(() => null)
      if (cancelled) return
      setMethods(overview ? [...new Set(overview.devices.map(device => device.type))] : undefined)
      setState('locked')
    }).catch(failure => { if (!cancelled) setError((failure as Error).message) })
    return () => { cancelled = true }
  }, [])

  if (state === 'open') return children
  return <>
    <PageTitle eyebrow="ADMINISTRATION" title="管理员面板" description="进入管理员面板前，请先完成两步验证。" />
    <DataState loading={state === 'checking' && !error} error={error}>
      <Card className="admin-step-up-card" title={<span><SafetyCertificateOutlined /> 验证身份</span>}>
        <Typography.Paragraph type="secondary">验证后 15 分钟内，管理操作和删除操作不再询问。</Typography.Paragraph>
        <TwoFactorVerify methods={methods}
          onCode={async code => { await stepUpWithCode(code); setState('open') }}
          onSecurityKey={async () => { await stepUpWithSecurityKey(); setState('open') }} />
      </Card>
    </DataState>
  </>
}

/**
 * 管理员面板：与图库工作台分开的一套外壳，只对系统管理员组开放（后端各接口同样只认
 * 系统管理员，这里的判断只是不让别人看到一个会全部 403 的空壳）。
 * 数据面板在 `/admin`，原「系统管理」的各个页签现在各占一页，清单见 `adminNav.ts`。
 */
export default function AdminShell() {
  const { user, logout } = useAuth()
  const { message } = AntApp.useApp()
  const branding = useBranding()
  const navigate = useNavigate()
  const location = useLocation()
  const screens = Grid.useBreakpoint()
  const mobile = !screens.md
  const [collapsed, setCollapsed] = useState(false)
  useEffect(() => {
    setCollapsed(mobile)
  }, [mobile])

  if (!user) return <Navigate to="/login" replace state={{ from: location }} />
  if (user.mfa?.enrollmentRequired) {
    return <Navigate to={`/two-factor?next=${encodeURIComponent(`${location.pathname}${location.search}`)}`} replace />
  }
  if (user.mustChangePassword) return <Navigate to="/initial-password" replace />
  if (user.permissionGroupCode !== 'ADMIN') return <Navigate to="/" replace />
  // 旧地址 /admin?tab=xxx（站内信里存着的注册审核链接、老书签）改写到新页面。
  const legacy = legacyAdminRedirect(location.pathname, location.search)
  if (legacy) return <Navigate to={legacy} replace />

  const current = adminSectionOf(location.pathname)
  const menuItems: MenuProps['items'] = ADMIN_GROUPS.map(group => ({
    type: 'group' as const,
    key: group,
    label: collapsed ? null : group,
    children: ADMIN_SECTIONS.filter(item => item.group === group).map(item => ({
      key: adminPath(item.key), icon: ICONS[item.key], label: item.label,
    })),
  }))
  const backToGallery = () => navigate('/')

  return <Layout className="app-shell admin-shell">
    <Sider className="side-nav admin-side-nav" width={236} collapsedWidth={mobile ? 0 : 72}
      collapsed={collapsed} breakpoint="md" trigger={null} theme="light">
      <div className="brand" onClick={() => navigate('/admin')}>
        <div className={`brand-mark ${(branding.displayIconType ?? branding.iconType) === 'custom' ? 'brand-mark-custom' : ''}`}>
          <BrandGlyph branding={branding} />
        </div>
        {!collapsed && <div className="brand-copy">
          <strong className="brand-title">管理员面板</strong>
          <span title={branding.title}>{branding.title}</span>
        </div>}
      </div>
      <div className="side-nav-scroll">
        <Menu theme="light" mode="inline" selectedKeys={current ? [adminPath(current.key)] : []} items={menuItems}
          onClick={({ key }) => { navigate(key); if (mobile) setCollapsed(true) }} />
      </div>
      <div className="side-foot">
        <Button block className="admin-back-button" icon={<AppstoreOutlined />} onClick={backToGallery}
          aria-label="返回图库面板">
          {!collapsed && '返回图库面板'}
        </Button>
      </div>
    </Sider>
    <Layout>
      <Header className="topbar">
        <div className="topbar-leading">
          <Button type="text" className="collapse-button" aria-label={collapsed ? '展开侧栏' : '收起侧栏'}
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed(!collapsed)} />
          <div className="topbar-context">
            <span>管理员面板 /</span>
            <strong>{current?.label ?? '页面不存在'}</strong>
          </div>
        </div>
        <div className="topbar-actions">
          <Button icon={<AppstoreOutlined />} onClick={backToGallery}>{mobile ? '图库' : '返回图库面板'}</Button>
          <Dropdown menu={{ items: [
            { key: 'gallery', icon: <AppstoreOutlined />, label: '返回图库面板', onClick: backToGallery },
            { type: 'divider' },
            { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', danger: true,
              onClick: async () => { await logout(); message.success('已安全退出'); navigate('/login') } },
          ] }}>
            <Space className="user-menu">
              <UserAvatar avatarUrl={user.avatarUrl} label={user.displayName} style={{ background: '#edf3f0', color: '#28594f' }} />
              {!mobile && <div><strong>{user.displayName}</strong><span>{user.permissionGroupName || user.role}</span></div>}
            </Space>
          </Dropdown>
        </div>
      </Header>
      <Content className="content admin-content">
        {/* 门放在按地址重挂的 route-stage 外面：切换管理页不必每次重查一遍再验证状态。 */}
        <StepUpGate><div className="route-stage" key={location.pathname}>
          <RouteErrorBoundary><Suspense fallback={<div className="route-loading">正在打开管理员面板…</div>}><Routes>
            <Route index element={<AdminDashboardPage />} />
            <Route path="branding" element={<SectionPage section={section('branding')}><BrandingSettingsPage /></SectionPage>} />
            <Route path="site" element={<SectionPage section={section('site')}><SiteContentPage /></SectionPage>} />
            <Route path="users" element={<SectionPage section={section('users')}><UsersPage /></SectionPage>} />
            <Route path="registration-codes" element={<SectionPage section={section('registration-codes')}><RegistrationCodesPage /></SectionPage>} />
            <Route path="registration-review" element={<SectionPage section={section('registration-review')}><RegistrationReviewPanel /></SectionPage>} />
            <Route path="permissions" element={<SectionPage section={section('permissions')}><PermissionGroupsPanel /></SectionPage>} />
            <Route path="campuses" element={<SectionPage section={section('campuses')}><CampusesPage /></SectionPage>} />
            <Route path="two-factor" element={<SectionPage section={section('two-factor')}><MfaSettingsPanel /></SectionPage>} />
            <Route path="audit-logs" element={<SectionPage section={section('audit-logs')}><AuditLogsPanel /></SectionPage>} />
            <Route path="backups" element={<SectionPage section={section('backups')}><DatabaseBackupPanel /></SectionPage>} />
            <Route path="upload-limits" element={<SectionPage section={section('upload-limits')}><UploadLimitsPanel /></SectionPage>} />
            <Route path="*" element={<NotFound />} />
          </Routes></Suspense></RouteErrorBoundary>
        </div></StepUpGate>
      </Content>
    </Layout>
    {canManageTwoFactor(user) && <Suspense fallback={null}><StepUpHost /></Suspense>}
  </Layout>
}
