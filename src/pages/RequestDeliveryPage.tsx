import {
  Alert, App, Button, Card, Checkbox, Col, DatePicker, Descriptions, Form, Image, Input, Progress,
  Result, Row, Select, Space, Statistic, Tag, Typography, Upload,
} from 'antd'
import {
  ArrowLeftOutlined, CheckCircleOutlined, CloudUploadOutlined, DeleteOutlined, DownloadOutlined,
  InboxOutlined, PictureOutlined, SendOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, emptyPage } from '../api'
import { readTakenAt } from '../exif'
import { uploadToObjectStorage } from '../storageUpload'
import { useAuth } from '../auth'
import { DataState, StatusTag } from '../components'
import { useLoad } from '../hooks'
import type { Campus, CampusMember, EntityId, PageData, Photo, PhotoRequest, Project } from '../types'
import MarkdownRenderer from '../MarkdownRenderer'
import { photoTitleFromFileName } from '../photoTitle'
import { hasPermission } from '../permissions'
import { PREVIEW_CROSS_ORIGIN } from '../previewImage'
import { preparePhotoBatchDownload } from '../photoBatchDownload'

type UploadValues = {
  files: { originFileObj?: File }[]
  photographerContactId: EntityId
  takenAt: dayjs.Dayjs
  tags?: string[]
  description?: string
}

const photoStatuses = [
  { value: 'AVAILABLE', label: '可用图片' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'UPLOADING', label: '上传中' },
  { value: 'ARCHIVED', label: '已归档' },
]

