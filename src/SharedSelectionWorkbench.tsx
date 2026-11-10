import { Button, Empty, Segmented, Space, Tooltip, Typography } from 'antd'
import {
  AppstoreOutlined, ArrowLeftOutlined, BlockOutlined, CheckCircleOutlined, CheckOutlined, CloseOutlined,
  CompressOutlined, DownloadOutlined, ExpandOutlined, FullscreenOutlined, LeftOutlined, PictureOutlined,
  RightOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useRefreshOnResume } from './hooks'
import { PreviewPhotoImg } from './PreviewPhoto'
import { shareApi, type SharePhotoQuery } from './projectShare'
import { useSelectionFocus } from './selectionFocus'
import { MAX_SHARE_BATCH } from './shareSelection'
import type { SharePhoto } from './types'

const ZoomableImage = lazy(() => import('./ZoomableImage'))

/** 一次取回的张数，用满访客列表接口允许的最大页长（`@Max(100)`）。 */
const PAGE_SIZE = 100
/** 浏览到离末尾这么近就自动续上一页，翻图的人不该感觉到分页存在。 */
const PREFETCH_MARGIN = 20

type QualityMode = 'preview' | 'original'
type ViewMode = 'single' | 'grid'
type PickFilter = 'ALL' | 'UNPICKED' | 'PICKED'

type ListQuery = Omit<SharePhotoQuery, 'page' | 'pageSize'>

interface SharedSelectionWorkbenchProps {
  token: string
  session: string
  projectTitle?: string
  /** 相册那一页当前的筛选和搜索，选图台沿用同一批图。 */
  query: ListQuery
  searching: boolean
  selected: readonly string[]
  selectedIds: ReadonlySet<string>
  onToggle: (photoId: string, checked: boolean) => void
  onClear: () => void
  onBatchDownload: () => void
  batchDownloading: boolean
  onDownloadOne: (photo: SharePhoto) => void
  onExit: () => void
  /** 会话失效（403/404）要退回密码页，交给页面统一处理。 */
  onError: (reason: unknown) => void
}

/**
 * 分享链接的选图台：站内活动选题选片页（PhotoSelectionPage）的访客版。
 *
 * 布局、两种视角（逐张 / 网格）、画质开关、弹窗选图和键盘操作都与站内一致，
 * 差别只在「选」的含义：站内是给图片打标签（写库），这里是把图片挑进打包下载的
 * 勾选集合——和相册页共用同一份勾选，来回切换不会丢。访客不能改图片上的任何东西。
 *
 * 只有开了下载的链接才进得来：挑图的目的就是打包下载，而「大图（原图）」的签名地址
 * 服务端也只签给开了下载的链接（`ProjectShareService#photos`）。
 */
