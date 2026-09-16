import {
  App, Alert, Breadcrumb, Button, Empty, Progress, Segmented, Space, Spin, Tag, Tooltip, Typography,
} from 'antd'
import {
  ArrowLeftOutlined, CheckCircleOutlined, ExpandOutlined, LeftOutlined, RightOutlined, ScissorOutlined,
  StopOutlined,
} from '@ant-design/icons'
import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { api } from '../api'
import type { PageData, PhotoEditTicket, Photo, Project, SelectionPhoto, TaggedPhoto } from '../types'
import { DataState } from '../components'
import { useLoad, useRefreshOnResume } from '../hooks'
import { PreviewPhotoImg } from '../PreviewPhoto'
import { DEPRECATED_TAG, DEPRECATED_TAG_LABEL, isDeprecated } from '../photoTags'
import { uploadToObjectStorage } from '../storageUpload'
import { sha256Hex } from '../photoEdit'

const PhotoCropEditor = lazy(() => import('../PhotoCropEditor'))

/** 一次取回的张数。活动选题动辄几千张，整批拉回来会让首屏等到天荒地老。 */
const PAGE_SIZE = 120
/** 浏览到离末尾这么近就自动续上一页，翻图的人不该感觉到分页存在。 */
const PREFETCH_MARGIN = 20

type QualityMode = 'preview' | 'original'
type SelectionFilter = 'ALL' | 'PENDING' | 'PICKED' | 'DROPPED'

const filterOptions: { value: SelectionFilter; label: string }[] = [
  { value: 'ALL', label: '全部' },
  { value: 'PENDING', label: '待处理' },
  { value: 'PICKED', label: '已选中' },
  { value: 'DROPPED', label: DEPRECATED_TAG_LABEL },
]

function matchesFilter(photo: SelectionPhoto, filter: SelectionFilter) {
  const dropped = isDeprecated(photo.tags)
  const picked = photo.tags.some(tag => tag.toLowerCase() !== DEPRECATED_TAG)
  if (filter === 'DROPPED') return dropped
  if (filter === 'PICKED') return picked && !dropped
  if (filter === 'PENDING') return !dropped && !picked
  return true
}

/**
 * 活动选题的选片工作台（issue #94）。
 *
 * 布局照着需求给的草图：外壳的侧边栏和顶栏照旧，内容区左边一列缩略图、
 * 右边是真正干活的地方——大图加打标签。当前这张在左列里用深色标出来。
 *
 * ## 大图和小图
 * 「画质」开关同时管左列和大图区：`preview` 用 480px 的 WebP 预览（省流量，
 * 翻图快），`original` 直接渲染成品图，也就是选片要看的那张原图。默认是预览，
 * 因为 issue 自己就提了原图会吃流量；真要判虚焦、看细节时再切过去。
 * 左列的图一律 `loading="lazy"`，切到原图也只有屏幕上那几张会真的下载。
 *
 * ## 为什么不用图库那套接口
 * 选片人常常连 `PHOTO_VIEW` 都没有——他不上传也不管需求，只判「这张能不能用」。
 * 这一页的每条请求都走 `/projects/{id}/selection/**`，凭据是 `project_selector`
 * 里的那一行。详见后端 `ProjectSelectionController`。
 */
