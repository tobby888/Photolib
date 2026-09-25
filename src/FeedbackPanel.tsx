import { App, Button, Input, List, Modal, Radio, Select, Space, Tag, Typography } from 'antd'
import { MessageOutlined, PlusOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, emptyPage } from './api'
import { DataState } from './components'
import { useAuth } from './auth'
import { useLoad } from './hooks'
import type { FeedbackCategory, FeedbackStatus, FeedbackSummary, PageData } from './types'
import { FEEDBACK_CATEGORY_LABEL, FEEDBACK_STATUS_COLOR, FEEDBACK_STATUS_LABEL } from './feedback'
import { richTextIsEmpty } from './richText'
import RichTextEditor from './RichTextEditor'

/**
 * 消息中心的「反馈」标签：成员提交网站问题/建议，ADMIN 看全量并按状态筛选。
 * 提交后是轻量工单，点开进 `/notifications/feedback/{id}` 的线程页。
 */
const PAGE_SIZE = 20

export default function FeedbackPanel() {
  const { user } = useAuth()
  const { message } = App.useApp()
  const navigate = useNavigate()
  const isAdmin = user?.permissionGroupCode === 'ADMIN'
  const [status, setStatus] = useState<FeedbackStatus | ''>('')
  const [page, setPage] = useState(1)
  const [open, setOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [title, setTitle] = useState('')
  const [category, setCategory] = useState<FeedbackCategory>('ISSUE')
  const [contentHtml, setContentHtml] = useState('')

  const { data, loading, error, reload } = useLoad(
    () => api<PageData<FeedbackSummary>>({
      url: '/feedback', params: { page, pageSize: PAGE_SIZE, ...(status ? { status } : {}) },
    }),
    emptyPage<FeedbackSummary>(), [status, page],
  )

  const submit = async () => {
    if (!title.trim()) { message.error('请填写标题'); return }
    if (richTextIsEmpty(contentHtml)) { message.error('请填写反馈内容'); return }
    try {
      setSubmitting(true)
      await api({ method: 'POST', url: '/feedback', data: { title, category, contentHtml } })
      message.success('反馈已提交')
      setOpen(false)
      setTitle('')
      setCategory('ISSUE')
      setContentHtml('')
      // 新反馈排在第一页最前面；已经在第一页就直接刷新，否则切页会触发重新加载。
      if (page === 1) await reload()
      else setPage(1)
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  return <div>
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, marginBottom: 12, flexWrap: 'wrap' }}>
      {isAdmin && <Select allowClear placeholder="全部状态" style={{ width: 160 }} value={status || undefined}
        onChange={(value) => { setStatus((value as FeedbackStatus) || ''); setPage(1) }}
        options={[{ value: 'PENDING', label: '待处理' }, { value: 'IN_PROGRESS', label: '处理中' },
          { value: 'RESOLVED', label: '已解决' }]} />}
      <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>我要反馈</Button>
    </div>
    <DataState loading={loading} error={error} empty={!data.items.length} onRetry={reload}
      emptyText={isAdmin ? '还没有收到反馈' : '你还没有提交过反馈'}
      emptyHint={isAdmin ? '成员提交的网站问题与建议会出现在这里。' : '点「我要反馈」报告网站问题或提建议。'}>
      <List dataSource={data.items}
        pagination={data.total > PAGE_SIZE ? {
          current: page, pageSize: PAGE_SIZE, total: data.total, showSizeChanger: false, onChange: setPage,
        } : false}
        renderItem={(item) => (
        <List.Item className="message-list-item" onClick={() => navigate(`/notifications/feedback/${item.id}`)}>
          <div className="message-list-icon"><MessageOutlined /></div>
          <div className="message-list-main">
            <Space wrap>
              <Typography.Text strong>{item.title}</Typography.Text>
              <Tag>{FEEDBACK_CATEGORY_LABEL[item.category]}</Tag>
              <Tag color={FEEDBACK_STATUS_COLOR[item.status]} variant="filled">{FEEDBACK_STATUS_LABEL[item.status]}</Tag>
            </Space>
            <div>
              <Typography.Text type="secondary">
                {isAdmin ? `${item.submitterName} · ` : ''}{dayjs(item.createdAt).format('YYYY-MM-DD HH:mm')}
              </Typography.Text>
            </div>
          </div>
        </List.Item>
      )} />
    </DataState>
    <Modal title="我要反馈" open={open} width={720} confirmLoading={submitting} onOk={() => void submit()}
      okText="提交" cancelText="取消" onCancel={() => setOpen(false)} destroyOnHidden>
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        <div>
          <Typography.Text strong>标题</Typography.Text>
          <Input value={title} onChange={(event) => setTitle(event.target.value)} maxLength={200}
            placeholder="一句话说明问题或建议" />
        </div>
        <div>
          <Typography.Text strong>分类</Typography.Text>
          <div style={{ marginTop: 4 }}>
            <Radio.Group value={category} onChange={(event) => setCategory(event.target.value as FeedbackCategory)}>
              <Radio.Button value="ISSUE">问题</Radio.Button>
              <Radio.Button value="SUGGESTION">建议</Radio.Button>
            </Radio.Group>
          </div>
        </div>
        <div>
          <Typography.Text strong>正文</Typography.Text>
          <RichTextEditor value={contentHtml} onChange={setContentHtml} placeholder="描述问题或建议……" />
        </div>
      </Space>
    </Modal>
  </div>
}
