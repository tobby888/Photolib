import { Button, Divider, Empty, List, Typography } from 'antd'
import dayjs from 'dayjs'
import type { Notification } from './types'

interface NotificationPanelProps {
  notifications: Notification[]
  unreadCount: number
  onOpen: (item: Notification) => void
  onMarkAllRead: () => void
  onViewAll: () => void
}

/**
 * Content of the bell popover. Kept in its own lazily loaded module: `List` drags in Pagination,
 * Select and Input, which is a sizeable part of the UI kit for a panel most sessions never open.
 */
export default function NotificationPanel({
  notifications, unreadCount, onOpen, onMarkAllRead, onViewAll,
}: NotificationPanelProps) {
  return <div className="notification-panel">
    <div className="notification-head">
      <Typography.Text strong>消息通知</Typography.Text>
      <Button type="link" size="small" disabled={!unreadCount} onClick={onMarkAllRead}>
        全部已读
      </Button>
    </div>
    <Divider />
    {notifications.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无消息" /> :
      <List dataSource={notifications} renderItem={(item) =>
        <List.Item className={`notification-item ${item.readAt ? '' : 'unread'}`}
          onClick={() => onOpen(item)}>
          <div className="notification-dot" />
          <div>
            <Typography.Text strong={!item.readAt}>{item.title}</Typography.Text>
            {item.content && <Typography.Paragraph ellipsis={{ rows: 2 }}>{item.content}</Typography.Paragraph>}
            <Typography.Text type="secondary">{dayjs(item.createdAt).format('MM-DD HH:mm')}</Typography.Text>
          </div>
        </List.Item>} />}
    <Button block type="link" onClick={onViewAll}>
      查看全部消息
    </Button>
  </div>
}