export default function RequestDeliveryPage() {
  const { requestId = '' } = useParams()
  const navigate = useNavigate()
  const { user } = useAuth()
  const { message, modal } = App.useApp()
  const [form] = Form.useForm<UploadValues>()
  const [photoStatus, setPhotoStatus] = useState('AVAILABLE')
  const [uploading, setUploading] = useState(false)
  const [progress, setProgress] = useState(0)
  const [actioning, setActioning] = useState(false)
  const [selectedPhotoIds, setSelectedPhotoIds] = useState<EntityId[]>([])
  const [batchWorking, setBatchWorking] = useState(false)

  const requestState = useLoad(async () => {
    const request = await api<PhotoRequest>({ url: `/requests/${requestId}` })
    const [project, campuses] = await Promise.all([
      api<Project>({ url: `/projects/${request.projectId}` }).catch(() => null),
      api<Campus[]>({ url: '/campuses', params: { enabled: true } }),
    ])
    return { request, project, campuses }
  }, { request: null as PhotoRequest | null, project: null as Project | null,
    campuses: [] as Campus[] }, [requestId])

  const photosState = useLoad(
    () => api<PageData<Photo>>({
      url: '/photos',
      params: { page: 1, pageSize: 100, requestId, status: photoStatus },
    }),
    emptyPage<Photo>(),
    [requestId, photoStatus],
  )

  const request = requestState.data.request
  const campusScoped = user?.dataScope === 'CAMPUS'
  const canManagePhotos = hasPermission(user, 'REQUEST_PHOTO_MANAGE')
  const canUpload = canManagePhotos && request?.status === 'ACCEPTED'
  const canDelete = canManagePhotos && request?.status !== 'COMPLETED' && request?.status !== 'CANCELLED'
  const membersState = useLoad(
    () => canUpload && request
      ? api<CampusMember[]>({
          url: '/campus-members', params: { enabled: true, campusId: request.campusId },
        })
      : Promise.resolve([] as CampusMember[]),
    [] as CampusMember[],
    [canUpload, request?.campusId],
  )
  const campusName = useMemo(() => request
    ? requestState.data.campuses.find(campus => campus.id === request.campusId)?.name || `校区 #${request.campusId}`
    : '', [request, requestState.data.campuses])

  const accept = async () => {
    if (!request) return
    setActioning(true)
    try {
      await api({ method: 'POST', url: `/requests/${request.id}/accept` })
      message.success('任务已接受，现在可以上传交付图片')
      await requestState.reload()
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setActioning(false)
    }
  }

  const submit = async () => {
    if (!request) return
    setActioning(true)
    try {
      await api({ method: 'POST', url: `/requests/${request.id}/submit`, data: { version: request.version } })
      message.success('图片交付已提交，等待确认')
      await requestState.reload()
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setActioning(false)
    }
  }

  const upload = async () => {
    if (!request) return
    const values = await form.validateFields()
    const files = values.files.map(item => item.originFileObj).filter((file): file is File => !!file)
    if (!files.length) return
    setUploading(true)
    setProgress(0)
    try {
      for (let index = 0; index < files.length; index += 1) {
        const file = files[index]
        const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await file.arrayBuffer())))
          .map(byte => byte.toString(16).padStart(2, '0')).join('')
        const ticket = await api<{ photoId: string; uploadUrl: string; method: string; contentType: string }>({
          method: 'POST',
          url: '/photos/upload-tickets',
          data: {
            requestId: request.id,
            projectId: request.projectId,
            fileName: file.name,
            contentType: file.type,
            size: file.size,
            sha256: hash,
            photographerContactId: values.photographerContactId,
            takenAt: values.takenAt.format('YYYY-MM-DDTHH:mm:ss'),
          },
        })
        await uploadToObjectStorage(ticket, file)
        await api({
          method: 'POST',
          url: `/photos/${ticket.photoId}/complete-upload`,
          data: {
            title: photoTitleFromFileName(file.name),
            description: values.description,
            tags: values.tags || [],
          },
        })
        setProgress(Math.round(((index + 1) / files.length) * 100))
      }
      message.success(`${files.length} 张图片已上传，正在后台处理`)
      form.resetFields()
      setPhotoStatus('PROCESSING')
      await photosState.reload()
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setUploading(false)
    }
  }

  const batchDownload = async () => {
    if (!selectedPhotoIds.length) return
    setBatchWorking(true)
    try {
      const downloadUrl = await preparePhotoBatchDownload(selectedPhotoIds)
      if (downloadUrl) window.location.assign(downloadUrl)
      else message.info('ZIP 仍在后台生成，请稍后重试')
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setBatchWorking(false)
    }
  }

  const batchDelete = () => {
    if (!selectedPhotoIds.length) return
    const selectedCount = selectedPhotoIds.length
    modal.confirm({
      title: `删除 ${selectedCount} 张需求图片？`,
      content: '删除后图片记录和存储对象会被清理，且无法恢复。',
      okText: '确认删除', okButtonProps: { danger: true }, cancelText: '取消',
      onOk: async () => {
        setBatchWorking(true)
        try {
          await api({ method: 'POST', url: '/photos/batch-delete', data: { photoIds: selectedPhotoIds } })
          setSelectedPhotoIds([])
          message.success(`已删除 ${selectedCount} 张需求图片`)
          await photosState.reload()
        } finally {
          setBatchWorking(false)
        }
      },
    })
  }

  if (requestState.error) {
    return <Result status="403" title="无法访问这个需求" subTitle={requestState.error}
      extra={<Button onClick={() => navigate('/requests')}>返回需求列表</Button>} />
  }

  return <DataState loading={requestState.loading} error={undefined} empty={!request} onRetry={requestState.reload}
    emptyText="找不到这个需求"
    emptyHint="它可能已经被删除，或者已经不在你的授权校区里。">
    {request && <div className="request-delivery">
      <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/requests')}>返回需求列表</Button>

      <section className="delivery-hero">
        <div>
          <Typography.Text className="eyebrow">REQUEST DELIVERY · REQ-{request.id}</Typography.Text>
          <Typography.Title>{request.title}</Typography.Title>
          {request.description
            ? <MarkdownRenderer value={request.description} />
            : <Typography.Paragraph>暂无拍摄说明</Typography.Paragraph>}
          <Space wrap>
            <StatusTag value={request.status} />
            <Tag>{campusName}</Tag>
            <Tag>截止 {dayjs(request.deadline).format('YYYY-MM-DD HH:mm')}</Tag>
          </Space>
        </div>
        <Space wrap>
          {campusScoped && request.status === 'PUBLISHED' &&
            <Button size="large" type="primary" loading={actioning} onClick={() => void accept()}>接受任务</Button>}
          {campusScoped && request.status === 'ACCEPTED' &&
            <Button size="large" icon={<SendOutlined />} loading={actioning}
              onClick={() => void submit()}>提交交付</Button>}
        </Space>
      </section>

      {request.status === 'ACCEPTED' && request.returnReason && <Alert showIcon type="warning"
        title="需求已被退回，请修改后重新提交"
        description={<Space direction="vertical" size={2}>
          <span>{request.returnReason}</span>
          {request.returnedAt && <Typography.Text type="secondary">
            退回时间：{dayjs(request.returnedAt).format('YYYY-MM-DD HH:mm')}
          </Typography.Text>}
        </Space>} />}

      <Row gutter={[18, 18]} className="delivery-metrics">
        <Col xs={12} md={8}><Statistic title="当前列表" value={photosState.data.total} suffix="张" /></Col>
        <Col xs={12} md={8}><Statistic title="项目" value={requestState.data.project?.title || `#${request.projectId}`} /></Col>
        <Col xs={12} md={8}><Statistic title="剩余时间"
          value={dayjs(request.deadline).isBefore(dayjs()) ? '已截止' : `${Math.max(1, dayjs(request.deadline).diff(dayjs(), 'day'))} 天`} /></Col>
      </Row>

      <Row gutter={[20, 20]}>
        <Col xs={24} xl={canUpload ? 15 : 24}>
          <Card title="需求图片" extra={<Space wrap>
            {!!selectedPhotoIds.length && <Typography.Text>已选 {selectedPhotoIds.length} 张</Typography.Text>}
            {canManagePhotos && <Button icon={<DownloadOutlined />} disabled={!selectedPhotoIds.length}
              loading={batchWorking} onClick={() => void batchDownload()}>批量下载</Button>}
            {canDelete && <Button danger icon={<DeleteOutlined />} disabled={!selectedPhotoIds.length}
              loading={batchWorking} onClick={batchDelete}>批量删除</Button>}
            <Select value={photoStatus} options={photoStatuses} onChange={value => {
              setPhotoStatus(value); setSelectedPhotoIds([])
            }} style={{ width: 130 }} />
          </Space>}>
            <DataState loading={photosState.loading} error={photosState.error}
              empty={!photosState.data.items.length} onRetry={photosState.reload}
              emptyText="这个状态下还没有图片"
              emptyHint="上传交付图片后，会先进入“处理中”，压缩完成才变成可用图片。">
              <div className="delivery-gallery">
                {photosState.data.items.map(photo => <article key={photo.id}>
                  <div>
                    {canManagePhotos && (photo.status === 'AVAILABLE' || photo.status === 'ARCHIVED') &&
                      <Checkbox checked={selectedPhotoIds.includes(photo.id)}
                        disabled={selectedPhotoIds.length >= 200 && !selectedPhotoIds.includes(photo.id)}
                        onChange={event => setSelectedPhotoIds(current => event.target.checked
                          ? [...new Set([...current, photo.id])].slice(0, 200)
                          : current.filter(id => id !== photo.id))} />}
                    {photo.thumbnailUrl
                      ? <Image preview crossOrigin={PREVIEW_CROSS_ORIGIN}
                          src={photo.thumbnailUrl} alt={photo.title} />
                      : <div className="delivery-placeholder"><PictureOutlined /></div>}
                    <StatusTag value={photo.status} />
                  </div>
                  <strong>{photo.title || '未命名图片'}</strong>
                  <span>{dayjs(photo.createdAt).format('MM-DD HH:mm')}</span>
                </article>)}
              </div>
            </DataState>
          </Card>
        </Col>

        {canUpload && <Col xs={24} xl={9}>
          <Card className="delivery-upload-card" title={<Space><CloudUploadOutlined />上传交付图片</Space>}
            extra={<Button type="link" icon={<InboxOutlined />}
              onClick={() => navigate(`/photos/batch-upload?requestId=${request.id}&projectId=${request.projectId}`)}>
              上传 ZIP
            </Button>}>
            {membersState.error && <Alert showIcon type="warning"
              title="拍摄者通讯录加载失败"
              description={membersState.error} />}
            <Form form={form} layout="vertical" requiredMark={false}
              initialValues={{ takenAt: dayjs() }}>
              <Form.Item name="files" valuePropName="fileList" getValueFromEvent={event => event.fileList}
                rules={[{ required: true, message: '请选择图片' }]}>
                <Upload.Dragger multiple accept=".jpg,.jpeg,.png" beforeUpload={async (file, fileList) => {
                  // 批量上传共用一个拍摄时间，自动读取取自第一张
                  if (fileList[0] === file) {
                    const takenAt = await readTakenAt(file)
                    if (takenAt) {
                      form.setFieldsValue({ takenAt })
                      message.info('已根据第一张照片的 EXIF 自动填入拍摄时间')
                    } else {
                      message.warning('未能识别照片拍摄时间，请手动选择')
                    }
                  }
                  return false
                }}>
                  <p className="ant-upload-drag-icon"><InboxOutlined /></p>
                  <p className="ant-upload-text">拖入或选择需求图片</p>
                  <p className="ant-upload-hint">支持多张 JPG / PNG，单张不超过 100 MiB</p>
                </Upload.Dragger>
              </Form.Item>
              <Form.Item label="拍摄者" name="photographerContactId"
                extra="从需求所属校区通讯录选择，通讯录在「通讯录」页维护"
                rules={[{ required: true, message: '请从通讯录选择拍摄者' }]}>
                <Select showSearch optionFilterProp="label" loading={membersState.loading}
                  placeholder={membersState.data.length ? '按姓名或学号选择' : '该校区通讯录为空，请先添加成员'}
                  notFoundContent="该校区通讯录中没有可用成员"
                  options={membersState.data.map(member => ({
                    value: member.id, label: `${member.name} · ${member.studentId}`,
                  }))} />
              </Form.Item>
              <Form.Item label="拍摄时间" name="takenAt" rules={[{ required: true }]}
                extra="批量上传统一采用该时间，已自动取自第一张照片的 EXIF，可手动调整">
                <DatePicker showTime style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item label="标签" name="tags">
                <Select mode="tags" maxCount={30} placeholder="输入后回车添加" />
              </Form.Item>
              <Form.Item label="统一说明" name="description"><Input.TextArea rows={2} /></Form.Item>
              {uploading && <Progress percent={progress} status="active" />}
              <Button block size="large" type="primary" icon={<CloudUploadOutlined />}
                loading={uploading} onClick={() => void upload()}>开始上传</Button>
            </Form>
          </Card>
        </Col>}
      </Row>

      {!canUpload && <Card className="delivery-state-card">
        <Descriptions column={{ xs: 1, sm: 2, lg: 4 }} items={[
          { key: 'published', label: '1. 接受任务', children: request.status === 'PUBLISHED' ? '等待你接受' : <CheckCircleOutlined /> },
          { key: 'upload', label: '2. 上传图片', children: request.status === 'ACCEPTED' ? '当前阶段' : '接单后开放' },
          { key: 'submit', label: '3. 提交交付', children: request.status === 'SUBMITTED' ? '等待确认' : '上传后提交' },
          { key: 'complete', label: '4. 确认完成', children: request.status === 'COMPLETED' ? <CheckCircleOutlined /> : '由发布者确认' },
        ]} />
      </Card>}
    </div>}
  </DataState>
}
