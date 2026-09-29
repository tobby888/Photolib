import {
  Alert, App, Button, Progress, Select, Space, Tag, Tooltip, Typography,
} from 'antd'
import {
  ArrowLeftOutlined, ArrowRightOutlined, CloseOutlined, DeleteOutlined, EditOutlined, ExclamationCircleOutlined,
  HolderOutlined, InfoCircleOutlined, PlusOutlined, ReloadOutlined, SaveOutlined, SettingOutlined, UndoOutlined,
  WarningOutlined,
} from '@ant-design/icons'
import { lazy, Suspense, useCallback, useEffect, useRef, useState, type DragEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError, api, withoutStepUpPrompt } from '../../api'
import type {
  DashboardLayout, DashboardLayoutView, DashboardMetrics, DashboardWidget,
} from '../../types'
import { DataState, PageTitle } from '../../components'
import {
  MAX_WIDGETS, REFRESH_INTERVALS, TRAFFIC_RANGES, createWidget, defaultLayout, formatMetric, metricOf, moveWidget,
  normalizeLayout, seriesOf, seriesPoints, thresholdStatus, widgetTitle, type ThresholdStatus,
} from '../../adminDashboard'
import { ADMIN_SECTIONS, adminPath } from '../../adminNav'
import { ADMIN_SECTION_ICONS } from '../../adminIcons'
import TrendChart from '../../TrendChart'

const DashboardWidgetEditor = lazy(() => import('./DashboardWidgetEditor'))

interface SavedLayout {
  layout: DashboardLayout
  version: number | null
  customized: boolean
}

const STATUS_LABEL: Record<ThresholdStatus, string> = { normal: '正常', warning: '告警', critical: '严重' }

function StatusTag({ status }: { status: ThresholdStatus }) {
  if (status === 'normal') return null
  return <Tag color={status === 'critical' ? 'error' : 'warning'} variant="filled"
    icon={status === 'critical' ? <ExclamationCircleOutlined /> : <WarningOutlined />}>
    {STATUS_LABEL[status]}
  </Tag>
}

function Retired() {
  return <div className="dashboard-widget-empty">这个指标已下线，请编辑面板换一个指标。</div>
}

function StatWidget({ widget, metrics }: { widget: DashboardWidget; metrics: DashboardMetrics }) {
  const metric = metricOf(widget.metric)
  if (!metric) return <Retired />
  const value = metric.read(metrics)
  const status = thresholdStatus(value, widget.warnAt, widget.criticalAt)
  return <div className={`dashboard-stat dashboard-stat-${status}`}>
    <strong>{formatMetric(metric.kind, value)}</strong>
    <div className="dashboard-stat-meta">
      <StatusTag status={status} />
      <Typography.Text type="secondary" ellipsis={{ tooltip: metric.description }}>{metric.description}</Typography.Text>
    </div>
  </div>
}

function GaugeWidget({ widget, metrics }: { widget: DashboardWidget; metrics: DashboardMetrics }) {
  const metric = metricOf(widget.metric)
  if (!metric) return <Retired />
  const value = metric.read(metrics)
  const status = thresholdStatus(value, widget.warnAt, widget.criticalAt)
  const percent = value == null ? 0 : Math.max(0, Math.min(100, value))
  return <div className={`dashboard-gauge dashboard-stat-${status}`}>
    <div className="dashboard-gauge-value">
      <strong>{formatMetric('percent', value)}</strong>
      <StatusTag status={status} />
    </div>
    <Progress percent={percent} showInfo={false} size={['100%', 10]}
      status={status === 'critical' ? 'exception' : 'normal'}
      strokeColor={status === 'warning' ? 'var(--dashboard-warning)' : status === 'critical' ? undefined : 'var(--sky-steel)'} />
    <Typography.Text type="secondary" className="dashboard-gauge-caption">
      {metric.description}
      {(widget.warnAt != null || widget.criticalAt != null) && <span>
        {' '}阈值：{[widget.warnAt != null && `告警 ${widget.warnAt}%`, widget.criticalAt != null && `严重 ${widget.criticalAt}%`]
          .filter(Boolean).join(' · ')}
      </span>}
    </Typography.Text>
  </div>
}

