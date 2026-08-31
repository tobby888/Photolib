import {
  Alert, App as AntApp, Badge, Button, Dropdown, Grid, Layout, Menu, Popover, Progress, Result, Space, Typography,
} from 'antd'
import {
  BarChartOutlined, BellOutlined, BookOutlined, CameraOutlined, ContactsOutlined,
  DashboardOutlined, EnvironmentOutlined, FolderOutlined, LogoutOutlined, MenuFoldOutlined, MenuUnfoldOutlined, SettingOutlined,
  MessageOutlined, ReadOutlined, StarOutlined, TeamOutlined, TrophyOutlined,
  UnorderedListOutlined, UserOutlined,
} from '@ant-design/icons'
import { lazy, Suspense, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from './auth'
import { NotFound } from './components'
import { api } from './api'
import type { BrandingSettings, Notification, PreviewGenerationStatus } from './types'
import { BrandGlyph, useBranding } from './branding'
import { hasAnyPermission, hasPermission, hasSystemAccess } from './permissions'
import UserAvatar from './UserAvatar'

const LoginPage = lazy(() => import('./pages/LoginPage'))
const InitialPasswordPage = lazy(() => import('./pages/InitialPasswordPage'))
const DashboardPage = lazy(() => import('./pages/DashboardPage'))
const ProjectsPage = lazy(() => import('./pages/ProjectsPage'))
const ProjectDetailPage = lazy(() => import('./pages/ProjectDetailPage'))
const RequestsPage = lazy(() => import('./pages/RequestsPage'))
const RequestDeliveryPage = lazy(() => import('./pages/RequestDeliveryPage'))
const PhotosPage = lazy(() => import('./pages/PhotosPage'))
const PhotoDetailPage = lazy(() => import('./pages/PhotoDetailPage'))
const BatchUploadPage = lazy(() => import('./pages/BatchUploadPage'))
const WorklogsPage = lazy(() => import('./pages/WorklogsPage'))
const DirectoryPage = lazy(() => import('./pages/DirectoryPage'))
const StatisticsPage = lazy(() => import('./pages/StatisticsPage'))
const ManagerCampusesPage = lazy(() => import('./pages/ManagerCampusesPage'))
const AdminPage = lazy(() => import('./pages/AdminPage'))
const NotificationsPage = lazy(() => import('./pages/NotificationsPage'))
const NotificationDetailPage = lazy(() => import('./pages/NotificationDetailPage'))
const PublicRecruitmentPage = lazy(() => import('./pages/PublicRecruitmentPage'))
const DocsPage = lazy(() => import('./pages/DocsPage'))
const DocumentsPage = lazy(() => import('./pages/DocumentsPage'))
const FeaturedCollectionsPage = lazy(() => import('./pages/FeaturedCollectionsPage'))
const FeaturedCollectionDetailPage = lazy(() => import('./pages/FeaturedCollectionDetailPage'))
const RecruitmentsPage = lazy(() => import('./pages/RecruitmentsPage'))
const RecruitmentDetailPage = lazy(() => import('./pages/RecruitmentDetailPage'))
const RecruitmentApplicationDetailPage = lazy(() => import('./pages/RecruitmentApplicationDetailPage'))
const AvatarSettingsModal = lazy(() => import('./AvatarSettingsModal'))
const NotificationPanel = lazy(() => import('./NotificationPanel'))

const { Header, Sider, Content } = Layout

function BrandCopy({ title, slogan }: Pick<BrandingSettings, 'title' | 'slogan'>) {
  const copyRef = useRef<HTMLDivElement>(null)
  const titleRef = useRef<HTMLElement>(null)

  useLayoutEffect(() => {
    const copy = copyRef.current
    const titleElement = titleRef.current
    if (!copy || !titleElement) return

    let active = true
    const fitTitle = () => {
      titleElement.style.fontSize = ''
      const availableWidth = Math.max(0, copy.clientWidth - 1)
      const requiredWidth = titleElement.scrollWidth
      const maximumFontSize = Number.parseFloat(window.getComputedStyle(titleElement).fontSize)
      if (!availableWidth || !requiredWidth || !maximumFontSize || requiredWidth <= availableWidth) return

      const calculatedFontSize = Math.floor(maximumFontSize * availableWidth / requiredWidth * 10) / 10
      titleElement.style.fontSize = `${Math.max(10, calculatedFontSize)}px`
    }

    fitTitle()
    const observer = new ResizeObserver(fitTitle)
    observer.observe(copy)
    void document.fonts.ready.then(() => {
      if (active) fitTitle()
    })
    return () => {
      active = false
      observer.disconnect()
    }
  }, [title])

  return <div className="brand-copy" ref={copyRef}>
    <strong className="brand-title" ref={titleRef} title={title}>{title}</strong>
    <span title={slogan}>{slogan}</span>
  </div>
}

function Shell() {
  const { user, logout } = useAuth()
  const { message } = AntApp.useApp()
  const navigate = useNavigate()
  const location = useLocation()
  const screens = Grid.useBreakpoint()
  const [collapsed, setCollapsed] = useState(false)
  const branding = useBranding()
  const [notifications, setNotifications] = useState<Notification[]>([])
  const [unreadCount, setUnreadCount] = useState(0)
  const [notificationOpen, setNotificationOpen] = useState(false)
  const [avatarSettingsOpen, setAvatarSettingsOpen] = useState(false)
  const [previewStatus, setPreviewStatus] = useState<PreviewGenerationStatus | null>(null)
  const previousPreviewState = useRef<PreviewGenerationStatus['status'] | undefined>(undefined)
  const mobile = !screens.md
  useEffect(() => {
    setCollapsed(mobile)
  }, [mobile])
  const loadNotifications = async () => {
    try {
      const [items, unread] = await Promise.all([
        api<Notification[]>({ url: '/notifications' }),
        api<{ count: number }>({ url: '/notifications/unread-count' }),
      ])
      setNotifications(items)
      setUnreadCount(unread.count)
    } catch {
      // The shell stays usable during a temporary notification service failure.
    }
  }
  useEffect(() => {
    if (user?.dataScope === 'NONE') return
    void loadNotifications()
    const timer = window.setInterval(() => void loadNotifications(), 30_000)
    return () => window.clearInterval(timer)
  }, [user?.dataScope])
  useEffect(() => {
    if (user?.dataScope === 'NONE') return
    let cancelled = false
    let timer: number | undefined
    const loadPreviewStatus = async () => {
      try {
        const current = await api<PreviewGenerationStatus>({ url: '/preview-generation/status' })
        if (cancelled) return
        if (previousPreviewState.current === 'GENERATING' && current.status === 'SUCCEEDED') {
          message.success('预览图生成完成，图库已自动刷新')
          window.dispatchEvent(new Event('preview-generation-succeeded'))
        }
        previousPreviewState.current = current.status
        setPreviewStatus(current)
        if (current.status === 'PENDING' || current.status === 'GENERATING') {
          timer = window.setTimeout(() => void loadPreviewStatus(), 3_000)
        }
      } catch {
        if (!cancelled) timer = window.setTimeout(() => void loadPreviewStatus(), 10_000)
      }
    }
    void loadPreviewStatus()
    return () => {
      cancelled = true
      if (timer !== undefined) window.clearTimeout(timer)
    }
  }, [message, user?.dataScope])
  const markRead = async (item: Notification) => {
    if (!item.readAt) {
      await api<void>({ method: 'post', url: `/notifications/${item.id}/read` })
      setNotifications((current) => current.map((value) =>
        value.id === item.id ? { ...value, readAt: new Date().toISOString() } : value))
      setUnreadCount((count) => Math.max(0, count - 1))
    }
    if (item.contentHtml) {
      setNotificationOpen(false)
      navigate(`/notifications/${item.id}`)
    } else if (item.actionUrl) {
      setNotificationOpen(false)
      navigate(item.actionUrl)
    }
  }
  const markAllRead = async () => {
    await api<void>({ method: 'post', url: '/notifications/read-all' })
    setNotifications((current) => current.map((item) => ({ ...item, readAt: item.readAt || new Date().toISOString() })))
    setUnreadCount(0)
  }
  const nav = useMemo(() => {
    const common = [{ key: '/', icon: <DashboardOutlined />, label: '工作台' }]
    if (hasPermission(user, 'PROJECT_VIEW')) common.push(
      { key: '/projects', icon: <FolderOutlined />, label: '选题项目' })
    if (hasPermission(user, 'REQUEST_VIEW')) common.push(
      { key: '/requests', icon: <UnorderedListOutlined />, label: '图片需求' })
    if (hasPermission(user, 'PHOTO_VIEW')) common.push(
      { key: '/photos', icon: <CameraOutlined />, label: '图片库' },
      { key: '/favorites', icon: <StarOutlined />, label: '收藏图片' })
    if (hasAnyPermission(user, 'WORKLOG_SUBMIT', 'WORKLOG_CONFIRM', 'WORKLOG_EXPORT')) common.push(
      { key: '/worklogs', icon: <BookOutlined />, label: '工时记录' })
    if (hasAnyPermission(user, 'DIRECTORY_VIEW', 'DIRECTORY_MANAGE')) common.push(
      { key: '/directory', icon: <ContactsOutlined />, label: '通讯录' })
    // 好图精选的查看与下载不设限，所以入口对每个能进系统的账号都显示；
    // 发布、删除和手动截止在页面内按 FEATURED_MANAGE 收起。
    common.push({ key: '/featured', icon: <TrophyOutlined />, label: '好图精选' })
    if (hasPermission(user, 'RECRUITMENT_VIEW')) common.push(
      { key: '/recruitments', icon: <TeamOutlined />, label: '成员招募' })
    // 文档中心对每个能进系统的账号都显示，不按 DOC_MANAGE 收起：
    // "需要登录才能看"的文档正是给普通成员准备的，按编辑权限藏入口
    // 等于让唯一能看到它们的人找不到入口。编辑器在页面内按权限收起。
    common.push({ key: '/documents', icon: <ReadOutlined />, label: '文档中心' })
    common.push({ key: '/notifications', icon: <MessageOutlined />, label: '消息中心' })
    if (hasPermission(user, 'STATISTICS_DOWNLOAD')) common.push(
      { key: '/statistics', icon: <BarChartOutlined />, label: '数据统计' })
    if (hasPermission(user, 'MANAGER_CAMPUS_ASSIGN')) common.push(
      { key: '/manager-campuses', icon: <EnvironmentOutlined />, label: '负责人校区' })
    if (user?.permissionGroupCode === 'ADMIN') common.push(
      { key: '/admin', icon: <SettingOutlined />, label: '系统管理' },
    )
    return common
  }, [user])

  if (!user) return <Navigate to="/login" replace state={{ from: location }} />
  if (user.mustChangePassword) return <Navigate to="/initial-password" replace />
  // 还没分配权限组的账号进不了工作台，但文档中心要留一个入口：新同学登录后
  // 第一件事往往就是读"要求登录才能看"的入部须知，而后端已经把这类会话当成
  // 已登录的成员来判可见范围（见 AccessTokenFilter）。
  if (!hasSystemAccess(user)) return <div className="access-pending-page">
    <Result status="403" title="账号暂未分配可用权限"
      subTitle="原权限组可能已被删除。您仍可登录账号，但暂时无法进入系统，请联系管理员重新分配权限组。文档中心仍然可以正常查看。"
      extra={<Space>
        <Button type="primary" icon={<ReadOutlined />}
          onClick={() => navigate('/docs')}>查看文档中心</Button>
        <Button onClick={async () => {
          await logout(); navigate('/login')
        }}>退出登录</Button>
      </Space>} />
  </div>
  const selected = location.pathname.startsWith('/recruitment-applications/')
    ? '/recruitments'
    : location.pathname === '/' ? '/' : `/${location.pathname.split('/')[1]}`
  return <Layout className="app-shell" onPointerMove={(event) => {
    event.currentTarget.style.setProperty('--pointer-x', `${event.clientX}px`)
    event.currentTarget.style.setProperty('--pointer-y', `${event.clientY}px`)
  }}>
    <Sider className="side-nav" width={236} collapsedWidth={mobile ? 0 : 72}
      collapsed={collapsed} breakpoint="md" trigger={null} theme="light">
      <div className="brand" onClick={() => navigate('/')}>
        <div className={`brand-mark ${(branding.displayIconType ?? branding.iconType) === 'custom' ? 'brand-mark-custom' : ''}`}>
          <BrandGlyph branding={branding} />
        </div>
        {!collapsed && <BrandCopy title={branding.title} slogan={branding.slogan} />}
      </div>
      <div className="side-nav-scroll">
        {!collapsed && <Typography.Text className="nav-section">工作空间</Typography.Text>}
        <Menu theme="light" mode="inline" selectedKeys={[selected]} items={nav}
          onClick={({ key }) => { navigate(key); if (mobile) setCollapsed(true) }} />
      </div>
      <div className="side-foot">
        {!collapsed && <div className="storage-note">
          <span>工作流</span><strong>拍摄 · 归档 · 采纳</strong>
        </div>}
      </div>
    </Sider>
    <Layout>
      <Header className="topbar">
        <div className="topbar-leading">
          <Button type="text" className="collapse-button"
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed(!collapsed)} />
          <div className="topbar-context">
            <span>{branding.title} /</span>
            <strong>{nav.find((item) => item.key === selected)?.label}</strong>
          </div>
        </div>
        <div className="topbar-actions">
          <Popover open={notificationOpen} onOpenChange={(open) => {
            setNotificationOpen(open)
            if (open) void loadNotifications()
          }} trigger="click" placement="bottomRight" content={
            // The element itself must stay truthy even while closed: antd keeps a popover with
            // empty content shut, which would leave the bell dead until the panel loads.
            <Suspense fallback={<div className="notification-panel notification-loading">正在加载消息…</div>}>
              {notificationOpen && <NotificationPanel notifications={notifications} unreadCount={unreadCount}
                onOpen={(item) => void markRead(item)} onMarkAllRead={() => void markAllRead()}
                onViewAll={() => { setNotificationOpen(false); navigate('/notifications') }} />}
            </Suspense>}>
            <Badge count={unreadCount} size="small" overflowCount={99}>
              <Button aria-label="消息通知" type="text" shape="circle" icon={<BellOutlined />} />
            </Badge>
          </Popover>
          <Dropdown menu={{ items: [
            { key: 'profile', icon: <UserOutlined />, label: '个人头像', onClick: () => setAvatarSettingsOpen(true) },
            { type: 'divider' },
            { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', danger: true,
              onClick: async () => { await logout(); message.success('已安全退出'); navigate('/login') } },
            ] }}>
            <Space className="user-menu">
              <UserAvatar avatarUrl={user.avatarUrl} label={user.displayName}
                style={{ background: '#edf3f0', color: '#28594f' }} />
              {!mobile && <div><strong>{user.displayName}</strong><span>{user.permissionGroupName || user.role}</span></div>}
            </Space>
          </Dropdown>
        </div>
      </Header>
      <Content className="content">
        {(previewStatus?.status === 'PENDING' || previewStatus?.status === 'GENERATING') &&
          <Alert className="preview-generation-alert" type="info" showIcon
            message="预览图正在后台生成"
            description={<div>
              <div>您可以正常使用系统；生成期间部分图片可能暂时没有预览图。</div>
              {previewStatus.status === 'GENERATING' && previewStatus.total > 0 &&
                <Progress size="small" percent={previewStatus.percentage}
                  format={() => `${previewStatus.processed}/${previewStatus.total}`} />}
            </div>} />}
        {previewStatus?.status === 'FAILED' &&
          <Alert className="preview-generation-alert" type="warning" showIcon
            message={previewStatus.message} description={previewStatus.errorMessage} />}
        <div className="route-stage" key={location.pathname}>
          <Suspense fallback={<div className="route-loading">正在整理工作台…</div>}><Routes>
            <Route path="/" element={<DashboardPage />} />
            <Route path="/projects" element={hasPermission(user, 'PROJECT_VIEW') ? <ProjectsPage /> : <Navigate to="/" />} />
            <Route path="/projects/:projectId" element={hasPermission(user, 'PROJECT_VIEW') ? <ProjectDetailPage /> : <Navigate to="/" />} />
            <Route path="/requests" element={hasPermission(user, 'REQUEST_VIEW') ? <RequestsPage /> : <Navigate to="/" />} />
            <Route path="/requests/:requestId" element={hasPermission(user, 'REQUEST_VIEW') ? <RequestDeliveryPage /> : <Navigate to="/" />} />
            <Route path="/photos" element={hasPermission(user, 'PHOTO_VIEW') ? <PhotosPage /> : <Navigate to="/" />} />
            <Route path="/photos/batch-upload" element={hasAnyPermission(user, 'PHOTO_UPLOAD', 'REQUEST_PHOTO_MANAGE') ? <BatchUploadPage /> : <Navigate to="/" />} />
            <Route path="/photos/:photoId" element={hasPermission(user, 'PHOTO_VIEW') ? <PhotoDetailPage /> : <Navigate to="/" />} />
            <Route path="/favorites" element={hasPermission(user, 'PHOTO_VIEW') ? <PhotosPage favoritesOnly /> : <Navigate to="/" />} />
            <Route path="/favorites/:photoId" element={hasPermission(user, 'PHOTO_VIEW') ? <PhotoDetailPage favoritesOnly /> : <Navigate to="/" />} />
            <Route path="/worklogs" element={hasAnyPermission(user, 'WORKLOG_SUBMIT', 'WORKLOG_CONFIRM', 'WORKLOG_EXPORT') ? <WorklogsPage /> : <Navigate to="/" />} />
            <Route path="/directory" element={hasAnyPermission(user, 'DIRECTORY_VIEW', 'DIRECTORY_MANAGE') ? <DirectoryPage /> : <Navigate to="/" />} />
            <Route path="/featured" element={<FeaturedCollectionsPage />} />
            <Route path="/featured/:collectionId" element={<FeaturedCollectionDetailPage />} />
            <Route path="/notifications" element={<NotificationsPage />} />
            <Route path="/notifications/:notificationId" element={<NotificationDetailPage />} />
            <Route path="/statistics" element={hasPermission(user, 'STATISTICS_DOWNLOAD') ? <StatisticsPage /> : <Navigate to="/" />} />
            <Route path="/manager-campuses" element={hasPermission(user, 'MANAGER_CAMPUS_ASSIGN') ? <ManagerCampusesPage /> : <Navigate to="/" />} />
            <Route path="/recruitments" element={hasPermission(user, 'RECRUITMENT_VIEW') ? <RecruitmentsPage /> : <Navigate to="/" />} />
            <Route path="/recruitments/:taskId" element={hasPermission(user, 'RECRUITMENT_VIEW') ? <RecruitmentDetailPage /> : <Navigate to="/" />} />
            <Route path="/recruitment-applications/:applicationId" element={hasPermission(user, 'RECRUITMENT_VIEW') ? <RecruitmentApplicationDetailPage /> : <Navigate to="/" />} />
            <Route path="/documents" element={<DocumentsPage />} />
            <Route path="/documents/:publicId" element={<DocumentsPage />} />
            <Route path="/admin" element={user.permissionGroupCode === 'ADMIN' ? <AdminPage /> : <Navigate to="/" />} />
            <Route path="*" element={<NotFound />} />
          </Routes></Suspense>
        </div>
      </Content>
    </Layout>
    {avatarSettingsOpen && <Suspense fallback={null}>
      <AvatarSettingsModal open onClose={() => setAvatarSettingsOpen(false)} />
    </Suspense>}
  </Layout>
}

export default function App() {
  const { user } = useAuth()
  const branding = useBranding()
  return <Suspense fallback={<div className="route-loading">正在进入{branding.title}…</div>}><Routes>
    <Route path="/login" element={user ? <Navigate to={user.mustChangePassword ? '/initial-password' : '/'} replace /> : <LoginPage />} />
    <Route path="/recruitment" element={user
      ? <Navigate to={user.mustChangePassword ? '/initial-password' : '/'} replace />
      : <PublicRecruitmentPage />} />
    {/*
      文档中心和招募页不同，登录用户不会被弹回工作台：文档对所有人都是要读的东西，
      而且登录之后能多看到"仅成员可见"的那部分，把登录用户挡在外面反而没道理。
    */}
    <Route path="/docs" element={<DocsPage />} />
    <Route path="/docs/:publicId" element={<DocsPage />} />
    <Route path="/initial-password" element={!user ? <Navigate to="/login" replace /> :
      user.mustChangePassword ? <InitialPasswordPage /> : <Navigate to="/" replace />} />
    <Route path="/*" element={<Shell />} />
  </Routes></Suspense>
}
