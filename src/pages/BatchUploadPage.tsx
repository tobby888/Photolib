import {
  Alert, App, Button, Card, DatePicker, Form, Input, Progress, Result, Select,
  Space, Steps, Typography, Upload,
} from 'antd'
import { ArrowLeftOutlined, FileZipOutlined, InboxOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { PageTitle } from '../components'
import { useLoad } from '../hooks'
import { uploadToObjectStorage } from '../storageUpload'
import { describeBytes } from '../uploadLimits'
import { useUploadLimits } from '../useUploadLimits'
import type {
  BatchUploadStatus, BatchUploadView, CampusMember, DedupedMember, EntityId, PhotoRequest, Project, TagOptions,
} from '../types'
import {
  PUBLIC_PHOTOGRAPHER_VALUE, PUBLIC_PHOTO_HINT, photographerOptionsFor, photographerPayload,
} from '../publicPhoto'
import TagSelect from '../TagSelect'
import { normalizeTags, tagRules } from '../photoTags'
import ZipTagReviewModal from '../ZipTagReviewModal'
import { hasFolderTags, itemTagsPayload } from '../zipFolderTags'
import type { ArchiveItem, ItemTags } from '../zipFolderTags'

const TERMINAL_STATUSES: BatchUploadStatus[] = ['SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED']

type FormValues = {
  archive: { originFileObj?: File }[]
  photographerContactId: EntityId
  takenAt: dayjs.Dayjs
  tags?: string[]
  description?: string
}

type Phase = 'ready' | 'uploading' | 'extracting' | 'reviewing' | 'organizing' | 'processing' | 'finished'

/** 「确认标签」对话框打开期间的状态：提交成功或放弃时 resolve，submit 才继续往下走。 */
type TagReview = {
  items: ArchiveItem[]
  archive: File
  sharedTags: string[]
  finish: (tags: ItemTags) => Promise<void>
  resolve: (confirmed: boolean) => void
}

/** 上传者在对话框里放弃了这一包：不是错误，不弹红色提示。 */
class ReviewCancelled extends Error {}

const wait = (milliseconds: number) => new Promise(resolve => window.setTimeout(resolve, milliseconds))

export default function BatchUploadPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const { user } = useAuth()
  const { message } = App.useApp()
  const uploadLimits = useUploadLimits()
  const zipTooLarge = `ZIP 压缩包不得超过 ${describeBytes(uploadLimits.PHOTO_ZIP_MAX_BYTES)}`
  const [form] = Form.useForm<FormValues>()
  const [phase, setPhase] = useState<Phase>('ready')
  const [uploadPercent, setUploadPercent] = useState(0)
  const [batch, setBatch] = useState<BatchUploadView | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [review, setReview] = useState<TagReview | null>(null)
  const requestId = searchParams.get('requestId') || undefined
  const requestedProjectId = searchParams.get('projectId') || undefined
  const returnPath = requestId ? `/requests/${requestId}` : '/photos'

  const context = useLoad(async () => {
    let request: PhotoRequest | null = null
    // 需求批次受所属选题的预设标签约束；直接传图库的批次不受限。
    let tagOptions: TagOptions = { restricted: false, tags: [] }
    if (requestId) {
      [request, tagOptions] = await Promise.all([
        api<PhotoRequest>({ url: `/requests/${requestId}` }),
        api<TagOptions>({ url: `/requests/${requestId}/tag-options` }),
      ])
    } else if (requestedProjectId) {
      // 直接传进选题相册：只为知道选题是否仅收公共照片。读不到就按不限处理，后端照样会拦。
      const project = await api<Project>({ url: `/projects/${requestedProjectId}` }).catch(() => null)
      tagOptions = { ...tagOptions, publicPhotosOnly: !!project?.publicPhotosOnly }
    }
    const members = request
      ? (await api<CampusMember[]>({
          url: '/campus-members', params: { enabled: true, campusId: request.campusId },
        })).map(member => ({ value: member.id, label: `${member.name} · ${member.studentId}` }))
      : user?.dataScope === 'CAMPUS'
        ? (await api<CampusMember[]>({ url: '/campus-members', params: { enabled: true } }))
            .map(member => ({ value: member.id, label: `${member.name} · ${member.studentId}` }))
        : (await api<DedupedMember[]>({ url: '/campus-members/deduped' }))
            .map(member => ({ value: member.id, label: `${member.name} · ${member.studentId}` }))
    return { request, members, tagOptions }
  }, { request: null as PhotoRequest | null, members: [] as { value: EntityId; label: string }[],
    tagOptions: { restricted: false, tags: [] } as TagOptions },
  [requestId, requestedProjectId, user?.dataScope])
  const publicPhotosOnly = !!context.data.tagOptions.publicPhotosOnly
  // 选题只收公共照片：拍摄者直接选好并锁住。
  useEffect(() => {
    if (publicPhotosOnly) form.setFieldValue('photographerContactId', PUBLIC_PHOTOGRAPHER_VALUE)
  }, [form, publicPhotosOnly])

  const currentStep = useMemo(() => {
    if (phase === 'uploading') return 0
    if (phase === 'extracting') return 1
    if (phase === 'reviewing') return 2
    if (phase === 'organizing') return 3
    if (phase === 'processing' || phase === 'finished') return 4
    return 0
  }, [phase])

  const pollBatch = async (batchId: string, expected: (status: BatchUploadStatus) => boolean) => {
    const deadline = Date.now() + 10 * 60_000
    let latest: BatchUploadView | null = null
    while (Date.now() < deadline) {
      latest = await api<BatchUploadView>({ url: `/photos/batches/${batchId}` })
      setBatch(latest)
      if (expected(latest.batch.status)) return latest
      await wait(2000)
    }
    throw new Error('后台处理时间较长，请稍后返回图片库查看结果')
  }

  const submit = async () => {
    const values = await form.validateFields()
    const file = values.archive?.[0]?.originFileObj
    if (!file) return
    if (!file.name.toLowerCase().endsWith('.zip')) {
      message.error('请选择 ZIP 压缩包')
      return
    }
    if (file.size > uploadLimits.PHOTO_ZIP_MAX_BYTES) {
      message.error(zipTooLarge)
      return
    }
    setSubmitting(true)
    setUploadPercent(0)
    setBatch(null)
    try {
      setPhase('uploading')
      const ticket = await api<{
        batchId: string
        tickets: { uploadUrl: string; contentType: string }[]
      }>({
        method: 'POST',
        url: '/photos/batch-upload-tickets',
        data: {
          mode: 'ZIP',
          requestId: requestId || null,
          projectId: context.data.request?.projectId || requestedProjectId || null,
          archiveFileName: file.name,
          archiveSize: file.size,
        },
      })
      const uploadTicket = ticket.tickets[0]
      if (!uploadTicket) throw new Error('未能创建 ZIP 上传地址')
      await uploadToObjectStorage(uploadTicket, file, setUploadPercent)

      setPhase('extracting')
      await api<BatchUploadView>({
        method: 'POST', url: `/photos/batches/${ticket.batchId}/complete-upload`,
      })
      const extracted = await pollBatch(ticket.batchId,
        status => status === 'WAITING_METADATA' || status === 'FAILED')
      if (extracted.batch.status === 'FAILED') {
        throw new Error(extracted.batch.failureReason
          || '压缩包没能解开。请确认它是完整的 .zip，且里面有 JPG / PNG / GIF 图片，然后重新上传。')
      }

      const sharedTags = normalizeTags(values.tags)
      const organize = async (itemTags?: { itemId: EntityId; tags: string[] }[]) => {
        setBatch(await api<BatchUploadView>({
          method: 'PUT',
          url: `/photos/batches/${ticket.batchId}/metadata`,
          data: {
            ...photographerPayload(values.photographerContactId),
            takenAt: values.takenAt.format('YYYY-MM-DDTHH:mm:ss'),
            tags: sharedTags,
            description: values.description,
            itemTags: itemTags ?? null,
          },
        }))
      }
      // 压缩包里有文件夹：每层文件夹名识别成标签，先让上传者在对话框里逐张确认，确认了才导入。
      // 一个文件夹都没有就照原来的流程，整批共用「统一标签」。
      const archiveItems: ArchiveItem[] = extracted.items
        .filter(item => item.status === 'WAITING_METADATA')
        .map(item => ({ id: item.id, fileName: item.originalFileName, folderPath: item.folderPath }))
      if (hasFolderTags(archiveItems)) {
        setPhase('reviewing')
        const confirmed = await new Promise<boolean>(resolve => setReview({
          items: archiveItems, archive: file, sharedTags, resolve,
          finish: async tags => {
            setPhase('organizing')
            try {
              await organize(itemTagsPayload(archiveItems, tags))
            } catch (error) {
              setPhase('reviewing')
              throw error
            }
          },
        }))
        if (!confirmed) throw new ReviewCancelled()
      } else {
        setPhase('organizing')
        await organize()
      }

      setPhase('processing')
      const finished = await pollBatch(ticket.batchId, status => TERMINAL_STATUSES.includes(status))
      setPhase('finished')
      if (finished.batch.status === 'SUCCEEDED') {
        message.success(`批量上传完成，共整理 ${finished.batch.successCount} 张图片`)
        navigate(returnPath)
      } else if (finished.batch.status === 'PARTIALLY_SUCCEEDED') {
        message.warning(`已完成 ${finished.batch.successCount} 张，失败 ${finished.batch.failureCount} 张`)
      } else {
        throw new Error(finished.batch.failureReason
          || '这批图片没能全部处理完成。先回图片库看看已经入库的部分，剩下的重新传一次。')
      }
    } catch (error) {
      if (error instanceof ReviewCancelled) {
        setPhase('ready')
        setBatch(null)
        message.info('已放弃这次上传，压缩包里的图片没有导入')
        return
      }
      setPhase('finished')
      message.error((error as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  if (context.error) {
    return <Result status="403" title="无法进入批量上传" subTitle={context.error}
      extra={<Button onClick={() => navigate(returnPath)}>返回</Button>} />
  }

  const processed = (batch?.batch.successCount || 0) + (batch?.batch.failureCount || 0)
  const processingPercent = batch?.batch.totalCount
    ? Math.round(processed / batch.batch.totalCount * 100)
    : 0

  return <>
    <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate(returnPath)} disabled={submitting}>
      返回{requestId ? '需求交付' : '图片库'}
    </Button>
    <PageTitle eyebrow="BATCH UPLOAD" title="ZIP 批量上传"
      description="后台自动解压 JPG / PNG / GIF，图片标题使用原文件名（不含扩展名）。压缩包里的每一层文件夹名会识别成标签，导入前可以逐张确认、修改。动图原样保存，不压缩。" />
    <Card loading={context.loading}>
      {context.data.request && <Alert showIcon type="info" style={{ marginBottom: 20 }}
        message={`上传到需求：${context.data.request.title}`}
        description="解压后的图片会自动关联到该需求及其项目。" />}
      <Steps current={currentStep} size="small" style={{ marginBottom: 28 }} items={[
        { title: '上传 ZIP' }, { title: '后台解压' }, { title: '确认标签' }, { title: '整理图片' },
        { title: '生成成品' },
      ]} />
      <Form form={form} layout="vertical" requiredMark={false} initialValues={{ takenAt: dayjs() }}>
        <Form.Item name="archive" valuePropName="fileList" getValueFromEvent={event => event.fileList}
          rules={[{ required: true, message: '请选择 ZIP 压缩包' }]}>
          <Upload.Dragger accept=".zip,application/zip" maxCount={1} disabled={submitting}
            beforeUpload={(file) => {
              if (!file.name.toLowerCase().endsWith('.zip')) message.error('仅支持 ZIP 压缩包')
              if (file.size > uploadLimits.PHOTO_ZIP_MAX_BYTES) message.error(zipTooLarge)
              return false
            }}>
            <p className="ant-upload-drag-icon"><InboxOutlined /></p>
            <p className="ant-upload-text">拖入 ZIP，或点击选择文件</p>
            <p className="ant-upload-hint">
              最大 {describeBytes(uploadLimits.PHOTO_ZIP_MAX_BYTES)}；包内最多 {uploadLimits.PHOTO_ZIP_MAX_IMAGES} 张 JPG / PNG / GIF；
              单张不超过 {describeBytes(uploadLimits.PHOTO_IMAGE_MAX_BYTES)}
            </p>
          </Upload.Dragger>
        </Form.Item>
        <Form.Item label="拍摄者" name="photographerContactId"
          extra={publicPhotosOnly ? '这个选题只收公共照片，拍摄者固定为「公共照片」。' : PUBLIC_PHOTO_HINT}
          rules={[{ required: true, message: '请从通讯录选择拍摄者，或选「公共照片」' }]}>
          <Select showSearch optionFilterProp="label" disabled={publicPhotosOnly}
            options={photographerOptionsFor(context.data.members, publicPhotosOnly)}
            placeholder={context.data.members.length ? '按姓名或学号选择' : '通讯录中没有可用成员，可选「公共照片」'} />
        </Form.Item>
        <Form.Item label="统一拍摄时间" name="takenAt" rules={[{ required: true }]}>
          <DatePicker showTime style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item label="统一标签" name="tags" rules={tagRules}
          extra={context.data.tagOptions.restricted
            ? '需求所属选题设置了预设标签，只能从中选择，也可以不加标签' : undefined}>
          <TagSelect restricted={context.data.tagOptions.restricted} presets={context.data.tagOptions.tags} />
        </Form.Item>
        <Form.Item label="统一说明" name="description"><Input.TextArea rows={3} /></Form.Item>
        {submitting && <Space direction="vertical" style={{ width: '100%', marginBottom: 20 }}>
          <Progress percent={phase === 'uploading' ? uploadPercent : phase === 'processing' ? processingPercent : 100}
            status="active" />
          <Typography.Text type="secondary">
            {phase === 'uploading' && `正在上传 ZIP… ${uploadPercent}%`}
            {phase === 'extracting' && '上传完成，后台正在安全解压并筛选图片…'}
            {phase === 'reviewing' && '已按压缩包里的文件夹识别出标签，请在弹出的对话框里确认'}
            {phase === 'organizing' && `已找到 ${batch?.batch.totalCount || 0} 张图片，正在按文件名生成标题…`}
            {phase === 'processing' && `正在生成成品图和缩略图：${processed}/${batch?.batch.totalCount || 0}`}
          </Typography.Text>
        </Space>}
        {batch?.batch.status === 'PARTIALLY_SUCCEEDED' && <Alert showIcon type="warning" style={{ marginBottom: 20 }}
          message={`部分完成：成功 ${batch.batch.successCount} 张，失败 ${batch.batch.failureCount} 张`}
          description={batch.items.filter(item => item.failureReason)
            .map(item => `${item.originalFileName}：${item.failureReason}`).join('；')} />}
        <Button type="primary" size="large" block icon={<FileZipOutlined />}
          loading={submitting} disabled={!context.data.members.length} onClick={() => void submit()}>
          上传并自动整理
        </Button>
      </Form>
    </Card>
    {review && <ZipTagReviewModal items={review.items} archive={review.archive} sharedTags={review.sharedTags}
      candidates={context.data.tagOptions.tags}
      rules={{ restricted: context.data.tagOptions.restricted, presets: context.data.tagOptions.tags,
        forbidReserved: false }}
      onConfirm={async tags => {
        await review.finish(tags)
        review.resolve(true)
        setReview(null)
      }}
      onCancel={() => {
        review.resolve(false)
        setReview(null)
      }} />}
  </>
}
