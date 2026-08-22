import {
  ArrowLeftOutlined,
  CalendarOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  EditOutlined,
  EyeOutlined,
  FileImageOutlined,
  IdcardOutlined,
  RocketOutlined,
  StopOutlined,
  TeamOutlined,
} from '@ant-design/icons'
import {
  App,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Form,
  Input,
  Modal,
  Pagination,
  Result,
  Row,
  Space,
  Statistic,
  Tag,
  Typography,
} from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { DataState } from '../components'
import { useLoad } from '../hooks'
import MarkdownEditor from '../MarkdownEditor'
import MarkdownRenderer from '../MarkdownRenderer'
import RecruitmentFormEditor from '../RecruitmentFormEditor'
import {
  normalizeRecruitmentFormSchema,
  recruitmentTaskFormPayload,
  validateRecruitmentFormSchema,
  type RecruitmentFormSchema,
} from '../recruitmentForm'
import {
  normalizeApplicationSummary,
  normalizeRecruitmentPage,
  normalizeRecruitmentTask,
  type RecruitmentApplicationSummary,
  type RecruitmentTask,
} from '../recruitmentTypes'
import { canPublishRecruitments, recruitmentStatusDisplay } from './RecruitmentsPage'

type EditValues = {
  title: string
  description?: string
  activeRange: [Dayjs, Dayjs]
  formSchema: RecruitmentFormSchema
}

