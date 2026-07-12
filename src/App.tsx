import {
  App as AntApp, Avatar, Badge, Button, Divider, Dropdown, Empty, Grid, Layout, List, Menu, Popover, Space, Typography,
} from 'antd'
import {
  AimOutlined, BarChartOutlined, BellOutlined, BookOutlined, BulbOutlined, CameraOutlined, ContactsOutlined,
  DashboardOutlined, EnvironmentOutlined, FolderOutlined, LogoutOutlined, MenuFoldOutlined, MenuUnfoldOutlined, SettingOutlined,
  MessageOutlined, PictureOutlined, StarOutlined, UnorderedListOutlined, UserOutlined,
} from '@ant-design/icons'
import { lazy, Suspense, useEffect, useMemo, useState } from 'react'
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from './auth'
import { roleName, NotFound } from './components'
import { api } from './api'
import type { BrandingSettings, Notification } from './types'
import dayjs from 'dayjs'

const LoginPage = lazy(() => import('./pages/LoginPage'))
const InitialPasswordPage = lazy(() => import('./pages/InitialPasswordPage'))
const DashboardPage = lazy(() => import('./pages/DashboardPage'))
const ProjectsPage = lazy(() => import('./pages/ProjectsPage'))
const ProjectDetailPage = lazy(() => import('./pages/ProjectDetailPage'))
const RequestsPage = lazy(() => import('./pages/RequestsPage'))
const RequestDeliveryPage = lazy(() => import('./pages/RequestDeliveryPage'))
const PhotosPage = lazy(() => import('./pages/PhotosPage'))
const WorklogsPage = lazy(() => import('./pages/WorklogsPage'))
const DirectoryPage = lazy(() => import('./pages/DirectoryPage'))
const StatisticsPage = lazy(() => import('./pages/StatisticsPage'))
const ManagerCampusesPage = lazy(() => import('./pages/ManagerCampusesPage'))
const AdminPage = lazy(() => import('./pages/AdminPage'))
const NotificationsPage = lazy(() => import('./pages/NotificationsPage'))
const NotificationDetailPage = lazy(() => import('./pages/NotificationDetailPage'))

const { Header, Sider, Content } = Layout
const defaultBranding: BrandingSettings = {
  title: 'PhotoLib', iconType: 'builtin', builtinIcon: 'camera', slogan: '摄影工作站',
}
const brandIcons = {
  camera: <CameraOutlined />, aperture: <AimOutlined />, picture: <PictureOutlined />,
  bulb: <BulbOutlined />, star: <StarOutlined />,
}

