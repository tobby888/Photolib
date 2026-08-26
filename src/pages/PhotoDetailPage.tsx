import {
  Alert, App, Button, Card, Col, Descriptions, Image, Input, Modal, Row, Skeleton, Space, Tag, Typography,
} from 'antd'
import {
  ArrowLeftOutlined, DeleteOutlined, DownloadOutlined, FolderAddOutlined, StarFilled, StarOutlined,
} from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { api, emptyPage, qs } from '../api'
import { DataState, formatBytes, PageTitle, StatusTag } from '../components'
import { ContentFitTable } from '../ContentFitTable'
import { useAuth } from '../auth'
import { useLoad } from '../hooks'
import { hasPermission } from '../permissions'
import PhotoHistogram from '../PhotoHistogram'
import { useLocalImageUrl } from '../useLocalImageUrl'
import type { EntityId, PageData, Photo, Project } from '../types'
import { withPhotoLibrarySearch } from '../photoLibrarySearch'

function isLinkedToProject(photo: Photo, projectId: EntityId) {
  return photo.relatedProjects?.some(project => String(project.id) === String(projectId))
    || photo.relatedProjectIds?.some(id => String(id) === String(projectId))
    || false
}

function withProjectLink(photo: Photo, project: Project): Photo {
  if (isLinkedToProject(photo, project.id)) return photo
  const onlyHasLegacyProjectIds = !photo.relatedProjects?.length && !!photo.relatedProjectIds?.length
  return {
    ...photo,
    relatedProjectIds: [...new Set([...(photo.relatedProjectIds || []), project.id])],
    relatedProjects: onlyHasLegacyProjectIds
      ? photo.relatedProjects
      : [...(photo.relatedProjects || []), { id: project.id, title: project.title }],
  }
}

