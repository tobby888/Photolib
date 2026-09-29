import { useLayoutEffect, useRef, useState, type PointerEvent } from 'react'
import { formatMetric, type ChartPoint, type MetricKind } from './adminDashboard'

const PAD_LEFT = 52
const PAD_RIGHT = 12
const PAD_TOP = 12
const PAD_BOTTOM = 26

/** 取一个「好看」的纵轴上限：1、2、2.5、5 乘以 10 的幂，保证刻度是整齐的数。 */
function niceMax(value: number) {
  if (value <= 0) return 1
  const power = 10 ** Math.floor(Math.log10(value))
  const step = [1, 2, 2.5, 5, 10].find(candidate => candidate * power >= value) ?? 10
  return step * power
}

/**
 * 数据面板的趋势图：单条折线 + 浅色面积，一条纵轴。只画一个序列，所以不需要图例——
 * 小面板的标题就是它的名字。悬停时给出十字线和数值提示；屏幕阅读器读的是隐藏的表格。
 */
export default function TrendChart({ points, kind, label, height = 176 }: {
  points: ChartPoint[]
  kind: MetricKind
  label: string
  height?: number
}) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)
  const [hover, setHover] = useState<number | null>(null)

  useLayoutEffect(() => {
    const element = containerRef.current
    if (!element) return
    const measure = () => setWidth(element.clientWidth)
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  const max = niceMax(Math.max(0, ...points.map(point => point.value)))
  const plotWidth = Math.max(0, width - PAD_LEFT - PAD_RIGHT)
  const plotHeight = height - PAD_TOP - PAD_BOTTOM
  const x = (index: number) => PAD_LEFT + (points.length <= 1 ? plotWidth / 2 : index / (points.length - 1) * plotWidth)
  const y = (value: number) => PAD_TOP + plotHeight - value / max * plotHeight
  const line = points.map((point, index) => `${index ? 'L' : 'M'}${x(index).toFixed(1)},${y(point.value).toFixed(1)}`).join('')
  const area = points.length
    ? `${line}L${x(points.length - 1).toFixed(1)},${y(0)}L${x(0).toFixed(1)},${y(0)}Z`
    : ''
  // 计数类的中间刻度落在小数上（上限 1 → 0.5）会被四舍五入成和上限一样的字，干脆不画。
  const ticks = kind === 'count' && !Number.isInteger(max / 2) ? [0, max] : [0, max / 2, max]
  const xLabels = points.length
    ? [0, Math.floor((points.length - 1) / 2), points.length - 1]
    : []
  const active = hover == null ? null : points[hover]

  const onPointerMove = (event: PointerEvent<SVGSVGElement>) => {
    if (!points.length || plotWidth <= 0) return
    const bounds = event.currentTarget.getBoundingClientRect()
    const offset = event.clientX - bounds.left - PAD_LEFT
    const index = Math.round(offset / plotWidth * (points.length - 1))
    setHover(Math.max(0, Math.min(points.length - 1, index)))
  }

  return <div className="trend-chart" ref={containerRef} style={{ height }}>
    {width > 0 && <svg width={width} height={height} role="img" aria-label={label}
      onPointerMove={onPointerMove} onPointerLeave={() => setHover(null)}>
      {ticks.map(tick => <g key={tick} className="trend-chart-grid">
        <line x1={PAD_LEFT} x2={width - PAD_RIGHT} y1={y(tick)} y2={y(tick)} />
        <text x={PAD_LEFT - 8} y={y(tick)} dy="0.32em" textAnchor="end">{formatMetric(kind, tick)}</text>
      </g>)}
      {xLabels.map((index, order) => <text key={`${index}-${order}`} className="trend-chart-axis"
        x={x(index)} y={height - 6}
        textAnchor={order === 0 ? 'start' : order === xLabels.length - 1 ? 'end' : 'middle'}>
        {points[index].label}
      </text>)}
      <path className="trend-chart-area" d={area} />
      <path className="trend-chart-line" d={line} />
      {active && hover != null && <g className="trend-chart-hover">
        <line x1={x(hover)} x2={x(hover)} y1={PAD_TOP} y2={PAD_TOP + plotHeight} />
        <circle cx={x(hover)} cy={y(active.value)} r={4} />
      </g>}
    </svg>}
    {active && hover != null && <div className="trend-chart-tooltip" style={{
      left: Math.min(Math.max(x(hover), 70), Math.max(70, width - 70)),
    }}>
      <span>{active.label}</span>
      <strong>{formatMetric(kind, active.value)}</strong>
    </div>}
    <table className="visually-hidden">
      <caption>{label}</caption>
      <tbody>{points.map((point, index) => <tr key={index}>
        <th scope="row">{point.label}</th><td>{formatMetric(kind, point.value)}</td>
      </tr>)}</tbody>
    </table>
  </div>
}