function Shell() {
  const { user, logout } = useAuth()
  const { message } = AntApp.useApp()
  const navigate = useNavigate()
  const location = useLocation()
  const screens = Grid.useBreakpoint()
  const [collapsed, setCollapsed] = useState(false)
  const [branding, setBranding] = useState<BrandingSettings>(defaultBranding)
  const [notifications, setNotifications] = useState<Notification[]>([])
  const [unreadCount, setUnreadCount] = useState(0)
  const [notificationOpen, setNotificationOpen] = useState(false)
  const mobile = !screens.md
  useEffect(() => {
    setCollapsed(mobile)
  }, [mobile])
  useEffect(() => {
    const loadBranding = () => void api<BrandingSettings>({ url: '/branding' })
      .then(setBranding).catch(() => setBranding(defaultBranding))
    loadBranding()
    window.addEventListener('branding-updated', loadBranding)
    return () => window.removeEventListener('branding-updated', loadBranding)
  }, [])
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
    void loadNotifications()
    const timer = window.setInterval(() => void loadNotifications(), 30_000)
    return () => window.clearInterval(timer)
  }, [])
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
    const common = [
      { key: '/', icon: <DashboardOutlined />, label: '工作台' },
      { key: '/projects', icon: <FolderOutlined />, label: '选题项目' },
      { key: '/requests', icon: <UnorderedListOutlined />, label: '图片需求' },
      { key: '/photos', icon: <CameraOutlined />, label: '图片库' },
      { key: '/worklogs', icon: <BookOutlined />, label: '工时记录' },
      { key: '/directory', icon: <ContactsOutlined />, label: '通讯录' },
      { key: '/notifications', icon: <MessageOutlined />, label: '消息中心' },
    ]
    if (user?.role !== 'CAMPUS_MANAGER') common.push(
      { key: '/statistics', icon: <BarChartOutlined />, label: '数据统计' },
      { key: '/manager-campuses', icon: <EnvironmentOutlined />, label: '负责人校区' },
    )
    if (user?.role === 'ADMIN') common.push(
      { key: '/admin', icon: <SettingOutlined />, label: '系统管理' },
    )
    return common
  }, [user?.role])

  if (!user) return <Navigate to="/login" replace state={{ from: location }} />
  if (user.mustChangePassword) return <Navigate to="/initial-password" replace />
  const selected = location.pathname === '/' ? '/' : `/${location.pathname.split('/')[1]}`

  return <Layout className="app-shell">
    <Sider className="side-nav" width={258} collapsedWidth={mobile ? 0 : 76}
      collapsed={collapsed} breakpoint="md" trigger={null}>
      <div className="brand" onClick={() => navigate('/')}>
        <div className={`brand-mark ${branding.iconType === 'custom' ? 'brand-mark-custom' : ''}`}>
          {branding.iconType === 'custom' && branding.customIconUrl
            ? <img src={branding.customIconUrl} alt="" />
            : brandIcons[branding.builtinIcon] || brandIcons.camera}
        </div>
        {!collapsed && <div><strong>{branding.title}</strong><span>{branding.slogan}</span></div>}
      </div>
      {!collapsed && <Typography.Text className="nav-section">工作空间</Typography.Text>}
      <Menu theme="dark" mode="inline" selectedKeys={[selected]} items={nav}
        onClick={({ key }) => { navigate(key); if (mobile) setCollapsed(true) }} />
      <div className="side-foot">
        {!collapsed && <div className="storage-note">
          <span>工作流</span><strong>拍摄 · 归档 · 采纳</strong>
        </div>}
      </div>
    </Sider>
    <Layout>
      <Header className="topbar">
        <Button type="text" className="collapse-button"
          icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
          onClick={() => setCollapsed(!collapsed)} />
        <div className="topbar-actions">
          <Popover open={notificationOpen} onOpenChange={(open) => {
            setNotificationOpen(open)
            if (open) void loadNotifications()
          }} trigger="click" placement="bottomRight" content={<div className="notification-panel">
            <div className="notification-head">
              <Typography.Text strong>消息通知</Typography.Text>
              <Button type="link" size="small" disabled={!unreadCount} onClick={() => void markAllRead()}>
                全部已读
              </Button>
            </div>
            <Divider />
            {notifications.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无消息" /> :
              <List dataSource={notifications} renderItem={(item) =>
                <List.Item className={`notification-item ${item.readAt ? '' : 'unread'}`}
                  onClick={() => void markRead(item)}>
                  <div className="notification-dot" />
                  <div>
                    <Typography.Text strong={!item.readAt}>{item.title}</Typography.Text>
                    {item.content && <Typography.Paragraph ellipsis={{ rows: 2 }}>{item.content}</Typography.Paragraph>}
                    <Typography.Text type="secondary">{dayjs(item.createdAt).format('MM-DD HH:mm')}</Typography.Text>
                  </div>
                </List.Item>} />}
            <Button block type="link" onClick={() => { setNotificationOpen(false); navigate('/notifications') }}>
              查看全部消息
            </Button>
          </div>}>
            <Badge count={unreadCount} size="small" overflowCount={99}>
              <Button aria-label="消息通知" type="text" shape="circle" icon={<BellOutlined />} />
            </Badge>
          </Popover>
          <Dropdown menu={{ items: [
            { key: 'profile', icon: <UserOutlined />, label: user.displayName },
            { type: 'divider' },
            { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', danger: true,
              onClick: async () => { await logout(); message.success('已安全退出'); navigate('/login') } },
          ] }}>
            <Space className="user-menu">
              <Avatar style={{ background: '#e9b16c', color: '#173b35' }}>{user.displayName.slice(0, 1)}</Avatar>
              {!mobile && <div><strong>{user.displayName}</strong><span>{roleName[user.role]}</span></div>}
            </Space>
          </Dropdown>
        </div>
      </Header>
      <Content className="content">
        <Suspense fallback={<div className="route-loading">正在整理工作台…</div>}><Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/projects/:projectId" element={<ProjectDetailPage />} />
          <Route path="/requests" element={<RequestsPage />} />
          <Route path="/requests/:requestId" element={<RequestDeliveryPage />} />
          <Route path="/photos" element={<PhotosPage />} />
          <Route path="/worklogs" element={<WorklogsPage />} />
          <Route path="/directory" element={<DirectoryPage />} />
          <Route path="/notifications" element={<NotificationsPage />} />
          <Route path="/notifications/:notificationId" element={<NotificationDetailPage />} />
          <Route path="/statistics" element={user.role === 'CAMPUS_MANAGER' ? <Navigate to="/" /> : <StatisticsPage />} />
          <Route path="/manager-campuses" element={user.role === 'CAMPUS_MANAGER' ? <Navigate to="/" /> : <ManagerCampusesPage />} />
          <Route path="/admin" element={user.role === 'ADMIN' ? <AdminPage /> : <Navigate to="/" />} />
          <Route path="*" element={<NotFound />} />
        </Routes></Suspense>
      </Content>
    </Layout>
  </Layout>
}

export default function App() {
  const { user } = useAuth()
  return <Suspense fallback={<div className="route-loading">正在进入 PhotoLib…</div>}><Routes>
    <Route path="/login" element={user ? <Navigate to={user.mustChangePassword ? '/initial-password' : '/'} replace /> : <LoginPage />} />
    <Route path="/initial-password" element={!user ? <Navigate to="/login" replace /> :
      user.mustChangePassword ? <InitialPasswordPage /> : <Navigate to="/" replace />} />
    <Route path="/*" element={<Shell />} />
  </Routes></Suspense>
}