function TrendWidget({ widget, metrics }: { widget: DashboardWidget; metrics: DashboardMetrics }) {
  const series = seriesOf(widget.metric)
  if (!series) return <Retired />
  const range = widget.range ?? '1h'
  const points = seriesPoints(metrics, widget.metric, range)
  if (!points) return <div className="dashboard-widget-empty">业务计数暂时取不到，稍后自动恢复。</div>
  const peak = Math.max(0, ...points.map(point => point.value))
  const total = points.reduce((sum, point) => sum + point.value, 0)
  const latest = points.at(-1)?.value ?? 0
  const summable = series.kind === 'count' || series.kind === 'bytes'
  return <div className="dashboard-trend">
    <div className="dashboard-trend-summary">
      <span>最新 <strong>{formatMetric(series.kind, latest)}</strong></span>
      <span>峰值 <strong>{formatMetric(series.kind, peak)}</strong></span>
      {summable && <span>合计 <strong>{formatMetric(series.kind, total)}</strong></span>}
      <Typography.Text type="secondary">
        {series.source === 'traffic' ? TRAFFIC_RANGES.find(item => item.value === range)?.label : '近 30 天'}
      </Typography.Text>
    </div>
    <TrendChart points={points} kind={series.kind} label={`${widgetTitle(widget)}（${series.description}）`}
      height={widget.size === 'large' ? 220 : 176} />
  </div>
}

function StatusBreakdownWidget({ metrics }: { metrics: DashboardMetrics }) {
  const hour = metrics.traffic.lastHour
  const rows = [
    { label: '2xx 成功', value: hour.successes },
    { label: '3xx 重定向 / 未修改', value: hour.redirects },
    { label: '4xx 客户端错误', value: hour.clientErrors },
    { label: '5xx 服务端错误', value: hour.serverErrors },
  ]
  const max = Math.max(1, ...rows.map(row => row.value))
  if (!hour.requests) return <div className="dashboard-widget-empty">近 1 小时还没有请求。</div>
  return <div className="dashboard-bars">
    {rows.map(row => <div className="dashboard-bar-row" key={row.label}>
      <span className="dashboard-bar-label">{row.label}</span>
      <span className="dashboard-bar-track"><span style={{ width: `${row.value / max * 100}%` }} /></span>
      <span className="dashboard-bar-value">
        {formatMetric('count', row.value)}
        <small>{formatMetric('percent', row.value / hour.requests * 100)}</small>
      </span>
    </div>)}
    <Typography.Text type="secondary" className="dashboard-footnote">近 1 小时共 {formatMetric('count', hour.requests)} 个请求</Typography.Text>
  </div>
}

function EndpointsWidget({ widget, metrics }: { widget: DashboardWidget; metrics: DashboardMetrics }) {
  const items = metrics.traffic.topEndpoints.slice(0, widget.limit ?? 8)
  if (!items.length) return <div className="dashboard-widget-empty">启动以来还没有接口调用。</div>
  return <div className="dashboard-table-wrap">
    <table className="dashboard-table">
      <thead><tr><th scope="col">接口</th><th scope="col">调用</th><th scope="col">平均耗时</th><th scope="col">错误</th></tr></thead>
      <tbody>{items.map(item => {
        const [method, ...path] = item.endpoint.split(' ')
        const errors = item.clientErrors + item.serverErrors
        return <tr key={item.endpoint}>
          <td><div className="dashboard-endpoint">
            {path.length ? <><Tag className="dashboard-method">{method}</Tag>
              <Typography.Text ellipsis={{ tooltip: path.join(' ') }}>{path.join(' ').replace(/^\/api\/v1/, '')}</Typography.Text></>
              : item.endpoint}
          </div></td>
          <td>{formatMetric('count', item.requests)}</td>
          <td>{formatMetric('ms', item.avgLatencyMs)}</td>
          <td>{errors ? <Tooltip title={`4xx ${item.clientErrors} · 5xx ${item.serverErrors}`}>
            <span className={item.serverErrors ? 'dashboard-error-count' : undefined}>{formatMetric('count', errors)}</span>
          </Tooltip> : '0'}</td>
        </tr>
      })}</tbody>
    </table>
    <Typography.Text type="secondary" className="dashboard-footnote">按服务这次启动以来的调用次数排序</Typography.Text>
  </div>
}

function ShortcutsWidget({ widget }: { widget: DashboardWidget }) {
  const navigate = useNavigate()
  const links = (widget.links ?? []).map(key => ADMIN_SECTIONS.find(item => item.key === key)).filter(Boolean)
  if (!links.length) return <div className="dashboard-widget-empty">还没有选择快捷入口。</div>
  return <div className="dashboard-shortcuts">
    {links.map(item => <Button key={item!.key} icon={ADMIN_SECTION_ICONS[item!.key]}
      onClick={() => navigate(adminPath(item!.key))}>{item!.label}</Button>)}
  </div>
}