export default function PhotoSelectionPage() {
  const { projectId = '' } = useParams()
  const navigate = useNavigate()
  const { message } = App.useApp()
  const [photos, setPhotos] = useState<SelectionPhoto[]>([])
  const [total, setTotal] = useState(0)
  const [loadedPages, setLoadedPages] = useState(0)
  const [loadingMore, setLoadingMore] = useState(false)
  const [activeId, setActiveId] = useState<string | null>(null)
  const [filter, setFilter] = useState<SelectionFilter>('ALL')
  const [quality, setQuality] = useState<QualityMode>('preview')
  const [editing, setEditing] = useState(false)
  const [savingEdit, setSavingEdit] = useState(false)
  const [taggingId, setTaggingId] = useState<string | null>(null)
  const filmstripRef = useRef<HTMLDivElement>(null)

  const { data: project, loading, error, reload, refresh } = useLoad(
    async () => {
      const detail = await api<Project>({ url: `/projects/${projectId}` })
      const first = await api<PageData<SelectionPhoto>>({
        url: `/projects/${projectId}/selection/photos`,
        params: { page: 1, pageSize: PAGE_SIZE },
      })
      setPhotos(first.items)
      setTotal(first.total)
      setLoadedPages(1)
      setActiveId(current => (current && first.items.some(item => item.id === current))
        ? current : (first.items[0]?.id ?? null))
      return detail
    },
    null as Project | null,
    [projectId],
  )
  useRefreshOnResume(refresh)

  const loadMore = useCallback(async () => {
    if (loadingMore || photos.length >= total) return
    setLoadingMore(true)
    try {
      const next = await api<PageData<SelectionPhoto>>({
        url: `/projects/${projectId}/selection/photos`,
        params: { page: loadedPages + 1, pageSize: PAGE_SIZE },
      })
      // 期间可能有人删了图，服务端分页会错位，按 id 去重比「相信页码」可靠。
      setPhotos(current => {
        const seen = new Set(current.map(item => String(item.id)))
        return [...current, ...next.items.filter(item => !seen.has(String(item.id)))]
      })
      setTotal(next.total)
      setLoadedPages(page => page + 1)
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setLoadingMore(false)
    }
  }, [loadedPages, loadingMore, message, photos.length, projectId, total])

  const visible = useMemo(() => photos.filter(photo => matchesFilter(photo, filter)), [photos, filter])
  const activeIndex = visible.findIndex(photo => String(photo.id) === String(activeId))
  const active = activeIndex >= 0 ? visible[activeIndex] : visible[0]
  // 每次渲染都新建一个数组会让键盘监听的 effect 反复重挂（每次按键都重绑一遍）。
  const presetTags = useMemo(() => project?.tags || [], [project?.tags])
  const decided = photos.filter(photo => photo.tags.length > 0).length

  // 翻到接近末尾就把下一页续上，不用用户自己去点「加载更多」。
  useEffect(() => {
    if (activeIndex >= 0 && activeIndex >= visible.length - PREFETCH_MARGIN) void loadMore()
  }, [activeIndex, visible.length, loadMore])

  // 当前这张要始终留在左列可视范围内，否则用键盘翻几张之后就看不见自己在哪了。
  useEffect(() => {
    if (!active) return
    filmstripRef.current
      ?.querySelector(`[data-photo-id="${active.id}"]`)
      ?.scrollIntoView({ block: 'nearest' })
  }, [active])

  const step = useCallback((delta: number) => {
    if (!visible.length) return
    const current = visible.findIndex(photo => String(photo.id) === String(activeId))
    const next = Math.min(visible.length - 1, Math.max(0, (current < 0 ? 0 : current) + delta))
    setActiveId(visible[next].id as string)
  }, [activeId, visible])

  const applyTagged = (result: TaggedPhoto[]) => {
    const byId = new Map(result.map(item => [String(item.id), item]))
    setPhotos(current => current.map(photo => {
      const tagged = byId.get(String(photo.id))
      return tagged ? { ...photo, tags: tagged.tags, version: tagged.version } : photo
    }))
  }

  const toggleTag = useCallback(async (photo: SelectionPhoto, tag: string) => {
    const present = photo.tags.some(value => value === tag)
    setTaggingId(photo.id as string)
    try {
      applyTagged(await api<TaggedPhoto[]>({
        method: 'POST',
        url: `/projects/${projectId}/selection/tags`,
        data: {
          photoIds: [photo.id],
          addTags: present ? [] : [tag],
          removeTags: present ? [tag] : [],
        },
      }))
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setTaggingId(null)
    }
  }, [message, projectId])

  // 键盘优先：选片是几千张的重复动作，每张都去点按钮会把手点废。
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (editing) return
      const target = event.target as HTMLElement | null
      if (target && /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName)) return
      if (event.metaKey || event.ctrlKey || event.altKey) return
      if (event.key === 'ArrowLeft') { event.preventDefault(); step(-1); return }
      if (event.key === 'ArrowRight') { event.preventDefault(); step(1); return }
      if (!active) return
      if (event.key === ' ') {
        event.preventDefault()
        void toggleTag(active, DEPRECATED_TAG)
        return
      }
      const digit = Number.parseInt(event.key, 10)
      if (digit >= 1 && digit <= 9 && presetTags[digit - 1]) {
        event.preventDefault()
        void toggleTag(active, presetTags[digit - 1])
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [active, editing, presetTags, step, toggleTag])

  const saveEdit = async (blob: Blob) => {
    if (!active) return
    setSavingEdit(true)
    try {
      const contentType = active.contentType === 'image/png' ? 'image/png' : 'image/jpeg'
      const file = new File([blob], active.contentType === 'image/png' ? 'edited.png' : 'edited.jpg',
        { type: contentType })
      const ticket = await api<PhotoEditTicket>({
        method: 'POST',
        url: `/projects/${projectId}/selection/photos/${active.id}/edit-tickets`,
        data: { contentType, size: file.size },
      })
      await uploadToObjectStorage(ticket, file)
      const saved = await api<Photo>({
        method: 'POST',
        url: `/projects/${projectId}/selection/photos/${active.id}/apply-edit`,
        data: {
          sourceObjectKey: ticket.sourceObjectKey,
          contentType,
          size: file.size,
          sha256: await sha256Hex(file),
        },
      })
      // 成品图换了对象 key，旧的那条签名地址已经指向被删掉的对象，必须换一条新的。
      const fresh = await api<{ downloadUrl: string }>({
        url: `/projects/${projectId}/selection/photos/${active.id}/image-url`,
      })
      setPhotos(current => current.map(photo => photo.id === active.id ? {
        ...photo,
        tags: saved.tags,
        width: saved.width,
        height: saved.height,
        size: saved.size,
        contentType: saved.contentType,
        thumbnailUrl: saved.thumbnailUrl,
        imageUrl: fresh.downloadUrl,
        version: saved.version,
      } : photo))
      setEditing(false)
      message.success('已用编辑后的图片替换原图')
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setSavingEdit(false)
    }
  }

  const refreshImageUrl = async (photoId: string) => {
    const fresh = await api<{ downloadUrl: string }>({
      url: `/projects/${projectId}/selection/photos/${photoId}/image-url`,
    })
    setPhotos(current => current.map(photo => photo.id === photoId
      ? { ...photo, imageUrl: fresh.downloadUrl } : photo))
    return fresh.downloadUrl
  }

  const stageSrc = active ? (quality === 'original' ? active.imageUrl : active.thumbnailUrl) : undefined
  const editable = project?.status === 'ACTIVE'

  return <DataState loading={loading} error={error} empty={!project} onRetry={reload}
    emptyText="找不到这个选题" emptyHint="它可能已经被删除，或者你不是这个选题的选片人。">
    {project && <div className="selection-page">
      <Breadcrumb className="detail-breadcrumb" items={[
        { title: <a onClick={() => navigate('/projects')}>选题项目</a> },
        { title: <a onClick={() => navigate(`/projects/${projectId}`)}>{project.title}</a> },
        { title: '选片' },
      ]} />
      <header className="selection-header">
        <div>
          <Button type="text" icon={<ArrowLeftOutlined />}
            onClick={() => navigate(`/projects/${projectId}`)}>返回选题</Button>
          <Typography.Title level={4}>{project.title} · 选片</Typography.Title>
          <Typography.Text type="secondary">
            已处理 {decided} / 已加载 {photos.length}（共 {total} 张）
            ．方向键翻图，空格标记{DEPRECATED_TAG_LABEL}，数字键 1–9 套用预设标签
          </Typography.Text>
          <Progress percent={photos.length ? Math.round(decided / photos.length * 100) : 0}
            size="small" showInfo={false} />
        </div>
        <Space wrap>
          <Segmented<SelectionFilter> value={filter} onChange={setFilter}
            options={filterOptions.map(option => ({
              value: option.value,
              label: `${option.label}（${photos.filter(p => matchesFilter(p, option.value)).length}）`,
            }))} />
          <Tooltip title="原图按成品图渲染，看得清细节但更费流量；预览图是 480px 的压缩图">
            <Segmented<QualityMode> value={quality} onChange={setQuality} options={[
              { value: 'preview', label: '小图（预览）', icon: <CheckCircleOutlined /> },
              { value: 'original', label: '大图（原图）', icon: <ExpandOutlined /> },
            ]} />
          </Tooltip>
        </Space>
      </header>

      {project.status !== 'ACTIVE' && <Alert type="info" showIcon className="selection-alert"
        message="这个选题不在进行中，选片和编辑都已锁定" />}

      <div className="selection-workspace">
        <aside className="selection-filmstrip" ref={filmstripRef}>
          {visible.map((photo, index) => {
            const dropped = isDeprecated(photo.tags)
            const current = active && String(photo.id) === String(active.id)
            const src = quality === 'original' ? photo.imageUrl : photo.thumbnailUrl
            return <button type="button" key={photo.id} data-photo-id={photo.id}
              className={`selection-thumb${current ? ' is-current' : ''}${dropped ? ' is-dropped' : ''}`}
              onClick={() => setActiveId(photo.id as string)}
              aria-current={current ? 'true' : undefined}
              aria-label={`第 ${index + 1} 张：${photo.title || '未命名图片'}`}>
              {src
                ? <PreviewPhotoImg src={src} alt={photo.title || '选片图片'} loading="lazy" decoding="async"
                    refresh={() => refreshImageUrl(photo.id as string)} />
                : <span className="selection-thumb-empty">无预览</span>}
              <span className="selection-thumb-index">{index + 1}</span>
              {!!photo.tags.length && <span className="selection-thumb-mark">
                {dropped ? <StopOutlined /> : <CheckCircleOutlined />}
              </span>}
            </button>
          })}
          {photos.length < total && <div className="selection-filmstrip-more">
            <Button type="link" loading={loadingMore} onClick={() => void loadMore()}>
              继续加载（还有 {total - photos.length} 张）</Button>
          </div>}
          {!visible.length && <div className="selection-filmstrip-more">
            <Typography.Text type="secondary">没有符合筛选条件的图片</Typography.Text>
          </div>}
        </aside>

        <section className="selection-stage">
          {!active && <Empty description={total ? '换个筛选条件看看' : '这个选题相册里还没有可选的图片'} />}
          {active && editing && active.imageUrl && <Suspense
            fallback={<div className="empty-state">正在打开编辑器…</div>}>
            <PhotoCropEditor imageUrl={active.imageUrl} contentType={active.contentType}
              refresh={() => refreshImageUrl(active.id as string)} saving={savingEdit}
              onCancel={() => setEditing(false)} onSave={blob => void saveEdit(blob)} />
          </Suspense>}
          {active && !editing && <>
            <div className="selection-stage-image">
              {stageSrc
                ? <PreviewPhotoImg src={stageSrc} alt={active.title || '选片图片'} decoding="async"
                    refresh={() => refreshImageUrl(active.id as string)} />
                : <Empty description="这张图片暂时没有可展示的画面" />}
            </div>
            <div className="selection-stage-panel">
              <div className="selection-stage-meta">
                <Space wrap>
                  <Button icon={<LeftOutlined />} disabled={activeIndex <= 0}
                    onClick={() => step(-1)}>上一张</Button>
                  <Button icon={<RightOutlined />} disabled={activeIndex >= visible.length - 1}
                    onClick={() => step(1)}>下一张</Button>
                </Space>
                <div>
                  <strong>{active.title || '未命名图片'}</strong>
                  <Typography.Text type="secondary">
                    {active.photographerName} · {dayjs(active.takenAt).format('YYYY-MM-DD HH:mm')}
                    {active.width && active.height ? ` · ${active.width}×${active.height}` : ''}
                  </Typography.Text>
                </div>
                <Button icon={<ScissorOutlined />} disabled={!editable || !active.imageUrl}
                  onClick={() => setEditing(true)}>裁切 / 旋转</Button>
              </div>
              <div className="selection-tag-row">
                <Typography.Text type="secondary">标签</Typography.Text>
                {presetTags.length
                  ? presetTags.map((tag, index) => <Tag.CheckableTag key={tag}
                      checked={active.tags.includes(tag)}
                      onChange={() => { if (editable) void toggleTag(active, tag) }}>
                      {tag}<span className="selection-tag-key">{index < 9 ? index + 1 : ''}</span>
                    </Tag.CheckableTag>)
                  : <Typography.Text type="secondary">
                      这个选题还没有预设标签，请让选题负责人在选题里先定义一组</Typography.Text>}
                <Tag.CheckableTag checked={isDeprecated(active.tags)}
                  className="selection-tag-deprecated"
                  onChange={() => { if (editable) void toggleTag(active, DEPRECATED_TAG) }}>
                  <StopOutlined /> {DEPRECATED_TAG_LABEL}<span className="selection-tag-key">空格</span>
                </Tag.CheckableTag>
                {taggingId === active.id && <Spin size="small" />}
              </div>
              {isDeprecated(active.tags) && <Alert type="warning" showIcon
                message={`标记为${DEPRECATED_TAG_LABEL}的图片会在选题完成、负责人确认后从图库和对象存储中删除`} />}
            </div>
          </>}
        </section>
      </div>
    </div>}
  </DataState>
}
