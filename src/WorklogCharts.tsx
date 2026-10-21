import { useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import type { AnalysisBucket } from './worklogAnalysis'

/**
 * 工时分析用的几张图。都是手写 SVG（与 `TrendChart` 同一套做法），不引图表库：
 * 首屏体积有预算（AGENTS §2.18），而这里只要柱、条、热力格三种形状。
 *
 * 约定（见 dataviz 规范）：一张图只有一条纵轴；两个序列（拍摄 / 修图）用固定顺序的两种颜色，
 * 颜色在 CSS 变量里（`--viz-series-1/2`，已用校验脚本验过色盲可辨）；文字一律用文字色，
 * 不用序列色；每张图都有悬停提示，和一份给屏幕阅读器的隐藏表格。
 */

export interface ChartSeries {
  key: 'shootingMinutes' | 'retouchingMinutes' | 'totalMinutes' | 'worklogCount'
  label: string
  color: string
  /** 计数序列的单位，默认「条」；被引分析借这几张图画张数 / 次数时传「张」「次」。 */
  unit?: string
}

export const HOUR_SERIES: ChartSeries[] = [
  { key: 'shootingMinutes', label: '拍摄', color: 'var(--viz-series-1)' },
  { key: 'retouchingMinutes', label: '修图', color: 'var(--viz-series-2)' },
]

const formatHours = (minutes: number) => `${Number((minutes / 60).toFixed(1))} 小时`
export const formatValue = (series: ChartSeries, value: number) =>
  series.key === 'worklogCount' ? `${value} ${series.unit ?? '条'}` : formatHours(value)

function useWidth() {
  const ref = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)
  useLayoutEffect(() => {
    const element = ref.current
    if (!element) return
    const measure = () => setWidth(element.clientWidth)
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [])
  return [ref, width] as const
}

/** 纵轴上限取「好看」的整数：1、2、2.5、5 乘以 10 的幂。 */
function niceMax(value: number) {
  if (value <= 0) return 1
  const power = 10 ** Math.floor(Math.log10(value))
  const step = [1, 2, 2.5, 5, 10].find(candidate => candidate * power >= value) ?? 10
  return step * power
}

/** 只把远离基线的那一端做成 4px 圆角，贴着基线的一端保持直角。 */
function roundedEnd(x: number, y: number, w: number, h: number, r: number, end: 'top' | 'right') {
  const radius = Math.max(0, Math.min(r, end === 'top' ? h : w, (end === 'top' ? w : h) / 2))
  if (end === 'top') {
    return `M${x},${y + h}V${y + radius}Q${x},${y} ${x + radius},${y}H${x + w - radius}`
      + `Q${x + w},${y} ${x + w},${y + radius}V${y + h}Z`
  }
  return `M${x},${y}H${x + w - radius}Q${x + w},${y} ${x + w},${y + radius}V${y + h - radius}`
    + `Q${x + w},${y + h} ${x + w - radius},${y + h}H${x}Z`
}

export function ChartLegend({ series }: { series: ChartSeries[] }) {
  if (series.length < 2) return null
  return <div className="viz-legend">{series.map(item => <span key={item.key}>
    <i style={{ background: item.color }} />{item.label}
  </span>)}</div>
}

function Tooltip({ x, width, children }: { x: number; width: number; children: ReactNode }) {
  return <div className="viz-tooltip" style={{ left: Math.min(Math.max(x, 80), Math.max(80, width - 80)) }}>
    {children}
  </div>
}

function TooltipBody({ bucket, series }: { bucket: AnalysisBucket; series: ChartSeries[] }) {
  return <>
    <span>{bucket.label}</span>
    {series.map(item => <div key={item.key} className="viz-tooltip-row">
      {series.length > 1 && <i style={{ background: item.color }} />}
      <em>{item.label}</em><strong>{formatValue(item, bucket[item.key])}</strong>
    </div>)}
    {series.length > 1 && <div className="viz-tooltip-row"><em>合计</em>
      <strong>{formatHours(bucket.totalMinutes)}</strong></div>}
    <div className="viz-tooltip-row"><em>工时条数</em><strong>{bucket.worklogCount} 条</strong></div>
  </>
}

function HiddenTable({ label, buckets, series }: { label: string; buckets: AnalysisBucket[]; series: ChartSeries[] }) {
  return <table className="visually-hidden">
    <caption>{label}</caption>
    <thead><tr><th scope="col">分组</th>{series.map(item => <th key={item.key} scope="col">{item.label}</th>)}</tr></thead>
    <tbody>{buckets.map(bucket => <tr key={bucket.key}>
      <th scope="row">{bucket.label}</th>
      {series.map(item => <td key={item.key}>{formatValue(item, bucket[item.key])}</td>)}
    </tr>)}</tbody>
  </table>
}

const PAD = { left: 48, right: 8, top: 10, bottom: 28 }

/**
 * 纵向堆叠柱：趋势（按日 / 周 / 月）、按星期、时长分布都用它。
 * 横轴标签放不下时隔几个画一个，悬停照样能看每一根。
 */
export function ColumnChart({ buckets, series, label, height = 220, axisLabel = (bucket: AnalysisBucket) => bucket.label, tooltip }: {
  buckets: AnalysisBucket[]
  series: ChartSeries[]
  label: string
  height?: number
  axisLabel?: (bucket: AnalysisBucket) => string
  /** 换掉默认的悬停提示内容（默认是工时的拍摄 / 修图 / 条数）。 */
  tooltip?: (bucket: AnalysisBucket) => ReactNode
}) {
  const [ref, width] = useWidth()
  const [hover, setHover] = useState<number | null>(null)
  const sum = (bucket: AnalysisBucket) => series.reduce((total, item) => total + bucket[item.key], 0)
  const unit = series[0]?.key === 'worklogCount'
  const largest = Math.max(0, ...buckets.map(sum))
  // 时长按分钟存、按小时读：刻度要在「小时」上取整，否则会出现 16.7h 这种刻度。
  const max = unit ? niceMax(largest) : niceMax(largest / 60) * 60
  const plotWidth = Math.max(0, width - PAD.left - PAD.right)
  const plotHeight = height - PAD.top - PAD.bottom
  const slot = buckets.length ? plotWidth / buckets.length : 0
  const barWidth = Math.max(2, Math.min(36, slot * 0.68))
  const y = (value: number) => PAD.top + plotHeight - value / max * plotHeight
  const tickLabel = (value: number) => unit ? String(value) : `${Number((value / 60).toFixed(1))}h`
  const labelEvery = Math.max(1, Math.ceil(buckets.length / Math.max(1, Math.floor(plotWidth / 64))))
  const active = hover == null ? null : buckets[hover]

  return <div className="viz-chart" ref={ref} style={{ height }}>
    {width > 0 && <svg width={width} height={height} role="img" aria-label={label}
      onPointerLeave={() => setHover(null)}>
      {[0, max / 2, max].map(tick => <g key={tick} className="viz-grid">
        <line x1={PAD.left} x2={width - PAD.right} y1={y(tick)} y2={y(tick)} />
        <text x={PAD.left - 8} y={y(tick)} dy="0.32em" textAnchor="end">{tickLabel(tick)}</text>
      </g>)}
      {buckets.map((bucket, index) => {
        const x = PAD.left + index * slot + (slot - barWidth) / 2
        let base = 0
        const visible = series.filter(item => bucket[item.key] > 0)
        return <g key={bucket.key}>
          {visible.map((item, order) => {
            const top = y(base + bucket[item.key])
            const bottom = y(base)
            base += bucket[item.key]
            // 段与段之间留 2px 底色缝，最下面那段贴着基线。
            const gap = order > 0 ? 2 : 0
            const h = Math.max(0, bottom - top - gap)
            return order === visible.length - 1
              ? <path key={item.key} d={roundedEnd(x, top, barWidth, h, 4, 'top')} fill={item.color} />
              : <rect key={item.key} x={x} y={top} width={barWidth} height={h} fill={item.color} />
          })}
          {/* 命中区比柱子宽：整个槽位都能悬停。 */}
          <rect className="viz-hit" x={PAD.left + index * slot} y={PAD.top} width={slot} height={plotHeight}
            onPointerEnter={() => setHover(index)} />
          {index % labelEvery === 0 && <text className="viz-axis" x={x + barWidth / 2} y={height - 8}
            textAnchor="middle">{axisLabel(bucket)}</text>}
        </g>
      })}
      <line className="viz-baseline" x1={PAD.left} x2={width - PAD.right} y1={y(0)} y2={y(0)} />
      {hover != null && <rect className="viz-hover-band" x={PAD.left + hover * slot} y={PAD.top}
        width={slot} height={plotHeight} />}
    </svg>}
    {active && hover != null && <Tooltip x={PAD.left + (hover + 0.5) * slot} width={width}>
      {tooltip ? tooltip(active) : <TooltipBody bucket={active} series={series} />}
    </Tooltip>}
    <HiddenTable label={label} buckets={buckets} series={series} />
  </div>
}

/** 横向堆叠条：按成员 / 校区 / 选题的排行。名字在左、合计在右，条本身只表达长短。 */
export function RankingChart({ buckets, series, label, tooltip, onSelect }: {
  buckets: AnalysisBucket[]
  series: ChartSeries[]
  label: string
  tooltip?: (bucket: AnalysisBucket) => ReactNode
  /** 点一行时调用（比如点开那位成员的详情）；不传就不可点。 */
  onSelect?: (bucket: AnalysisBucket) => void
}) {
  const [ref, width] = useWidth()
  const [hover, setHover] = useState<number | null>(null)
  const rowHeight = 28
  const labelWidth = Math.min(160, Math.max(96, width * 0.28))
  const valueWidth = 72
  const plotWidth = Math.max(0, width - labelWidth - valueWidth - 12)
  const sum = (bucket: AnalysisBucket) => series.reduce((total, item) => total + bucket[item.key], 0)
  const max = Math.max(1, ...buckets.map(sum))
  const height = buckets.length * rowHeight + 4
  const active = hover == null ? null : buckets[hover]

  return <div className="viz-chart" ref={ref} style={{ height }}>
    {width > 0 && <svg width={width} height={height} role="img" aria-label={label}
      onPointerLeave={() => setHover(null)}>
      {buckets.map((bucket, index) => {
        const top = index * rowHeight + 6
        const barHeight = rowHeight - 12
        let left = labelWidth
        const visible = series.filter(item => bucket[item.key] > 0)
        return <g key={bucket.key}>
          {hover === index && <rect className="viz-hover-band" x={0} y={index * rowHeight} width={width} height={rowHeight} />}
          <text className="viz-row-label" x={labelWidth - 8} y={top + barHeight / 2} dy="0.32em" textAnchor="end">
            {bucket.label.length > 12 ? `${bucket.label.slice(0, 11)}…` : bucket.label}
          </text>
          {visible.map((item, order) => {
            const w = bucket[item.key] / max * plotWidth
            const x = left + (order > 0 ? 2 : 0)
            left += w
            const segment = Math.max(0, w - (order > 0 ? 2 : 0))
            return order === visible.length - 1
              ? <path key={item.key} d={roundedEnd(x, top, segment, barHeight, 4, 'right')} fill={item.color} />
              : <rect key={item.key} x={x} y={top} width={segment} height={barHeight} fill={item.color} />
          })}
          <text className="viz-row-value" x={width - 4} y={top + barHeight / 2} dy="0.32em" textAnchor="end">
            {formatValue(series.length > 1 ? { key: 'totalMinutes', label: '', color: '' } : series[0], sum(bucket))}
          </text>
          <rect className={onSelect ? 'viz-hit viz-hit-clickable' : 'viz-hit'} x={0} y={index * rowHeight}
            width={width} height={rowHeight} onPointerEnter={() => setHover(index)}
            onClick={onSelect ? () => onSelect(bucket) : undefined} />
        </g>
      })}
    </svg>}
    {active && hover != null && <div className="viz-tooltip" style={{
      left: Math.min(labelWidth + plotWidth / 2, Math.max(80, width - 80)), top: hover * rowHeight + rowHeight,
    }}>{tooltip ? tooltip(active) : <TooltipBody bucket={active} series={series} />}</div>}
    <HiddenTable label={label} buckets={buckets} series={series} />
  </div>
}

/** 100% 构成条：拍摄 / 修图、审核状态、来源这种「几块拼成一个整体」的比例。每块都写明名称和占比。 */
export function ShareBar({ parts, label, format }: {
  parts: { key: string; label: string; value: number; color: string }[]
  label: string
  format: (value: number) => string
}) {
  const total = parts.reduce((sum, part) => sum + part.value, 0)
  if (!total) return <div className="viz-share-empty">暂无数据</div>
  const shown = parts.filter(part => part.value > 0)
  return <div className="viz-share" role="img" aria-label={label}>
    <div className="viz-share-track">{shown.map(part => <span key={part.key}
      style={{ flexGrow: part.value, background: part.color }}
      title={`${part.label}：${format(part.value)}（${Math.round(part.value / total * 100)}%）`} />)}</div>
    <div className="viz-share-labels">{parts.map(part => <span key={part.key}>
      <i style={{ background: part.color }} />{part.label}
      <strong>{format(part.value)}</strong>
      <em>{Math.round(part.value / total * 100)}%</em>
    </span>)}</div>
  </div>
}

const HEAT_STEPS = ['var(--viz-heat-1)', 'var(--viz-heat-2)', 'var(--viz-heat-3)', 'var(--viz-heat-4)', 'var(--viz-heat-5)']
const WEEKDAY_SHORT = ['一', '二', '三', '四', '五', '六', '日']

/**
 * 每日工时热力图（按周排成列，周一在最上）。单一色相由浅到深表示时长；没有工时的日子是底色格。
 * 色阶按非零日子的分位数切五档：用最大值等分的话，一两天特别忙就会把其余日子全压成最浅的一档。
 */
export function CalendarHeatmap({ days, from, to }: { days: AnalysisBucket[]; from: string; to: string }) {
  const [hover, setHover] = useState<{ date: string; minutes: number; count: number; x: number; y: number } | null>(null)
  const byDate = new Map(days.map(day => [day.key, day]))
  const start = new Date(`${from}T00:00:00`)
  const end = new Date(`${to}T00:00:00`)
  const offset = (start.getDay() + 6) % 7
  const totalDays = Math.round((end.getTime() - start.getTime()) / 86_400_000) + 1
  const weeks = Math.ceil((offset + totalDays) / 7)
  const values = days.map(day => day.totalMinutes).filter(value => value > 0).sort((a, b) => a - b)
  const thresholds = [0.2, 0.4, 0.6, 0.8].map(q => values[Math.min(values.length - 1, Math.floor(q * values.length))] ?? 0)
  const step = (minutes: number) => thresholds.filter(threshold => minutes > threshold).length
  const cell = 13
  const gap = 3
  const left = 22
  const width = left + weeks * (cell + gap)
  const height = 7 * (cell + gap) + 18
  const cells: ReactNode[] = []
  const months: ReactNode[] = []
  for (let index = 0; index < totalDays; index += 1) {
    const date = new Date(start.getTime() + index * 86_400_000)
    const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
    const column = Math.floor((offset + index) / 7)
    const row = (offset + index) % 7
    const x = left + column * (cell + gap)
    const y = 16 + row * (cell + gap)
    const day = byDate.get(key)
    const minutes = day?.totalMinutes ?? 0
    if (date.getDate() === 1 || index === 0) {
      months.push(<text key={`m-${key}`} className="viz-axis" x={x} y={10}>{date.getMonth() + 1}月</text>)
    }
    cells.push(<rect key={key} x={x} y={y} width={cell} height={cell} rx={3}
      className={minutes ? undefined : 'viz-heat-empty'}
      fill={minutes ? HEAT_STEPS[step(minutes)] : undefined}
      onPointerEnter={() => setHover({ date: key, minutes, count: day?.worklogCount ?? 0, x, y })} />)
  }
  return <div className="viz-heatmap" onPointerLeave={() => setHover(null)}>
    <div className="viz-heatmap-scroll">
      <svg width={width} height={height} role="img" aria-label="每日工时热力图">
        {months}
        {[0, 2, 4, 6].map(row => <text key={row} className="viz-axis" x={0} y={16 + row * (cell + gap) + cell - 2}>
          {WEEKDAY_SHORT[row]}</text>)}
        {cells}
      </svg>
      {hover && <div className="viz-tooltip" style={{ left: hover.x + cell / 2, top: hover.y + cell + 6 }}>
        <span>{hover.date}</span>
        <div className="viz-tooltip-row"><em>总时长</em><strong>{formatHours(hover.minutes)}</strong></div>
        <div className="viz-tooltip-row"><em>工时条数</em><strong>{hover.count} 条</strong></div>
      </div>}
    </div>
    <div className="viz-heat-legend"><span>少</span>
      <i className="viz-heat-empty-swatch" />{HEAT_STEPS.map(color => <i key={color} style={{ background: color }} />)}
      <span>多</span></div>
    <table className="visually-hidden"><caption>每日工时</caption><tbody>
      {days.map(day => <tr key={day.key}><th scope="row">{day.key}</th><td>{formatHours(day.totalMinutes)}</td></tr>)}
    </tbody></table>
  </div>
}