function NoteWidget({ widget }: { widget: DashboardWidget }) {
  return widget.text?.trim()
    ? <div className="dashboard-note">{widget.text}</div>
    : <div className="dashboard-widget-empty">空便签。编辑面板时可以写点什么。</div>
}

function WidgetBody({ widget, metrics }: { widget: DashboardWidget; metrics: DashboardMetrics | null }) {
  if (widget.type === 'note') return <NoteWidget widget={widget} />
  if (widget.type === 'shortcuts') return <ShortcutsWidget widget={widget} />
  if (!metrics) return <div className="dashboard-widget-empty">正在读取数据…</div>
  switch (widget.type) {
    case 'stat': return <StatWidget widget={widget} metrics={metrics} />
    case 'gauge': return <GaugeWidget widget={widget} metrics={metrics} />
    case 'trend': return <TrendWidget widget={widget} metrics={metrics} />
    case 'status': return <StatusBreakdownWidget metrics={metrics} />
    case 'endpoints': return <EndpointsWidget widget={widget} metrics={metrics} />
    default: return null
  }
}

const timeOf = (millis: number) => new Date(millis).toLocaleTimeString('zh-CN', { hour12: false })

/**
 * 管理员面板的首页：系统数据一览，由管理员自己增减、排列小面板。
 *
 * 布局存在后端、按人保存（`/admin-dashboard/layout`），数据每隔几秒取一次快照
 * （`/admin-dashboard/metrics`，间隔也由管理员定）；标签页在后台时停止轮询。
 * 编辑时改的是一份草稿，点「保存布局」才写回，取消就原样丢掉。
 */
