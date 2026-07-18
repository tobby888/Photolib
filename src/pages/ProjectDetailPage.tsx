import {
  App, Breadcrumb, Button, Card, Checkbox, Col, DatePicker, Form, Image, Input,
  Modal, Radio, Row, Select, Space, Statistic, Table, Tag, Typography,
} from 'antd'
import {
  ArrowLeftOutlined, CameraOutlined, CheckCircleOutlined, DownloadOutlined, EditOutlined, FileImageOutlined,
  LinkOutlined, PlusOutlined, RocketOutlined, StopOutlined, UnorderedListOutlined,
} from '@ant-design/icons'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { useAuth } from '../auth'
import { api, emptyPage } from '../api'
import type { Adoption, BatchPublishResult, Campus, PageData, Photo, PhotoRequest, Project } from '../types'
import { DataState, StatusTag } from '../components'
import { useLoad } from '../hooks'
import MarkdownEditor from '../MarkdownEditor'
import MarkdownRenderer, { markdownExcerpt } from '../MarkdownRenderer'
import { preparePhotoBatchDownload } from '../photoBatchDownload'

const projectStateCopy = {
  DRAFT: {
    title: '项目仍在准备中',
    description: '请先补充选题说明和执行边界。启动后，才可以发布图片需求。',
  },
  ACTIVE: {
    title: '项目正在执行',
    description: '可以继续创建和发布图片需求，收集素材并记录图片采纳。',
  },
  COMPLETED: {
    title: '项目已经完成',
    description: '需求与采纳记录已锁定。如需补充素材，管理员可以重新开放项目。',
  },
  CANCELLED: {
    title: '项目已经取消',
    description: '项目不再接受新需求，已有记录仍会保留用于追溯。',
  },
}

