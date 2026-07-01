import { Alert, Button, Empty, Result, Skeleton, Space, Tag, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import type { ReactNode } from 'react'

const statusMap: Record<string, { text: string; color: string }> = {
  DRAFT: { text: '草稿', color: 'default' },
  ACTIVE: { text: '进行中', color: 'green' },
  COMPLETED: { text: '已完成', color: 'blue' },
  CANCELLED: { text: '已取消', color: 'red' },
  PUBLISHED: { text: '待接单', color: 'gold' },
  ACCEPTED: { text: '执行中', color: 'cyan' },
  SUBMITTED: { text: '待确认', color: 'purple' },
  CONFIRMED: { text: '已确认', color: 'green' },
  REJECTED: { text: '已退回', color: 'volcano' },
  AVAILABLE: { text: '可用', color: 'green' },
  PROCESSING: { text: '处理中', color: 'processing' },
  UPLOADING: { text: '上传中', color: 'processing' },
  ARCHIVED: { text: '已归档', color: 'default' },
  DELETED: { text: '已删除', color: 'red' },
}

export function StatusTag({ value }: { value: string }) {
  const item = statusMap[value] || { text: value, color: 'default' }
  return <Tag color={item.color} variant="filled">{item.text}</Tag>
}

export function PageTitle({ eyebrow, title, description, extra }: {
  eyebrow?: string; title: string; description?: string; extra?: ReactNode
}) {
  return <div className="page-heading">
    <div>
      {eyebrow && <Typography.Text className="eyebrow">{eyebrow}</Typography.Text>}
      <Typography.Title level={2}>{title}</Typography.Title>
      {description && <Typography.Paragraph type="secondary">{description}</Typography.Paragraph>}
    </div>
    {extra && <Space wrap>{extra}</Space>}
  </div>
}

export function DataState({ loading, error, empty, onRetry, children }: {
  loading: boolean; error?: string; empty?: boolean; onRetry?: () => void; children: ReactNode
}) {
  if (loading) return <Skeleton active paragraph={{ rows: 7 }} />
  if (error) return <Alert showIcon type="error" title="数据加载失败" description={error}
    action={onRetry && <Button icon={<ReloadOutlined />} onClick={onRetry}>重试</Button>} />
  if (empty) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂时没有数据" />
  return children
}

export function RoleGate({ allow, role, children }: { allow: string[]; role?: string; children: ReactNode }) {
  return allow.includes(role || '') ? children : null
}

export function NotFound() {
  return <Result status="404" title="页面不在镜头里" subTitle="它可能被移动或归档了。"
    extra={<Button type="primary" href="/">回到工作台</Button>} />
}

export const roleName: Record<string, string> = {
  ADMIN: '系统管理员',
  MINISTER: '摄影部部长',
  CAMPUS_MANAGER: '校区负责人',
}

export function formatMinutes(value = 0) {
  const hours = Math.floor(value / 60)
  const minutes = value % 60
  return hours ? `${hours} 小时${minutes ? ` ${minutes} 分` : ''}` : `${minutes} 分钟`
}

export function formatBytes(value = 0) {
  if (value < 1024 ** 2) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}
