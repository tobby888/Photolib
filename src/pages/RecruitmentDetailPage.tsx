import {
  ArrowLeftOutlined,
  CalendarOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  DownloadOutlined,
  EditOutlined,
  EyeOutlined,
  FileImageOutlined,
  IdcardOutlined,
  RocketOutlined,
  SearchOutlined,
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
  Empty,
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
import { api, http, qs } from '../api'
import { useAuth } from '../auth'
import { DataState } from '../components'
import { blobErrorMessage, fileNameFromContentDisposition, saveBlobAs } from '../exportDownload'
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
  const [exporting, setExporting] = useState(false)
  const [studentIdInput, setStudentIdInput] = useState('')
  const [studentIdFilter, setStudentIdFilter] = useState('')
  const canPublish = canPublishRecruitments(user)

  const taskState = useLoad(
    async () => normalizeRecruitmentTask(await api<unknown>({ url: `/recruitment-tasks/${taskId}` })),
    null as RecruitmentTask | null,
    [taskId],
  )
  const applications = useLoad(async () => {
    const value = await api<unknown>({
      url: `/recruitment-tasks/${taskId}/applications`,
      params: qs({ page: applicationPage, pageSize: 20, studentId: studentIdFilter }),
    })
    return normalizeRecruitmentPage(value, normalizeApplicationSummary, applicationPage, 20)
  }, { items: [] as RecruitmentApplicationSummary[], page: 1, pageSize: 20, total: 0, totalPages: 0 },
  [taskId, applicationPage, studentIdFilter])

  const task = taskState.data

  const searchByStudentId = (value: string) => {
    setApplicationPage(1)
    setStudentIdFilter(value.trim())
  }

  const exportApplications = async () => {
    if (!task) return
    setExporting(true)
    try {
      const response = await http.get<Blob>(`/recruitment-tasks/${task.id}/applications/export`, {
        params: qs({ studentId: studentIdFilter }),
        responseType: 'blob',
      })
      saveBlobAs(response.data, fileNameFromContentDisposition(response.headers['content-disposition'])
        || `${task.title}-报名-${dayjs().format('YYYY-MM-DD')}.xlsx`)
      message.success(studentIdFilter ? '已导出当前筛选出的报名' : '报名已导出')
    } catch (error) {
      message.error(await blobErrorMessage(error, '报名导出失败，请稍后重试'))
    } finally {
      setExporting(false)
    }
  }

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
      message.error('截止时间要晚于开始时间')
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
      message.success('修改已保存')
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
      title: publish ? '现在发布这个招募吗？' : '提前结束这次招募吗？',
      content: publish
        ? '发布后，报名页面会在你设定的时间段内向所有人开放，同学不用登录就能填写。'
        : '结束后报名页面会立刻停止接收新报名，已经收到的报名不受影响，仍然可以查看。',
      okText: publish ? '发布' : '结束招募',
      okButtonProps: publish ? undefined : { danger: true },
      cancelText: '再想想',
      onOk: async () => {
        setActioning(true)
        try {
          await api({ method: 'POST', url: `/recruitment-tasks/${task.id}/${action}`, data: { version: task.version } })
          message.success(publish ? '已发布，同学们现在可以报名了' : '招募已结束')
        } catch (error) {
          // Without this the confirm dialog swallows version conflicts entirely.
          message.error((error as Error).message)
        } finally {
          setActioning(false)
          await Promise.all([taskState.reload(), applications.reload()])
        }
      },
    })
  }

  if (taskState.error) return <Result status="403" title="这个招募看不了" subTitle={taskState.error}
    extra={<Button onClick={() => navigate('/recruitments')}>返回招募列表</Button>} />

  const status = recruitmentStatusDisplay(task?.status || 'DRAFT', task?.startAt, task?.endAt)
  const now = dayjs()
  const timeState = !task ? ''
    : task.status === 'CLOSED' || task.status === 'CANCELLED' ? '已停止报名'
      : now.isBefore(dayjs(task.startAt)) ? '还没到开始时间'
        : !now.isBefore(dayjs(task.endAt)) ? '已过报名截止时间' : '正在接收报名'

  return <DataState loading={taskState.loading} error={undefined} empty={!task} onRetry={taskState.reload}
    emptyText="找不到这次招募"
    emptyHint="它可能已经被删除了，回列表看看还有哪些在进行。">
    {task && <>
      <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/recruitments')}>返回招募列表</Button>
      <section className="project-detail-hero" style={{ marginTop: 10 }}>
        <div className="project-detail-heading">
          <div>
            <Space wrap><Typography.Text className="eyebrow">RECRUITMENT · {task.publicId || task.id}</Typography.Text>
              <Tag color={status.color}>{status.label}</Tag><Tag>{timeState}</Tag></Space>
            <Typography.Title>{task.title}</Typography.Title>
            {task.description ? <MarkdownRenderer value={task.description} /> : <Typography.Paragraph type="secondary">还没写招募说明</Typography.Paragraph>}
          </div>
          {canPublish && <Space wrap>
            {task.status !== 'CLOSED' && task.status !== 'CANCELLED' && <Button size="large" icon={<EditOutlined />} onClick={openEdit}>编辑</Button>}
            {task.status === 'DRAFT' && <Button size="large" type="primary" icon={<RocketOutlined />}
              loading={actioning} onClick={() => changeState('publish')}>发布</Button>}
            {(task.status === 'PUBLISHED' || task.status === 'ACTIVE') && <Button size="large" danger icon={<StopOutlined />}
              loading={actioning} onClick={() => changeState('close')}>结束招募</Button>}
          </Space>}
        </div>
      </section>

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={12} lg={6}><Card><Statistic title="收到报名" value={studentIdFilter
          ? task.applicationCount || 0
          : applications.data.total || task.applicationCount || 0} prefix={<TeamOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="自定义问题" value={task.formSchema.fields.length} prefix={<IdcardOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="作品上传" value={task.formSchema.upload.required ? '必须上传' : '可以不传'} prefix={<FileImageOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="当前状态" value={status.label} prefix={<CheckCircleOutlined />} /></Card></Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={9}>
          <Card title="招募设置" style={{ marginBottom: 16 }}>
            <Descriptions column={1} size="small" items={[
              { key: 'start', label: '开始时间', children: <Space><CalendarOutlined />{dayjs(task.startAt).format('YYYY-MM-DD HH:mm:ss')}</Space> },
              { key: 'end', label: '截止时间', children: <Space><ClockCircleOutlined />{dayjs(task.endAt).format('YYYY-MM-DD HH:mm:ss')}</Space> },
              { key: 'creator', label: '创建人', children: task.creatorDisplayName || '—' },
              { key: 'public', label: '报名页标识', children: task.publicId || '发布后生成' },
            ]} />
          </Card>
          <Card title="报名表长什么样" style={{ marginBottom: 16 }}>
            <RecruitmentFormEditor value={task.formSchema} disabled limits={task.uploadLimits} />
          </Card>
        </Col>
        <Col xs={24} xl={15}>
          <Card title={<Space><TeamOutlined />收到的报名</Space>} extra={<Space wrap>
            <Input allowClear prefix={<SearchOutlined />} placeholder="按学号搜索" style={{ width: 200 }}
              value={studentIdInput} onChange={event => setStudentIdInput(event.target.value)}
              onPressEnter={event => searchByStudentId(event.currentTarget.value)}
              onClear={() => searchByStudentId('')} />
            <Button onClick={() => searchByStudentId(studentIdInput)}>搜索</Button>
            <Button icon={<DownloadOutlined />} loading={exporting}
              onClick={() => void exportApplications()}>导出 XLSX</Button>
            <Typography.Text type="secondary">部内成员都能查看</Typography.Text>
          </Space>}>
            {studentIdFilter && <Typography.Paragraph type="secondary">
              学号包含“{studentIdFilter}”的报名共 {applications.data.total} 条
            </Typography.Paragraph>}
            <DataState loading={applications.loading} error={applications.error}
              empty={!applications.data.items.length && !studentIdFilter}
              onRetry={applications.reload}
              emptyText="还没有人报名"
              emptyHint="把报名页链接发给同学，交上来的报名会实时出现在这里。">
              {!applications.data.items.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={`没有学号包含“${studentIdFilter}”的报名`} /> : <>
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
              </>}
            </DataState>
          </Card>
        </Col>
      </Row>

      <Modal title="编辑招募" width={900} open={editOpen} destroyOnHidden forceRender
        onCancel={() => !saving && setEditOpen(false)} onOk={() => void save()} confirmLoading={saving}
        okText="保存" cancelText="取消">
        <Form form={editForm} layout="vertical" requiredMark={false}>
          <Form.Item label="招募名称" name="title" rules={[
            { required: true, whitespace: true, message: '请填写招募名称' }, { max: 200 },
          ]}><Input /></Form.Item>
          <Form.Item label="报名时间（北京时间）" name="activeRange" rules={[{ required: true }]}>
            <DatePicker.RangePicker showTime allowClear={false} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="招募说明" name="description"><MarkdownEditor allowImageUpload={false} /></Form.Item>
          <Form.Item label="报名表" name="formSchema"
            extra={task.status === 'DRAFT' ? undefined : '已经发布了，为了不影响正在填写的同学，题目、学号项和上传项不能再改；名称、说明和时间还可以调整。'}>
            <RecruitmentFormEditor disabled={task.status !== 'DRAFT'} limits={task.uploadLimits} />
          </Form.Item>
        </Form>
      </Modal>
    </>}
  </DataState>
}