export default function ProjectDetailPage() {
  const { projectId = '' } = useParams()
  const navigate = useNavigate()
  const { user } = useAuth()
  const { message, modal } = App.useApp()
  const [requestForm] = Form.useForm()
  const publishMode = Form.useWatch('publishMode', requestForm) || 'publish'
  const [editForm] = Form.useForm()
  const [requestOpen, setRequestOpen] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [galleryOpen, setGalleryOpen] = useState(false)
  const [galleryKeyword, setGalleryKeyword] = useState('')
  const [selectedPhotoIds, setSelectedPhotoIds] = useState<string[]>([])
  const [selectedDownloadPhotoIds, setSelectedDownloadPhotoIds] = useState<string[]>([])
  const [batchDownloading, setBatchDownloading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [markingPhotoId, setMarkingPhotoId] = useState<string | null>(null)
  const { data, setData, loading, error, reload } = useLoad(async () => {
    const [project, firstRequests, campuses, firstPhotos, firstAdoptions] = await Promise.all([
      api<Project>({ url: `/projects/${projectId}` }),
      api<PageData<PhotoRequest>>({ url: '/requests', params: { page: 1, pageSize: 100, projectId } }),
      api<Campus[]>({ url: '/campuses', params: { enabled: true } }),
      api<PageData<Photo>>({ url: '/photos', params: { page: 1, pageSize: 100, projectId, includeAllStatuses: true } }),
      user?.role === 'CAMPUS_MANAGER'
        ? Promise.resolve(emptyPage<Adoption>())
        : api<PageData<Adoption>>({ url: `/projects/${projectId}/adoptions`, params: { page: 1, pageSize: 100 } }),
    ])
    const requestPages = await Promise.all(Array.from(
      { length: Math.max(0, firstRequests.totalPages - 1) },
      (_, index) => api<PageData<PhotoRequest>>({
        url: '/requests',
        params: { page: index + 2, pageSize: 100, projectId },
      }),
    ))
    const photoPages = await Promise.all(Array.from(
      { length: Math.max(0, firstPhotos.totalPages - 1) },
      (_, index) => api<PageData<Photo>>({
        url: '/photos',
        params: { page: index + 2, pageSize: 100, projectId, includeAllStatuses: true },
      }),
    ))
    const adoptionPages = await Promise.all(Array.from(
      { length: Math.max(0, firstAdoptions.totalPages - 1) },
      (_, index) => api<PageData<Adoption>>({
        url: `/projects/${projectId}/adoptions`,
        params: { page: index + 2, pageSize: 100 },
      }),
    ))
    return {
      project,
      requests: [firstRequests, ...requestPages].flatMap(page => page.items),
      campuses,
      photos: [firstPhotos, ...photoPages].flatMap(page => page.items),
      adoptions: [firstAdoptions, ...adoptionPages].flatMap(page => page.items),
    }
  }, {
    project: null as Project | null,
    requests: [] as PhotoRequest[],
    campuses: [] as Campus[],
    photos: [] as Photo[],
    adoptions: [] as Adoption[],
  }, [projectId, user?.role])
  const { data: galleryPhotos, loading: galleryLoading } = useLoad(
    () => api<PageData<Photo>>({
      url: '/photos',
      params: { page: 1, pageSize: 100, status: 'AVAILABLE', keyword: galleryKeyword || undefined },
    }),
    emptyPage<Photo>(),
    [galleryKeyword],
  )

  const toggleAdoption = async (photo: Photo) => {
    const adoption = data.adoptions.find(item => item.photoId === photo.id)
    setMarkingPhotoId(photo.id)
    try {
      if (adoption) {
        await api({ method: 'DELETE', url: `/projects/${projectId}/adoptions/${adoption.id}` })
        setData(current => ({
          ...current,
          project: current.project && {
            ...current.project,
            adoptionCount: Math.max(0, (current.project.adoptionCount || 0) - 1),
          },
          photos: current.photos.map(item => item.id === photo.id
            ? { ...item, adoptionCount: Math.max(0, (item.adoptionCount || 0) - 1) }
            : item),
          adoptions: current.adoptions.filter(item => item.id !== adoption.id),
        }))
        message.success('已取消被引标注')
      } else {
        const created = await api<Adoption[]>({
          method: 'POST',
          url: `/projects/${projectId}/adoptions`,
          data: { photoIds: [photo.id], remark: null },
        })
        setData(current => ({
          ...current,
          project: current.project && {
            ...current.project,
            adoptionCount: (current.project.adoptionCount || 0) + created.length,
          },
          photos: current.photos.map(item => item.id === photo.id
            ? { ...item, adoptionCount: (item.adoptionCount || 0) + created.length }
            : item),
          adoptions: [
            ...current.adoptions.filter(item => !created.some(value => value.id === item.id)),
            ...created,
          ],
        }))
        message.success('已标注为被引图片')
      }
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setMarkingPhotoId(null)
    }
  }

  const changeStatus = async (status: Project['status']) => {
    if (!data.project) return
    try {
      await api({ method: 'POST', url: `/projects/${projectId}/status`,
        data: { status, version: data.project.version } })
      message.success(status === 'ACTIVE' ? '项目已启动' : status === 'COMPLETED' ? '项目已完成' : '项目已取消')
      await reload()
    } catch (reason) { message.error((reason as Error).message) }
  }

  const reopen = () => {
    if (!data.project) return
    let reason = ''
    modal.confirm({
      title: '重新开放项目',
      content: <Input.TextArea rows={3} placeholder="请填写重新开放的原因" onChange={event => { reason = event.target.value }} />,
      okText: '确认重新开放',
      onOk: async () => {
        if (!reason.trim()) throw new Error('请填写重新开放原因')
        await api({ method: 'POST', url: `/projects/${projectId}/reopen`,
          data: { reason: reason.trim(), version: data.project!.version } })
        message.success('项目已重新开放')
        await reload()
      },
    })
  }

  const createRequest = async () => {
    const values = await requestForm.validateFields()
    if (values.publishMode === 'draft' && values.campusIds.length !== 1) {
      message.warning('保存草稿时只能选择一个校区')
      return
    }
    setSaving(true)
    try {
      const { deadline, title, description, campusIds } = values
      if (values.publishMode === 'draft') {
        await api({
          method: 'POST', url: `/projects/${projectId}/requests`,
          data: { title, description, campusId: campusIds[0], deadline: deadline.format('YYYY-MM-DDTHH:mm:ss') },
        })
        message.success('图片需求草稿已创建')
        setRequestOpen(false)
        requestForm.resetFields()
        await reload()
        return
      }
      const results = await api<BatchPublishResult[]>({
        method: 'POST', url: `/projects/${projectId}/requests/batch-publish`,
        data: { title, description, campusIds, deadline: deadline.format('YYYY-MM-DDTHH:mm:ss') },
      })
      const succeeded = results.filter(item => item.success)
      const failed = results.filter(item => !item.success)
      if (failed.length) {
        requestForm.setFieldValue('campusIds', failed.map(item => item.campusId))
        const details = failed.map(item => {
          const campus = data.campuses.find(value => value.id === item.campusId)
          return `${campus?.name || `校区 #${item.campusId}`}：${item.message || '发布失败'}`
        }).join('；')
        message.warning({ content: `已成功发布 ${succeeded.length} 个，失败 ${failed.length} 个。${details}`, duration: 8 })
      } else {
        message.success(`已向 ${succeeded.length} 个校区分别发布需求`)
        setRequestOpen(false)
        requestForm.resetFields()
      }
      await reload()
    } catch (reason) { message.error((reason as Error).message) } finally { setSaving(false) }
  }

  const updateProject = async () => {
    if (!data.project) return
    const values = await editForm.validateFields()
    setSaving(true)
    try {
      await api({ method: 'PUT', url: `/projects/${projectId}`,
        data: { ...values, version: data.project.version } })
      message.success('项目信息已更新')
      setEditOpen(false)
      await reload()
    } catch (reason) { message.error((reason as Error).message) } finally { setSaving(false) }
  }

  const addFromGallery = async () => {
    if (!selectedPhotoIds.length) {
      message.warning('请至少选择一张图库图片')
      return
    }
    setSaving(true)
    try {
      await api({
        method: 'POST',
        url: `/projects/${projectId}/photos`,
        data: { photoIds: selectedPhotoIds },
      })
      message.success(`已向项目相册添加 ${selectedPhotoIds.length} 张图片，请按需标注被引`)
      setGalleryOpen(false)
      setSelectedPhotoIds([])
      await reload()
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const toggleDownloadPhoto = (photoId: string, checked: boolean) => {
    setSelectedDownloadPhotoIds(current => checked
      ? current.includes(photoId) ? current : [...current, photoId].slice(0, 200)
      : current.filter(id => id !== photoId))
  }

  const downloadPhoto = async (photo: Photo) => {
    try {
      const result = await api<{ downloadUrl: string }>({
        method: 'POST',
        url: `/photos/${photo.id}/download-url`,
      })
      window.open(result.downloadUrl, '_blank', 'noopener')
    } catch (reason) {
      message.error((reason as Error).message)
    }
  }

  const selectAllDownloadablePhotos = () => {
    const downloadableIds = data.photos
      .filter(photo => photo.status === 'AVAILABLE' || photo.status === 'ARCHIVED')
      .map(photo => photo.id)
    setSelectedDownloadPhotoIds(downloadableIds.slice(0, 200))
    if (downloadableIds.length > 200) {
      message.info('单次最多下载 200 张，已选择前 200 张可下载图片')
    }
  }

  const batchDownload = async () => {
    if (!selectedDownloadPhotoIds.length) return
    setBatchDownloading(true)
    try {
      const downloadUrl = await preparePhotoBatchDownload(selectedDownloadPhotoIds)
      if (downloadUrl) {
        window.location.assign(downloadUrl)
        setSelectedDownloadPhotoIds([])
        message.success('所选项目图片已打包为 ZIP')
      } else {
        message.info('ZIP 仍在后台生成，请稍后重新发起下载')
      }
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setBatchDownloading(false)
    }
  }

  const project = data.project
  const canManage = user?.role !== 'CAMPUS_MANAGER'
  const canBatchDownload = user?.role === 'ADMIN' || user?.role === 'MINISTER'
  const downloadablePhotoCount = data.photos.filter(
    photo => photo.status === 'AVAILABLE' || photo.status === 'ARCHIVED',
  ).length
  const addableGalleryPhotos = galleryPhotos.items.filter(photo =>
    !photo.relatedProjectIds?.some(id => String(id) === projectId))
  return <DataState loading={loading} error={error} empty={!project} onRetry={reload}>
    {project && <>
      <Breadcrumb className="detail-breadcrumb" items={[
        { title: <a onClick={() => navigate('/projects')}>选题项目</a> },
        { title: project.title },
      ]} />
      <section className="project-detail-hero">
        <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/projects')}>返回项目列表</Button>
        <div className="project-detail-heading">
          <div>
            <Space><Tag variant="filled">PROJECT {project.id}</Tag><StatusTag value={project.status} /></Space>
            <Typography.Title>{project.title}</Typography.Title>
            {project.description
              ? <MarkdownRenderer value={project.description} />
              : <Typography.Paragraph>尚未添加项目说明。清晰的说明能帮助负责人准确理解拍摄目标。</Typography.Paragraph>}
          </div>
          <Space wrap>
            {project.status === 'ACTIVE' &&
              <Button icon={<FileImageOutlined />} onClick={() => setGalleryOpen(true)}>从图库添加图片</Button>}
            {canManage && <>
            {project.status !== 'COMPLETED' && project.status !== 'CANCELLED' &&
              <Button icon={<EditOutlined />} onClick={() => {
                editForm.setFieldsValue({ title: project.title, description: project.description })
                setEditOpen(true)
              }}>编辑项目</Button>}
            {(project.status === 'DRAFT' || project.status === 'ACTIVE') &&
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setRequestOpen(true)}>新建图片需求</Button>}
            </>}
          </Space>
        </div>
      </section>

      <Row gutter={[16, 16]} className="project-summary">
        <Col xs={12} lg={6}><Card><Statistic title="图片需求" value={project.requestCount || 0} prefix={<UnorderedListOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="项目图片" value={project.photoCount || 0} prefix={<FileImageOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="已采纳" value={project.adoptionCount || 0} prefix={<CheckCircleOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="当前版本" value={project.version} prefix={<CameraOutlined />} /></Card></Col>
      </Row>

      <Card className={`state-guide state-${project.status.toLowerCase()}`}>
        <div>
          <Typography.Text className="eyebrow">STATUS GUIDE</Typography.Text>
          <Typography.Title level={4}>{projectStateCopy[project.status].title}</Typography.Title>
          <Typography.Paragraph>{projectStateCopy[project.status].description}</Typography.Paragraph>
        </div>
        {canManage && <Space wrap>
          {project.status === 'DRAFT' && <Button type="primary" icon={<RocketOutlined />} onClick={() => void changeStatus('ACTIVE')}>启动项目</Button>}
          {project.status === 'ACTIVE' && <Button type="primary" icon={<CheckCircleOutlined />} onClick={() => void changeStatus('COMPLETED')}>标记为已完成</Button>}
          {['DRAFT', 'ACTIVE'].includes(project.status) && <Button danger icon={<StopOutlined />}
            onClick={() => modal.confirm({ title: '确认取消这个项目？', content: '取消后不能再创建需求，已有记录会继续保留。',
              okText: '确认取消', okButtonProps: { danger: true }, onOk: () => changeStatus('CANCELLED') })}>取消项目</Button>}
          {project.status === 'COMPLETED' && user?.role === 'ADMIN' && <Button type="primary" onClick={reopen}>重新开放</Button>}
        </Space>}
      </Card>

      <Card title="项目图片需求" extra={canManage && ['DRAFT', 'ACTIVE'].includes(project.status) &&
        <Button type="link" icon={<PlusOutlined />} onClick={() => setRequestOpen(true)}>新建需求</Button>}>
        <Table rowKey="id" dataSource={data.requests} pagination={false} locale={{ emptyText: '这个项目还没有图片需求' }}
          columns={[
            { title: '需求', dataIndex: 'title', render: (value, item) => <div className="table-title"><strong>{value}</strong><span>{markdownExcerpt(item.description) || '暂无拍摄说明'}</span></div> },
            { title: '校区', dataIndex: 'campusId', render: value => data.campuses.find(c => c.id === value)?.name || `校区 #${value}` },
            { title: '截止时间', dataIndex: 'deadline', render: value => dayjs(value).format('YYYY-MM-DD HH:mm') },
            { title: '状态', dataIndex: 'status', render: value => <StatusTag value={value} /> },
            { title: '操作', render: (_, item) => <Button type="link" onClick={() => navigate(`/requests?projectId=${projectId}&requestId=${item.id}`)}>查看需求</Button> },
          ]} />
      </Card>

      <Card
        className="project-photo-gallery"
        title={`需求图片（${data.photos.length}）`}
        extra={<Space wrap>
          <Typography.Text type="secondary">汇总展示本选题下所有需求已上传的图片</Typography.Text>
          {canBatchDownload && <>
            <Button type="link" disabled={!downloadablePhotoCount || batchDownloading}
              onClick={selectAllDownloadablePhotos}>全选可下载</Button>
            {!!selectedDownloadPhotoIds.length && <Button type="link" disabled={batchDownloading}
              onClick={() => setSelectedDownloadPhotoIds([])}>清空选择</Button>}
            <Button type="primary" icon={<DownloadOutlined />} loading={batchDownloading}
              disabled={!selectedDownloadPhotoIds.length} onClick={() => void batchDownload()}>
              打包下载{selectedDownloadPhotoIds.length ? `（${selectedDownloadPhotoIds.length}）` : ''}
            </Button>
          </>}
        </Space>}
      >
        {data.photos.length ? <Row gutter={[16, 20]} className="photo-grid">
          {data.photos.map(photo => {
            const adopted = data.adoptions.some(item => item.photoId === photo.id)
            const request = data.requests.find(item => item.id === photo.requestId)
            return <Col xs={24} sm={12} lg={8} xxl={6} key={photo.id}>
              <Card
                className={`photo-card${selectedDownloadPhotoIds.includes(photo.id) ? ' photo-card-selected' : ''}`}
                cover={<div className="photo-cover">
                  {photo.thumbnailUrl
                    ? <Image src={photo.thumbnailUrl} alt={photo.title || '需求图片'} />
                    : <div className="image-placeholder"><span>{photo.title?.slice(0, 1) || '图'}</span></div>}
                  <div className="photo-overlay">
                    {canBatchDownload && (photo.status === 'AVAILABLE' || photo.status === 'ARCHIVED') &&
                      <Checkbox
                        className="photo-select-checkbox"
                        checked={selectedDownloadPhotoIds.includes(photo.id)}
                        disabled={batchDownloading || (selectedDownloadPhotoIds.length >= 200
                          && !selectedDownloadPhotoIds.includes(photo.id))}
                        onChange={event => toggleDownloadPhoto(photo.id, event.target.checked)}
                        aria-label={`选择项目图片 ${photo.title || photo.id}`} />}
                    {canBatchDownload && (photo.status === 'AVAILABLE' || photo.status === 'ARCHIVED') &&
                      <Button className="photo-download-button" shape="circle" icon={<DownloadOutlined />}
                        aria-label={`下载项目图片 ${photo.title || photo.id}`}
                        onClick={() => void downloadPhoto(photo)} />}
                  </div>
                  <div className="photo-badges"><Space size={4}>
                    <StatusTag value={photo.status} />
                    {adopted && <Tag color="gold">已被引</Tag>}
                  </Space></div>
                </div>}
              >
                <Typography.Title level={5} ellipsis>{photo.title || '未命名图片'}</Typography.Title>
                <Typography.Text type="secondary" ellipsis>
                  {request?.title || (photo.requestId ? `需求 #${photo.requestId}` : '未关联需求')}
                </Typography.Text>
                <div className="photo-meta">
                  <span>{photo.photographerName}</span>
                  <span>{dayjs(photo.takenAt).format('YYYY.MM.DD')}</span>
                </div>
                {canManage && <Button
                  block
                  type={adopted ? 'default' : 'primary'}
                  danger={adopted}
                  icon={<LinkOutlined />}
                  loading={markingPhotoId === photo.id}
                  disabled={project.status !== 'ACTIVE' || photo.status !== 'AVAILABLE'}
                  onClick={() => void toggleAdoption(photo)}
                >
                  {adopted ? '取消被引' : '标注图片被引'}
                </Button>}
              </Card>
            </Col>
          })}
        </Row> : <div className="empty-state">这个选题还没有上传图片</div>}
      </Card>

      <Modal title="新建图片需求" width={780} open={requestOpen} onCancel={() => setRequestOpen(false)}
        onOk={createRequest} okText={publishMode === 'publish' ? '创建并发布' : '保存草稿'} confirmLoading={saving}>
        <Typography.Paragraph type="secondary">需求将归属到“{project.title}”。可以保存单校区草稿，或向多个校区立即发布独立需求。</Typography.Paragraph>
        <Form form={requestForm} layout="vertical"
          initialValues={{ publishMode: project.status === 'ACTIVE' ? 'publish' : 'draft' }} requiredMark={false}>
          <Form.Item label="建立方式" name="publishMode">
            <Radio.Group optionType="button" buttonStyle="solid" options={[
              { value: 'publish', label: '立即向多校区发布', disabled: project.status !== 'ACTIVE' },
              { value: 'draft', label: '保存单校区草稿' },
            ]} />
          </Form.Item>
          <Form.Item label="需求标题" name="title" rules={[{ required: true, message: '请输入需求标题' }, { max: 200 }]}>
            <Input placeholder="例如：毕业典礼现场图片" />
          </Form.Item>
          <Form.Item label="拍摄说明" name="description">
            <MarkdownEditor placeholder="使用 Markdown 说明场景、人物、构图和交付标准；可上传说明图片" />
          </Form.Item>
          <Form.Item label={publishMode === 'publish' ? '发布校区' : '草稿校区'} name="campusIds"
            extra={publishMode === 'publish' ? '每个校区独立建立；个别校区失败不会撤销其他已成功需求' : '草稿仍按原流程保存，检查后可单独发布'}
            rules={[{ required: true, message: '请至少选择一个校区' }]}>
            <Select mode="multiple" maxCount={publishMode === 'draft' ? 1 : undefined}
              showSearch optionFilterProp="label" maxTagCount="responsive"
              options={data.campuses.map(c => ({ value: c.id, label: c.name }))}
              placeholder={publishMode === 'publish' ? '可同时选择多个校区' : '选择一个校区'} />
          </Form.Item>
          <Form.Item label="截止时间" name="deadline" rules={[{ required: true, message: '请选择截止时间' }]}>
            <DatePicker showTime format="YYYY-MM-DD HH:mm" disabledDate={date => date.isBefore(dayjs(), 'day')} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title="编辑项目信息" width={760} open={editOpen} onCancel={() => setEditOpen(false)} onOk={updateProject}
        okText="保存修改" confirmLoading={saving}>
        <Form form={editForm} layout="vertical" requiredMark={false}>
          <Form.Item label="项目名称" name="title" rules={[{ required: true, message: '请输入项目名称' }, { max: 200 }]}><Input /></Form.Item>
          <Form.Item label="项目说明" name="description">
            <MarkdownEditor placeholder="使用 Markdown 说明选题方向、内容范围和交付目标；可上传说明图片" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title="从图库添加图片" width={860} open={galleryOpen}
        onCancel={() => setGalleryOpen(false)} onOk={addFromGallery}
        okText={`添加所选图片${selectedPhotoIds.length ? `（${selectedPhotoIds.length}）` : ''}`}
        confirmLoading={saving}>
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Typography.Paragraph type="secondary">
            选择你有权查看的可用图库图片。添加后只会进入项目相册，默认不标记为被引；
            管理员可在项目图片中按需标注。
          </Typography.Paragraph>
          <Input.Search allowClear placeholder="搜索图片标题、描述或标签"
            onSearch={setGalleryKeyword} style={{ maxWidth: 420 }} />
          <Table<Photo> rowKey="id" size="small" loading={galleryLoading}
            dataSource={addableGalleryPhotos} pagination={{ pageSize: 8 }}
            rowSelection={{
              selectedRowKeys: selectedPhotoIds,
              onChange: keys => setSelectedPhotoIds(keys.map(String)),
            }}
            locale={{ emptyText: '没有可添加的图库图片' }}
            columns={[
              { title: '预览', width: 92, render: (_, photo) =>
                photo.thumbnailUrl ? <Image width={68} height={48} style={{ objectFit: 'contain' }}
                  preview={false} src={photo.thumbnailUrl} /> : '-' },
              { title: '图片', dataIndex: 'title', render: (value, photo) =>
                <div className="table-title"><strong>{value || '未命名图片'}</strong>
                  <span>{photo.photographerName} · {dayjs(photo.takenAt).format('YYYY-MM-DD')}</span></div> },
              { title: '标签', dataIndex: 'tags', render: tags =>
                <Space size={4} wrap>{tags?.slice(0, 3).map((tag: string) => <Tag key={tag}>{tag}</Tag>)}</Space> },
            ]} />
        </Space>
      </Modal>
    </>}
  </DataState>
}
