import {
  Alert, App, Button, Card, Empty, Input, InputNumber, Progress, Segmented, Select, Space, Switch, Tabs,
  Tag, Tooltip, Typography,
} from 'antd'
import type { TableColumnsType } from 'antd'
import {
  CloudDownloadOutlined, InfoCircleOutlined, PauseCircleOutlined, PlayCircleOutlined, RedoOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { useEffect, useMemo, useRef, useState } from 'react'
import { api, emptyPage } from '../../api'
import { ContentFitTable, TableEllipsisText } from '../../ContentFitTable'
import { DataState } from '../../components'
import { useLoad } from '../../hooks'
import { clientTablePagination } from '../../pagination'
import TrendChart from '../../TrendChart'
import { formatBytes } from '../../adminDashboard'
import {
  LIVE_REFRESH_MS, LIVE_WINDOWS, MAX_LIVE_WINDOW_SECONDS, MIN_LIVE_WINDOW_SECONDS, SNAPSHOT_DURATIONS,
  SNAPSHOT_STATUS, STOP_REASON_LABELS, clampLiveWindow, describeMinutes, describeWindow, errorRate, featureName,
  formatMs, progressPercent, remaining, snapshotMinutes,
} from '../../performance'
import type {
  PageData, PerformanceCall, PerformanceDownload, PerformanceFeature, PerformanceLive, PerformanceSnapshot,
  PerformanceSnapshotOverview, PerformanceStats, PerformanceWeekly,
} from '../../types'

const time = (value?: string | null) => value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—'

/** 一组耗时的小格子：p50 / p90 / p95 / p99 等。 */
function StatGrid({ items }: { items: { label: string; value: string; hint?: string; tone?: 'warn' }[] }) {
  return <div className="perf-stat-grid">
    {items.map(item => <div key={item.label} className={`perf-stat${item.tone === 'warn' ? ' perf-stat-warn' : ''}`}>
      <span className="perf-stat-label">
        {item.label}
        {item.hint && <Tooltip title={item.hint}><InfoCircleOutlined /></Tooltip>}
      </span>
      <strong>{item.value}</strong>
    </div>)}
  </div>
}

/** 没有样本时显示「—」而不是 0 ms：0 ms 看起来像"很快"，其实是没数据。 */
const msOf = (stats: PerformanceStats, value: number) => stats.count ? formatMs(value) : '—'

function percentileItems(stats: PerformanceStats, withP90 = true) {
  return [
    { label: 'P50', value: msOf(stats, stats.p50Ms) },
    ...(withP90 ? [{ label: 'P90', value: msOf(stats, stats.p90Ms) }] : []),
    { label: 'P95', value: msOf(stats, stats.p95Ms) },
    { label: 'P99', value: msOf(stats, stats.p99Ms) },
  ]
}

const sortBy = (pick: (stats: PerformanceStats) => number) =>
  (a: PerformanceFeature, b: PerformanceFeature) => pick(a.stats) - pick(b.stats)

/** 按功能汇总的表：实时窗口和一周分位数共用。 */
function FeatureTable({ rows, showP90, emptyText }: { rows: PerformanceFeature[]; showP90: boolean; emptyText: string }) {
  const [keyword, setKeyword] = useState('')
  const [module, setModule] = useState<string>()
  const modules = useMemo(() => [...new Set(rows.map(row => row.module).filter((m): m is string => !!m))].sort(),
    [rows])
  const filtered = useMemo(() => rows.filter(row => {
    if (module && row.module !== module) return false
    if (!keyword.trim()) return true
    const text = `${featureName(row)} ${row.key} ${row.module ?? ''}`.toLowerCase()
    return text.includes(keyword.trim().toLowerCase())
  }), [rows, module, keyword])
  const columns: TableColumnsType<PerformanceFeature> = [
    {
      title: '功能', key: 'name', fixed: 'left',
      render: (_, row) => <div className="perf-feature-name">
        <TableEllipsisText value={featureName(row)} maxWidth={240} />
        {row.label && row.kind === 'REQUEST' && <Typography.Text type="secondary" className="perf-feature-key">
          {row.key.replace(' /api/v1/', ' /')}
        </Typography.Text>}
      </div>,
    },
    { title: '模块', dataIndex: 'module', render: (value?: string | null) => value || '—' },
    {
      title: '次数', key: 'count', align: 'right', sorter: sortBy(s => s.count), defaultSortOrder: 'descend',
      render: (_, row) => row.stats.count.toLocaleString(),
    },
    {
      title: '失败', key: 'errors', align: 'right', sorter: sortBy(s => s.errors),
      render: (_, row) => row.stats.errors
        ? <Typography.Text type="danger">{row.stats.errors}（{errorRate(row.stats.count, row.stats.errors)?.toFixed(1)}%）</Typography.Text>
        : 0,
    },
    { title: '平均', key: 'avg', align: 'right', sorter: sortBy(s => s.avgMs), render: (_, row) => formatMs(row.stats.avgMs) },
    { title: 'P50', key: 'p50', align: 'right', sorter: sortBy(s => s.p50Ms), render: (_, row) => formatMs(row.stats.p50Ms) },
    ...(showP90 ? [{
      title: 'P90', key: 'p90', align: 'right' as const, sorter: sortBy(s => s.p90Ms),
      render: (_: unknown, row: PerformanceFeature) => formatMs(row.stats.p90Ms),
    }] : []),
    { title: 'P95', key: 'p95', align: 'right', sorter: sortBy(s => s.p95Ms), render: (_, row) => formatMs(row.stats.p95Ms) },
    { title: 'P99', key: 'p99', align: 'right', sorter: sortBy(s => s.p99Ms), render: (_, row) => formatMs(row.stats.p99Ms) },
    { title: '最大', key: 'max', align: 'right', sorter: sortBy(s => s.maxMs), render: (_, row) => formatMs(row.stats.maxMs) },
  ]
  return <>
    <Space wrap className="perf-table-filters">
      <Input.Search allowClear placeholder="搜索功能名或接口" value={keyword} onChange={event => setKeyword(event.target.value)}
        style={{ width: 240 }} />
      <Select allowClear placeholder="全部模块" value={module} onChange={setModule} style={{ width: 160 }}
        options={modules.map(value => ({ value, label: value }))} />
    </Space>
    <ContentFitTable<PerformanceFeature> size="small" rowKey={row => `${row.kind}-${row.key}`} columns={columns}
      dataSource={filtered} locale={{ emptyText }} pagination={clientTablePagination(filtered.length)} />
  </>
}

// ------------------------------------------------------------------ 实时调用

function LiveTab() {
  const [windowSeconds, setWindowSeconds] = useState(60)
  const [custom, setCustom] = useState(false)
  const [customValue, setCustomValue] = useState<number | null>(120)
  const [auto, setAuto] = useState(true)
  const { data, loading, error, reload, refresh } = useLoad<PerformanceLive | null>(
    () => api<PerformanceLive>({ url: '/performance/live', params: { windowSeconds } }), null, [windowSeconds])

  useEffect(() => {
    if (!auto) return
    const timer = window.setInterval(() => { void refresh() }, LIVE_REFRESH_MS)
    return () => window.clearInterval(timer)
  }, [auto, refresh])

  const preset = LIVE_WINDOWS.some(option => option.value === windowSeconds) && !custom ? windowSeconds : 'custom'
  const recentColumns: TableColumnsType<PerformanceCall> = [
    { title: '时间', dataIndex: 'at', render: (value: number) => dayjs(value).format('HH:mm:ss.SSS') },
    { title: '功能', key: 'name', render: (_, row) => <TableEllipsisText value={featureName(row)} maxWidth={240} /> },
    { title: '模块', dataIndex: 'module', render: (value?: string | null) => value || '—' },
    {
      title: '结果', dataIndex: 'status',
      render: (status: number, row) => row.kind === 'IMAGE'
        ? <Tag color={status < 500 ? 'green' : 'red'}>{status < 500 ? '成功' : '失败'}</Tag>
        : <Tag color={status >= 500 ? 'red' : status >= 400 ? 'orange' : 'green'}>{status}</Tag>,
    },
    {
      title: '耗时', dataIndex: 'durationMs', align: 'right', sorter: (a, b) => a.durationMs - b.durationMs,
      render: (value: number) => formatMs(value),
    },
    { title: '说明', dataIndex: 'detail', render: (value?: string | null) => <TableEllipsisText value={value} maxWidth={320} /> },
  ]

  return <Space orientation="vertical" size={16} className="perf-tab">
    <div className="perf-toolbar">
      <Space wrap>
        <Typography.Text type="secondary">统计最近</Typography.Text>
        <Segmented
          value={preset}
          options={[...LIVE_WINDOWS.map(option => ({ value: option.value as number | 'custom', label: option.label })),
            { value: 'custom', label: '自定义' }]}
          onChange={value => {
            if (value === 'custom') {
              setCustom(true)
              const next = clampLiveWindow(customValue)
              if (next) setWindowSeconds(next)
            } else {
              setCustom(false)
              setWindowSeconds(value as number)
            }
          }}
        />
        {preset === 'custom' && <InputNumber min={MIN_LIVE_WINDOW_SECONDS} max={MAX_LIVE_WINDOW_SECONDS}
          value={customValue} addonAfter="秒" style={{ width: 140 }}
          onChange={value => setCustomValue(value)}
          onBlur={() => { const next = clampLiveWindow(customValue); if (next) { setCustomValue(next); setWindowSeconds(next) } }}
          onPressEnter={() => { const next = clampLiveWindow(customValue); if (next) { setCustomValue(next); setWindowSeconds(next) } }} />}
      </Space>
      <Space wrap>
        <Space size={6}><Switch size="small" checked={auto} onChange={setAuto} /><Typography.Text type="secondary">每 5 秒刷新</Typography.Text></Space>
        <Button icon={<ReloadOutlined />} onClick={() => void reload()}>刷新</Button>
      </Space>
    </div>
    <DataState loading={loading && !data} error={data ? undefined : error} onRetry={() => void reload()}>
      {data && <>
        {data.truncated && <Alert showIcon type="warning"
          title={`流量较大，这个窗口里只保留了最近 ${data.capacity.toLocaleString()} 次调用，更早的已被挤掉，统计只算保留下来的部分。`} />}
        <Card size="small" title={`接口调用 · 最近 ${describeWindow(data.windowSeconds)}`}>
          <StatGrid items={[
            { label: '调用次数', value: data.requests.count.toLocaleString() },
            { label: '每秒调用', value: data.callsPerSecond.toFixed(2) },
            {
              label: '失败（5xx）', value: data.requests.errors.toLocaleString(),
              tone: data.requests.errors ? 'warn' : undefined,
            },
            { label: '平均', value: msOf(data.requests, data.requests.avgMs) },
            ...percentileItems(data.requests),
            { label: '最大', value: msOf(data.requests, data.requests.maxMs) },
          ]} />
        </Card>
        <Card size="small" title="图片处理（缩略图 / 压缩原图）">
          <div className="perf-image-row">
            <div>
              <Typography.Text strong>生成缩略图</Typography.Text>
              <StatGrid items={[
                { label: '次数', value: data.thumbnails.count.toLocaleString() },
                { label: '平均', value: msOf(data.thumbnails, data.thumbnails.avgMs) },
                ...percentileItems(data.thumbnails, false),
                { label: '最大', value: msOf(data.thumbnails, data.thumbnails.maxMs) },
              ]} />
            </div>
            <div>
              <Typography.Text strong>压缩原图</Typography.Text>
              <StatGrid items={[
                { label: '次数', value: data.compressions.count.toLocaleString() },
                { label: '平均', value: msOf(data.compressions, data.compressions.avgMs) },
                ...percentileItems(data.compressions, false),
                { label: '最大', value: msOf(data.compressions, data.compressions.maxMs) },
              ]} />
            </div>
          </div>
        </Card>
        <Card size="small" title="按功能汇总">
          <FeatureTable rows={data.features} showP90 emptyText="这段时间里没有接口调用" />
        </Card>
        <Card size="small" title={`调用明细（最新 ${data.recent.length} 条）`}>
          <ContentFitTable<PerformanceCall> size="small" rowKey={(row, index) => `${row.at}-${row.key}-${index}`}
            columns={recentColumns} dataSource={data.recent} locale={{ emptyText: '这段时间里没有调用' }}
            pagination={clientTablePagination(data.recent.length)} />
        </Card>
        <Typography.Text type="secondary" className="perf-footnote">
          只统计本实例、自上次启动以来的调用；静态资源、浏览器直连 OSS 的上传/下载和本面板自己的轮询不计入。
          分位数按窗口内全部调用精确计算。
        </Typography.Text>
      </>}
    </DataState>
  </Space>
}

// ------------------------------------------------------------------ 一周分位数

function WeeklyTab() {
  const { data, loading, error, reload } = useLoad<PerformanceWeekly | null>(
    () => api<PerformanceWeekly>({ url: '/performance/weekly' }), null, [])
  const [metric, setMetric] = useState<'p50Ms' | 'p95Ms' | 'p99Ms'>('p95Ms')
  return <Space orientation="vertical" size={16} className="perf-tab">
    <div className="perf-toolbar">
      <Typography.Text type="secondary">
        {data ? `${time(data.from)} 至 ${time(data.to)}` : '最近 7 天'}
      </Typography.Text>
      <Button icon={<ReloadOutlined />} onClick={() => void reload()}>刷新</Button>
    </div>
    <DataState loading={loading} error={error} onRetry={() => void reload()}>
      {data && <>
        <Card size="small" title="接口响应时间（全部接口合并）">
          <StatGrid items={[
            ...percentileItems(data.requests),
            { label: '平均', value: msOf(data.requests, data.requests.avgMs) },
            { label: '最大', value: msOf(data.requests, data.requests.maxMs) },
            { label: '调用次数', value: data.requests.count.toLocaleString() },
            {
              label: '失败率（5xx）',
              value: errorRate(data.requests.count, data.requests.errors)?.toFixed(2).concat('%') ?? '—',
              tone: data.requests.errors ? 'warn' : undefined,
            },
          ]} />
        </Card>
        <div className="perf-image-row">
          <Card size="small" title="生成缩略图耗时">
            <StatGrid items={[...percentileItems(data.thumbnails),
              { label: '次数', value: data.thumbnails.count.toLocaleString() }]} />
          </Card>
          <Card size="small" title="压缩原图耗时">
            <StatGrid items={[...percentileItems(data.compressions),
              { label: '次数', value: data.compressions.count.toLocaleString() }]} />
          </Card>
        </div>
        <Card size="small" title="每天走势" extra={<Segmented size="small" value={metric} onChange={value => setMetric(value as typeof metric)}
          options={[{ value: 'p50Ms', label: 'P50' }, { value: 'p95Ms', label: 'P95' }, { value: 'p99Ms', label: 'P99' }]} />}>
          <div className="perf-trend-row">
            <div>
              <Typography.Text type="secondary">接口</Typography.Text>
              <TrendChart kind="ms" label={`每天接口 ${metric.replace('Ms', '').toUpperCase()}`}
                points={data.daily.map(point => ({ label: dayjs(point.day).format('MM-DD'), value: point.requests[metric] }))} />
            </div>
            <div>
              <Typography.Text type="secondary">生成缩略图</Typography.Text>
              <TrendChart kind="ms" label={`每天缩略图 ${metric.replace('Ms', '').toUpperCase()}`}
                points={data.daily.map(point => ({ label: dayjs(point.day).format('MM-DD'), value: point.thumbnails[metric] }))} />
            </div>
          </div>
        </Card>
        <Card size="small" title="各功能分位数">
          <FeatureTable rows={data.features} showP90 emptyText="最近 7 天没有接口调用记录" />
        </Card>
        <Typography.Text type="secondary" className="perf-footnote">
          按每小时耗时直方图合并，分位数误差不超过 8%（取所在区间上界，且不超过实测最大值）；每分钟落库一次，重启最多丢失最近一分钟。
          需要逐次明细请使用「性能快照」。
        </Typography.Text>
      </>}
    </DataState>
  </Space>
}

// ------------------------------------------------------------------ 性能快照

function SnapshotTab() {
  const { message, modal } = App.useApp()
  const [preset, setPreset] = useState<number | 'custom'>(60)
  const [hours, setHours] = useState<number | null>(2)
  const [minutes, setMinutes] = useState<number | null>(0)
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [now, setNow] = useState(() => Date.now())

  const overview = useLoad<PerformanceSnapshotOverview | null>(
    () => api<PerformanceSnapshotOverview>({ url: '/performance/snapshots/current' }), null, [])
  const history = useLoad(
    () => api<PageData<PerformanceSnapshot>>({ url: '/performance/snapshots', params: { page: 1, pageSize: 100 } }),
    emptyPage<PerformanceSnapshot>(), [])
  const current = overview.data?.current ?? null
  const maxMinutes = overview.data?.maxDurationMinutes ?? 72 * 60
  const exporting = history.data.items.some(item => item.status === 'EXPORTING')

  const refreshAll = useRef(() => {})
  refreshAll.current = () => { void overview.refresh(); void history.refresh() }
  useEffect(() => {
    if (!current && !exporting) return
    const timer = window.setInterval(() => refreshAll.current(), 3000)
    return () => window.clearInterval(timer)
  }, [current, exporting])
  useEffect(() => {
    if (!current) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [current])

  const duration = preset === 'custom' ? snapshotMinutes(hours, minutes, maxMinutes) : preset

  const start = () => {
    if (!duration) {
      message.error(`记录时长须在 1 分钟到 ${describeMinutes(maxMinutes)}之间`)
      return
    }
    modal.confirm({
      title: `开始记录 ${describeMinutes(duration)}的性能快照？`,
      content: <Space orientation="vertical">
        <span>记录期间每一条 SQL、每一次对象存储操作、每次图片处理和 HTTP 请求都会写进数据库，
          另外每 {overview.data?.sampleIntervalSeconds ?? 5} 秒采集一次 CPU、内存和连接池。这会给数据库带来额外写入。</span>
        <span>到点自动停止，也可以随时手动停止。停止后自动导出 CSV 到对象存储并删除库内明细；CSV 永久保留。</span>
      </Space>,
      okText: '开始记录',
      onOk: async () => {
        setBusy(true)
        try {
          await api<PerformanceSnapshot>({ method: 'POST', url: '/performance/snapshots', data: { durationMinutes: duration, note: note.trim() || null } })
          message.success('性能快照已开始记录')
          setNote('')
          refreshAll.current()
        } catch (e) {
          message.error((e as Error).message)
        } finally {
          setBusy(false)
        }
      },
    })
  }

  const stop = (snapshot: PerformanceSnapshot) => modal.confirm({
    title: '停止这次性能快照？',
    content: '停止后会立刻导出 CSV 到对象存储，并删除数据库里的明细。',
    okText: '停止并导出',
    okButtonProps: { danger: true },
    onOk: async () => {
      try {
        await api<PerformanceSnapshot>({ method: 'POST', url: `/performance/snapshots/${snapshot.id}/stop` })
        message.success('已停止，正在导出 CSV')
        refreshAll.current()
      } catch (e) {
        message.error((e as Error).message)
      }
    },
  })

  const retry = async (snapshot: PerformanceSnapshot) => {
    try {
      await api<PerformanceSnapshot>({ method: 'POST', url: `/performance/snapshots/${snapshot.id}/retry-export` })
      message.success('已重新开始导出')
      refreshAll.current()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const download = async (snapshot: PerformanceSnapshot, file: 'events' | 'samples') => {
    try {
      const link = await api<PerformanceDownload>({ url: `/performance/snapshots/${snapshot.id}/download`, params: { file } })
      window.open(link.url, '_blank', 'noopener')
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const columns: TableColumnsType<PerformanceSnapshot> = [
    { title: '开始时间', dataIndex: 'startedAt', render: time },
    {
      title: '时长', key: 'duration',
      render: (_, row) => {
        const end = row.endedAt ?? row.plannedEndAt
        const actual = Math.max(0, Math.round((new Date(end).getTime() - new Date(row.startedAt).getTime()) / 60000))
        return row.endedAt ? describeMinutes(actual) : `计划 ${describeMinutes(actual)}`
      },
    },
    { title: '发起人', dataIndex: 'startedByName', render: (value?: string | null) => value || '—' },
    { title: '备注', dataIndex: 'note', render: (value?: string | null) => <TableEllipsisText value={value} maxWidth={200} /> },
    {
      title: '状态', dataIndex: 'status',
      render: (status: PerformanceSnapshot['status'], row) => <Space size={4}>
        <Tag color={SNAPSHOT_STATUS[status].color}>{SNAPSHOT_STATUS[status].text}</Tag>
        {row.errorMessage && <Tooltip title={row.errorMessage}><InfoCircleOutlined /></Tooltip>}
      </Space>,
    },
    {
      title: '停止原因', dataIndex: 'stopReason',
      render: (reason: PerformanceSnapshot['stopReason'], row) => reason
        ? `${STOP_REASON_LABELS[reason]}${row.stoppedByName ? `（${row.stoppedByName}）` : ''}` : '—',
    },
    { title: '事件', dataIndex: 'eventCount', align: 'right', render: (value: number) => value.toLocaleString() },
    { title: '采样', dataIndex: 'sampleCount', align: 'right', render: (value: number) => value.toLocaleString() },
    {
      title: '丢弃', dataIndex: 'droppedCount', align: 'right',
      render: (value: number) => value ? <Tooltip title="写库跟不上时丢弃的事件，CSV 不完整">
        <Typography.Text type="warning">{value.toLocaleString()}</Typography.Text></Tooltip> : 0,
    },
    {
      title: '操作', key: 'actions', fixed: 'right',
      render: (_, row) => <Space size={4} wrap>
        {row.downloadable && <>
          <Button size="small" icon={<CloudDownloadOutlined />} onClick={() => void download(row, 'events')}>
            事件明细{row.eventsSize != null ? `（${formatBytes(row.eventsSize)}）` : ''}
          </Button>
          <Button size="small" icon={<CloudDownloadOutlined />} onClick={() => void download(row, 'samples')}>
            系统指标{row.samplesSize != null ? `（${formatBytes(row.samplesSize)}）` : ''}
          </Button>
        </>}
        {row.status === 'FAILED' && <Button size="small" icon={<RedoOutlined />} onClick={() => void retry(row)}>重新导出</Button>}
        {row.status === 'RECORDING' && <Button size="small" danger icon={<PauseCircleOutlined />} onClick={() => stop(row)}>停止</Button>}
      </Space>,
    },
  ]

  return <Space orientation="vertical" size={16} className="perf-tab">
    <DataState loading={overview.loading} error={overview.error} onRetry={() => void overview.reload()}>
      {current
        ? <Card size="small" className="perf-recording" title={<Space><span className="perf-recording-dot" />正在记录性能快照</Space>}
          extra={<Button danger icon={<PauseCircleOutlined />} onClick={() => stop(current)}>停止记录</Button>}>
          <Progress percent={Math.round(progressPercent(current.startedAt, current.plannedEndAt, now))}
            status="active" format={() => `剩余 ${remaining(current.plannedEndAt, now)}`} />
          <StatGrid items={[
            { label: '开始', value: dayjs(current.startedAt).format('MM-DD HH:mm') },
            { label: '计划结束', value: dayjs(current.plannedEndAt).format('MM-DD HH:mm') },
            { label: '已记事件', value: current.eventCount.toLocaleString() },
            { label: '系统采样', value: current.sampleCount.toLocaleString() },
            { label: '待写入', value: current.queuedEvents.toLocaleString() },
            {
              label: '丢弃', value: current.droppedCount.toLocaleString(),
              hint: '写库跟不上时丢弃的事件', tone: current.droppedCount ? 'warn' : undefined,
            },
          ]} />
          <Typography.Text type="secondary">
            发起人 {current.startedByName ?? '—'}{current.note ? ` · ${current.note}` : ''}
            {overview.data && ` · 事件数达到 ${overview.data.maxEvents.toLocaleString()} 条时也会自动停止`}
          </Typography.Text>
        </Card>
        : <Card size="small" title="开始新的性能快照">
          <Space orientation="vertical" size={12} style={{ width: '100%' }}>
            <Typography.Paragraph type="secondary" className="perf-snapshot-intro">
              打开后系统会详细记录每一次数据库操作（含 ORM 生成的 SQL，不含参数值）、对象存储操作、
              缩略图生成和原图压缩的耗时，以及 CPU、内存、数据库连接池和图片处理队列，用来判断调优方向和是否需要扩容。
              最长 {describeMinutes(maxMinutes)}，到点自动停止；停止后 CSV 原始文件上传到对象存储供下载并永久保留，数据库里的明细随即删除。
            </Typography.Paragraph>
            <Space wrap align="center">
              <Typography.Text>记录时长</Typography.Text>
              <Select value={preset} style={{ width: 140 }} onChange={setPreset}
                options={[...SNAPSHOT_DURATIONS.filter(option => option.value <= maxMinutes)
                  .map(option => ({ value: option.value as number | 'custom', label: option.label })),
                { value: 'custom', label: '自定义' }]} />
              {preset === 'custom' && <>
                <InputNumber min={0} max={Math.floor(maxMinutes / 60)} value={hours} onChange={setHours} addonAfter="小时" style={{ width: 130 }} />
                <InputNumber min={0} max={59} value={minutes} onChange={setMinutes} addonAfter="分钟" style={{ width: 130 }} />
              </>}
              <Input placeholder="备注（可选），如：压测前基线" maxLength={200} value={note}
                onChange={event => setNote(event.target.value)} style={{ width: 260 }} />
              <Button type="primary" icon={<PlayCircleOutlined />} loading={busy} disabled={!duration} onClick={start}>
                开始记录
              </Button>
            </Space>
            {preset === 'custom' && !duration && <Typography.Text type="danger">
              时长须在 1 分钟到 {describeMinutes(maxMinutes)}之间
            </Typography.Text>}
          </Space>
        </Card>}
    </DataState>
    <Card size="small" title="快照记录" extra={<Button size="small" icon={<ReloadOutlined />} onClick={() => refreshAll.current()}>刷新</Button>}>
      <DataState loading={history.loading} error={history.error} onRetry={() => void history.reload()}>
        {history.data.items.length
          ? <ContentFitTable<PerformanceSnapshot> size="small" rowKey="id" columns={columns}
            dataSource={history.data.items} pagination={clientTablePagination(history.data.items.length)} />
          : <Empty description="还没有性能快照" />}
      </DataState>
    </Card>
  </Space>
}

export default function PerformancePage() {
  const [tab, setTab] = useState('live')
  return <Tabs activeKey={tab} onChange={setTab} destroyOnHidden items={[
    { key: 'live', label: '实时调用', children: <LiveTab /> },
    { key: 'weekly', label: '一周分位数', children: <WeeklyTab /> },
    { key: 'snapshot', label: '性能快照', children: <SnapshotTab /> },
  ]} />
}
