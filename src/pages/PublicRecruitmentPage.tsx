import {
  CalendarOutlined,
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
import { BrandGlyph, useBranding } from '../branding'
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
  describeBytes,
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

/**
 * The object-storage helper is shared with the staff photo tools, so its errors
 * name buckets, CORS and presigned URLs. An applicant can act on none of that.
 */
function friendlyUploadError(error: unknown) {
  const raw = (error as Error)?.message || ''
  if (/OSS|Bucket|CORS|预签名|Content-Type/i.test(raw)) {
    return '图片没能上传成功。请检查网络后重试；如果一直不行，请把这个情况告诉摄影部的同学。'
  }
  return raw || '图片没能上传成功，请稍后重试。'
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
    throw new Error('图片还在处理，暂时没等到结果。请先别关页面，稍后再点一次提交。')
  }

  const uploadAttachments = async (draftId: string, token: string) => {
    if (!files.length) return
    setPhase(uploadMode === 'ZIP' ? '正在读取压缩包…' : '正在整理你选的图片…')
    setProgress(3)
    const request = uploadMode === 'ZIP'
      ? buildRecruitmentZipRequest(files[0])
      : await buildRecruitmentFilesRequest(files)
    setProgress(10)
    setPhase('正在准备上传…')
    const ticket = await api<RecruitmentBatchTicketResponse>({
      method: 'POST',
      url: `/public/recruitments/${task.publicId}/drafts/${draftId}/batches`,
      headers: { [DRAFT_TOKEN_HEADER]: token },
      data: request,
    })
    if (!ticket.batchId || ticket.tickets?.length !== files.length) {
      throw new Error('有文件没能开始上传，请再试一次')
    }
    for (let index = 0; index < files.length; index += 1) {
      setPhase(files.length > 1
        ? `正在上传 ${files[index].name}（第 ${index + 1} 个，共 ${files.length} 个）…`
        : `正在上传 ${files[index].name}…`)
      try {
        await uploadToObjectStorage(ticket.tickets[index], files[index], filePercent => {
          const completed = index / files.length
          const current = filePercent / 100 / files.length
          setProgress(10 + Math.round((completed + current) * 75))
        })
      } catch (error) {
        throw new Error(friendlyUploadError(error))
      }
    }
    setProgress(88)
    setPhase(uploadMode === 'ZIP' ? '上传完成，正在打开压缩包查看图片…' : '上传完成，正在检查图片…')
    const completed = await api<RecruitmentBatchView>({
      method: 'POST',
      url: `/public/recruitments/${task.publicId}/drafts/${draftId}/batches/${ticket.batchId}/complete`,
      headers: { [DRAFT_TOKEN_HEADER]: token },
    })
    const batch = isRecruitmentBatchTerminal(completed)
      ? completed
      : await pollBatch(draftId, token, ticket.batchId)
    if (normalizeRecruitmentBatchStatus(batch) === 'FAILED') {
      throw new Error(failureReason(batch) || '这些文件没能通过检查，换几张图片再试试吧')
    }
    if (normalizeRecruitmentBatchStatus(batch) === 'PARTIALLY_SUCCEEDED') {
      const reason = failureReason(batch) || '有几张图片打不开，可能是文件损坏了'
      await new Promise<void>((resolve, reject) => modal.confirm({
        title: '有几张图片没传上去',
        content: `${reason}。你可以先交上已经传好的那几张，也可以先取消、换几张图片再交。`,
        okText: '就交已传好的',
        cancelText: '我再换几张',
        onOk: () => resolve(),
        onCancel: () => reject(new Error('已经暂停提交，你填的内容都还在。换好图片后再点一次提交就行。')),
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
    const uploadErrors = files.length
      ? validateRecruitmentUploadFiles(uploadMode, files, task.uploadLimits) : []
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
      setPhase('正在为你占位…')
      const draft = normalizeRecruitmentDraft(await api<unknown>({
        method: 'POST', url: `/public/recruitments/${task.publicId}/drafts`, data: { studentId },
      }))
      if (!draft.draftId || !draft.token) throw new Error('没能开始提交，请刷新页面再试一次')
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
      message.success('报名成功')
    } catch (error) {
      const reason = (error as Error).message || '提交没成功，请稍后再试一次'
      const duplicate = /重复|已经提交|已提交|DUPLICATE/i.test(reason)
      setInlineError(duplicate ? '这个学号已经报过名啦，每人只能报一次。你刚填的内容还在页面上，不会丢。' : reason)
      message.error(duplicate ? '这个学号已经报过名了' : reason)
    } finally {
      setSubmitting(false)
      setPhase('')
    }
  }

  if (submittedAt) return <Result icon={<CheckCircleOutlined style={{ color: '#3f7b65' }} />}
    title="报名成功，我们收到啦！"
    subTitle={`提交时间：${dayjs(submittedAt).format('YYYY-MM-DD HH:mm:ss')}。接下来请留意学院或摄影部的通知，我们会尽快联系你。`} />

  return <Form name={`recruitment-${task.publicId}`} form={form} layout="vertical" size="large" requiredMark="optional" disabled={submitting}
    preserve initialValues={{ answers: {} }} onFinish={() => void submit()}>
    <Form.Item name="studentId" label={task.formSchema.studentId.label} extra={task.formSchema.studentId.helpText}
      rules={[{ required: true, message: '请填写学号' }, { validator: async (_, value) => {
        const issue = value ? validateStudentId(value) : undefined
        if (issue) throw new Error(issue)
      } }]}>
      <Input inputMode="text" autoComplete="off" maxLength={128} placeholder="填写你的学号" />
    </Form.Item>
    {task.formSchema.fields.map(field => <DynamicAnswerField key={field.id} field={field} />)}

    <Form.Item required={task.formSchema.upload.required}
      label={<Space><FileImageOutlined />{task.formSchema.upload.label}{task.formSchema.upload.required && <Tag color="red">必传</Tag>}</Space>}
      extra={task.formSchema.upload.prompt}>
      <Radio.Group optionType="button" buttonStyle="solid" value={uploadMode} disabled={submitting}
        onChange={event => { setUploadMode(event.target.value as RecruitmentUploadMode); setFileList([]); setInlineError('') }}
        options={[
          { value: 'FILES', label: <Space><FileImageOutlined />逐张选图片</Space> },
          { value: 'ZIP', label: <Space><FileZipOutlined />传一个压缩包</Space> },
        ]} />
      <Upload.Dragger style={{ marginTop: 12 }} multiple={uploadMode === 'FILES'}
        maxCount={uploadMode === 'ZIP' ? 1 : 100}
        accept={uploadMode === 'ZIP' ? '.zip,application/zip' : '.jpg,.jpeg,.png,image/jpeg,image/png'}
        fileList={fileList} disabled={submitting} beforeUpload={() => false}
        onChange={({ fileList: next }) => {
          const limited = next.slice(0, uploadMode === 'ZIP' ? 1 : task.uploadLimits.maxImageCount)
          const selected = nativeFiles(limited)
          const errors = selected.length
            ? validateRecruitmentUploadFiles(uploadMode, selected, task.uploadLimits) : []
          if (errors.length) message.error(errors[0])
          setInlineError(errors[0] || '')
          setFileList(errors.length ? fileList : limited)
        }}>
        <p className="ant-upload-drag-icon"><InboxOutlined /></p>
        <p className="ant-upload-text">{uploadMode === 'ZIP' ? '把 ZIP 拖到这里，或点一下选择' : '把照片拖到这里，或点一下选择'}</p>
        <p className="ant-upload-hint">{uploadMode === 'ZIP'
          ? `一个 ZIP，不超过 ${describeBytes(task.uploadLimits.maxArchiveBytes)}，`
            + `里面最多放 ${task.uploadLimits.maxImageCount} 张 JPG / PNG`
          : `一次 1–${task.uploadLimits.maxImageCount} 张 JPG / PNG，`
            + `单张不超过 ${describeBytes(task.uploadLimits.maxImageBytes)}，我们会保留你的原图`}</p>
      </Upload.Dragger>
    </Form.Item>

    {inlineError && <Alert type="error" showIcon title="还差一点就好了" description={inlineError}
      style={{ marginBottom: 18 }} />}
    {submitting && <Space orientation="vertical" style={{ width: '100%', marginBottom: 18 }}>
      <Progress percent={progress} status="active" />
      <Typography.Text type="secondary">{phase}</Typography.Text>
    </Space>}
    <Button block size="large" type="primary" htmlType="submit" icon={<SendOutlined />} loading={submitting}>
      提交报名
    </Button>
  </Form>
}

export default function PublicRecruitmentPage() {
  const { user } = useAuth()
  const branding = useBranding()
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
      <Space size={12}><span className="brand-glyph"><BrandGlyph branding={branding} /></span>
        <div><Typography.Title level={4} style={{ color: 'white', margin: 0 }}>{branding.title}</Typography.Title>
          <Typography.Text style={{ color: 'rgba(255,255,255,.62)' }}>摄影部新成员招募</Typography.Text></div></Space>
    </header>
    <div style={{ width: 'min(1120px, calc(100% - 28px))', margin: '0 auto', padding: 'clamp(30px, 6vw, 72px) 0 60px' }}>
      <div style={{ textAlign: 'center', marginBottom: 34 }}>
        <Typography.Text style={{ color: '#b66d31', fontWeight: 700, letterSpacing: '.16em' }}>JOIN THE TEAM</Typography.Text>
        <Typography.Title style={{ margin: '8px 0 6px', fontSize: 'clamp(30px, 6vw, 50px)' }}>把你看到的世界，带到这里</Typography.Title>
        <Typography.Paragraph type="secondary">在下面挑一个正在招人的岗位，填好报名表、放上你的照片就可以了。</Typography.Paragraph>
      </div>

      {tasks.loading && <Card><Skeleton active paragraph={{ rows: 10 }} /></Card>}
      {!tasks.loading && tasks.error && <Result status="warning" title="招募信息没能加载出来" subTitle={tasks.error}
        extra={<Button onClick={() => void tasks.reload()}>再试一次</Button>} />}
      {!tasks.loading && !tasks.error && !tasks.data.length && <Card style={{ textAlign: 'center', padding: '36px 8px' }}>
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={
          <Typography.Title level={4}>现在还没有正在进行的招募，过阵子再来看看吧</Typography.Title>
        } />
      </Card>}
      {!tasks.loading && !tasks.error && <Space orientation="vertical" size={24} style={{ width: '100%' }}>
        {tasks.data.map(task => <Card key={task.publicId} styles={{ body: { padding: 'clamp(18px, 4vw, 36px)' } }}>
          <Row gutter={[28, 24]}>
            <Col xs={24} lg={9}>
              <Space orientation="vertical" size={14} style={{ width: '100%' }}>
                <Tag color="green">正在招人</Tag>
                <Typography.Title level={2} style={{ margin: 0 }}>{task.title}</Typography.Title>
                <Space><CalendarOutlined /><Typography.Text type="secondary">
                  报名时间：{dayjs(task.startAt).format('YYYY-MM-DD HH:mm')} 至 {dayjs(task.endAt).format('YYYY-MM-DD HH:mm')}（北京时间）
                </Typography.Text></Space>
                {task.description
                  ? <MarkdownRenderer value={task.description} />
                  : <Typography.Paragraph type="secondary">填好右边的表格就完成报名啦。</Typography.Paragraph>}
                <Alert type="info" showIcon title="你的原图会原封不动地保存"
                  description="我们不会压缩或转格式，照片只有摄影部的工作人员看得到，不会公开。" />
              </Space>
            </Col>
            <Col xs={24} lg={15}><PublicTaskForm task={task} /></Col>
          </Row>
        </Card>)}
      </Space>}
      <Typography.Paragraph type="secondary" style={{ textAlign: 'center', marginTop: 26 }}>
        已经是摄影部成员了？<Link to="/login">去登录工作站</Link>。<CloudUploadOutlined /> 整个提交过程都走加密连接。
      </Typography.Paragraph>
    </div>
  </main>
}

export { inferRecruitmentUploadMode }
