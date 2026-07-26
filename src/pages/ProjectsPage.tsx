import {
  App, Button, Card, Col, Form, Input, Modal, Pagination, Row, Select, Space, Typography,
} from 'antd'
import { ArrowRightOutlined, FolderOpenOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons'
import { useState } from 'react'
import dayjs from 'dayjs'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth'
import { api, emptyPage, qs } from '../api'
import type { PageData, Project } from '../types'
import { DataState, PageTitle, StatusTag } from '../components'
import { useLoad } from '../hooks'
import MarkdownEditor from '../MarkdownEditor'
import { markdownExcerpt } from '../MarkdownRenderer'
import { hasPermission } from '../permissions'

const statusOptions = [
  { value: 'DRAFT', label: '草稿' }, { value: 'ACTIVE', label: '进行中' },
  { value: 'COMPLETED', label: '已完成' }, { value: 'CANCELLED', label: '已取消' },
]

export default function ProjectsPage() {
  const { user } = useAuth()
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [form] = Form.useForm()
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [filters, setFilters] = useState({ page: 1, keyword: '', status: '' })
  const { data, loading, error, reload } = useLoad(
    () => api<PageData<Project>>({ url: '/projects', params: qs({ ...filters, pageSize: 12 }) }),
    emptyPage<Project>(), [filters.page, filters.keyword, filters.status],
  )
  const create = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      await api({ method: 'POST', url: '/projects', data: values })
      message.success('项目已创建')
      setOpen(false); form.resetFields(); await reload()
    } catch (e) { message.error((e as Error).message) } finally { setSaving(false) }
  }
  return <>
    <PageTitle eyebrow="PROJECTS" title="选题项目" description="从一个清晰的选题开始，组织需求、图片和采纳记录。"
      extra={hasPermission(user, 'PROJECT_CREATE') && <Button type="primary" size="large" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建项目</Button>} />
    <Card className="filter-card">
      <Space wrap>
        <Input allowClear prefix={<SearchOutlined />} placeholder="搜索项目名称" style={{ width: 260 }}
          onPressEnter={(e) => setFilters({ ...filters, page: 1, keyword: e.currentTarget.value })}
          onClear={() => setFilters({ ...filters, page: 1, keyword: '' })} />
        <Select allowClear placeholder="全部状态" options={statusOptions} style={{ width: 150 }}
          onChange={(status = '') => setFilters({ ...filters, page: 1, status })} />
      </Space>
    </Card>
    <DataState loading={loading} error={error} empty={!data.items.length} onRetry={reload}>
      <Row gutter={[16, 16]} className="project-grid">
        {data.items.map((item) => <Col xs={24} md={12} xl={8} key={item.id}>
          <Card className="project-card" hoverable>
            <div className="project-card-top"><div className="folder-icon"><FolderOpenOutlined /></div><StatusTag value={item.status} /></div>
            <Typography.Title level={4}>{item.title}</Typography.Title>
            <Typography.Paragraph ellipsis={{ rows: 2 }}>{markdownExcerpt(item.description) || '尚未添加项目说明'}</Typography.Paragraph>
            <div className="project-meta"><span>创建于 {dayjs(item.createdAt).format('YYYY.MM.DD')}</span><span>#{item.id}</span></div>
            <Button block onClick={() => navigate(`/projects/${item.id}`)}>打开项目 <ArrowRightOutlined /></Button>
          </Card>
        </Col>)}
      </Row>
      <Pagination current={filters.page} pageSize={12} total={data.total} hideOnSinglePage
        onChange={(page) => setFilters({ ...filters, page })} />
    </DataState>
    <Modal title="新建选题项目" width={760} open={open} onCancel={() => setOpen(false)} onOk={create} confirmLoading={saving}
      okText="创建项目" cancelText="取消">
      <Form form={form} layout="vertical" initialValues={{ status: 'DRAFT' }} requiredMark={false}>
        <Form.Item label="项目名称" name="title" rules={[{ required: true, message: '请输入项目名称' }, { max: 200 }]}>
          <Input placeholder="例如：2026 毕业季" />
        </Form.Item>
        <Form.Item label="项目说明" name="description">
          <MarkdownEditor placeholder="使用 Markdown 说明选题方向、交付目标等；可直接上传说明图片" />
        </Form.Item>
        <Form.Item label="初始状态" name="status"><Select options={statusOptions.slice(0, 2)} /></Form.Item>
      </Form>
    </Modal>
  </>
}
