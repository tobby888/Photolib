import {
  App, Button, Card, Checkbox, Col, DatePicker, Descriptions, Drawer, Form, Image, Input, Modal,
  Pagination, Progress, Row, Select, Space, Tag, Typography, Upload,
} from 'antd'
import { CloudUploadOutlined, DeleteOutlined, DownloadOutlined, InboxOutlined, SearchOutlined } from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import { api, emptyPage, qs } from '../api'
import { readTakenAt } from '../exif'
import { uploadToObjectStorage } from '../storageUpload'
import type { CampusMember, DedupedMember, EntityId, PageData, Photo } from '../types'
import { DataState, formatBytes, PageTitle, StatusTag } from '../components'
import { useLoad } from '../hooks'
import { useAuth } from '../auth'
import { preparePhotoBatchDownload } from '../photoBatchDownload'

export default function PhotosPage() {
  const navigate = useNavigate()
  const { message, modal } = App.useApp()
  const { user } = useAuth()
  const [uploadForm] = Form.useForm()
  const [uploadOpen, setUploadOpen] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [uploadPhase, setUploadPhase] = useState<'uploading' | 'processing' | null>(null)
  const [uploadPercent, setUploadPercent] = useState(0)
  const [selected, setSelected] = useState<Photo | null>(null)
  const [selectedIds, setSelectedIds] = useState<EntityId[]>([])
  const [batchDownloading, setBatchDownloading] = useState(false)
  const [filters, setFilters] = useState({ page: 1, keyword: '', status: 'AVAILABLE' })
  const { data, loading, error, reload } = useLoad(
    () => api<PageData<Photo>>({ url: '/photos', params: qs({ ...filters, pageSize: 24 }) }),
    emptyPage<Photo>(), [filters.page, filters.keyword, filters.status],
  )
  useEffect(() => {
    const onPreviewRegenerated = () => void reload()
    window.addEventListener('preview-generation-succeeded', onPreviewRegenerated)
    return () => window.removeEventListener('preview-generation-succeeded', onPreviewRegenerated)
  }, [reload])
  const { data: photographers, loading: photographersLoading } = useLoad(
    async () => user?.role === 'CAMPUS_MANAGER'
      ? (await api<CampusMember[]>({ url: '/campus-members', params: { enabled: true } }))
          .map(m => ({ value: m.id, label: `${m.name} · ${m.studentId}` }))
      : (await api<DedupedMember[]>({ url: '/campus-members/deduped' }))
          .map(m => ({ value: m.id, label: `${m.name} · ${m.studentId}` })),
    [] as { value: EntityId; label: string }[], [user?.role],
  )
  const waitForProcessing = async (photoId: string): Promise<Photo> => {
    // complete-upload leaves the photo PROCESSING; the async pipeline flips it to
    // AVAILABLE on success or back to UPLOADING (with failureReason) on failure.
    // Poll until it settles so we can surface the real outcome instead of a silent
    // "processing" that never appears in the (AVAILABLE-filtered) gallery.
    const deadline = Date.now() + 90_000
    let last: Photo | null = null
    while (Date.now() < deadline) {
      last = await api<Photo>({ url: `/photos/${photoId}` })
      if (last.status !== 'PROCESSING') return last
      await new Promise(resolve => setTimeout(resolve, 1500))
    }
    return last ?? await api<Photo>({ url: `/photos/${photoId}` })
  }
  const submitUpload = async () => {
    const values = await uploadForm.validateFields()
    const file = values.file?.[0]?.originFileObj as File | undefined
    if (!file) return
    setUploading(true); setUploadPercent(0); setUploadPhase('uploading')
    try {
      const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await file.arrayBuffer())))
        .map(b => b.toString(16).padStart(2, '0')).join('')
      const ticket = await api<{ photoId: string; uploadUrl: string; method: string; contentType: string }>({
        method: 'POST', url: '/photos/upload-tickets', data: {
          requestId: values.requestId || null, projectId: values.projectId || null,
          fileName: file.name, contentType: file.type, size: file.size, sha256: hash,
          photographerContactId: values.photographerContactId,
          takenAt: values.takenAt.format('YYYY-MM-DDTHH:mm:ss'),
        },
      })
      await uploadToObjectStorage(ticket, file, setUploadPercent)
      setUploadPhase('processing')
      await api({ method: 'POST', url: `/photos/${ticket.photoId}/complete-upload`,
        data: { title: values.title, description: values.description, tags: values.tags || [] } })
      const photo = await waitForProcessing(ticket.photoId)
      if (photo.status === 'AVAILABLE' || photo.status === 'ARCHIVED') {
        message.success('图片已上传并处理完成')
      } else if (photo.status === 'PROCESSING') {
        message.warning('图片仍在后台处理，请稍后在图片库刷新查看')
      } else {
        message.error(photo.failureReason ? `图片处理失败：${photo.failureReason}` : '图片处理失败，请重新上传')
      }
      setUploadOpen(false); uploadForm.resetFields(); await reload()
    } catch (e) { message.error((e as Error).message) }
    finally { setUploading(false); setUploadPhase(null); setUploadPercent(0) }
  }
  const download = async (photo: Photo) => {
    try {
      const result = await api<{ downloadUrl: string }>({ method: 'POST', url: `/photos/${photo.id}/download-url` })
      window.open(result.downloadUrl, '_blank', 'noopener')
    } catch (e) { message.error((e as Error).message) }
  }
  const toggleSelected = (photoId: EntityId, checked: boolean) => {
    setSelectedIds(current => checked
      ? current.includes(photoId) ? current : [...current, photoId].slice(0, 200)
      : current.filter(id => id !== photoId))
  }
  const batchDownload = async () => {
    if (!selectedIds.length) return
    setBatchDownloading(true)
    try {
      const downloadUrl = await preparePhotoBatchDownload(selectedIds)
      if (downloadUrl) {
        window.location.assign(downloadUrl)
        setSelectedIds([])
        message.success('所选图片已打包为 ZIP')
      } else {
        message.info('ZIP 仍在后台生成，请稍后重新发起下载')
      }
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setBatchDownloading(false)
    }
  }
  const remove = (photo: Photo) => {
    modal.confirm({
      title: '确认删除图片？',
      content: '图片记录及 OSS 中的成品图、缩略图和保留原图都将被永久删除，此操作无法撤销。',
      okText: '确认删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      async onOk() {
        try {
          await api({ method: 'DELETE', url: `/photos/${photo.id}` })
          setSelected(null)
          message.success('图片已删除')
          await reload()
        } catch (e) {
          message.error((e as Error).message)
          throw e
        }
      },
    })
  }
  const canDelete = user?.role === 'ADMIN' || user?.role === 'MINISTER'
  return <>
    <PageTitle eyebrow="LIBRARY" title="图片库" description="检索、查看并下载团队沉淀的每一帧。"
      extra={<Space wrap>
        <Button size="large" icon={<DownloadOutlined />} loading={batchDownloading}
          disabled={!selectedIds.length} onClick={() => void batchDownload()}>
          打包下载{selectedIds.length ? `（${selectedIds.length}）` : ''}
        </Button>
        {!!selectedIds.length && <Button size="large" onClick={() => setSelectedIds([])}>清空选择</Button>}
        <Button size="large" icon={<InboxOutlined />} onClick={() => navigate('/photos/batch-upload')}>ZIP 批量上传</Button>
        <Button type="primary" size="large" icon={<CloudUploadOutlined />} onClick={() => setUploadOpen(true)}>上传图片</Button>
      </Space>} />
    <Card className="filter-card">
      <Space wrap>
        <Input.Search allowClear placeholder="搜索标题、描述或标签" enterButton={<SearchOutlined />} style={{ width: 320 }}
          onSearch={keyword => setFilters({ ...filters, page: 1, keyword })} />
        <Select value={filters.status} style={{ width: 150 }} options={[
          { value: 'AVAILABLE', label: '可用图片' }, { value: 'PROCESSING', label: '处理中' }, { value: 'ARCHIVED', label: '已归档' },
        ]} onChange={status => setFilters({ ...filters, page: 1, status })} />
      </Space>
      <Typography.Text type="secondary">共 {data.total} 张图片</Typography.Text>
    </Card>
    <DataState loading={loading} error={error} empty={!data.items.length} onRetry={reload}>
      <Row gutter={[16, 20]} className="photo-grid">
        {data.items.map(photo => <Col xs={24} sm={12} lg={8} xxl={6} key={photo.id}>
          <Card className={`photo-card${selectedIds.includes(photo.id) ? ' photo-card-selected' : ''}`} hoverable cover={<div className="photo-cover" onClick={() => setSelected(photo)}>
            {photo.thumbnailUrl ? <Image preview={false} src={photo.thumbnailUrl} alt={photo.title} /> : <div className="image-placeholder"><span>{photo.title?.slice(0, 1) || '图'}</span></div>}
            <div className="photo-overlay"><Space><StatusTag value={photo.status} />{!!photo.adoptionCount && <Tag color="gold">已采用 × {photo.adoptionCount}</Tag>}</Space><Space>
              {(photo.status === 'AVAILABLE' || photo.status === 'ARCHIVED') && <Checkbox
                checked={selectedIds.includes(photo.id)}
                disabled={selectedIds.length >= 200 && !selectedIds.includes(photo.id)}
                onClick={event => event.stopPropagation()}
                onChange={event => toggleSelected(photo.id, event.target.checked)}
                aria-label={`选择图片 ${photo.title || photo.id}`} />}
              <Button shape="circle" icon={<DownloadOutlined />} onClick={e => { e.stopPropagation(); void download(photo) }} />
            </Space></div>
          </div>}>
            <Typography.Title level={5} ellipsis>{photo.title || '未命名图片'}</Typography.Title>
            <Space size={4} wrap>{photo.tags?.slice(0, 3).map(tag => <Tag variant="filled" key={tag}>{tag}</Tag>)}</Space>
            <div className="photo-meta"><span>{photo.photographerName}</span><span>{dayjs(photo.takenAt).format('YYYY.MM.DD')}</span></div>
          </Card>
        </Col>)}
      </Row>
      <Pagination current={filters.page} pageSize={24} total={data.total} hideOnSinglePage onChange={page => setFilters({ ...filters, page })} />
    </DataState>
    <Modal title="上传单张图片" width={680} open={uploadOpen} onCancel={() => { if (!uploading) setUploadOpen(false) }} onOk={submitUpload}
      okText="开始上传" confirmLoading={uploading} destroyOnHidden
      maskClosable={!uploading} closable={!uploading} cancelButtonProps={{ disabled: uploading }}>
      <Form form={uploadForm} layout="vertical" requiredMark={false}>
        <Form.Item name="file" valuePropName="fileList" getValueFromEvent={e => e.fileList} rules={[{ required: true, message: '请选择图片' }]}>
          <Upload.Dragger accept=".jpg,.jpeg,.png" maxCount={1} beforeUpload={async file => {
            const takenAt = await readTakenAt(file)
            if (takenAt) {
              uploadForm.setFieldsValue({ takenAt })
              message.info('已根据照片 EXIF 自动填入拍摄时间')
            } else {
              message.warning('未能识别照片拍摄时间，请手动选择')
            }
            return false
          }}>
            <p className="ant-upload-drag-icon"><InboxOutlined /></p><p className="ant-upload-text">拖拽图片到这里，或点击选择</p><p className="ant-upload-hint">仅支持 JPG / PNG，单张不超过 100 MiB</p>
          </Upload.Dragger>
        </Form.Item>
        <Row gutter={16}><Col span={12}><Form.Item label="图片标题" name="title" rules={[{ required: true }]}><Input /></Form.Item></Col>
          <Col span={12}><Form.Item label="拍摄时间" name="takenAt" rules={[{ required: true }]}><DatePicker showTime style={{ width: '100%' }} /></Form.Item></Col></Row>
        <Form.Item label="拍摄者" name="photographerContactId" rules={[{ required: true, message: '请从通讯录选择拍摄者' }]}>
          <Select showSearch optionFilterProp="label" loading={photographersLoading}
            placeholder={photographers.length ? '按姓名或学号选择' : '通讯录为空，请先在「通讯录」页添加成员'}
            notFoundContent={photographersLoading ? '正在加载通讯录…' : '通讯录中没有可用成员'}
            options={photographers} />
        </Form.Item>
        <Form.Item label="标签" name="tags"><Select mode="tags" maxCount={30} placeholder="输入后回车添加标签" /></Form.Item>
        <Form.Item label="图片说明" name="description"><Input.TextArea rows={3} /></Form.Item>
      </Form>
      {uploadPhase && <div style={{ marginTop: 4 }}>
        <Progress percent={uploadPhase === 'uploading' ? uploadPercent : 100} status="active" />
        <Typography.Text type="secondary">
          {uploadPhase === 'uploading'
            ? `正在上传到对象存储… ${uploadPercent}%`
            : '上传完成，后台正在压缩生成成品图与缩略图，请稍候…'}
        </Typography.Text>
      </div>}
    </Modal>
    <Drawer title="图片详情" width={520} open={!!selected} onClose={() => setSelected(null)}
      extra={selected && <Space>
        {canDelete && <Button danger icon={<DeleteOutlined />} onClick={() => remove(selected)}>删除图片</Button>}
        <Button type="primary" icon={<DownloadOutlined />} onClick={() => void download(selected)}>下载原图</Button>
      </Space>}>
      {selected && <>
        <div className="detail-image">{selected.thumbnailUrl ? <Image src={selected.thumbnailUrl} /> : <div className="image-placeholder"><span>{selected.title.slice(0, 1)}</span></div>}</div>
        <Typography.Title level={3}>{selected.title}</Typography.Title>
        <Typography.Paragraph type="secondary">{selected.description || '暂无图片说明'}</Typography.Paragraph>
        <Descriptions column={1} size="small" items={[
          { key: 'status', label: '状态', children: <StatusTag value={selected.status} /> },
          { key: 'adoption', label: '采用状态', children: selected.adoptionCount ? <Tag color="gold">已采用 × {selected.adoptionCount}</Tag> : '未采用' },
          { key: 'projects', label: '关联项目', children: selected.relatedProjects?.length
            ? <Space size={4} wrap>{selected.relatedProjects.map(project => <Tag key={project.id} color="blue">{project.title}</Tag>)}</Space>
            : selected.relatedProjectIds?.length
              ? <Space size={4} wrap>{selected.relatedProjectIds.map(pid => <Tag key={pid} color="blue">项目 #{pid}</Tag>)}</Space>
            : '无关联项目' },
          { key: 'photographer', label: '拍摄者', children: `${selected.photographerName} · ${selected.photographerStudentId}` },
          { key: 'taken', label: '拍摄时间', children: dayjs(selected.takenAt).format('YYYY-MM-DD HH:mm') },
          { key: 'size', label: '文件信息', children: `${selected.width || '-'} × ${selected.height || '-'} · ${formatBytes(selected.size)}` },
          { key: 'previewSize', label: '预览图体积', children: selected.thumbnailSize == null ? '-' : formatBytes(selected.thumbnailSize) },
          { key: 'file', label: '归档文件名', children: selected.storedFileName },
        ]} />
      </>}
    </Drawer>
  </>
}