export default function RecruitmentDetailPage() {
  const params = useParams()
  const taskId = params.taskId || params.recruitmentId || ''
  const navigate = useNavigate()
  const { user } = useAuth()
  const { message, modal } = App.useApp()
  const [editForm] = Form.useForm<EditValues>()
  const [editOpen, setEditOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [actioning, setActioning] = useState(false)
  const [applicationPage, setApplicationPage] = useState(1)
  const canPublish = canPublishRecruitments(user)

  const taskState = useLoad(
    async () => normalizeRecruitmentTask(await api<unknown>({ url: `/recruitment-tasks/${taskId}` })),
    null as RecruitmentTask | null,
    [taskId],
  )
  const applications = useLoad(async () => {
    const value = await api<unknown>({
      url: `/recruitment-tasks/${taskId}/applications`, params: { page: applicationPage, pageSize: 20 },
    })
    return normalizeRecruitmentPage(value, normalizeApplicationSummary, applicationPage, 20)
  }, { items: [] as RecruitmentApplicationSummary[], page: 1, pageSize: 20, total: 0, totalPages: 0 },
  [taskId, applicationPage])

  const task = taskState.data

  const openEdit = () => {
    if (!task) return
    editForm.setFieldsValue({
      title: task.title,
      description: task.description || '',
      activeRange: [dayjs(task.startAt), dayjs(task.endAt)],
      formSchema: normalizeRecruitmentFormSchema(task.formSchema),
    })
    setEditOpen(true)
  }

  const save = async () => {
    if (!task) return
    let values: EditValues
    try {
      values = await editForm.validateFields()
    } catch {
      return
    }
    const formSchema = normalizeRecruitmentFormSchema(values.formSchema)
    const issue = validateRecruitmentFormSchema(formSchema)[0]
    if (issue) {
      message.error(issue.message)
      return
    }
    if (!values.activeRange[1].isAfter(values.activeRange[0])) {
      message.error('招募结束时间必须晚于开始时间')
      return
    }
    setSaving(true)
    try {
      await api({
        method: 'PUT',
        url: `/recruitment-tasks/${task.id}`,
        data: {
          title: values.title.trim(),
          introMarkdown: values.description?.trim() || null,
          startsAt: values.activeRange[0].format('YYYY-MM-DDTHH:mm:ss'),
          endsAt: values.activeRange[1].format('YYYY-MM-DDTHH:mm:ss'),
          ...recruitmentTaskFormPayload(formSchema),
          version: task.version,
        },
      })
      message.success('招募任务已更新')
      setEditOpen(false)
      await taskState.reload()
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const changeState = (action: 'publish' | 'close') => {
    if (!task) return
    const publish = action === 'publish'
    modal.confirm({
      title: publish ? '发布这个招募任务？' : '提前关闭这个招募任务？',
      content: publish
        ? '发布后，未登录访客可在设定的时间范围内填写和提交表单。'
        : '关闭后公开页面立即停止接收新申请，已经提交的申请仍会保留。',
      okText: publish ? '确认发布' : '确认关闭',
      okButtonProps: publish ? undefined : { danger: true },
      cancelText: '取消',
      onOk: async () => {
        setActioning(true)
        try {
          await api({ method: 'POST', url: `/recruitment-tasks/${task.id}/${action}`, data: { version: task.version } })
          message.success(publish ? '招募任务已发布' : '招募任务已关闭')
          await Promise.all([taskState.reload(), applications.reload()])
        } finally {
          setActioning(false)
        }
      },
    })
  }

  if (taskState.error) return <Result status="403" title="无法访问这个招募任务" subTitle={taskState.error}
    extra={<Button onClick={() => navigate('/recruitments')}>返回招募列表</Button>} />

  const status = recruitmentStatusDisplay(task?.status || 'DRAFT', task?.startAt, task?.endAt)
  const now = dayjs()
  const timeState = !task ? ''
    : task.status === 'CLOSED' || task.status === 'CANCELLED' ? '不再接收申请'
      : now.isBefore(dayjs(task.startAt)) ? '尚未开始'
        : !now.isBefore(dayjs(task.endAt)) ? '已过截止时间' : '开放时间内'

  return <DataState loading={taskState.loading} error={undefined} empty={!task} onRetry={taskState.reload}>
    {task && <>
      <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/recruitments')}>返回招募列表</Button>
      <section className="project-detail-hero" style={{ marginTop: 10 }}>
        <div className="project-detail-heading">
          <div>
            <Space wrap><Typography.Text className="eyebrow">RECRUITMENT · {task.publicId || task.id}</Typography.Text>
              <Tag color={status.color}>{status.label}</Tag><Tag>{timeState}</Tag></Space>
            <Typography.Title>{task.title}</Typography.Title>
            {task.description ? <MarkdownRenderer value={task.description} /> : <Typography.Paragraph type="secondary">暂无招募说明</Typography.Paragraph>}
          </div>
          {canPublish && <Space wrap>
            {task.status !== 'CLOSED' && task.status !== 'CANCELLED' && <Button size="large" icon={<EditOutlined />} onClick={openEdit}>编辑任务</Button>}
            {task.status === 'DRAFT' && <Button size="large" type="primary" icon={<RocketOutlined />}
              loading={actioning} onClick={() => changeState('publish')}>发布</Button>}
            {(task.status === 'PUBLISHED' || task.status === 'ACTIVE') && <Button size="large" danger icon={<StopOutlined />}
              loading={actioning} onClick={() => changeState('close')}>关闭招募</Button>}
          </Space>}
        </div>
      </section>

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={12} lg={6}><Card><Statistic title="申请总数" value={applications.data.total || task.applicationCount || 0} prefix={<TeamOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="自定义问题" value={task.formSchema.fields.length} prefix={<IdcardOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="作品上传" value={task.formSchema.upload.required ? '必填' : '选填'} prefix={<FileImageOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="任务状态" value={status.label} prefix={<CheckCircleOutlined />} /></Card></Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={9}>
          <Card title="任务设置" style={{ marginBottom: 16 }}>
            <Descriptions column={1} size="small" items={[
              { key: 'start', label: '开始时间', children: <Space><CalendarOutlined />{dayjs(task.startAt).format('YYYY-MM-DD HH:mm:ss')}</Space> },
              { key: 'end', label: '结束时间', children: <Space><ClockCircleOutlined />{dayjs(task.endAt).format('YYYY-MM-DD HH:mm:ss')}</Space> },
              { key: 'creator', label: '发布人', children: task.creatorDisplayName || '—' },
              { key: 'public', label: '公开标识', children: task.publicId || '发布后生成' },
            ]} />
          </Card>
          <Card title="申请表结构" style={{ marginBottom: 16 }}>
            <RecruitmentFormEditor value={task.formSchema} disabled />
          </Card>
        </Col>
        <Col xs={24} xl={15}>
          <Card title={<Space><TeamOutlined />新人申请</Space>} extra={<Typography.Text type="secondary">所有角色均可查看</Typography.Text>}>
            <DataState loading={applications.loading} error={applications.error} empty={!applications.data.items.length}
              onRetry={applications.reload}>
              <div>
                {applications.data.items.map((application, index) => <div key={String(application.id)} style={{
                  display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap',
                  gap: 12, padding: '14px 0', borderBottom: index === applications.data.items.length - 1
                    ? undefined : '1px solid rgba(5, 5, 5, 0.08)',
                }}>
                  <Space align="start">
                    <span style={{ width: 42, height: 42, display: 'grid', placeItems: 'center', flex: '0 0 auto',
                      borderRadius: 12, background: '#edf3f0', color: '#28594f' }}><IdcardOutlined /></span>
                    <div>
                      <Space wrap><Typography.Text strong>{application.studentId}</Typography.Text>
                        {application.attachmentCount !== undefined && <Tag>{application.attachmentCount} 个附件</Tag>}</Space>
                      <div><Typography.Text type="secondary">
                        提交于 {dayjs(application.submittedAt).format('YYYY-MM-DD HH:mm:ss')}
                      </Typography.Text></div>
                    </div>
                  </Space>
                  <Button type="link" icon={<EyeOutlined />}
                    onClick={() => navigate(`/recruitment-applications/${application.id}`)}>查看详情</Button>
                </div>)}
              </div>
              <Pagination style={{ marginTop: 16 }} current={applicationPage} pageSize={20} total={applications.data.total}
                hideOnSinglePage onChange={setApplicationPage} />
            </DataState>
          </Card>
        </Col>
      </Row>

      <Modal title="编辑招募任务" width={900} open={editOpen} destroyOnHidden forceRender
        onCancel={() => !saving && setEditOpen(false)} onOk={() => void save()} confirmLoading={saving}
        okText="保存修改" cancelText="取消">
        <Form form={editForm} layout="vertical" requiredMark={false}>
          <Form.Item label="任务名称" name="title" rules={[
            { required: true, whitespace: true, message: '请输入任务名称' }, { max: 200 },
          ]}><Input /></Form.Item>
          <Form.Item label="招募时间（北京时间）" name="activeRange" rules={[{ required: true }]}>
            <DatePicker.RangePicker showTime allowClear={false} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="招募说明" name="description"><MarkdownEditor allowImageUpload={false} /></Form.Item>
          <Form.Item label="新人申请表" name="formSchema"
            extra={task.status === 'DRAFT' ? undefined : '任务发布后表单、学号项与上传项已冻结，只能修改名称、说明和时间。'}>
            <RecruitmentFormEditor disabled={task.status !== 'DRAFT'} />
          </Form.Item>
        </Form>
      </Modal>
    </>}
  </DataState>
}
