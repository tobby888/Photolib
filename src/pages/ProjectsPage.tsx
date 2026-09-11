import {
  App, Button, Card, Col, Form, Input, Modal, Pagination, Row, Select, Space, Tag, Typography,
} from 'antd'
import { ArrowRightOutlined, FolderOpenOutlined, PlusOutlined, SearchOutlined, TagsOutlined } from '@ant-design/icons'
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
import { normalizeTags, tagRules } from '../photoTags'
import TagSelect from '../TagSelect'

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
  const [searchText, setSearchText] = useState('')
  const searchKeyword = (keyword: string) => {
    setSearchText(keyword)
    setFilters(current => ({ ...current, page: 1, keyword: keyword.trim() }))
  }
  const { data, loading, error, reload } = useLoad(
    () => api<PageData<Project>>({ url: '/projects', params: qs({ ...filters, pageSize: 12 }) }),
    emptyPage<Project>(), [filters.page, filters.keyword, filters.status],
  )
  const create = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      await api({ method: 'POST', url: '/projects', data: { ...values, tags: normalizeTags(values.tags) } })
      message.success('项目已创建')
      setOpen(false); form.resetFields(); await reload()
    } catch (e) { message.error((e as Error).message) } finally { setSaving(false) }
  }
  return <>
    <PageTitle eyebrow="PROJECTS" title="选题项目" description="从一个清晰的选题开始，组织需求、图片和采纳记录。"
      extra={hasPermission(user, 'PROJECT_CREATE') && <Button type="primary" size="large" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建项目</Button>} />
    <Card className="filter-card">
      <Space wrap>
        <Input allowClear prefix={<SearchOutlined />} placeholder="搜索项目名称、说明或标签" style={{ width: 280 }}
          value={searchText} onChange={(e) => {
            setSearchText(e.target.value)
            if (!e.target.value) searchKeyword('')
          }}
          onPressEnter={(e) => searchKeyword(e.currentTarget.value)} />
        <Select allowClear placeholder="全部状态" options={statusOptions} style={{ width: 150 }}
          onChange={(status = '') => setFilters({ ...filters, page: 1, status })} />
      </Space>
    </Card>
    <DataState loading={loading} error={error} empty={!data.items.length} onRetry={reload}
      emptyText={filters.keyword || filters.status ? '没有符合筛选条件的项目' : '还没有选题项目'}
      emptyHint={filters.keyword || filters.status
        ? '换个关键词或状态再看看。'
        : '新建一个选题，就可以往下拆需求、收图片了。'}>
      <Row gutter={[16, 16]} className="project-grid">
        {data.items.map((item) => <Col xs={24} md={12} xl={8} key={item.id}>
          <Card className="project-card" hoverable>
            <div className="project-card-top"><div className="folder-icon"><FolderOpenOutlined /></div><StatusTag value={item.status} /></div>
            <Typography.Title level={4}>{item.title}</Typography.Title>
            <Typography.Paragraph ellipsis={{ rows: 2 }}>{markdownExcerpt(item.description) || '尚未添加项目说明'}</Typography.Paragraph>
            {!!item.tags?.length && <div className="project-card-tags">
              <TagsOutlined />
              {item.tags.slice(0, 5).map(tag => <Tag key={tag} color="blue" variant="filled"
                className="clickable-tag" onClick={() => searchKeyword(tag)}>{tag}</Tag>)}
              {item.tags.length > 5 && <Tag variant="filled">+{item.tags.length - 5}</Tag>}
            </div>}
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
        <Form.Item label="预设标签" name="tags" rules={tagRules}
          extra="设置后，需求上传图片和在选题里给图片加标签时只能从这些标签中选择（也可以不加）；留空则允许上传者自定义标签。直接上传到图片库不受影响。">
          <TagSelect placeholder="输入后回车添加，例如：开幕式、合影、颁奖" />
        </Form.Item>
        <Form.Item label="初始状态" name="status"><Select options={statusOptions.slice(0, 2)} /></Form.Item>
      </Form>
    </Modal>
  </>
}
