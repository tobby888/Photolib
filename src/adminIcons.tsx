import {
  AuditOutlined, BgColorsOutlined, CloudUploadOutlined, DashboardOutlined, DatabaseOutlined, EnvironmentOutlined,
  FileTextOutlined, IdcardOutlined, LayoutOutlined, SafetyCertificateOutlined, SafetyOutlined, TeamOutlined,
} from '@ant-design/icons'
import type { ReactNode } from 'react'

/** 管理员面板各页的图标，key 同 `adminNav.ts`。侧栏和数据面板的快捷入口共用。 */
export const ADMIN_SECTION_ICONS: Record<string, ReactNode> = {
  '': <DashboardOutlined />,
  branding: <BgColorsOutlined />,
  site: <LayoutOutlined />,
  users: <TeamOutlined />,
  'registration-codes': <IdcardOutlined />,
  'registration-review': <AuditOutlined />,
  permissions: <SafetyCertificateOutlined />,
  campuses: <EnvironmentOutlined />,
  'two-factor': <SafetyOutlined />,
  'audit-logs': <FileTextOutlined />,
  // 数据库备份/回滚只对系统管理员开放，刻意没有对应的权限项，因此不出现在权限面板里。
  backups: <DatabaseOutlined />,
  'upload-limits': <CloudUploadOutlined />,
}
