import {
  App as AntApp, Avatar, Badge, Button, Dropdown, Grid, Layout, Menu, Space, Typography,
} from 'antd'
import {
  AimOutlined, BarChartOutlined, BellOutlined, BookOutlined, BulbOutlined, CameraOutlined, DashboardOutlined,
  FolderOutlined, LogoutOutlined, MenuFoldOutlined, MenuUnfoldOutlined, SettingOutlined,
  PictureOutlined, StarOutlined, UnorderedListOutlined, UserOutlined,
} from '@ant-design/icons'
import { lazy, Suspense, useEffect, useMemo, useState } from 'react'
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from './auth'
import { roleName, NotFound } from './components'
import { api } from './api'
import type { BrandingSettings } from './types'

const LoginPage = lazy(() => import('./pages/LoginPage'))
const InitialPasswordPage = lazy(() => import('./pages/InitialPasswordPage'))
const DashboardPage = lazy(() => import('./pages/DashboardPage'))
const ProjectsPage = lazy(() => import('./pages/ProjectsPage'))
const ProjectDetailPage = lazy(() => import('./pages/ProjectDetailPage'))
const RequestsPage = lazy(() => import('./pages/RequestsPage'))
const PhotosPage = lazy(() => import('./pages/PhotosPage'))
const WorklogsPage = lazy(() => import('./pages/WorklogsPage'))
const StatisticsPage = lazy(() => import('./pages/StatisticsPage'))
const AdminPage = lazy(() => import('./pages/AdminPage'))

const { Header, Sider, Content } = Layout
const defaultBranding: BrandingSettings = { icon: 'camera', slogan: '摄影工作站' }
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
  const mobile = !screens.md
  useEffect(() => {
    const loadBranding = () => void api<BrandingSettings>({ url: '/branding' })
      .then(setBranding).catch(() => setBranding(defaultBranding))
    loadBranding()
    window.addEventListener('branding-updated', loadBranding)
    return () => window.removeEventListener('branding-updated', loadBranding)
  }, [])
  const nav = useMemo(() => {
    const common = [
      { key: '/', icon: <DashboardOutlined />, label: '工作台' },
      { key: '/projects', icon: <FolderOutlined />, label: '选题项目' },
      { key: '/requests', icon: <UnorderedListOutlined />, label: '图片需求' },
      { key: '/photos', icon: <CameraOutlined />, label: '图片库' },
      { key: '/worklogs', icon: <BookOutlined />, label: '工时记录' },
    ]
    if (user?.role !== 'CAMPUS_MANAGER') common.push(
      { key: '/statistics', icon: <BarChartOutlined />, label: '数据统计' },
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
    <Sider className="side-nav" width={236} collapsedWidth={mobile ? 0 : 76}
      collapsed={mobile ? collapsed : collapsed} breakpoint="md" trigger={null}>
      <div className="brand" onClick={() => navigate('/')}>
        <div className="brand-mark">{brandIcons[branding.icon] || brandIcons.camera}</div>
        {!collapsed && <div><strong>PhotoLib</strong><span>{branding.slogan}</span></div>}
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
          <Badge dot><Button type="text" shape="circle" icon={<BellOutlined />} /></Badge>
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
          <Route path="/photos" element={<PhotosPage />} />
          <Route path="/worklogs" element={<WorklogsPage />} />
          <Route path="/statistics" element={user.role === 'CAMPUS_MANAGER' ? <Navigate to="/" /> : <StatisticsPage />} />
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
