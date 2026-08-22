import {
  CalendarOutlined,
  CameraOutlined,
  CheckCircleOutlined,
  CloudUploadOutlined,
  FileImageOutlined,
  FileZipOutlined,
  InboxOutlined,
  SendOutlined,
} from '@ant-design/icons'
import {
  Alert,
  App,
  Button,
  Card,
  Checkbox,
  Col,
  Empty,
  Form,
  Input,
  Progress,
  Radio,
  Result,
  Row,
  Skeleton,
  Space,
  Tag,
  Typography,
  Upload,
  type UploadFile,
} from 'antd'
import dayjs from 'dayjs'
import { useMemo, useState } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { useLoad } from '../hooks'
import MarkdownRenderer from '../MarkdownRenderer'
import {
  normalizeRecruitmentAnswers,
  normalizeStudentId,
  validateRecruitmentAnswers,
  validateStudentId,
  type RecruitmentFormField,
} from '../recruitmentForm'
import {
  buildRecruitmentFilesRequest,
  buildRecruitmentZipRequest,
  inferRecruitmentUploadMode,
  isRecruitmentBatchTerminal,
  normalizeRecruitmentBatchStatus,
  validateRecruitmentUploadFiles,
  type RecruitmentUploadMode,
} from '../recruitmentUpload'
import {
  normalizePublicRecruitmentTask,
  normalizeRecruitmentDraft,
  type PublicRecruitmentTask,
  type RecruitmentBatchTicketResponse,
  type RecruitmentBatchView,
} from '../recruitmentTypes'
import { uploadToObjectStorage } from '../storageUpload'

const wait = (milliseconds: number) => new Promise(resolve => window.setTimeout(resolve, milliseconds))
const DRAFT_TOKEN_HEADER = 'X-Recruitment-Draft-Token'

type PublicFormValues = {
  studentId: string
  answers?: Record<string, unknown>
}

function nativeFiles(fileList: UploadFile[]) {
  return fileList.map(item => item.originFileObj || item as unknown as File)
}

function failureReason(batch: RecruitmentBatchView) {
  const itemReasons = batch.items?.filter(item => item.failureReason)
    .map(item => `${item.fileName || item.originalFileName || '未命名文件'}：${item.failureReason}`).join('；')
  return itemReasons || batch.batch?.failureReason || batch.failureReason
}

function DynamicAnswerField({ field }: { field: RecruitmentFormField }) {
  const common = {
    name: ['answers', field.id],
    label: field.label,
    extra: field.helpText,
    rules: field.required ? [{ required: true, message: `请填写“${field.label}”` }] : undefined,
  }
  if (field.type === 'LONG_TEXT') {
    return <Form.Item {...common}><Input.TextArea autoSize={{ minRows: 4, maxRows: 10 }} maxLength={5000}
      showCount placeholder={field.placeholder || '请输入'} /></Form.Item>
  }
  if (field.type === 'SINGLE_CHOICE') {
    return <Form.Item {...common}><Radio.Group options={(field.options || []).map(option => ({ label: option, value: option }))} /></Form.Item>
  }
  if (field.type === 'MULTIPLE_CHOICE') {
    return <Form.Item {...common}><Checkbox.Group options={(field.options || []).map(option => ({ label: option, value: option }))} /></Form.Item>
  }
  if (field.type === 'DATE') {
    return <Form.Item {...common}><Input type="date" style={{ maxWidth: 320 }} /></Form.Item>
  }
  return <Form.Item {...common}><Input maxLength={500} showCount placeholder={field.placeholder || '请输入'} /></Form.Item>
}