export default function AdminDashboardPage() {
  const { message, modal } = App.useApp()
  const [metrics, setMetrics] = useState<DashboardMetrics | null>(null)
  const [metricsError, setMetricsError] = useState('')
  const [refreshing, setRefreshing] = useState(false)
  const [saved, setSaved] = useState<SavedLayout | null>(null)
  const [layoutError, setLayoutError] = useState('')
  const [draft, setDraft] = useState<DashboardLayout | null>(null)
  const [saving, setSaving] = useState(false)
  const [editor, setEditor] = useState<{ widget: DashboardWidget; isNew: boolean } | null>(null)
  const dragIndex = useRef<number | null>(null)
  const [dropTarget, setDropTarget] = useState<number | null>(null)
  const metricsSequence = useRef(0)
  // 两步验证过了 15 分钟有效期：后台轮询不弹验证框，先停下来等管理员自己点。
  const [stepUpExpired, setStepUpExpired] = useState(false)

  const loadMetrics = useCallback(async (quiet: boolean) => {
    const id = ++metricsSequence.current
    if (!quiet) setRefreshing(true)
    try {
      const next = await api<DashboardMetrics>({
        url: '/admin-dashboard/metrics', ...(quiet ? withoutStepUpPrompt : {}),
      })
      if (id !== metricsSequence.current) return
      setMetrics(next)
      setMetricsError('')
      setStepUpExpired(false)
    } catch (failure) {
      if (id !== metricsSequence.current) return
      if (failure instanceof ApiError && failure.code === 'STEP_UP_REQUIRED') {
        setStepUpExpired(true)
        return
      }
      // 后台轮询失败不接管画面：上一份数据仍然是管理员想看的，只在顶部提示一下。
      setMetricsError((failure as Error).message)
    } finally {
      if (id === metricsSequence.current) setRefreshing(false)
    }
  }, [])

  const loadLayout = useCallback(async () => {
    setLayoutError('')
    try {
      const view = await api<DashboardLayoutView>({ url: '/admin-dashboard/layout' })
      setSaved({ layout: normalizeLayout(view.layout), version: view.version, customized: !!view.layout })
    } catch (failure) {
      setLayoutError((failure as Error).message)
    }
  }, [])

  useEffect(() => {
    void loadLayout()
    void loadMetrics(false)
    return () => { metricsSequence.current += 1 }
  }, [loadLayout, loadMetrics])

  const layout = draft ?? saved?.layout
  const refreshSeconds = layout?.refreshSeconds ?? 0
  const pollSeconds = stepUpExpired ? 0 : refreshSeconds
  // 自动刷新：标签页在后台时停掉，切回来立刻补一次。
  useEffect(() => {
    if (!pollSeconds) return
    let timer: number | undefined
    const start = () => { timer ??= window.setInterval(() => void loadMetrics(true), pollSeconds * 1000) }
    const stop = () => {
      if (timer !== undefined) window.clearInterval(timer)
      timer = undefined
    }
    const onVisibilityChange = () => {
      if (document.visibilityState === 'hidden') { stop(); return }
      void loadMetrics(true)
      start()
    }
    if (document.visibilityState !== 'hidden') start()
    document.addEventListener('visibilitychange', onVisibilityChange)
    return () => {
      stop()
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [pollSeconds, loadMetrics])

  const persist = async (next: DashboardLayout) => {
    setSaving(true)
    try {
      const view = await api<DashboardLayoutView>({ method: 'PUT', url: '/admin-dashboard/layout',
        data: { layout: next, version: saved?.version ?? null } })
      setSaved({ layout: normalizeLayout(view.layout), version: view.version, customized: true })
      return true
    } catch (failure) {
      message.error((failure as Error).message)
      return false
    } finally {
      setSaving(false)
    }
  }

  const saveDraft = async () => {
    if (!draft) return
    if (await persist(draft)) {
      setDraft(null)
      message.success('面板布局已保存')
    }
  }

  const changeRefresh = async (value: number) => {
    if (draft) { setDraft({ ...draft, refreshSeconds: value }); return }
    if (!saved) return
    if (await persist({ ...saved.layout, refreshSeconds: value })) {
      message.success(value ? `已改为每 ${value} 秒自动刷新` : '已关闭自动刷新')
    }
  }

  const resetLayout = () => modal.confirm({
    title: '恢复默认面板',
    content: '你摆好的小面板会被清空，换回系统内置的默认布局。其他管理员的面板不受影响。',
    okText: '恢复默认', cancelText: '取消', okButtonProps: { danger: true },
    onOk: async () => {
      try {
        await api<void>({ method: 'DELETE', url: '/admin-dashboard/layout' })
        setSaved({ layout: defaultLayout(), version: null, customized: false })
        setDraft(null)
        message.success('已恢复默认面板')
      } catch (failure) {
        message.error((failure as Error).message)
        throw failure
      }
    },
  })

  const updateWidgets = (update: (widgets: DashboardWidget[]) => DashboardWidget[]) =>
    setDraft(current => current ? { ...current, widgets: update(current.widgets) } : current)

  const removeWidget = (id: string) => updateWidgets(widgets => widgets.filter(item => item.id !== id))

  const commitWidget = (widget: DashboardWidget) => {
    updateWidgets(widgets => widgets.some(item => item.id === widget.id)
      ? widgets.map(item => item.id === widget.id ? widget : item)
      : [...widgets, widget])
    setEditor(null)
  }

  const onDrop = (event: DragEvent, index: number) => {
    event.preventDefault()
    const from = dragIndex.current
    dragIndex.current = null
    setDropTarget(null)
    if (from != null) updateWidgets(widgets => moveWidget(widgets, from, index))
  }

  const editing = draft !== null
  const widgets = layout?.widgets ?? []

  return <div className="admin-dashboard">
    <PageTitle eyebrow="ADMINISTRATION" title="数据面板"
      description="注册用户、系统流量、QPS 与运行状态。小面板可以自己增减、调整顺序和大小，布局只对你自己生效。"
      extra={editing ? <>
        <Button icon={<PlusOutlined />} disabled={widgets.length >= MAX_WIDGETS}
          onClick={() => setEditor({ widget: createWidget('stat'), isNew: true })}>添加小面板</Button>
        <Button icon={<UndoOutlined />} onClick={() => setDraft(defaultLayout())}>换成默认布局</Button>
        <Button icon={<CloseOutlined />} onClick={() => setDraft(null)}>取消</Button>
        <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={() => void saveDraft()}>保存布局</Button>
      </> : <>
        <Select aria-label="自动刷新间隔" value={refreshSeconds} style={{ width: 150 }} disabled={!saved || saving}
          onChange={value => void changeRefresh(value)}
          options={REFRESH_INTERVALS.map(value => ({ value, label: value ? `每 ${value} 秒刷新` : '不自动刷新' }))} />
        <Button icon={<ReloadOutlined />} loading={refreshing} onClick={() => void loadMetrics(false)}>刷新</Button>
        {saved?.customized && <Button icon={<UndoOutlined />} onClick={resetLayout}>恢复默认</Button>}
        <Button type="primary" icon={<SettingOutlined />} disabled={!saved}
          onClick={() => saved && setDraft(structuredClone(saved.layout))}>编辑面板</Button>
      </>} />

    <div className="dashboard-status-line">
      <Typography.Text type="secondary">
        {metrics ? `数据更新于 ${timeOf(metrics.generatedAt)}` : '正在读取数据…'}
        {metrics && ` · 服务已运行 ${formatMetric('duration', metrics.runtime.uptimeMs)}`}
      </Typography.Text>
      <Tooltip title="流量、QPS、在线人数和热门接口记在后端进程的内存里，服务重启后重新计；OSS 直传和图片直链下载不经过应用服务器，不计入流量。业务计数每 30 秒更新一次。">
        <Typography.Text type="secondary" className="dashboard-hint"><InfoCircleOutlined /> 数据口径</Typography.Text>
      </Tooltip>
    </div>
    {stepUpExpired && <Alert type="info" showIcon className="dashboard-alert"
      title="两步验证已过期，自动刷新已暂停" description="下面是暂停前的数据。重新验证后继续自动刷新。"
      action={<Button size="small" type="primary" onClick={() => void loadMetrics(false)}>重新验证</Button>} />}
    {metricsError && <Alert type="warning" showIcon className="dashboard-alert"
      title="最新数据没能取回来" description={`${metricsError}${metrics ? '。下面显示的是上一次成功取到的数据。' : ''}`}
      action={<Button size="small" onClick={() => void loadMetrics(false)}>重试</Button>} />}
    {metrics?.businessError && <Alert type="warning" showIcon className="dashboard-alert" title={metrics.businessError} />}
    {editing && <Alert type="info" showIcon className="dashboard-alert"
      title="正在编辑面板：拖动小面板调整顺序，或用每块右上角的按钮设置、移动和删除。改完点「保存布局」。" />}

    <DataState loading={!saved && !layoutError} error={layoutError} onRetry={() => void loadLayout()}
      empty={!widgets.length} emptyText="面板上还没有小面板"
      emptyHint={editing ? '点「添加小面板」挑几个想看的指标。' : '点「编辑面板」添加想看的指标，或者恢复默认布局。'}
      emptyAction={editing
        ? <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditor({ widget: createWidget('stat'), isNew: true })}>添加小面板</Button>
        : <Button type="primary" icon={<SettingOutlined />} onClick={() => saved && setDraft(structuredClone(saved.layout))}>编辑面板</Button>}>
      <div className={`dashboard-grid${editing ? ' dashboard-grid-editing' : ''}`}>
        {widgets.map((widget, index) => <section key={widget.id}
          className={`dashboard-widget dashboard-widget-${widget.size} dashboard-widget-type-${widget.type}${dropTarget === index ? ' dashboard-widget-drop' : ''}`}
          aria-label={widgetTitle(widget)}
          draggable={editing}
          onDragStart={event => { dragIndex.current = index; event.dataTransfer.effectAllowed = 'move' }}
          onDragOver={event => { if (editing && dragIndex.current != null) { event.preventDefault(); setDropTarget(index) } }}
          onDragLeave={() => setDropTarget(current => current === index ? null : current)}
          onDragEnd={() => { dragIndex.current = null; setDropTarget(null) }}
          onDrop={event => onDrop(event, index)}>
          <header>
            {editing && <HolderOutlined className="dashboard-drag-handle" aria-hidden />}
            <h3 title={widgetTitle(widget)}>{widgetTitle(widget)}</h3>
            {editing && <Space size={2} className="dashboard-widget-tools">
              <Tooltip title="设置"><Button type="text" size="small" icon={<EditOutlined />}
                aria-label={`设置「${widgetTitle(widget)}」`} onClick={() => setEditor({ widget, isNew: false })} /></Tooltip>
              <Tooltip title="前移"><Button type="text" size="small" icon={<ArrowLeftOutlined />} disabled={index === 0}
                aria-label={`前移「${widgetTitle(widget)}」`}
                onClick={() => updateWidgets(items => moveWidget(items, index, index - 1))} /></Tooltip>
              <Tooltip title="后移"><Button type="text" size="small" icon={<ArrowRightOutlined />}
                disabled={index === widgets.length - 1} aria-label={`后移「${widgetTitle(widget)}」`}
                onClick={() => updateWidgets(items => moveWidget(items, index, index + 1))} /></Tooltip>
              <Tooltip title="删除"><Button type="text" size="small" danger icon={<DeleteOutlined />}
                aria-label={`删除「${widgetTitle(widget)}」`} onClick={() => removeWidget(widget.id)} /></Tooltip>
            </Space>}
          </header>
          <div className="dashboard-widget-body"><WidgetBody widget={widget} metrics={metrics} /></div>
        </section>)}
      </div>
    </DataState>

    {editor && <Suspense fallback={null}>
      <DashboardWidgetEditor open widget={editor.widget} isNew={editor.isNew}
        onCancel={() => setEditor(null)} onSubmit={commitWidget} />
    </Suspense>}
  </div>
}