export default function SharedSelectionWorkbench({
  token, session, projectTitle, query, searching, selected, selectedIds, onToggle, onClear,
  onBatchDownload, batchDownloading, onDownloadOne, onExit, onError,
}: SharedSelectionWorkbenchProps) {
  const [photos, setPhotos] = useState<SharePhoto[]>([])
  const [total, setTotal] = useState(0)
  const [loadedPages, setLoadedPages] = useState(0)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [activeId, setActiveId] = useState<string | null>(null)
  const [filter, setFilter] = useState<PickFilter>('ALL')
  const [quality, setQuality] = useState<QualityMode>('preview')
  // 访客挑图多半是先整墙扫一遍再细看，默认网格。
  const [view, setView] = useState<ViewMode>('grid')
  const [focusRequested, setFocusOpen] = useState(false)
  const focusRef = useRef<HTMLDivElement>(null)
  const focusStripRef = useRef<HTMLDivElement>(null)
  const browserRef = useRef<HTMLDivElement>(null)
  // 重取时要知道「已经加载到第几页」，但不能把它列进依赖——那会让每加载一页都整页重取一次。
  const loadedPagesRef = useRef(0)
  loadedPagesRef.current = loadedPages

  const fetchPages = useCallback(async (pages: number) => {
    const loaded: SharePhoto[] = []
    const seen = new Set<string>()
    let latestTotal = 0
    for (let page = 1; page <= pages; page += 1) {
      const result = await shareApi.photos(token, session, { ...query, page, pageSize: PAGE_SIZE })
      for (const item of result.items) {
        if (!seen.has(item.id)) {
          seen.add(item.id)
          loaded.push(item)
        }
      }
      latestTotal = result.total
      if (!result.items.length || loaded.length >= result.total) break
    }
    return { loaded, total: latestTotal }
  }, [query, session, token])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    fetchPages(1)
      .then(result => {
        if (cancelled) return
        setPhotos(result.loaded)
        setTotal(result.total)
        setLoadedPages(1)
        setActiveId(result.loaded[0]?.id ?? null)
      })
      .catch(reason => { if (!cancelled) onError(reason) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [fetchPages, onError])

  // 预签名地址只活 10 分钟：切回前台、或者某张图加载失败时，把已经加载过的那几页
  // 整批重取一遍（不退回第一页，挑到第 300 张的人不该被扔回开头）。几十张图会
  // 几乎同时失败，所以在途的那一次请求要共用。
  const refreshing = useRef<Promise<SharePhoto[]> | null>(null)
  const refreshLoaded = useCallback(() => {
    refreshing.current ??= fetchPages(Math.max(1, loadedPagesRef.current))
      .then(result => {
        setPhotos(result.loaded)
        setTotal(result.total)
        setActiveId(current => (current && result.loaded.some(item => item.id === current))
          ? current : result.loaded[0]?.id ?? null)
        return result.loaded
      })
      .finally(() => { refreshing.current = null })
    return refreshing.current
  }, [fetchPages])
  useRefreshOnResume(useCallback(() => { void refreshLoaded().catch(() => undefined) }, [refreshLoaded]))

  // 用户没有主动发起这次请求，失败了不弹提示，让图片自己的占位顶上就行。
  const refreshUrl = useCallback(async (photoId: string, original: boolean) => {
    const items = await refreshLoaded().catch(() => [] as SharePhoto[])
    const photo = items.find(item => item.id === photoId)
    return original ? photo?.imageUrl : photo?.thumbnailUrl
  }, [refreshLoaded])

  const loadMore = useCallback(async () => {
    if (loading || loadingMore || photos.length >= total) return
    setLoadingMore(true)
    try {
      const next = await shareApi.photos(token, session, {
        ...query, page: loadedPages + 1, pageSize: PAGE_SIZE,
      })
      // 期间可能有图被移出相册，服务端分页会错位，按 id 去重比「相信页码」可靠。
      setPhotos(current => {
        const seen = new Set(current.map(item => item.id))
        return [...current, ...next.items.filter(item => !seen.has(item.id))]
      })
      // 后面已经没有图了却还报着更大的总数（有图刚被移走），按已加载的算，免得一直续页。
      setTotal(next.items.length ? next.total : Math.min(next.total, photos.length))
      setLoadedPages(page => page + 1)
    } catch (reason) {
      onError(reason)
    } finally {
      setLoadingMore(false)
    }
  }, [loadedPages, loading, loadingMore, onError, photos.length, query, session, token, total])

  const matchesFilter = useCallback((photo: SharePhoto, value: PickFilter) => {
    if (value === 'PICKED') return selectedIds.has(photo.id)
    if (value === 'UNPICKED') return !selectedIds.has(photo.id)
    return true
  }, [selectedIds])
  const visible = useMemo(
    () => photos.filter(photo => matchesFilter(photo, filter)), [filter, matchesFilter, photos])
  const activeIndex = visible.findIndex(photo => photo.id === activeId)
  const active = activeIndex >= 0 ? visible[activeIndex] : visible[0]
  const focusOpen = focusRequested && !!active
  const hasOriginals = photos.some(photo => !!photo.imageUrl)
  const pickedLoaded = photos.filter(photo => selectedIds.has(photo.id)).length

  useEffect(() => {
    if (activeIndex >= 0 && activeIndex >= visible.length - PREFETCH_MARGIN) void loadMore()
  }, [activeIndex, visible.length, loadMore])

  // 当前这张要始终留在缩略图列（和弹窗底部那条）的可视范围内；用 nearest，不会把外层页面也带着滚。
  useEffect(() => {
    if (!active) return
    for (const container of [browserRef.current, focusStripRef.current]) {
      container
        ?.querySelector(`[data-photo-id="${active.id}"]`)
        ?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
    }
  }, [active, view, focusOpen])

  const { browserFullscreen, toggleBrowserFullscreen, exitBrowserFullscreen } =
    useSelectionFocus(focusOpen, focusRef)
  const closeFocus = useCallback(() => {
    setFocusOpen(false)
    exitBrowserFullscreen()
  }, [exitBrowserFullscreen])

  const step = useCallback((delta: number) => {
    if (!visible.length) return
    const current = visible.findIndex(photo => photo.id === activeId)
    const next = Math.min(visible.length - 1, Math.max(0, (current < 0 ? 0 : current) + delta))
    setActiveId(visible[next].id)
  }, [activeId, visible])

  const togglePick = useCallback((photo: SharePhoto) => {
    const picked = selectedIds.has(photo.id)
    // 到了上限这一下选不进去（页面会提示），当前这张也就不该往后挪。
    if (!picked && selected.length >= MAX_SHARE_BATCH) {
      onToggle(photo.id, true)
      return
    }
    // 在「未选 / 已选」筛选下，改完的这张会立刻从列表里消失。当前这张就顺延到下一张，
    // 而不是跳回第一张——一路按空格挑下去时，进度不该被打断。
    if (photo.id === active?.id && filter !== 'ALL') {
      const index = visible.findIndex(item => item.id === photo.id)
      const next = visible[index + 1] ?? visible[index - 1]
      if (next) setActiveId(next.id)
    }
    onToggle(photo.id, !picked)
  }, [active?.id, filter, onToggle, selected.length, selectedIds, visible])

  // 键盘优先：挑几百张图，每张都去点按钮会把手点废。
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (target && /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName)) return
      if (event.metaKey || event.ctrlKey || event.altKey) return
      if (event.key === 'Escape' && focusOpen) { event.preventDefault(); closeFocus(); return }
      if ((event.key === 'f' || event.key === 'F') && visible.length) {
        event.preventDefault()
        if (focusOpen) closeFocus()
        else setFocusOpen(true)
        return
      }
      if (event.key === 'ArrowLeft') { event.preventDefault(); step(-1); return }
      if (event.key === 'ArrowRight') { event.preventDefault(); step(1); return }
      if (event.key === ' ' && active && !batchDownloading) {
        event.preventDefault()
        togglePick(active)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [active, batchDownloading, closeFocus, focusOpen, step, togglePick, visible.length])

  const srcOf = (photo: SharePhoto) =>
    quality === 'original' && photo.imageUrl ? photo.imageUrl : photo.thumbnailUrl
  const usingOriginal = (photo: SharePhoto) => quality === 'original' && !!photo.imageUrl

  /*
    缩略图在逐张的左列、网格和弹窗底部那条里尺寸不同（由 CSS 决定），行为一致：
    点一下设为当前、双击进逐张细看、右上角的勾直接选中 / 取消。
    右上角的勾不进 Tab 序列：键盘用户用空格选当前这张，免得空格在按钮上触发两次。
  */
  const renderThumb = (photo: SharePhoto, index: number) => {
    const current = !!active && photo.id === active.id
    const picked = selectedIds.has(photo.id)
    const src = srcOf(photo)
    const label = photo.title || '未命名图片'
    return <div key={photo.id} data-photo-id={photo.id} role="button" tabIndex={0}
      className={`selection-thumb${current ? ' is-current' : ''}${picked ? ' is-picked' : ''}`}
      onClick={() => setActiveId(photo.id)}
      onDoubleClick={() => { setActiveId(photo.id); setView('single') }}
      onKeyDown={event => {
        if (event.key !== 'Enter' || event.target !== event.currentTarget) return
        event.preventDefault()
        setActiveId(photo.id)
      }}
      aria-current={current ? 'true' : undefined}
      aria-label={`第 ${index + 1} 张：${label}${picked ? '（已选中）' : ''}`}>
      {src
        ? <PreviewPhotoImg src={src} alt={label} loading="lazy" decoding="async"
            refresh={() => refreshUrl(photo.id, usingOriginal(photo))} />
        : <span className="selection-thumb-empty">无预览</span>}
      <span className="selection-thumb-index">{index + 1}</span>
      <button type="button" tabIndex={-1} className="selection-thumb-pick"
        aria-pressed={picked} aria-label={`${picked ? '取消选中' : '选中'} ${label}`}
        title={picked ? '取消选中' : '选中'}
        disabled={batchDownloading || (!picked && selected.length >= MAX_SHARE_BATCH)}
        onClick={event => { event.stopPropagation(); togglePick(photo) }}
        onDoubleClick={event => event.stopPropagation()}>
        <CheckOutlined />
      </button>
    </div>
  }

  const pickButton = (photo: SharePhoto) => {
    const picked = selectedIds.has(photo.id)
    return <Button type={picked ? 'default' : 'primary'} icon={picked ? <CloseOutlined /> : <CheckOutlined />}
      disabled={batchDownloading || (!picked && selected.length >= MAX_SHARE_BATCH)}
      onClick={() => togglePick(photo)}>
      {picked ? '取消选中' : '选中'}<span className="selection-tag-key">空格</span>
    </Button>
  }

  const downloadActions = <>
    <Typography.Text type="secondary">已选 {selected.length} 张</Typography.Text>
    {!!selected.length && <Button type="link" disabled={batchDownloading} onClick={onClear}>清空选择</Button>}
    <Button type="primary" icon={<DownloadOutlined />} loading={batchDownloading}
      disabled={!selected.length} onClick={onBatchDownload}>
      打包下载{selected.length ? `（${selected.length}）` : ''}
    </Button>
  </>

  const qualitySwitch = hasOriginals && <Tooltip
    title="原图按成品图渲染，看得清细节但更费流量；预览图是 480px 的压缩图">
    <Segmented<QualityMode> value={quality} onChange={setQuality} options={[
      { value: 'preview', label: '小图（预览）', icon: <CheckCircleOutlined /> },
      { value: 'original', label: '大图（原图）', icon: <ExpandOutlined /> },
    ]} />
  </Tooltip>

  const stageImage = (photo: SharePhoto) => {
    const src = srcOf(photo)
    if (!src) return <Empty description="这张图片暂时没有可展示的画面" />
    const original = usingOriginal(photo)
    return <Suspense fallback={<div className="empty-state">正在加载图片…</div>}>
      <ZoomableImage src={src} alt={photo.title || '分享图片'}
        refresh={() => refreshUrl(photo.id, original)}
        resetKey={`${photo.id}-${original ? 'original' : 'preview'}`}
        previewHint={original ? undefined
          : `当前是 480px 预览图，放大后会糊${hasOriginals ? '；要看清细节请切到「大图（原图）」' : ''}`} />
    </Suspense>
  }

  const meta = (photo: SharePhoto) => <Typography.Text type="secondary">
    {photo.photographerName} · {dayjs(photo.takenAt).format('YYYY-MM-DD HH:mm')}
    {photo.width && photo.height ? ` · ${photo.width}×${photo.height}` : ''}
  </Typography.Text>

  const loadMoreButton = photos.length < total && <Button type="link" loading={loadingMore}
    onClick={() => void loadMore()}>继续加载（还有 {total - photos.length} 张）</Button>

  const focusWindow = focusOpen && active && createPortal(
    <div className="selection-focus" ref={focusRef} tabIndex={-1} role="dialog" aria-modal="true"
      aria-label={`弹窗选图：${projectTitle ?? ''}`}>
      <header className="selection-focus-bar">
        <div className="selection-focus-title">
          <span className="selection-focus-count">
            {activeIndex + 1} / {visible.length}{photos.length < total ? '+' : ''}
          </span>
          <strong>{active.title || '未命名图片'}</strong>
          {meta(active)}
        </div>
        <Space wrap>
          {qualitySwitch}
          <Tooltip title={browserFullscreen ? '退出浏览器全屏' : '浏览器全屏，连地址栏也收起来'}>
            <Button icon={browserFullscreen ? <CompressOutlined /> : <FullscreenOutlined />}
              aria-label={browserFullscreen ? '退出浏览器全屏' : '浏览器全屏'}
              onClick={toggleBrowserFullscreen} />
          </Tooltip>
          <Tooltip title="关闭（Esc 或 F）">
            <Button icon={<CloseOutlined />} aria-label="关闭弹窗" onClick={closeFocus} />
          </Tooltip>
        </Space>
      </header>
      <div className="selection-focus-stage">
        {stageImage(active)}
        <Button className="selection-focus-nav is-prev" shape="circle" size="large" icon={<LeftOutlined />}
          aria-label="上一张" disabled={activeIndex <= 0} onClick={() => step(-1)} />
        <Button className="selection-focus-nav is-next" shape="circle" size="large" icon={<RightOutlined />}
          aria-label="下一张" disabled={activeIndex >= visible.length - 1} onClick={() => step(1)} />
      </div>
      <div className="selection-focus-panel">
        <div className="selection-tag-row">
          {pickButton(active)}
          <Typography.Text type="secondary">已选 {selected.length} 张</Typography.Text>
        </div>
        <div className="selection-focus-strip" ref={focusStripRef}>
          {visible.map((photo, index) => renderThumb(photo, index))}
          {loadMoreButton}
        </div>
      </div>
    </div>,
    document.body,
  )

  const emptyText = filter === 'ALL'
    ? (searching ? '没有符合筛选条件的图片' : '这个项目还没有可查看的图片')
    : filter === 'PICKED' ? '已加载的图片里还没有选中的' : '已加载的图片都选中了'

  return <div className="selection-page share-selection">
    <header className="selection-header">
      <div>
        <Button type="text" icon={<ArrowLeftOutlined />} onClick={onExit}>返回相册</Button>
        {/* 选题标题已经在页头上了，这里不再重复。 */}
        <Typography.Title level={4}>选图</Typography.Title>
        <Typography.Text type="secondary">
          已加载 {photos.length}（共 {total} 张{searching ? '，沿用相册里的筛选和搜索' : ''}）
          ．方向键翻图，空格选中 / 取消，按 F 弹窗选图．单次最多打包 {MAX_SHARE_BATCH} 张
        </Typography.Text>
      </div>
      <Space wrap>
        <Segmented<PickFilter> value={filter} onChange={setFilter} options={[
          { value: 'ALL', label: `全部（${photos.length}）` },
          { value: 'UNPICKED', label: `未选（${photos.length - pickedLoaded}）` },
          { value: 'PICKED', label: `已选（${pickedLoaded}）` },
        ]} />
        <Tooltip title="逐张：左边缩略图列 + 右边大图，适合细看；网格：整墙小图，适合快速扫一遍">
          <Segmented<ViewMode> value={view} onChange={setView} options={[
            { value: 'single', label: '逐张', icon: <PictureOutlined /> },
            { value: 'grid', label: '网格', icon: <AppstoreOutlined /> },
          ]} />
        </Tooltip>
        {qualitySwitch}
        <Tooltip title="把大图铺满整个浏览器窗口来选（快捷键 F）">
          <Button icon={<BlockOutlined />} disabled={!visible.length}
            onClick={() => setFocusOpen(true)}>弹窗选图</Button>
        </Tooltip>
      </Space>
    </header>
    <div className="share-selection-actions">{downloadActions}</div>

    <div className={`selection-workspace view-${view}`}>
      {view === 'single' && <aside className="selection-filmstrip" ref={browserRef}>
        {visible.map((photo, index) => renderThumb(photo, index))}
        {loadMoreButton && <div className="selection-filmstrip-more">{loadMoreButton}</div>}
        {!loading && !visible.length && <div className="selection-filmstrip-more">
          <Typography.Text type="secondary">{emptyText}</Typography.Text>
        </div>}
      </aside>}

      <section className="selection-stage">
        {view === 'grid' && <>
          <div className="selection-grid" ref={browserRef}>
            {visible.map((photo, index) => renderThumb(photo, index))}
            {!loading && !visible.length && <div className="empty-state">{emptyText}</div>}
            {loading && <div className="empty-state">正在加载图片…</div>}
          </div>
          {loadMoreButton && <div className="selection-grid-more">{loadMoreButton}</div>}
        </>}

        {view === 'single' && <>
          {!active && <Empty description={loading ? '正在加载图片…' : emptyText} />}
          {active && <div className="selection-stage-image">
            {focusOpen ? <Empty description="正在弹窗中选图" /> : stageImage(active)}
          </div>}
        </>}

        {active && <div className="selection-stage-panel">
          <div className="selection-stage-meta">
            <Space wrap>
              <Button icon={<LeftOutlined />} disabled={activeIndex <= 0} onClick={() => step(-1)}>上一张</Button>
              <Button icon={<RightOutlined />} disabled={activeIndex >= visible.length - 1}
                onClick={() => step(1)}>下一张</Button>
            </Space>
            <div>
              <strong>{active.title || '未命名图片'}</strong>
              {meta(active)}
            </div>
            <Space wrap>
              {pickButton(active)}
              <Button icon={<DownloadOutlined />} onClick={() => onDownloadOne(active)}>下载这张</Button>
            </Space>
          </div>
        </div>}
      </section>
    </div>
    {focusWindow}
  </div>
}
