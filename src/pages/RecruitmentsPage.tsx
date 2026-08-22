import {
  ArrowRightOutlined,
  CalendarOutlined,
  FileTextOutlined,
  PlusOutlined,
  SearchOutlined,
  TeamOutlined,
} from '@ant-design/icons'
import {
  App,
  Button,
  Card,
  Col,
  DatePicker,
  Form,
  Input,
  Modal,
  Pagination,
  Row,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, qs } from '../api'
import { useAuth } from '../auth'
import { DataState, PageTitle } from '../components'
import { useLoad } from '../hooks'
import MarkdownEditor from '../MarkdownEditor'
import { markdownExcerpt } from '../MarkdownRenderer'
import RecruitmentFormEditor from '../RecruitmentFormEditor'
import {
  EMPTY_RECRUITMENT_FORM,
  normalizeRecruitmentFormSchema,
  recruitmentTaskFormPayload,
  validateRecruitmentFormSchema,
  type RecruitmentFormSchema,
} from '../recruitmentForm'
import { normalizeRecruitmentPage, normalizeRecruitmentTask, type RecruitmentTask } from '../recruitmentTypes'

type CreateValues = {
  title: string
  description?: string
  activeRange: [Dayjs, Dayjs]
  formSchema: RecruitmentFormSchema
}

const statusOptions = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'CLOSED', label: '已关闭' },
]

export function recruitmentStatusDisplay(status: string, startAt?: string, endAt?: string, now = dayjs()) {
  if (status === 'DRAFT') return { label: '草稿', color: 'default' }
  if (status === 'PUBLISHED' || status === 'ACTIVE') {
    const start = dayjs(startAt)
    const end = dayjs(endAt)
    if (start.isValid() && now.isBefore(start)) return { label: '待开始', color: 'blue' }
    if (end.isValid() && !now.isBefore(end)) return { label: '已截止', color: 'orange' }
    return { label: '招募中', color: 'green' }
  }
  if (status === 'CLOSED') return { label: '已关闭', color: 'blue' }
  if (status === 'CANCELLED') return { label: '已取消', color: 'red' }
  return { label: status, color: 'default' }
}

export function canPublishRecruitments(user: ReturnType<typeof useAuth>['user']) {
  return user?.permissionGroupCode === 'ADMIN'
    || user?.permissions?.some(permission => String(permission) === 'RECRUITMENT_PUBLISH') === true
}