export default function PhotoDetailPage({ favoritesOnly = false }: { favoritesOnly?: boolean }) {
  const { photoId } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const libraryRoot = favoritesOnly ? '/favorites' : '/photos'
  const { message, modal } = App.useApp()
  const { user } = useAuth()
  const canAddToProject = hasPermission(user, 'PROJECT_ADOPT')
  const canDelete = hasPermission(user, 'PHOTO_DELETE')
  const canDownload = hasPermission(user, 'PHOTO_DOWNLOAD')
  const [favoriteSaving, setFavoriteSaving] = useState(false)
  const [projectPickerOpen, setProjectPickerOpen] = useState(false)
  const [projectSaving, setProjectSaving] = useState(false)
  const [selectedProjectId, setSelectedProjectId] = useState<EntityId | null>(null)
  const [projectFilters, setProjectFilters] = useState({ page: 1, keyword: '' })
  const { data: photo, setData: setPhoto, loading, error, reload } = useLoad(
    () => photoId
      ? api<Photo>({ url: `/photos/${photoId}` })
      : Promise.reject(new Error('图片 ID 无效')),
    null as Photo | null,
    [photoId],
  )
  const localImage = useLocalImageUrl(photo?.thumbnailUrl)
  useEffect(() => {
    const onPreviewRegenerated = () => void reload()
    window.addEventListener('preview-generation-succeeded', onPreviewRegenerated)
    return () => window.removeEventListener('preview-generation-succeeded', onPreviewRegenerated)
  }, [reload])
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

  const download = async () => {
    if (!photo) return
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

  const toggleFavorite = async () => {
    if (!photo || favoriteSaving) return
    const nextFavorited = !photo.favorited
    setFavoriteSaving(true)
    setPhoto(current => current ? { ...current, favorited: nextFavorited } : current)
    try {
      await api<void>({
        method: nextFavorited ? 'PUT' : 'DELETE',
        url: `/photos/${photo.id}/favorite`,
      })
      message.success(nextFavorited ? '已收藏图片' : '已取消收藏')
    } catch (reason) {
      setPhoto(current => current?.id === photo.id ? { ...current, favorited: photo.favorited } : current)
      message.error(`${nextFavorited ? '收藏' : '取消收藏'}失败：${(reason as Error).message}`)
    } finally {
      setFavoriteSaving(false)
    }
  }

  const remove = () => {
    if (!photo) return
    modal.confirm({
      title: '确认删除图片？',
      content: '图片记录及 OSS 中的成品图、缩略图和保留原图都将被永久删除，此操作无法撤销。',
      okText: '确认删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      async onOk() {
        await api({ method: 'DELETE', url: `/photos/${photo.id}` })
        message.success('图片已删除')
        navigate(withPhotoLibrarySearch(libraryRoot, location.search), { replace: true })
      },
    })
  }

  const openProjectPicker = () => {
    setProjectFilters({ page: 1, keyword: '' })
    setSelectedProjectId(null)
    setProjectPickerOpen(true)
  }

  const closeProjectPicker = () => {
    setProjectPickerOpen(false)
    setSelectedProjectId(null)
  }

  const addToProject = async () => {
    if (!photo || !selectedProjectId) {
      message.warning('请选择一个选题项目')
      return
    }
    const project = activeProjects.items.find(item => String(item.id) === String(selectedProjectId))
    if (!project) {
      message.error('所选项目已不在当前列表中，请重新选择')
      setSelectedProjectId(null)
      return
    }

    setProjectSaving(true)
    try {
      await api({
        method: 'POST',
        url: `/projects/${project.id}/photos`,
        data: { photoIds: [photo.id] },
      })
      setPhoto(current => current ? withProjectLink(current, project) : current)
      closeProjectPicker()
      message.success(`已将图片添加到选题项目“${project.title}”`)
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setProjectSaving(false)
    }
  }

  return <>
    <PageTitle eyebrow="PHOTO DETAIL" title={photo?.title || '图片详情'}
      description={photo ? `图片 #${photo.id} · ${photo.photographerName}` : '正在读取图片信息…'}
      extra={<>
        <Button icon={<ArrowLeftOutlined />}
          onClick={() => navigate(withPhotoLibrarySearch(libraryRoot, location.search))}>
          {favoritesOnly ? '返回收藏图片' : '返回图片库'}
        </Button>
        {photo && <Button icon={photo.favorited ? <StarFilled /> : <StarOutlined />}
          loading={favoriteSaving} aria-pressed={photo.favorited}
          aria-label={photo.favorited ? '取消收藏当前图片' : '收藏当前图片'}
          title={photo.favorited ? '取消收藏当前图片' : '收藏当前图片'}
          onClick={event => { event.stopPropagation(); void toggleFavorite() }}>
          {photo.favorited ? '取消收藏' : '收藏图片'}
        </Button>}
        {photo && canAddToProject && <Button icon={<FolderAddOutlined />}
          disabled={photo.status !== 'AVAILABLE'}
          title={photo.status === 'AVAILABLE' ? undefined : '仅可用图片可以添加到项目'}
          onClick={openProjectPicker}>添加到项目</Button>}
        {photo && canDelete && <Button danger icon={<DeleteOutlined />} onClick={remove}>删除图片</Button>}
        {photo && canDownload && <Button type="primary" icon={<DownloadOutlined />}
          onClick={() => void download()}>下载原图</Button>}
      </>} />

    <DataState loading={loading} error={error} empty={!photo} onRetry={reload}>
      {photo && <Row gutter={[24, 24]} className="photo-detail-layout">
        <Col xs={24} xl={16}>
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <Card className="photo-detail-preview-card">
              <div className="photo-detail-image">
                {localImage.status === 'ready'
                  ? <Image src={localImage.url} alt={photo.title || `图片 ${photo.id}`} />
                  : localImage.status === 'loading'
                    ? <Skeleton.Image active />
                    : <div className="image-placeholder"><span>{photo.title?.slice(0, 1) || '图'}</span></div>}
              </div>
            </Card>
            <Card title="曝光直方图" extra={<Typography.Text type="secondary">浏览器本地生成</Typography.Text>}>
              {localImage.status === 'ready'
                ? <PhotoHistogram imageUrl={localImage.url} alt={photo.title || `图片 ${photo.id}`} />
                : localImage.status === 'loading'
                  ? <Skeleton active paragraph={{ rows: 3 }} title={false} />
                  : localImage.status === 'error'
                    ? <Alert type="info" showIcon message={`${localImage.message}，无法生成曝光直方图。`} />
                    : <Alert type="info" showIcon message="预览图生成后即可分析曝光分布。" />}
            </Card>
          </Space>
        </Col>
        <Col xs={24} xl={8}>
          <Card className="photo-detail-info-card" title="图片信息">
            <Typography.Paragraph className="photo-detail-description" type="secondary">
              {photo.description || '暂无图片说明'}
            </Typography.Paragraph>
            {!!photo.tags?.length && <Space className="photo-detail-tags" size={[4, 6]} wrap>
              {photo.tags.map(tag => <Tag variant="filled" key={tag}>{tag}</Tag>)}
            </Space>}
            <Descriptions column={1} size="small" items={[
              { key: 'status', label: '状态', children: <StatusTag value={photo.status} /> },
              { key: 'adoption', label: '采纳状态', children: photo.adoptionCount
                ? <Tag color="gold">已采纳 × {photo.adoptionCount}</Tag> : '未采纳' },
              { key: 'projects', label: '关联项目', children: photo.relatedProjects?.length
                ? <Space size={4} wrap>{photo.relatedProjects.map(project =>
                    <Tag key={project.id} color="blue">{project.title}</Tag>)}</Space>
                : photo.relatedProjectIds?.length
                  ? <Space size={4} wrap>{photo.relatedProjectIds.map(projectId =>
                      <Tag key={projectId} color="blue">项目 #{projectId}</Tag>)}</Space>
                  : '无关联项目' },
              { key: 'photographer', label: '拍摄者', children: `${photo.photographerName} · ${photo.photographerStudentId}` },
              { key: 'taken', label: '拍摄时间', children: dayjs(photo.takenAt).format('YYYY-MM-DD HH:mm') },
              { key: 'size', label: '文件信息', children: `${photo.width || '-'} × ${photo.height || '-'} · ${formatBytes(photo.size)}` },
              { key: 'previewSize', label: '预览图体积', children: photo.thumbnailSize == null ? '-' : formatBytes(photo.thumbnailSize) },
              { key: 'file', label: '归档文件名', children: photo.storedFileName },
            ]} />
          </Card>
        </Col>
      </Row>}
    </DataState>

    <Modal title="添加图片到选题项目" width={760} open={projectPickerOpen}
      onCancel={() => { if (!projectSaving) closeProjectPicker() }}
      onOk={() => void addToProject()}
      okText="添加到所选项目" okButtonProps={{ disabled: !selectedProjectId }}
      confirmLoading={projectSaving} maskClosable={!projectSaving} closable={!projectSaving}
      cancelButtonProps={{ disabled: projectSaving }} destroyOnHidden>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          添加后图片只会进入项目相册，不会自动标记为采纳。
        </Typography.Paragraph>
        <Input.Search allowClear placeholder="搜索项目名称或说明" style={{ maxWidth: 420 }}
          onSearch={keyword => {
            setSelectedProjectId(null)
            setProjectFilters({ page: 1, keyword })
          }} />
        {projectsError && <Typography.Text type="danger">项目加载失败：{projectsError}</Typography.Text>}
        <ContentFitTable<Project> rowKey="id" size="small" loading={projectsLoading}
          dataSource={activeProjects.items}
          rowSelection={{
            type: 'radio',
            selectedRowKeys: selectedProjectId ? [selectedProjectId] : [],
            onChange: keys => setSelectedProjectId(keys.length ? String(keys[0]) : null),
            getCheckboxProps: project => ({
              disabled: !!photo && isLinkedToProject(photo, project.id),
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
            { title: '关联状态', width: 130, render: (_, project) =>
              photo && isLinkedToProject(photo, project.id)
                ? <Tag color="blue">已关联</Tag>
                : <Typography.Text type="secondary">可添加</Typography.Text> },
          ]} />
      </Space>
    </Modal>
  </>
}
