import {
  App, Breadcrumb, Button, Card, Checkbox, Col, DatePicker, Form, Input,
  Modal, Radio, Row, Select, Space, Statistic, Tag, Tooltip, Typography,
} from 'antd'
import {
  ArrowLeftOutlined, CameraOutlined, CheckCircleOutlined, DeleteOutlined, DownloadOutlined, EditOutlined, EyeOutlined,
  FileImageOutlined, LinkOutlined, MinusCircleOutlined, PlusOutlined, RocketOutlined, ScissorOutlined,
  ShareAltOutlined, StopOutlined, TagsOutlined, TeamOutlined, UnorderedListOutlined,
} from '@ant-design/icons'
import { lazy, memo, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { useAuth } from '../auth'
import { api, emptyPage } from '../api'
import type {
  Adoption, BatchPublishResult, Campus, EntityId, PageData, Photo, PhotoRequest, Project, ProjectSelector,
  TaggedPhoto,
} from '../types'
import { DataState, StatusTag, PhotoStatusTag } from '../components'
import { ContentFitTable } from '../ContentFitTable'
import { useLoad, useRefreshOnResume, useStableCallback } from '../hooks'
import MarkdownEditor from '../MarkdownEditor'
import MarkdownRenderer, { markdownExcerpt } from '../MarkdownRenderer'
import RequestAssigneeSelect from '../RequestAssigneeSelect'
import { preparePhotoBatchDownload } from '../photoBatchDownload'
import { hasPermission } from '../permissions'
import { selectPhotoRange } from '../photoSelection'
import { groupPhotoRequests, selectedRequestFor } from '../requestBatch'
import type { RequestBatchRow } from '../requestBatch'
import { RequestBatchCampusCell, RequestBatchStatusCell } from '../RequestBatchCells'
import PreviewPhoto from '../PreviewPhoto'
import { refreshPhotoPreviewUrl } from '../previewRefresh'
import { PhotoPlaceholder, pickPlaceholderImage, usePlaceholderImages } from '../photoPlaceholder'
import { isPortalEvent, selectablePreview, usePhotoCardClick } from '../usePhotoCardClick'
import { matchPhotoCardShortcut, photoCardHint, usePhotoCardShortcuts } from '../photoCardShortcuts'
import BatchTagModal from '../BatchTagModal'
import type { BatchTagMode } from '../BatchTagModal'
import type { SelectionCleanupMode } from '../SelectionCleanupModal'
import TagSelect from '../TagSelect'
import {
  DEPRECATED_TAG_LABEL, collectPhotographers, collectTagOptions, emptyProjectPhotoFilters, filterPhotos,
  hasActiveFilters, normalizeTags, tagRules,
} from '../photoTags'
import type { ProjectPhotoFilters } from '../photoTags'
import ProjectPhotoFilterBar from '../ProjectPhotoFilterBar'
import { PHOTO_LIBRARY_PAGE_SIZE } from '../photoLibrarySearch'
import ListPagination from '../ListPagination'
import { GRID_PAGE_SIZES, clientTablePagination } from '../pagination'

const ProjectShareLinksModal = lazy(() => import('../ProjectShareLinksModal'))
const SelectionCleanupModal = lazy(() => import('../SelectionCleanupModal'))

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

/**
 * 相册分页。以前一次把选题里的全部图片都挂成卡片，400 张时首屏要卡 3 秒多、
 * 每勾一张图卡 1.5 秒。默认张数与图片库（PHOTO_LIBRARY_PAGE_SIZE）一致。
 */
const PHOTO_PAGE_SIZES = GRID_PAGE_SIZES
const DEFAULT_PHOTO_PAGE_SIZE = PHOTO_LIBRARY_PAGE_SIZE

const isDownloadableStatus = (status: Photo['status']) => status === 'AVAILABLE' || status === 'ARCHIVED'

interface ProjectPhotoCardProps {
  photo: Photo
  requestLabel: string
  adopted: boolean
  selected: boolean
  selectable: boolean
  selectDisabled: boolean
  downloadable: boolean
  canAdopt: boolean
  adoptDisabled: boolean
  marking: boolean
  activeTags: string[]
  placeholderImages: string[]
  onToggleSelect: (photoId: string, checked: boolean) => void
  onSelectRange: (photoId: string) => void
  onOpenDetail?: (photo: Photo) => void
  onDownload: (photo: Photo) => void
  onToggleAdoption: (photo: Photo) => void
  onTagClick: (tag: string) => void
}

/**
 * 相册里的一张卡片。用 memo 包起来、且只接收基本类型和引用稳定的回调，
 * 这样勾选一张图只会重渲染这一张，而不是整页几百张。
 *
 * 标题和需求名刻意不用 `Typography` 的 `ellipsis`：它会在每个实例上量一次布局，
 * 几十上百个实例叠在一起就是一连串强制重排；单行省略交给 CSS（`.photo-card-line`）。
 */
const ProjectPhotoCard = memo(function ProjectPhotoCard({
  photo, requestLabel, adopted, selected, selectable, selectDisabled, downloadable, canAdopt, adoptDisabled,
  marking, activeTags, placeholderImages, onToggleSelect, onSelectRange, onOpenDetail,
  onDownload, onToggleAdoption, onTagClick,
}: ProjectPhotoCardProps) {
  const [previewOpen, setPreviewOpen] = useState(false)
  const shortcuts = usePhotoCardShortcuts()
  const selecting = selectable && !selectDisabled
  // 有详情权限去详情页，没有就打开大图预览（原先单击缩略图的行为）。
  const openPhoto = (item: Photo) => {
    if (onOpenDetail) onOpenDetail(item)
    else if (photo.thumbnailUrl) setPreviewOpen(true)
  }
  const { click, doubleClick } = usePhotoCardClick<Photo>(
    item => {
      if (selectable && !selectDisabled) onToggleSelect(item.id, !selected)
    },
    openPhoto,
  )
  const openLabel = onOpenDetail ? '查看详情' : '查看大图'
  return <Card
    className={`photo-card${selected ? ' photo-card-selected' : ''}`}
    cover={<div className="photo-cover"
      role={selecting ? 'button' : undefined}
      tabIndex={selecting ? 0 : undefined}
      aria-pressed={selecting ? selected : undefined}
      aria-label={selecting
        ? `选择项目图片 ${photo.title || photo.id}`
        : undefined}
      title={selecting ? photoCardHint(shortcuts, openLabel) : undefined}
      onClick={event => {
        if (!selecting || isPortalEvent(event)) return
        if (event.shiftKey) {
          event.preventDefault()
          onSelectRange(photo.id)
          return
        }
        click(photo)
      }}
      onDoubleClick={event => {
        if (!selecting || isPortalEvent(event)) return
        event.preventDefault()
        doubleClick(photo)
      }}
      onKeyDown={event => {
        if (event.target !== event.currentTarget || !selecting) return
        // 键盘没有双击：「打开」键打开，「选择」键勾选（Shift 连选），键位由用户设置。
        const action = matchPhotoCardShortcut(event, shortcuts)
        if (!action) return
        event.preventDefault()
        if (action === 'open') openPhoto(photo)
        else if (event.shiftKey) onSelectRange(photo.id)
        else onToggleSelect(photo.id, !selected)
      }}>
      {photo.thumbnailUrl
        ? <PreviewPhoto src={photo.thumbnailUrl} alt={photo.title || '需求图片'} loading="lazy" decoding="async"
            preview={selecting ? selectablePreview(previewOpen, () => setPreviewOpen(false)) : undefined}
            refresh={() => refreshPhotoPreviewUrl(photo.id)}
            fallback={pickPlaceholderImage(placeholderImages, photo.id)} />
        : <PhotoPlaceholder seed={photo.id}>
          <span>{photo.title?.slice(0, 1) || '图'}</span>
        </PhotoPlaceholder>}
      <div className="photo-overlay" onClick={event => event.stopPropagation()}
        onDoubleClick={event => event.stopPropagation()}
        onKeyDown={event => event.stopPropagation()}>
        {selectable &&
          <Checkbox
            className="photo-select-checkbox"
            checked={selected}
            disabled={selectDisabled}
            onChange={event => onToggleSelect(photo.id, event.target.checked)}
            aria-label={`选择项目图片 ${photo.title || photo.id}`} />}
        {/* 触屏上没有可靠的双击，单击又用来选图，打开大图 / 详情得有个看得见的入口。 */}
        {(selecting && (onOpenDetail || photo.thumbnailUrl) || downloadable) && <Space size={8} className="photo-card-actions">
          {selecting && (onOpenDetail || photo.thumbnailUrl) &&
            <Button className="photo-view-button" shape="circle" icon={<EyeOutlined />}
              aria-label={`${openLabel} ${photo.title || photo.id}`} title={openLabel}
              onClick={() => openPhoto(photo)} />}
          {downloadable &&
            <Button className="photo-download-button" shape="circle" icon={<DownloadOutlined />}
              aria-label={`下载项目图片 ${photo.title || photo.id}`}
              onClick={() => onDownload(photo)} />}
        </Space>}
      </div>
      <div className="photo-badges"><Space size={4}>
        <PhotoStatusTag photo={photo} />
        {adopted && <Tag color="gold">已采纳</Tag>}
      </Space></div>
    </div>}
  >
    <Typography.Title level={5} className="photo-card-line" title={photo.title || '未命名图片'}>
      {photo.title || '未命名图片'}</Typography.Title>
    <Typography.Text type="secondary" className="photo-card-line" title={requestLabel}>
      {requestLabel}</Typography.Text>
    <div className="photo-card-tags">
      {photo.tags?.length
        ? photo.tags.map(tag => <Tag key={tag} variant="filled" className="clickable-tag"
            color={activeTags.includes(tag) ? 'blue' : undefined}
            onClick={() => onTagClick(tag)}>
            {tag}</Tag>)
        : <Typography.Text type="secondary">无标签</Typography.Text>}
    </div>
    <div className="photo-meta">
      <span>{photo.photographerName}</span>
      <span>{dayjs(photo.takenAt).format('YYYY.MM.DD')}</span>
    </div>
    {canAdopt && <Button
      block
      type={adopted ? 'default' : 'primary'}
      danger={adopted}
      icon={<LinkOutlined />}
      loading={marking}
      disabled={adoptDisabled}
      onClick={() => onToggleAdoption(photo)}
    >
      {adopted ? '取消采纳' : '标注为采纳'}
    </Button>}
  </Card>
})

/** 需求表格。单独 memo：它和相册勾选毫无关系，却会跟着每次勾选重算每行的摘要。 */
const ProjectRequestTable = memo(function ProjectRequestTable({ requests, campuses, emptyText, onOpen }: {
  requests: PhotoRequest[]
  campuses: Campus[]
  emptyText: string
  onOpen: (request: PhotoRequest) => void
}) {
  const [selectedRequestByBatch, setSelectedRequestByBatch] = useState<Record<string, string>>({})
  const batches = useMemo(() => groupPhotoRequests(requests), [requests])
  const columns = useMemo(() => {
    const campusNames = new Map(campuses.map(campus => [String(campus.id), campus.name]))
    const campusName = (campusId: EntityId) => campusNames.get(String(campusId)) || `校区 #${campusId}`
    const selectedRequest = (batch: RequestBatchRow) =>
      selectedRequestFor(batch, selectedRequestByBatch)
    const selectRequest = (batch: RequestBatchRow, requestId: string) =>
      setSelectedRequestByBatch(current => ({ ...current, [batch.key]: requestId }))
    return [
      { title: '需求', render: (_: unknown, batch: RequestBatchRow) => {
        const item = batch.representative
        const title = item.title || '未命名需求'
        const description = markdownExcerpt(item.description) || '暂无拍摄说明'
        return <div className="table-title">
          <strong className="table-ellipsis-text" style={{ maxWidth: 360 }} title={title}>{title}</strong>
          <span className="table-ellipsis-text" style={{ maxWidth: 360 }} title={description}>{description}</span>
        </div>
      } },
      { title: '校区', render: (_: unknown, batch: RequestBatchRow) =>
        <RequestBatchCampusCell batch={batch} selected={selectedRequest(batch)} campusName={campusName}
          onSelect={requestId => selectRequest(batch, requestId)} /> },
      { title: '截止时间', render: (_: unknown, batch: RequestBatchRow) =>
        dayjs(batch.representative.deadline).format('YYYY-MM-DD HH:mm') },
      { title: '状态', render: (_: unknown, batch: RequestBatchRow) =>
        <RequestBatchStatusCell batch={batch} selected={selectedRequest(batch)} /> },
      { title: '操作', render: (_: unknown, batch: RequestBatchRow) =>
        <Button type="link" onClick={() => onOpen(selectedRequest(batch))}>查看需求</Button> },
    ]
  }, [campuses, onOpen, selectedRequestByBatch])
  return <ContentFitTable<RequestBatchRow> rowKey="key" dataSource={batches} pagination={false}
    locale={{ emptyText }} columns={columns} />
})

export default function ProjectDetailPage() {
  const { projectId = '' } = useParams()
  const navigate = useNavigate()
  const { user } = useAuth()
  // 「能看选题」和「能看需求」是两条权限，选题详情页要能在只有前者时正常打开，
  // 所以这一条必须在取数之前就算出来（其余 can* 常量只服务渲染，声明在下面）。
  const canViewRequests = hasPermission(user, 'REQUEST_VIEW')
  const { message, modal } = App.useApp()
  const placeholderImages = usePlaceholderImages()
  const [requestForm] = Form.useForm()
  const publishMode = Form.useWatch('publishMode', requestForm) || 'publish'
  const requestCampusIds = Form.useWatch('campusIds', requestForm)
  const [editForm] = Form.useForm()
  const [requestOpen, setRequestOpen] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [galleryOpen, setGalleryOpen] = useState(false)
  const [shareOpen, setShareOpen] = useState(false)
  const [galleryKeyword, setGalleryKeyword] = useState('')
  const [selectedPhotoIds, setSelectedPhotoIds] = useState<string[]>([])
  // 项目相册里的勾选：打包下载和批量改标签共用一份选择。
  const [selectedAlbumPhotoIds, setSelectedAlbumPhotoIds] = useState<string[]>([])
  const [albumSelectionAnchor, setAlbumSelectionAnchor] = useState<string | null>(null)
  const [photoFilters, setPhotoFilters] = useState<ProjectPhotoFilters>(emptyProjectPhotoFilters)
  const [photoPage, setPhotoPage] = useState({ current: 1, pageSize: DEFAULT_PHOTO_PAGE_SIZE })
  const galleryRef = useRef<HTMLDivElement>(null)
  const [tagMode, setTagMode] = useState<BatchTagMode | null>(null)
  // 活动选题的选片人维护。候选账号只在弹窗打开时才去取——它是一条全量用户列表，
  // 对只是来看看选题的人没有必要。
  const [selectorsOpen, setSelectorsOpen] = useState(false)
  const [selectorIds, setSelectorIds] = useState<string[]>([])
  // 活动选题结束时的图片清理弹窗：`complete` 是点「标记为已完成」打开的，`cleanup` 是完成之后再清理。
  const [cleanupMode, setCleanupMode] = useState<SelectionCleanupMode | null>(null)
  const [batchDownloading, setBatchDownloading] = useState(false)
  // 部分校区发布失败时记下已成功的那一批；重试时带回这个批次号，重试成功的校区才会并进同一行。
  const [retryBatch, setRetryBatch] = useState<{ projectId: string; batchId: string } | null>(null)
  const [saving, setSaving] = useState(false)
  const [markingPhotoId, setMarkingPhotoId] = useState<string | null>(null)
  const { data, setData, loading, error, reload, refresh } = useLoad(async () => {
    const [project, firstRequests, campuses, firstPhotos, firstAdoptions] = await Promise.all([
      api<Project>({ url: `/projects/${projectId}` }),
      // GET /requests 硬性要求 REQUEST_VIEW。只有选题权限的账号打这条会 403，而它和
      // 其余请求同在一个 Promise.all 里——一条被拒，整页详情就进不去。
      canViewRequests
        ? api<PageData<PhotoRequest>>({ url: '/requests', params: { page: 1, pageSize: 100, projectId } })
        : Promise.resolve(emptyPage<PhotoRequest>()),
      // 需求表要显示已停用校区的名字，这里不按 enabled 过滤；发布表单的可选校区再单独筛。
      api<Campus[]>({ url: '/campuses' }),
      api<PageData<Photo>>({ url: '/photos', params: { page: 1, pageSize: 100, projectId, includeAllStatuses: true } }),
      user?.dataScope === 'CAMPUS'
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
  }, [projectId, user?.dataScope, canViewRequests])
  useRefreshOnResume(refresh)
  const { data: selectorCandidates, loading: candidatesLoading } = useLoad(
    () => selectorsOpen
      ? api<ProjectSelector[]>({ url: `/projects/${projectId}/selector-candidates` })
      : Promise.resolve([] as ProjectSelector[]),
    [] as ProjectSelector[], [selectorsOpen, projectId],
  )

  const { data: galleryPhotos, loading: galleryLoading } = useLoad(
    () => galleryOpen && hasPermission(user, 'PROJECT_ADOPT') && hasPermission(user, 'PHOTO_VIEW')
      ? api<PageData<Photo>>({
      url: '/photos',
      params: { page: 1, pageSize: 100, status: 'AVAILABLE', keyword: galleryKeyword || undefined },
    }) : Promise.resolve(emptyPage<Photo>()),
    emptyPage<Photo>(),
    [galleryOpen, galleryKeyword, user?.permissionGroupId],
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
        message.success('已取消采纳标注')
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
        message.success('已标注为采纳图片')
      }
      // 按被引状态筛选时，改完的这张已经移出结果，不能留在看不见的勾选里。
      if (photoFilters.adoption) setSelectedAlbumPhotoIds(current => current.filter(id => id !== photo.id))
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

  /**
   * 删除选题。后端另有一道「选题下还有需求、图片或采用记录就只能取消」的保护
   * （ProjectService.delete），这里按详情页的计数先把按钮禁掉，省得点下去才吃一个报错；
   * 真正的判定仍在后端，前端这一层只是提示。
   */
  const confirmDelete = () => {
    if (!data.project) return
    const title = data.project.title
    modal.confirm({
      title: '确认删除这个选题？',
      content: <div>
        <p>选题「{title}」会从列表中消失，已建立的分享链接随之失效。<strong>此操作不可撤销。</strong></p>
        <p>如果只是想停掉它、保留已有记录，请改用「取消项目」。</p>
      </div>,
      okText: '确认删除',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await api({ method: 'DELETE', url: `/projects/${projectId}` })
        } catch (reason) {
          message.error((reason as Error).message)
          return
        }
        message.success('选题已删除')
        navigate('/projects')
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
      const { deadline, title, description, campusIds, assigneeId } = values
      if (values.publishMode === 'draft') {
        await api({
          method: 'POST', url: `/projects/${projectId}/requests`,
          data: { title, description, campusId: campusIds[0], assigneeId, deadline: deadline.format('YYYY-MM-DDTHH:mm:ss') },
        })
        message.success('图片需求草稿已创建')
        setRetryBatch(null)
        setRequestOpen(false)
        requestForm.resetFields()
        await reload()
        return
      }
      const batchId = retryBatch?.projectId === projectId ? retryBatch.batchId : undefined
      const results = await api<BatchPublishResult[]>({
        method: 'POST', url: `/projects/${projectId}/requests/batch-publish`,
        data: { title, description, campusIds, assigneeId, batchId, deadline: deadline.format('YYYY-MM-DDTHH:mm:ss') },
      })
      const succeeded = results.filter(item => item.success)
      const failed = results.filter(item => !item.success)
      if (failed.length) {
        const nextBatchId = succeeded.find(item => item.request?.batchId)?.request?.batchId || batchId
        setRetryBatch(nextBatchId ? { projectId, batchId: nextBatchId } : null)
        requestForm.setFieldValue('campusIds', failed.map(item => item.campusId))
        const details = failed.map(item => {
          const campus = data.campuses.find(value => value.id === item.campusId)
          return `${campus?.name || `校区 #${item.campusId}`}：${item.message || '发布失败'}`
        }).join('；')
        message.warning({ content: `已成功发布 ${succeeded.length} 个，失败 ${failed.length} 个。${details}`, duration: 8 })
      } else {
        message.success(`已向 ${succeeded.length} 个校区分别发布需求`)
        setRetryBatch(null)
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
        data: { ...values, tags: normalizeTags(values.tags), version: data.project.version } })
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
      message.success(`已向项目相册添加 ${selectedPhotoIds.length} 张图片，请按需标注采纳`)
      setGalleryOpen(false)
      setSelectedPhotoIds([])
      await reload()
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const toggleAlbumPhoto = (photoId: string, checked: boolean) => {
    if (checked && selectedAlbumPhotoIds.length >= 200 && !selectedAlbumPhotoIds.includes(photoId)) {
      message.info('单次最多选择 200 张，已选到上限')
      return
    }
    setSelectedAlbumPhotoIds(current => checked
      ? current.includes(photoId) ? current : [...current, photoId].slice(0, 200)
      : current.filter(id => id !== photoId))
    if (checked) setAlbumSelectionAnchor(photoId)
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

  // 每张卡片都要问「采纳了吗 / 属于哪个需求 / 勾上了吗」，逐张在数组里线性查找
  // 就是 图片数 × 采纳数 的开销，这里一次建好索引。
  const adoptedPhotoIds = useMemo(() => new Set(data.adoptions.map(item => String(item.photoId))), [data.adoptions])
  const isAdoptedHere = useCallback((photo: Photo) => adoptedPhotoIds.has(String(photo.id)), [adoptedPhotoIds])
  // 被引状态也参与筛选：在「未被引」下标注一张，它就从当前结果里移走。
  const filteredPhotos = useMemo(
    () => filterPhotos(data.photos, photoFilters, isAdoptedHere), [data.photos, photoFilters, isAdoptedHere])
  const tagFilterOptions = useMemo(
    () => collectTagOptions(data.project?.tags, data.photos), [data.project?.tags, data.photos])
  const photographerOptions = useMemo(() => collectPhotographers(data.photos), [data.photos])
  const filtersActive = hasActiveFilters(photoFilters)
  const requestTitles = useMemo(
    () => new Map(data.requests.map(item => [String(item.id), item.title])), [data.requests])
  const selectedAlbumIdSet = useMemo(() => new Set(selectedAlbumPhotoIds), [selectedAlbumPhotoIds])

  // 分页只作用于展示；筛选、全选、批量操作仍然针对全部筛选结果。
  const photoPageCount = Math.max(1, Math.ceil(filteredPhotos.length / photoPage.pageSize))
  const currentPhotoPage = Math.min(photoPage.current, photoPageCount)
  const pagedPhotos = useMemo(
    () => filteredPhotos.slice((currentPhotoPage - 1) * photoPage.pageSize, currentPhotoPage * photoPage.pageSize),
    [filteredPhotos, currentPhotoPage, photoPage.pageSize])
  const changePhotoPage = (current: number, pageSize: number) => {
    setPhotoPage({ current: pageSize === photoPage.pageSize ? current : 1, pageSize })
    galleryRef.current?.scrollIntoView({ block: 'start' })
  }

  // 筛选变了就把看不见的图片从勾选里去掉，免得批量操作落到用户看不到的图片上。
  const updatePhotoFilters = (next: ProjectPhotoFilters) => {
    setPhotoFilters(next)
    setPhotoPage(current => ({ ...current, current: 1 }))
    const visibleIds = new Set(filterPhotos(data.photos, next, isAdoptedHere).map(photo => photo.id))
    setSelectedAlbumPhotoIds(current => current.filter(id => visibleIds.has(id)))
  }

  const selectAlbumRange = (photoId: string) => {
    // 只在可勾选的图片里连选，和「全选」一样按 isDownloadableStatus 过滤：不可下载的图片没有勾选框，连选进去就取消不掉。
    const orderedIds = pagedPhotos.filter(photo => isDownloadableStatus(photo.status)).map(photo => photo.id)
    if (!albumSelectionAnchor || !orderedIds.includes(albumSelectionAnchor)) {
      toggleAlbumPhoto(photoId, !selectedAlbumIdSet.has(photoId))
      return
    }
    const result = selectPhotoRange(selectedAlbumPhotoIds, orderedIds, albumSelectionAnchor, photoId, 200)
    setSelectedAlbumPhotoIds(result.selected)
    setAlbumSelectionAnchor(result.anchorId)
    if (result.truncated) message.info('单次最多选择 200 张，已选到上限')
  }

  // 传给 memo 卡片的回调必须引用稳定，否则每次渲染都是新函数，memo 等于没加。
  const onToggleAlbumPhoto = useStableCallback(toggleAlbumPhoto)
  const onSelectAlbumRange = useStableCallback(selectAlbumRange)
  const onOpenPhotoDetail = useStableCallback((photo: Photo) => navigate(`/photos/${photo.id}`))
  const onDownloadPhoto = useStableCallback((photo: Photo) => void downloadPhoto(photo))
  const onToggleAdoption = useStableCallback((photo: Photo) => void toggleAdoption(photo))
  const onPhotoTagClick = useStableCallback((tag: string) => updatePhotoFilters({ ...photoFilters,
    tags: photoFilters.tags.includes(tag) ? photoFilters.tags : [...photoFilters.tags, tag] }))
  const onOpenRequest = useStableCallback((request: PhotoRequest) =>
    navigate(`/requests?projectId=${projectId}&requestId=${request.id}`))
  useEffect(() => {
    setAlbumSelectionAnchor(null)
  }, [currentPhotoPage, photoPage.pageSize, photoFilters])
  useEffect(() => {
    if (!selectedAlbumPhotoIds.length) setAlbumSelectionAnchor(null)
  }, [selectedAlbumPhotoIds.length])

  const selectAllVisiblePhotos = () => {
    const selectableIds = filteredPhotos
      .filter(photo => isDownloadableStatus(photo.status))
      .map(photo => photo.id)
    setSelectedAlbumPhotoIds(selectableIds.slice(0, 200))
    if (selectableIds.length > 200) {
      message.info('单次最多选择 200 张，已选择前 200 张')
    }
  }

  const applyTaggedPhotos = (result: TaggedPhoto[]) => {
    const byId = new Map(result.map(item => [String(item.id), item]))
    setData(current => ({
      ...current,
      photos: current.photos.map(photo => {
        const tagged = byId.get(String(photo.id))
        return tagged ? { ...photo, tags: tagged.tags, version: tagged.version } : photo
      }),
    }))
    setTagMode(null)
  }

  const batchDownload = async () => {
    if (!selectedAlbumPhotoIds.length) return
    setBatchDownloading(true)
    try {
      const downloadUrl = await preparePhotoBatchDownload(selectedAlbumPhotoIds)
      if (downloadUrl) {
        window.location.assign(downloadUrl)
        setSelectedAlbumPhotoIds([])
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

  const saveSelectors = async () => {
    setSaving(true)
    try {
      await api<ProjectSelector[]>({
        method: 'PUT',
        url: `/projects/${projectId}/selectors`,
        data: { userIds: selectorIds },
      })
      message.success('选片人已更新')
      setSelectorsOpen(false)
      await reload()
    } catch (reason) { message.error((reason as Error).message) } finally { setSaving(false) }
  }

  // 只改状态、不提示也不刷新：由清理弹窗决定之后是否继续删图、何时刷新。
  const markCompleted = async () => {
    if (!data.project) return
    await api({ method: 'POST', url: `/projects/${projectId}/status`,
      data: { status: 'COMPLETED', version: data.project.version } })
  }

  const project = data.project
  const isEvent = project?.type === 'EVENT'
  const canEdit = hasPermission(user, 'PROJECT_CREATE')
  const canCreateRequest = hasPermission(user, 'REQUEST_CREATE')
  const canComplete = hasPermission(user, 'PROJECT_COMPLETE')
  const canAdopt = hasPermission(user, 'PROJECT_ADOPT')
  const canBatchDownload = hasPermission(user, 'PROJECT_DOWNLOAD')
  const canShare = hasPermission(user, 'PROJECT_SHARE')
  // 后端的删除是 PROJECT_CREATE + 本人创建或管理员，入口刻意只给管理员。
  const canDelete = user?.permissionGroupCode === 'ADMIN'
  // 详情页的计数对校区范围账号是裁剪过的，而删除入口只给全局范围的管理员，这里读到的是全量。
  const hasBusinessData = (project?.requestCount || 0) + (project?.photoCount || 0)
    + (project?.adoptionCount || 0) > 0
  // 与 PUT /photos/{id}、POST /photos/batch-tags 的方法级授权一致；逐张的归属/上传者限制由后端判断。
  const canTag = hasPermission(user, 'PHOTO_UPLOAD') || hasPermission(user, 'REQUEST_PHOTO_MANAGE')
  const canSelectPhotos = canBatchDownload || canTag
  const presetTags = project?.tags || []
  const selectedAlbumPhotos = useMemo(
    () => data.photos.filter(photo => selectedAlbumIdSet.has(photo.id)), [data.photos, selectedAlbumIdSet])
  const downloadablePhotoCount = useMemo(
    () => data.photos.filter(photo => isDownloadableStatus(photo.status)).length, [data.photos])
  const selectionFull = selectedAlbumPhotoIds.length >= 200
  const addableGalleryPhotos = galleryPhotos.items.filter(photo =>
    !photo.relatedProjectIds?.some(id => String(id) === projectId))
  return <DataState loading={loading} error={error} empty={!project} onRetry={reload}
    emptyText="找不到这个项目"
    emptyHint="它可能已经被删除，或者你参与的需求都不在这个项目下。">
    {project && <>
      <Breadcrumb className="detail-breadcrumb" items={[
        { title: <a onClick={() => navigate('/projects')}>选题项目</a> },
        { title: project.title },
      ]} />
      <section className="project-detail-hero">
        <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/projects')}>返回项目列表</Button>
        <div className="project-detail-heading">
          <div>
            <Space><Tag variant="filled">PROJECT {project.id}</Tag>
              <Tag color={isEvent ? 'purple' : 'default'} variant="filled">
                {isEvent ? '活动选题' : '创作选题'}</Tag>
              <StatusTag value={project.status} /></Space>
            <Typography.Title>{project.title}</Typography.Title>
            {project.description
              ? <MarkdownRenderer value={project.description} />
              : <Typography.Paragraph>尚未添加项目说明。清晰的说明能帮助负责人准确理解拍摄目标。</Typography.Paragraph>}
            <div className="project-preset-tags">
              <Typography.Text type="secondary"><TagsOutlined /> 预设标签</Typography.Text>
              {presetTags.length
                ? presetTags.map(tag => <Tag key={tag} color="blue" variant="filled" className="clickable-tag"
                    onClick={() => onPhotoTagClick(tag)}>
                    {tag}</Tag>)
                : <Typography.Text type="secondary">
                    {isEvent ? '未设置。活动选题的选片人只能从预设标签里挑，请先定义一组' : '未设置，上传者可以自定义标签'}
                  </Typography.Text>}
            </div>
            {isEvent && <div className="project-preset-tags">
              <Typography.Text type="secondary"><TeamOutlined /> 选片人</Typography.Text>
              {project.selectors?.length
                ? project.selectors.map(selector => <Tag key={String(selector.userId)} variant="filled">
                    {selector.displayName}</Tag>)
                : <Typography.Text type="secondary">尚未指定</Typography.Text>}
            </div>}
          </div>
          <Space wrap>
            {project.canSelect && <Button type="primary" icon={<ScissorOutlined />}
              onClick={() => navigate(`/projects/${projectId}/select`)}>进入选片</Button>}
            {project.canManageSelection && <Button icon={<TeamOutlined />} onClick={() => {
              setSelectorIds((project.selectors || []).map(selector => String(selector.userId)))
              setSelectorsOpen(true)
            }}>指定选片人</Button>}
            {canAdopt && project.status === 'ACTIVE' &&
              <Button icon={<FileImageOutlined />} onClick={() => setGalleryOpen(true)}>从图库添加图片</Button>}
            {canShare && <Button icon={<ShareAltOutlined />} onClick={() => setShareOpen(true)}>分享链接</Button>}
            {canEdit && <>
            {project.status !== 'COMPLETED' && project.status !== 'CANCELLED' &&
              <Button icon={<EditOutlined />} onClick={() => {
                editForm.setFieldsValue({ title: project.title, description: project.description, tags: project.tags || [] })
                setEditOpen(true)
              }}>编辑项目</Button>}
            {canCreateRequest && (project.status === 'DRAFT' || project.status === 'ACTIVE') &&
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
        <Space wrap>
          {canEdit && project.status === 'DRAFT' && <Button type="primary" icon={<RocketOutlined />} onClick={() => void changeStatus('ACTIVE')}>启动项目</Button>}
          {canComplete && project.status === 'ACTIVE' && <Button type="primary" icon={<CheckCircleOutlined />}
            onClick={() => project.canManageSelection
              ? setCleanupMode('complete')
              : void changeStatus('COMPLETED')}>标记为已完成</Button>}
          {canEdit && ['DRAFT', 'ACTIVE'].includes(project.status) && <Button danger icon={<StopOutlined />}
            onClick={() => modal.confirm({ title: '确认取消这个项目？', content: '取消后不能再创建需求，已有记录会继续保留。',
              okText: '确认取消', okButtonProps: { danger: true }, onOk: () => changeStatus('CANCELLED') })}>取消项目</Button>}
          {project.canManageSelection && project.status === 'COMPLETED' &&
            <Button danger icon={<DeleteOutlined />} onClick={() => setCleanupMode('cleanup')}>
              清理图片{project.deprecatedCount ? `（${DEPRECATED_TAG_LABEL} ${project.deprecatedCount}）` : ''}</Button>}
          {project.status === 'COMPLETED' && user?.permissionGroupCode === 'ADMIN' && <Button type="primary" onClick={reopen}>重新开放</Button>}
          {canDelete && (hasBusinessData
            ? <Tooltip title="选题下已有需求、图片或采用记录，只能取消，不能删除">
                {/* 禁用的 <button> 不派发鼠标事件，不套一层包裹元素 Tooltip 就永远不会弹。 */}
                <span style={{ display: 'inline-block' }}>
                  <Button danger disabled icon={<DeleteOutlined />}>删除选题</Button>
                </span>
              </Tooltip>
            : <Button danger icon={<DeleteOutlined />} onClick={confirmDelete}>删除选题</Button>)}
        </Space>
      </Card>

      <Card title="项目图片需求" extra={canCreateRequest && ['DRAFT', 'ACTIVE'].includes(project.status) &&
        <Button type="link" icon={<PlusOutlined />} onClick={() => setRequestOpen(true)}>新建需求</Button>}>
        <ProjectRequestTable requests={data.requests} campuses={data.campuses} onOpen={onOpenRequest}
          emptyText={canViewRequests ? '这个项目还没有图片需求'
            : '你的权限组没有需求访问权限，这里不显示需求'} />
      </Card>

      <Card
        ref={galleryRef}
        className="project-photo-gallery"
        title={filtersActive
          ? `需求图片（${filteredPhotos.length} / ${data.photos.length}）`
          : `需求图片（${data.photos.length}）`}
        extra={<Space wrap>
          <Typography.Text type="secondary">汇总展示本选题下所有需求已上传的图片</Typography.Text>
          {canSelectPhotos && <>
            <Button type="link" disabled={!downloadablePhotoCount || batchDownloading}
              onClick={selectAllVisiblePhotos}>{filtersActive ? '全选筛选结果' : '全选'}</Button>
            {!!selectedAlbumPhotoIds.length && <Button type="link" disabled={batchDownloading}
              onClick={() => setSelectedAlbumPhotoIds([])}>清空选择</Button>}
          </>}
          {canTag && <>
            <Button icon={<TagsOutlined />} disabled={!selectedAlbumPhotoIds.length}
              onClick={() => setTagMode('add')}>添加标签</Button>
            <Button icon={<MinusCircleOutlined />} disabled={!selectedAlbumPhotoIds.length}
              onClick={() => setTagMode('remove')}>移除标签</Button>
          </>}
          {canBatchDownload &&
            <Button type="primary" icon={<DownloadOutlined />} loading={batchDownloading}
              disabled={!selectedAlbumPhotoIds.length} onClick={() => void batchDownload()}>
              打包下载{selectedAlbumPhotoIds.length ? `（${selectedAlbumPhotoIds.length}）` : ''}
            </Button>}
        </Space>}
      >
        {!!data.photos.length && <ProjectPhotoFilterBar value={photoFilters} onChange={updatePhotoFilters}
          tagOptions={tagFilterOptions} photographerOptions={photographerOptions}
          historyScope={`project:${projectId}`} />}
        {filteredPhotos.length ? <>
          <Row gutter={[16, 20]} className="photo-grid">
            {pagedPhotos.map(photo => {
              const selected = selectedAlbumIdSet.has(photo.id)
              const downloadable = isDownloadableStatus(photo.status)
              return <Col xs={24} sm={12} lg={8} xxl={6} key={photo.id}>
                <ProjectPhotoCard
                  photo={photo}
                  requestLabel={requestTitles.get(String(photo.requestId))
                    || (photo.requestId ? `需求 #${photo.requestId}` : '未关联需求')}
                  adopted={adoptedPhotoIds.has(String(photo.id))}
                  selected={selected}
                  selectable={canSelectPhotos && downloadable}
                  selectDisabled={batchDownloading || (selectionFull && !selected)}
                  downloadable={canBatchDownload && downloadable}
                  canAdopt={canAdopt}
                  adoptDisabled={project.status !== 'ACTIVE' || photo.status !== 'AVAILABLE'}
                  marking={markingPhotoId === photo.id}
                  activeTags={photoFilters.tags}
                  placeholderImages={placeholderImages}
                  onToggleSelect={onToggleAlbumPhoto}
                  onSelectRange={onSelectAlbumRange}
                  onOpenDetail={hasPermission(user, 'PHOTO_VIEW') ? onOpenPhotoDetail : undefined}
                  onDownload={onDownloadPhoto}
                  onToggleAdoption={onToggleAdoption}
                  onTagClick={onPhotoTagClick} />
              </Col>
            })}
          </Row>
          {/* 勾选跨页保留（selectedAlbumPhotoIds 按 id 累加），翻页和改每页张数都不清空。 */}
          <ListPagination className="project-photo-pagination"
            page={currentPhotoPage} pageSize={photoPage.pageSize} total={filteredPhotos.length}
            sizes={PHOTO_PAGE_SIZES} showTotal={total => `共 ${total} 张`}
            onChange={changePhotoPage} />
        </> : <div className="empty-state">
          {data.photos.length ? '没有符合筛选条件的图片' : '这个选题还没有上传图片'}
          {filtersActive && <Button type="link" onClick={() => updatePhotoFilters(emptyProjectPhotoFilters)}>
            清空筛选</Button>}
        </div>}
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
              options={data.campuses.filter(c => c.enabled).map(c => ({ value: c.id, label: c.name }))}
              placeholder={publishMode === 'publish' ? '可同时选择多个校区' : '选择一个校区'} />
          </Form.Item>
          <Form.Item label="指派给" name="assigneeId"
            extra="可选。被指派人需有「需求访问、接受和提交」权限并能访问所选校区；发布后直接成为参与人">
            <RequestAssigneeSelect campusIds={requestCampusIds} />
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
          <Form.Item label="预设标签" name="tags" rules={tagRules}
            extra="需求上传图片和在选题里给图片加标签时只能从这些标签中选择；清空则允许自定义。修改不会改动图片上已有的标签。">
            <TagSelect placeholder="输入后回车添加预设标签" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title="从图库添加图片" width={860} open={galleryOpen}
        onCancel={() => setGalleryOpen(false)} onOk={addFromGallery}
        okText={`添加所选图片${selectedPhotoIds.length ? `（${selectedPhotoIds.length}）` : ''}`}
        confirmLoading={saving}>
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Typography.Paragraph type="secondary">
            选择你有权查看的可用图库图片。添加后只会进入项目相册，默认不标记为采纳；
            管理员可在项目图片中按需标注。
          </Typography.Paragraph>
          <Input.Search allowClear placeholder="搜索图片标题、描述或标签"
            onSearch={setGalleryKeyword} style={{ maxWidth: 420 }} />
          <ContentFitTable<Photo> rowKey="id" size="small" loading={galleryLoading}
            dataSource={addableGalleryPhotos}
            pagination={clientTablePagination(addableGalleryPhotos.length,
              { defaultPageSize: 8, sizes: [8, 16, 32] })}
            rowSelection={{
              // 这张表是本地分页（dataSource 就是全部候选图），勾选本来就跨页保留；
              // preserveSelectedRowKeys 还管住「换了关键词再搜一次」时已勾选的那几张。
              preserveSelectedRowKeys: true,
              selectedRowKeys: selectedPhotoIds,
              onChange: keys => setSelectedPhotoIds(keys.map(String)),
            }}
            locale={{ emptyText: '没有可添加的图库图片' }}
            columns={[
              { title: '预览', width: 92, render: (_, photo) =>
                photo.thumbnailUrl ? <PreviewPhoto width={68} height={48} style={{ objectFit: 'contain' }}
                  preview={false} src={photo.thumbnailUrl}
                  refresh={() => refreshPhotoPreviewUrl(photo.id)}
                  fallback={pickPlaceholderImage(placeholderImages, photo.id)} /> : '-' },
              { title: '图片', dataIndex: 'title', render: (value, photo) =>
                <div className="table-title"><strong>{value || '未命名图片'}</strong>
                  <span>{photo.photographerName} · {dayjs(photo.takenAt).format('YYYY-MM-DD')}</span></div> },
              { title: '标签', dataIndex: 'tags', render: tags =>
                <Space size={4} wrap>{tags?.slice(0, 3).map((tag: string) => <Tag key={tag}>{tag}</Tag>)}</Space> },
            ]} />
        </Space>
      </Modal>
      <Modal title="指定选片人" width={620} open={selectorsOpen} onCancel={() => setSelectorsOpen(false)}
        onOk={saveSelectors} okText="保存名单" confirmLoading={saving}>
        <Typography.Paragraph type="secondary">
          选片人可以打开这个选题的选片页，逐张打标签、标记{DEPRECATED_TAG_LABEL}，并对图片做裁切和旋转。
          被指派本身就是查看这个选题的凭据——不必先让他接一条需求，也不需要图库权限。
          新加入的人会收到一条站内通知。
        </Typography.Paragraph>
        {/*
          唯一的前置条件，写在这里而不是等他打开页面报 403：指派放行的是「看不看得到
          这一个选题」，不是「进不进得了选题模块」，后者仍由权限组决定。三个内置权限组
          都带这条权限，所以只有自建的「无任何选题权限」权限组会撞上。
        */}
        <Typography.Paragraph type="secondary">
          前提：对方的权限组要有「选题查看」权限。内置的管理员、部长和校区负责人都有；
          若自建的权限组一条选题权限都没勾，他会打不开选片页。
        </Typography.Paragraph>
        <Select mode="multiple" style={{ width: '100%' }} loading={candidatesLoading}
          value={selectorIds} onChange={setSelectorIds} showSearch
          optionFilterProp="label" maxCount={50} allowClear placeholder="搜索姓名或账号"
          options={selectorCandidates.map(candidate => ({
            value: String(candidate.userId),
            label: `${candidate.displayName}（${candidate.username}）`,
          }))} />
      </Modal>

      <BatchTagModal mode={tagMode} photos={selectedAlbumPhotos} projectId={projectId}
        presets={presetTags} onClose={() => setTagMode(null)} onDone={applyTaggedPhotos} />
      {shareOpen && <Suspense fallback={null}>
        <ProjectShareLinksModal projectId={projectId} open onClose={() => setShareOpen(false)}
          uploadLinksAvailable={isEvent && project.status === 'ACTIVE'} />
      </Suspense>}
      {cleanupMode && <Suspense fallback={null}>
        <SelectionCleanupModal key={cleanupMode} open mode={cleanupMode} project={project} photos={data.photos}
          onCancel={() => setCleanupMode(null)} onComplete={markCompleted} onFinished={reload} />
      </Suspense>}
    </>}
  </DataState>
}