export default function RecruitmentsPage() {
  const { user } = useAuth()
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [form] = Form.useForm<CreateValues>()
  const [modalOpen, setModalOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [filters, setFilters] = useState({ page: 1, keyword: '', status: '' })
  const canPublish = canPublishRecruitments(user)

  const tasks = useLoad(async () => {
    const value = await api<unknown>({
      url: '/recruitment-tasks', params: qs({ ...filters, pageSize: 12 }),
    })
    return normalizeRecruitmentPage(value, normalizeRecruitmentTask, filters.page, 12)
  }, { items: [] as RecruitmentTask[], page: 1, pageSize: 12, total: 0, totalPages: 0 },
  [filters.page, filters.keyword, filters.status])

  const openCreate = () => {
    form.setFieldsValue({
      title: '',
      description: '',
      activeRange: [dayjs().add(1, 'hour').startOf('hour'), dayjs().add(14, 'day').endOf('day')],
      formSchema: structuredClone(EMPTY_RECRUITMENT_FORM),
    })
    setModalOpen(true)
  }

  const create = async () => {
    let values: CreateValues
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    const schema = normalizeRecruitmentFormSchema(values.formSchema)
    const schemaIssues = validateRecruitmentFormSchema(schema)
    if (schemaIssues.length) {
      message.error(schemaIssues[0].message)
      return
    }
    if (!values.activeRange?.[1]?.isAfter(values.activeRange[0])) {
      message.error('招募结束时间必须晚于开始时间')
      return
    }
    setSaving(true)
    try {
      const task = normalizeRecruitmentTask(await api<unknown>({
        method: 'POST',
        url: '/recruitment-tasks',
        data: {
          title: values.title.trim(),
          introMarkdown: values.description?.trim() || null,
          startsAt: values.activeRange[0].format('YYYY-MM-DDTHH:mm:ss'),
          endsAt: values.activeRange[1].format('YYYY-MM-DDTHH:mm:ss'),
          ...recruitmentTaskFormPayload(schema),
        },
      }))
      message.success('招募任务草稿已创建')
      setModalOpen(false)
      form.resetFields()
      if (task.id) navigate(`/recruitments/${task.id}`)
      else await tasks.reload()
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setSaving(false)
    }
  }

  return <>
    <PageTitle eyebrow="RECRUITMENT" title="新成员招募" description="设计申请表、安排招募时间，并集中查看每一份新人申请。"
      extra={canPublish && <Button type="primary" size="large" icon={<PlusOutlined />} onClick={openCreate}>新建招募任务</Button>} />
    <Card className="filter-card">
      <Space wrap>
        <Input allowClear prefix={<SearchOutlined />} placeholder="搜索招募任务" style={{ width: 260 }}
          onPressEnter={event => setFilters(current => ({ ...current, page: 1, keyword: event.currentTarget.value.trim() }))}
          onClear={() => setFilters(current => ({ ...current, page: 1, keyword: '' }))} />
        <Select allowClear placeholder="全部状态" options={statusOptions} style={{ width: 150 }}
          onChange={(status = '') => setFilters(current => ({ ...current, page: 1, status }))} />
      </Space>
    </Card>
    <DataState loading={tasks.loading} error={tasks.error} empty={!tasks.data.items.length} onRetry={tasks.reload}>
      <Row gutter={[16, 16]} style={{ marginBottom: 22 }}>
        {tasks.data.items.map(task => {
          const status = recruitmentStatusDisplay(task.status, task.startAt, task.endAt)
          return <Col xs={24} md={12} xl={8} key={String(task.id)}>
            <Card hoverable style={{ height: '100%' }} onClick={() => navigate(`/recruitments/${task.id}`)}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
                <span style={{ width: 48, height: 48, display: 'grid', placeItems: 'center', borderRadius: 13,
                  background: '#edf3f0', color: '#28594f', fontSize: 22 }}><FileTextOutlined /></span>
                <Tag color={status.color}>{status.label}</Tag>
              </div>
              <Typography.Title level={4} style={{ margin: '18px 0 7px' }}>{task.title}</Typography.Title>
              <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }} style={{ minHeight: 44 }}>
                {markdownExcerpt(task.description) || '尚未添加招募说明'}
              </Typography.Paragraph>
              <Space orientation="vertical" size={5} style={{ width: '100%', margin: '8px 0 16px' }}>
                <Typography.Text type="secondary"><CalendarOutlined /> {dayjs(task.startAt).format('YYYY-MM-DD HH:mm')} 至 {dayjs(task.endAt).format('YYYY-MM-DD HH:mm')}</Typography.Text>
                <Typography.Text type="secondary"><TeamOutlined /> 已提交 {task.applicationCount || 0} 份申请</Typography.Text>
              </Space>
              <Button block>查看任务与申请 <ArrowRightOutlined /></Button>
            </Card>
          </Col>
        })}
      </Row>
      <Pagination current={filters.page} pageSize={12} total={tasks.data.total} hideOnSinglePage
        onChange={page => setFilters(current => ({ ...current, page }))} />
    </DataState>

    <Modal title="新建招募任务" width={900} open={modalOpen} onCancel={() => !saving && setModalOpen(false)}
      onOk={() => void create()} confirmLoading={saving} okText="保存草稿" cancelText="取消" destroyOnHidden forceRender>
      <Form form={form} layout="vertical" requiredMark={false}>
        <Form.Item label="任务名称" name="title" rules={[
          { required: true, whitespace: true, message: '请输入任务名称' }, { max: 200, message: '任务名称不能超过 200 个字符' },
        ]}><Input placeholder="例如：2026 秋季摄影部招新" /></Form.Item>
        <Form.Item label="招募时间（北京时间）" name="activeRange" rules={[{ required: true, message: '请选择招募开始和结束时间' }]}>
          <DatePicker.RangePicker showTime style={{ width: '100%' }} allowClear={false} />
        </Form.Item>
        <Form.Item label="招募说明" name="description">
          <MarkdownEditor allowImageUpload={false} placeholder="介绍摄影部、招募对象、流程和注意事项……" />
        </Form.Item>
        <Form.Item label="新人申请表" name="formSchema" rules={[{ required: true }]}>
          <RecruitmentFormEditor />
        </Form.Item>
      </Form>
    </Modal>
  </>
}
