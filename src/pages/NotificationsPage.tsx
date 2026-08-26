import { App, Badge, Button, Card, Form, Input, List, Modal, Radio, Select, Space, Typography } from 'antd'
import { NotificationOutlined, SendOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { DataState, PageTitle } from '../components'
import { useAuth } from '../auth'
import { useLoad } from '../hooks'
import type { MessageRecipient, Notification } from '../types'
import RichTextEditor from '../RichTextEditor'
import { hasPermission } from '../permissions'

export default function NotificationsPage() {
  const { user } = useAuth()
  const { message } = App.useApp()
  const navigate = useNavigate()
  const canSend = hasPermission(user, 'MESSAGE_SEND')
  const [open, setOpen] = useState(false)
  const [sending, setSending] = useState(false)
  const [broadcast, setBroadcast] = useState(true)
  const [contentHtml, setContentHtml] = useState('')
  const [form] = Form.useForm()
  const { data, loading, error, reload } = useLoad(
    () => api<Notification[]>({ url: '/notifications' }), [] as Notification[], [],
  )
  const { data: members } = useLoad(
    () => canSend
      ? api<MessageRecipient[]>({ url: '/users/message-recipients' })
      : Promise.resolve([] as MessageRecipient[]),
    [] as MessageRecipient[], [canSend],
  )
  const send = async () => {
    try {
      const values = await form.validateFields()
      if (!contentHtml.replace(/<[^>]+>/g, '').trim() && !contentHtml.includes('<img')) {
        throw new Error('请输入消息内容')
      }
      setSending(true)
      const result = await api<{ recipientCount: number }>({
        method: 'POST', url: '/notifications/messages',
        data: { broadcast, targetUserId: broadcast ? null : values.targetUserId, title: values.title, contentHtml },
      })
      message.success(`消息已发送给 ${result.recipientCount} 位成员`)
      setOpen(false); form.resetFields(); setContentHtml(''); setBroadcast(true)
      await reload()
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setSending(false)
    }
  }
  return <>
    <PageTitle eyebrow="MESSAGE CENTER" title="消息中心" description="查看工作通知与管理消息。"
      extra={canSend && <Button type="primary" icon={<SendOutlined />} onClick={() => setOpen(true)}>发送消息</Button>} />
    <Card>
      <DataState loading={loading} error={error} empty={!data.length} onRetry={reload}
        emptyText="还没有收到消息"
        emptyHint="需求发布、工时审核这些事发生时，通知会送到这里。">
        <List dataSource={data} renderItem={(item) =>
          <List.Item className="message-list-item" onClick={() => navigate(`/notifications/${item.id}`)}>
            <Badge dot={!item.readAt} offset={[-3, 4]}>
              <div className="message-list-icon"><NotificationOutlined /></div>
            </Badge>
            <div className="message-list-main">
              <Space><Typography.Text strong={!item.readAt}>{item.title}</Typography.Text>
                {(item.eventType === 'BROADCAST_MESSAGE' || item.eventType === 'DIRECT_MESSAGE') &&
                  <Typography.Text type="secondary">管理消息</Typography.Text>}
              </Space>
              <Typography.Paragraph type="secondary" ellipsis={{ rows: 1 }}>{item.content}</Typography.Paragraph>
              <Typography.Text type="secondary">{dayjs(item.createdAt).format('YYYY-MM-DD HH:mm')}</Typography.Text>
            </div>
          </List.Item>} />
      </DataState>
    </Card>
    <Modal title="发送消息" open={open} width={760} confirmLoading={sending} onOk={() => void send()}
      okText="发送" cancelText="取消" onCancel={() => setOpen(false)} destroyOnHidden>
      <Form form={form} layout="vertical">
        <Form.Item label="发送范围">
          <Radio.Group value={broadcast} onChange={(event) => setBroadcast(event.target.value)}>
            <Radio.Button value>广播给全部成员</Radio.Button>
            <Radio.Button value={false}>单独发送</Radio.Button>
          </Radio.Group>
        </Form.Item>
        {!broadcast && <Form.Item name="targetUserId" label="接收成员" rules={[{ required: true, message: '请选择接收成员' }]}>
          <Select showSearch optionFilterProp="label" placeholder="搜索并选择成员"
            options={members.map((member) => ({
              value: member.id, label: `${member.displayName} · ${member.permissionGroupName}`,
            }))} />
        </Form.Item>}
        <Form.Item name="title" label="消息标题" rules={[{ required: true }, { max: 100 }]}>
          <Input placeholder="请输入消息标题" />
        </Form.Item>
        <Form.Item label="消息正文" required>
          <RichTextEditor value={contentHtml} onChange={setContentHtml} />
        </Form.Item>
      </Form>
    </Modal>
  </>
}
