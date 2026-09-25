import { App, Button, Card, Space, Tag, Typography } from 'antd'
import { ArrowLeftOutlined, SendOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useState } from 'react'
import type { ReactNode } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { DataState } from '../components'
import { useAuth } from '../auth'
import { useLoad } from '../hooks'
import type { FeedbackDetail, FeedbackStatus } from '../types'
import { FEEDBACK_CATEGORY_LABEL, FEEDBACK_STATUS_COLOR, FEEDBACK_STATUS_LABEL } from '../feedback'
import { richTextIsEmpty } from '../richText'
import RichTextEditor from '../RichTextEditor'
import RichTextContent from '../RichTextContent'

const IMAGE_PREFIX = '/api/v1/notifications/images/'

/** 一条反馈的线程：标题、状态、正文、回复与状态流转按时间串起来。 */
export default function FeedbackDetailPage() {
  const { feedbackId } = useParams()
  const navigate = useNavigate()
  const { user } = useAuth()
  const { message } = App.useApp()
  const isAdmin = user?.permissionGroupCode === 'ADMIN'
  const { data, loading, error, reload } = useLoad(
    () => api<FeedbackDetail>({ url: `/feedback/${feedbackId}` }),
    null as FeedbackDetail | null, [feedbackId],
  )
  const [replyHtml, setReplyHtml] = useState('')
  const [sending, setSending] = useState(false)

  const reply = async () => {
    if (richTextIsEmpty(replyHtml)) { message.error('请填写回复内容'); return }
    try {
      setSending(true)
      await api({ method: 'POST', url: `/feedback/${feedbackId}/reply`, data: { contentHtml: replyHtml } })
      setReplyHtml('')
      await reload()
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setSending(false)
    }
  }

  const changeStatus = async (status: FeedbackStatus) => {
    if (!data) return
    try {
      await api({ method: 'PATCH', url: `/feedback/${feedbackId}/status`,
        data: { status, version: data.version } })
      await reload()
    } catch (error) {
      message.error((error as Error).message)
    }
  }

  const timeline = data ? buildTimeline(data) : []

  return <div className="message-detail">
    <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/notifications')}>返回消息中心</Button>
    <Card>
      <DataState loading={loading} error={error} empty={!data} onRetry={reload}
        emptyText="这条反馈不存在"
        emptyHint="它可能已被移除，回消息中心看看其他的。">
        {data && <>
          <div style={{ marginBottom: 16 }}>
            <Typography.Title level={4} style={{ margin: 0 }}>{data.title}</Typography.Title>
            <Space wrap style={{ marginTop: 8 }}>
              <Tag>{FEEDBACK_CATEGORY_LABEL[data.category]}</Tag>
              <Tag color={FEEDBACK_STATUS_COLOR[data.status]} variant="filled">{FEEDBACK_STATUS_LABEL[data.status]}</Tag>
            </Space>
            <div style={{ marginTop: 6 }}>
              <Typography.Text type="secondary">
                提交人 {data.submitterName} · {dayjs(data.createdAt).format('YYYY-MM-DD HH:mm')}
              </Typography.Text>
            </div>
          </div>
          {isAdmin && <Space wrap style={{ marginBottom: 16 }}>
            <Typography.Text type="secondary">改状态：</Typography.Text>
            {(['PENDING', 'IN_PROGRESS', 'RESOLVED'] as FeedbackStatus[]).map((status) => (
              <Button key={status} size="small" type={data.status === status ? 'primary' : 'default'}
                onClick={() => void changeStatus(status)}>{FEEDBACK_STATUS_LABEL[status]}</Button>
            ))}
          </Space>}
          <div>
            <div style={{ marginBottom: 20, paddingLeft: 14, borderLeft: '3px solid #4682B4' }}>
              <Space>
                <Typography.Text strong>{data.submitterName}</Typography.Text>
                <Typography.Text type="secondary">{dayjs(data.createdAt).format('MM-DD HH:mm')}</Typography.Text>
              </Space>
              <div style={{ marginTop: 6 }}>
                <RichTextContent value={data.contentHtml || data.content} imagePrefix={IMAGE_PREFIX} />
              </div>
            </div>
            {timeline}
          </div>
          <div style={{ marginTop: 20 }}>
            <RichTextEditor value={replyHtml} onChange={setReplyHtml} placeholder="输入回复……" />
            <Button type="primary" icon={<SendOutlined />} loading={sending} onClick={() => void reply()}
              style={{ marginTop: 8 }}>回复</Button>
          </div>
        </>}
      </DataState>
    </Card>
  </div>
}

function buildTimeline(data: FeedbackDetail) {
  const items: { time: string; node: ReactNode }[] = []
  data.replies.forEach((reply) => items.push({
    time: reply.createdAt,
    node: <div key={`reply-${reply.id}`} style={{ marginBottom: 16, paddingLeft: 14, borderLeft: '3px solid #b0e0e6' }}>
      <Space>
        <Typography.Text strong>
          {reply.authorName}{reply.authorId === data.submitterId ? '（提交人）' : '（管理员）'}
        </Typography.Text>
        <Typography.Text type="secondary">{dayjs(reply.createdAt).format('MM-DD HH:mm')}</Typography.Text>
      </Space>
      <div style={{ marginTop: 6 }}>
        <RichTextContent value={reply.contentHtml || reply.content} imagePrefix={IMAGE_PREFIX} />
      </div>
    </div>,
  }))
  data.statusChanges.forEach((change) => items.push({
    time: change.createdAt,
    node: <div key={`status-${change.id}`} style={{ marginBottom: 16, color: '#60798a' }}>
      <Space>
        <Tag>状态</Tag>
        <span>{FEEDBACK_STATUS_LABEL[change.fromStatus ?? 'PENDING']} → {FEEDBACK_STATUS_LABEL[change.toStatus]}</span>
        <Typography.Text type="secondary">{change.operatorName} · {dayjs(change.createdAt).format('MM-DD HH:mm')}</Typography.Text>
      </Space>
    </div>,
  }))
  return items.sort((a, b) => a.time.localeCompare(b.time)).map((item) => item.node)
}