function PublicTaskForm({ task }: { task: PublicRecruitmentTask }) {
  const { message, modal } = App.useApp()
  const [form] = Form.useForm<PublicFormValues>()
  const [uploadMode, setUploadMode] = useState<RecruitmentUploadMode>('FILES')
  const [fileList, setFileList] = useState<UploadFile[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [progress, setProgress] = useState(0)
  const [phase, setPhase] = useState('')
  const [inlineError, setInlineError] = useState('')
  const [submittedAt, setSubmittedAt] = useState('')

  const files = useMemo(() => nativeFiles(fileList), [fileList])

  const pollBatch = async (draftId: string, token: string, batchId: string | number) => {
    const deadline = Date.now() + 10 * 60_000
    let latest: RecruitmentBatchView = {}
    while (Date.now() < deadline) {
      latest = await api<RecruitmentBatchView>({
        url: `/public/recruitments/${task.publicId}/drafts/${draftId}/batches/${batchId}`,
        headers: { [DRAFT_TOKEN_HEADER]: token },
      })
      if (isRecruitmentBatchTerminal(latest)) return latest
      await wait(1500)
    }
    throw new Error('文件仍在后台处理，请保持页面打开并稍后重试')
  }

  const uploadAttachments = async (draftId: string, token: string) => {
    if (!files.length) return
    setPhase(uploadMode === 'ZIP' ? '正在计算 ZIP 信息并创建上传任务…' : '正在计算图片 SHA-256…')
    setProgress(3)
    const request = uploadMode === 'ZIP'
      ? buildRecruitmentZipRequest(files[0])
      : await buildRecruitmentFilesRequest(files)
    setProgress(10)
    setPhase('正在创建 OSS 直传地址…')
    const ticket = await api<RecruitmentBatchTicketResponse>({
      method: 'POST',
      url: `/public/recruitments/${task.publicId}/drafts/${draftId}/batches`,
      headers: { [DRAFT_TOKEN_HEADER]: token },
      data: request,
    })
    if (!ticket.batchId || ticket.tickets?.length !== files.length) {
      throw new Error('未能为全部文件创建上传地址，请重试')
    }
    for (let index = 0; index < files.length; index += 1) {
      setPhase(`正在原样上传 ${files[index].name}（${index + 1}/${files.length}）…`)
      await uploadToObjectStorage(ticket.tickets[index], files[index], filePercent => {
        const completed = index / files.length
        const current = filePercent / 100 / files.length
        setProgress(10 + Math.round((completed + current) * 75))
      })
    }
    setProgress(88)
    setPhase(uploadMode === 'ZIP' ? 'OSS 上传完成，正在安全解压并校验图片…' : 'OSS 上传完成，正在校验文件…')
    const completed = await api<RecruitmentBatchView>({
      method: 'POST',
      url: `/public/recruitments/${task.publicId}/drafts/${draftId}/batches/${ticket.batchId}/complete`,
      headers: { [DRAFT_TOKEN_HEADER]: token },
    })
    const batch = isRecruitmentBatchTerminal(completed)
      ? completed
      : await pollBatch(draftId, token, ticket.batchId)
    if (normalizeRecruitmentBatchStatus(batch) === 'FAILED') {
      throw new Error(failureReason(batch) || '文件处理失败，请检查文件后重试')
    }
    if (normalizeRecruitmentBatchStatus(batch) === 'PARTIALLY_SUCCEEDED') {
      const reason = failureReason(batch) || '部分图片未能通过格式或完整性校验'
      await new Promise<void>((resolve, reject) => modal.confirm({
        title: '部分图片处理失败',
        content: `${reason}。你可以仅提交已成功的图片，或取消后调整文件再重新提交。`,
        okText: '仅提交成功图片',
        cancelText: '暂不提交',
        onOk: () => resolve(),
        onCancel: () => reject(new Error('已暂停提交；表单内容仍保留，请调整上传文件后重试')),
      }))
    }
    setProgress(96)
  }

  const submit = async () => {
    setInlineError('')
    let values: PublicFormValues
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    const uploadErrors = files.length ? validateRecruitmentUploadFiles(uploadMode, files) : []
    const answerIssues = validateRecruitmentAnswers(task.formSchema, values.studentId, values.answers || {}, files.length)
    const validationMessage = answerIssues[0]?.message || uploadErrors[0]
    if (validationMessage) {
      const fieldId = answerIssues[0]?.fieldId
      if (fieldId === 'studentId') form.scrollToField('studentId', { focus: true })
      else if (fieldId && fieldId !== 'attachments') form.scrollToField(['answers', fieldId], { focus: true })
      setInlineError(validationMessage)
      message.error(validationMessage)
      return
    }

    setSubmitting(true)
    setProgress(0)
    try {
      const studentId = normalizeStudentId(values.studentId)
      setPhase('正在创建安全提交草稿…')
      const draft = normalizeRecruitmentDraft(await api<unknown>({
        method: 'POST', url: `/public/recruitments/${task.publicId}/drafts`, data: { studentId },
      }))
      if (!draft.draftId || !draft.token) throw new Error('提交凭证创建失败，请刷新后重试')
      await uploadAttachments(draft.draftId, draft.token)
      setPhase('正在提交报名表…')
      const result = await api<{ submittedAt?: string }>({
        method: 'POST',
        url: `/public/recruitments/${task.publicId}/drafts/${draft.draftId}/submit`,
        headers: { [DRAFT_TOKEN_HEADER]: draft.token },
        data: {
          studentId,
          answers: normalizeRecruitmentAnswers(task.formSchema, values.answers || {}),
        },
      })
      setProgress(100)
      setSubmittedAt(result.submittedAt || new Date().toISOString())
      message.success('招募申请已提交')
    } catch (error) {
      const reason = (error as Error).message || '提交失败，请稍后重试'
      const duplicate = /重复|已经提交|已提交|DUPLICATE/i.test(reason)
      setInlineError(duplicate ? '这个学号已经提交过本次招募，每位同学只能提交一次。你已填写的内容仍保留在页面中。' : reason)
      message.error(duplicate ? '这个学号已经提交过本次招募' : reason)
    } finally {
      setSubmitting(false)
      setPhase('')
    }
  }

  if (submittedAt) return <Result icon={<CheckCircleOutlined style={{ color: '#3f7b65' }} />}
    title="申请提交成功" subTitle={`提交时间：${dayjs(submittedAt).format('YYYY-MM-DD HH:mm:ss')}。请留意后续通知。`} />

  return <Form name={`recruitment-${task.publicId}`} form={form} layout="vertical" size="large" requiredMark="optional" disabled={submitting}
    preserve initialValues={{ answers: {} }} onFinish={() => void submit()}>
    <Form.Item name="studentId" label={task.formSchema.studentId.label} extra={task.formSchema.studentId.helpText}
      rules={[{ required: true, message: '请输入学号' }, { validator: async (_, value) => {
        const issue = value ? validateStudentId(value) : undefined
        if (issue) throw new Error(issue)
      } }]}>
      <Input inputMode="text" autoComplete="off" maxLength={128} placeholder="请输入你的学号" />
    </Form.Item>
    {task.formSchema.fields.map(field => <DynamicAnswerField key={field.id} field={field} />)}

    <Form.Item required={task.formSchema.upload.required}
      label={<Space><FileImageOutlined />{task.formSchema.upload.label}{task.formSchema.upload.required && <Tag color="red">必填</Tag>}</Space>}
      extra={task.formSchema.upload.prompt}>
      <Radio.Group optionType="button" buttonStyle="solid" value={uploadMode} disabled={submitting}
        onChange={event => { setUploadMode(event.target.value as RecruitmentUploadMode); setFileList([]); setInlineError('') }}
        options={[
          { value: 'FILES', label: <Space><FileImageOutlined />图片</Space> },
          { value: 'ZIP', label: <Space><FileZipOutlined />ZIP 压缩包</Space> },
        ]} />
      <Upload.Dragger style={{ marginTop: 12 }} multiple={uploadMode === 'FILES'}
        maxCount={uploadMode === 'ZIP' ? 1 : 100}
        accept={uploadMode === 'ZIP' ? '.zip,application/zip' : '.jpg,.jpeg,.png,image/jpeg,image/png'}
        fileList={fileList} disabled={submitting} beforeUpload={() => false}
        onChange={({ fileList: next }) => {
          const limited = next.slice(0, uploadMode === 'ZIP' ? 1 : 100)
          const selected = nativeFiles(limited)
          const errors = selected.length ? validateRecruitmentUploadFiles(uploadMode, selected) : []
          if (errors.length) message.error(errors[0])
          setInlineError(errors[0] || '')
          setFileList(errors.length ? fileList : limited)
        }}>
        <p className="ant-upload-drag-icon"><InboxOutlined /></p>
        <p className="ant-upload-text">{uploadMode === 'ZIP' ? '拖入一个 ZIP，或点击选择' : '拖入 JPG / PNG，或点击选择'}</p>
        <p className="ant-upload-hint">{uploadMode === 'ZIP'
          ? '最大 1.5GB；包内最多 100 张有效图片；安全解压规则与图库批量上传一致'
          : '1–100 张；单张不超过 100 MiB；原文件直传，不压缩、不转码'}</p>
      </Upload.Dragger>
    </Form.Item>

    {inlineError && <Alert type="error" showIcon title="暂时无法提交" description={inlineError}
      style={{ marginBottom: 18 }} />}
    {submitting && <Space orientation="vertical" style={{ width: '100%', marginBottom: 18 }}>
      <Progress percent={progress} status="active" />
      <Typography.Text type="secondary">{phase}</Typography.Text>
    </Space>}
    <Button block size="large" type="primary" htmlType="submit" icon={<SendOutlined />} loading={submitting}>
      提交招募申请
    </Button>
  </Form>
}

export default function PublicRecruitmentPage() {
  const { user } = useAuth()
  const tasks = useLoad(
    async () => {
      const response = await api<unknown>({ url: '/public/recruitments' })
      const values = Array.isArray(response)
        ? response
        : typeof response === 'object' && response !== null && Array.isArray((response as { items?: unknown[] }).items)
          ? (response as { items: unknown[] }).items
          : []
      // The API is the authoritative clock and already returns only tasks in
      // the half-open active window. A visitor's misconfigured device clock
      // must not hide a valid recruitment task.
      return values.map(normalizePublicRecruitmentTask)
    },
    [] as PublicRecruitmentTask[],
    [user],
  )

  if (user) return <Navigate to="/" replace />

  return <main style={{ minHeight: '100vh', background: 'linear-gradient(145deg, #f7f4ee 0%, #edf3f0 100%)' }}>
    <header style={{ background: '#173b35', color: 'white', padding: '22px clamp(18px, 5vw, 68px)' }}>
      <Space size={12}><CameraOutlined style={{ color: '#f0b66d', fontSize: 25 }} />
        <div><Typography.Title level={4} style={{ color: 'white', margin: 0 }}>PhotoLib</Typography.Title>
          <Typography.Text style={{ color: 'rgba(255,255,255,.62)' }}>摄影部新成员招募</Typography.Text></div></Space>
    </header>
    <div style={{ width: 'min(1120px, calc(100% - 28px))', margin: '0 auto', padding: 'clamp(30px, 6vw, 72px) 0 60px' }}>
      <div style={{ textAlign: 'center', marginBottom: 34 }}>
        <Typography.Text style={{ color: '#b66d31', fontWeight: 700, letterSpacing: '.16em' }}>JOIN THE TEAM</Typography.Text>
        <Typography.Title style={{ margin: '8px 0 6px', fontSize: 'clamp(30px, 6vw, 50px)' }}>把你看到的世界，带到这里</Typography.Title>
        <Typography.Paragraph type="secondary">请选择正在进行的招募任务，认真填写并提交你的作品。</Typography.Paragraph>
      </div>

      {tasks.loading && <Card><Skeleton active paragraph={{ rows: 10 }} /></Card>}
      {!tasks.loading && tasks.error && <Result status="warning" title="招募信息暂时加载失败" subTitle={tasks.error}
        extra={<Button onClick={() => void tasks.reload()}>重新加载</Button>} />}
      {!tasks.loading && !tasks.error && !tasks.data.length && <Card style={{ textAlign: 'center', padding: '36px 8px' }}>
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={
          <Typography.Title level={4}>当前暂无大规模招募任务哦，敬请期待</Typography.Title>
        } />
      </Card>}
      {!tasks.loading && !tasks.error && <Space orientation="vertical" size={24} style={{ width: '100%' }}>
        {tasks.data.map(task => <Card key={task.publicId} styles={{ body: { padding: 'clamp(18px, 4vw, 36px)' } }}>
          <Row gutter={[28, 24]}>
            <Col xs={24} lg={9}>
              <Space orientation="vertical" size={14} style={{ width: '100%' }}>
                <Tag color="green">正在招募</Tag>
                <Typography.Title level={2} style={{ margin: 0 }}>{task.title}</Typography.Title>
                <Space><CalendarOutlined /><Typography.Text type="secondary">
                  {dayjs(task.startAt).format('YYYY-MM-DD HH:mm')} 至 {dayjs(task.endAt).format('YYYY-MM-DD HH:mm')}（北京时间）
                </Typography.Text></Space>
                {task.description
                  ? <MarkdownRenderer value={task.description} />
                  : <Typography.Paragraph type="secondary">请按右侧表单提交你的招募申请。</Typography.Paragraph>}
                <Alert type="info" showIcon title="上传文件不会压缩"
                  description="图片及 ZIP 均原样直传私有 OSS，仅具有权限的摄影部工作人员可以查看。" />
              </Space>
            </Col>
            <Col xs={24} lg={15}><PublicTaskForm task={task} /></Col>
          </Row>
        </Card>)}
      </Space>}
      <Typography.Paragraph type="secondary" style={{ textAlign: 'center', marginTop: 26 }}>
        已有工作站账号？请<Link to="/login">返回登录页面</Link>。<CloudUploadOutlined /> 你的提交将通过加密连接传输。
      </Typography.Paragraph>
    </div>
  </main>
}

export { inferRecruitmentUploadMode }
