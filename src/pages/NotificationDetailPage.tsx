import { Button, Card } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { useEffect } from 'react'
import { Navigate, useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { DataState } from '../components'
import { useLoad } from '../hooks'
import { statementIdFromActionUrl } from '../settlementMail'
import type { Notification } from '../types'

export default function NotificationDetailPage() {
  const { notificationId } = useParams()
  const navigate = useNavigate()
  const { data, loading, error } = useLoad(
    () => api<Notification>({ url: `/notifications/${notificationId}` }),
    null as Notification | null, [notificationId],
  )
  useEffect(() => {
    if (data && !data.readAt) void api<void>({ method: 'POST', url: `/notifications/${data.id}/read` })
  }, [data])
  // 清算单通知的正文只是摘要，完整内容在清算单页（从别处拿到这个地址时也能看到全文）。
  if (data && statementIdFromActionUrl(data.actionUrl)) return <Navigate to={data.actionUrl ?? '/notifications'} replace />
  return <div className="message-detail">
    <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/notifications')}>返回消息中心</Button>
    <Card>
      <DataState loading={loading} error={error} empty={!data}
        emptyText="这条消息不在了"
        emptyHint="可能已经被发送者撤下，回消息中心看看其他的。">
        <div className="message-content"
          dangerouslySetInnerHTML={{ __html: data?.contentHtml || `<p>${escapeHtml(data?.content || '')}</p>` }} />
      </DataState>
    </Card>
  </div>
}

function escapeHtml(value: string) {
  return value.replace(/[&<>"']/g, (character) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;',
  })[character] || character)
}
