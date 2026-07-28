import {
  App, Button, Card, Checkbox, Col, DatePicker, Form, Image, Input, Modal,
  Pagination, Progress, Row, Select, Space, Table, Tag, Typography, Upload,
} from 'antd'
import {
  CloudUploadOutlined, DeleteOutlined, DownloadOutlined, FolderAddOutlined, InboxOutlined, SearchOutlined,
} from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import { api, emptyPage, qs } from '../api'
import { readTakenAt } from '../exif'
import { uploadToObjectStorage } from '../storageUpload'
import type { CampusMember, DedupedMember, EntityId, PageData, Photo, Project } from '../types'
import { DataState, PageTitle, StatusTag } from '../components'
import { useLoad } from '../hooks'
import { useAuth } from '../auth'
import { preparePhotoBatchDownload } from '../photoBatchDownload'
import { photoTitleFromFileName } from '../photoTitle'
import { hasPermission } from '../permissions'

export default function PhotosPage() {
  const navigate = useNavigate()
  const { message, modal } = App.useApp()
  const { user } = useAuth()
  const canAddToProject = hasPermission(user, 'PROJECT_ADOPT')
  const canDelete = hasPermission(user, 'PHOTO_DELETE')
  const canUpload = hasPermission(user, 'PHOTO_UPLOAD')
  const canDownload = hasPermission(user, 'PHOTO_DOWNLOAD')
  const [uploadForm] = Form.useForm()
  const [uploadOpen, setUploadOpen] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [uploadPhase, setUploadPhase] = useState<'uploading' | 'processing' | null>(null)
  const [uploadPercent, setUploadPercent] = useState(0)
  const [selectedPhotos, setSelectedPhotos] = useState<Photo[]>([])
  const [batchDownloading, setBatchDownloading] = useState(false)
  const [projectPickerOpen, setProjectPickerOpen] = useState(false)
  const [projectSaving, setProjectSaving] = useState(false)
  const [selectedProjectId, setSelectedProjectId] = useState<EntityId | null>(null)
  const [projectFilters, setProjectFilters] = useState({ page: 1, keyword: '' })
  const [projectPickerPhotos, setProjectPickerPhotos] = useState<Photo[]>([])
  const [filters, setFilters] = useState({ page: 1, keyword: '', status: 'AVAILABLE' })
  const selectedIds = selectedPhotos.map(photo => photo.id)
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
    async () => !canUpload ? [] : user?.dataScope === 'CAMPUS'
      ? (await api<CampusMember[]>({ url: '/campus-members', params: { enabled: true } }))
          .map(m => ({ value: m.id, label: `${m.name} · ${m.studentId}` }))
      : (await api<DedupedMember[]>({ url: '/campus-members/deduped' }))
          .map(m => ({ value: m.id, label: `${m.name} · ${m.studentId}` })),
    [] as { value: EntityId; label: string }[], [user?.dataScope, canUpload],
  )
  const {
    data: activeProjects,
    loading: projectsLoading,
    error: projectsError,
  } = useLoad(
    () => projectPickerOpen
      ? api<PageData<Project>>({
          url: '/projects',
          params: qs({ ...projectFilters, pageSize: 8, status: 'ACTIVE' }),
        })
      : Promise.resolve(emptyPage<Project>()),
    emptyPage<Project>(),
    [projectPickerOpen, projectFilters.page, projectFilters.keyword],
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
  const toggleSelected = (photo: Photo, checked: boolean) => {
    setSelectedPhotos(current => checked
      ? current.some(item => item.id === photo.id) ? current : [...current, photo].slice(0, 200)
      : current.filter(item => item.id !== photo.id))
  }
  const batchDownload = async () => {
    if (!selectedIds.length) return
    setBatchDownloading(true)
    try {
      const downloadUrl = await preparePhotoBatchDownload(selectedIds)
      if (downloadUrl) {
        window.location.assign(downloadUrl)
        setSelectedPhotos([])
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
  const batchRemove = () => {
    if (!selectedIds.length) return
    modal.confirm({
      title: `批量删除 ${selectedIds.length} 张图片`,
      content: '删除后图片记录和存储对象将被清理；已被引图片不能直接删除。',
      okText: '确认删除', okButtonProps: { danger: true }, cancelText: '取消',
      onOk: async () => {
        await api({ method: 'POST', url: '/photos/batch-delete', data: { photoIds: selectedIds } })
        setSelectedPhotos([]); message.success('所选图片已删除'); await reload()
      },
    })
  }
  const isLinkedToProject = (photo: Photo, projectId: EntityId) =>
    photo.relatedProjects?.some(project => String(project.id) === String(projectId))
    || photo.relatedProjectIds?.some(id => String(id) === String(projectId))
    || false
  const closeProjectPicker = () => {
    setProjectPickerOpen(false)
    setSelectedProjectId(null)
    setProjectPickerPhotos([])
  }
  const openProjectPicker = (photos: Photo[]) => {
    if (!photos.length) return
    if (photos.some(photo => photo.status !== 'AVAILABLE')) {
      message.warning('项目相册仅接收可用图片，请取消选择已归档图片后重试')
      return
    }
    setProjectPickerPhotos(photos)
    setProjectFilters({ page: 1, keyword: '' })
    setSelectedProjectId(null)
    setProjectPickerOpen(true)
  }
  const addPhotosToProject = async () => {
    if (!projectPickerPhotos.length || !selectedProjectId) {
      message.warning('请选择一个选题项目')
      return
    }
    const project = activeProjects.items.find(item => String(item.id) === String(selectedProjectId))
    if (!project) {
      message.error('所选项目已不在当前列表中，请重新选择')
      setSelectedProjectId(null)
      return
    }
    const photos = projectPickerPhotos
    const photoIds = photos.map(photo => photo.id)
    setProjectSaving(true)
    try {
      await api({
        method: 'POST',
        url: `/projects/${project.id}/photos`,
        data: { photoIds },
      })
      setSelectedPhotos([])
      closeProjectPicker()
      message.success(`已将 ${photoIds.length} 张图片添加到选题项目“${project.title}”`)
      await reload()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setProjectSaving(false)
    }
  }
  return <>
    <PageTitle eyebrow="LIBRARY" title="图片库" description="检索、查看并下载团队沉淀的每一帧。"
      extra={<Space wrap>
        {canAddToProject && <Button size="large" icon={<FolderAddOutlined />} disabled={!selectedIds.length}
          onClick={() => openProjectPicker(selectedPhotos)}>
          添加到项目{selectedIds.length ? `（${selectedIds.length}）` : ''}
        </Button>}
        {canDownload && <Button size="large" icon={<DownloadOutlined />} loading={batchDownloading}
          disabled={!selectedIds.length} onClick={() => void batchDownload()}>
          打包下载{selectedIds.length ? `（${selectedIds.length}）` : ''}
        </Button>}
        {canDelete && <Button danger size="large" icon={<DeleteOutlined />} disabled={!selectedIds.length}
          onClick={batchRemove}>批量删除{selectedIds.length ? `（${selectedIds.length}）` : ''}</Button>}
        {!!selectedIds.length && <Button size="large" onClick={() => setSelectedPhotos([])}>清空选择</Button>}
        {canUpload && <Button size="large" icon={<InboxOutlined />} onClick={() => navigate('/photos/batch-upload')}>ZIP 批量上传</Button>}
        {canUpload && <Button type="primary" size="large" icon={<CloudUploadOutlined />} onClick={() => setUploadOpen(true)}>上传图片</Button>}
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
          <Card className={`photo-card${selectedIds.includes(photo.id) ? ' photo-card-selected' : ''}`} hoverable cover={<div
            className="photo-cover" role="link" tabIndex={0}
            aria-label={`查看图片详情：${photo.title || photo.id}`}
            onClick={() => navigate(`/photos/${photo.id}`)}
            onKeyDown={event => {
              if (event.key === 'Enter') navigate(`/photos/${photo.id}`)
            }}>
            {photo.thumbnailUrl ? <Image preview={false} src={photo.thumbnailUrl} alt={photo.title} /> : <div className="image-placeholder"><span>{photo.title?.slice(0, 1) || '图'}</span></div>}
            <div className="photo-overlay">
              {(canAddToProject || canDownload || canDelete) && (photo.status === 'AVAILABLE' || photo.status === 'ARCHIVED') && <Checkbox
                className="photo-select-checkbox"
                checked={selectedIds.includes(photo.id)}
                disabled={selectedIds.length >= 200 && !selectedIds.includes(photo.id)}
                onClick={event => event.stopPropagation()}
                onChange={event => toggleSelected(photo, event.target.checked)}
                aria-label={`选择图片 ${photo.title || photo.id}`} />}
              {canDownload && <Button className="photo-download-button" shape="circle" icon={<DownloadOutlined />}
                aria-label={`下载图片 ${photo.title || photo.id}`}
                onClick={e => { e.stopPropagation(); void download(photo) }} />}
            </div>
            <div className="photo-badges"><Space size={4}><StatusTag value={photo.status} />
              {!!photo.adoptionCount && <Tag color="gold">已采用 × {photo.adoptionCount}</Tag>}
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
            uploadForm.setFieldValue('title', photoTitleFromFileName(file.name))
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
    <Modal title={projectPickerPhotos.length > 1 ? '批量添加图片到选题项目' : '添加图片到选题项目'}
      width={760} open={projectPickerOpen}
      onCancel={() => { if (!projectSaving) closeProjectPicker() }}
      onOk={() => void addPhotosToProject()}
      okText="添加到所选项目" okButtonProps={{ disabled: !selectedProjectId }}
      confirmLoading={projectSaving} maskClosable={!projectSaving} closable={!projectSaving}
      cancelButtonProps={{ disabled: projectSaving }} destroyOnHidden>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          将所选 {projectPickerPhotos.length} 张图片加入一个进行中的选题项目。添加后图片只会进入项目相册，
          不会自动标记为被引。
        </Typography.Paragraph>
        <Input.Search allowClear placeholder="搜索项目名称或说明" style={{ maxWidth: 420 }}
          onSearch={keyword => {
            setSelectedProjectId(null)
            setProjectFilters({ page: 1, keyword })
          }} />
        {projectsError && <Typography.Text type="danger">项目加载失败：{projectsError}</Typography.Text>}
        <Table<Project> rowKey="id" size="small" loading={projectsLoading}
          dataSource={activeProjects.items}
          rowSelection={{
            type: 'radio',
            selectedRowKeys: selectedProjectId ? [selectedProjectId] : [],
            onChange: keys => setSelectedProjectId(keys.length ? String(keys[0]) : null),
            getCheckboxProps: project => ({
              disabled: !!projectPickerPhotos.length
                && projectPickerPhotos.every(photo => isLinkedToProject(photo, project.id)),
              name: project.title,
            }),
          }}
          pagination={{
            current: activeProjects.page,
            pageSize: activeProjects.pageSize,
            total: activeProjects.total,
            showSizeChanger: false,
            onChange: page => {
              setSelectedProjectId(null)
              setProjectFilters(current => ({ ...current, page }))
            },
          }}
          locale={{ emptyText: projectsError ? '暂时无法加载项目' : '没有匹配的进行中项目' }}
          columns={[
            { title: '选题项目', dataIndex: 'title', render: (title, project) =>
              <div className="table-title"><strong>{title}</strong><span>项目 #{project.id}</span></div> },
            { title: '关联状态', width: 130, render: (_, project) => {
              const linkedCount = projectPickerPhotos.filter(photo => isLinkedToProject(photo, project.id)).length
              if (!linkedCount) return <Typography.Text type="secondary">可添加</Typography.Text>
              if (linkedCount === projectPickerPhotos.length) return <Tag color="blue">已全部关联</Tag>
              return <Tag color="orange">已关联 {linkedCount}/{projectPickerPhotos.length}</Tag>
            } },
          ]} />
      </Space>
    </Modal>
  </>
}
