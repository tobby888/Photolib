import { ColumnChart, RankingChart } from './WorklogCharts'
import type { ChartSeries } from './WorklogCharts'
import type { AnalysisBucket } from './worklogAnalysis'
import { toChartBuckets } from './adoptionAnalysis'
import type { AdoptionBucket, AdoptionGranularity } from './adoptionAnalysis'

/**
 * 被引分析的柱 / 条形图：借工时分析那两张图（同一套 SVG、配色和隐藏表格），只是画的是计数。
 * 时间分布画**被引次数**（每一次被选中都算），排行画**被引张数**（按图片去重，和成员核算同一个数）。
 */
const TIMES: ChartSeries[] = [{ key: 'worklogCount', label: '被引次数', color: 'var(--viz-series-1)', unit: '次' }]
const PHOTOS: ChartSeries[] = [{ key: 'worklogCount', label: '被引张数', color: 'var(--viz-series-1)', unit: '张' }]

export interface RankingItem {
  key: string
  label: string
  photoCount: number
  adoptionCount: number
  /** 悬停提示里额外的一行，比如「3 个选题」「5 位摄影师」。 */
  extra?: string
}

function Tip({ title, rows }: { title: string; rows: [string, string][] }) {
  return <>
    <span>{title}</span>
    {rows.map(([name, value]) => <div key={name} className="viz-tooltip-row"><em>{name}</em><strong>{value}</strong></div>)}
  </>
}

export function trendAxisLabel(granularity: AdoptionGranularity) {
  return (bucket: AnalysisBucket) => granularity === 'MONTH'
    ? bucket.key.slice(2).replace('-', '/')
    : bucket.key.slice(5).replace('-', '/')
}

export function AdoptionColumnChart({ buckets, label, height, axisLabel }: {
  buckets: AdoptionBucket[]
  label: string
  height?: number
  axisLabel?: (bucket: AnalysisBucket) => string
}) {
  const byKey = new Map(buckets.map(bucket => [bucket.key, bucket]))
  return <ColumnChart buckets={toChartBuckets(buckets, bucket => bucket.adoptionCount)} series={TIMES}
    label={label} height={height} axisLabel={axisLabel} tooltip={chart => {
      const bucket = byKey.get(chart.key)
      return <Tip title={chart.label} rows={[
        ['被引次数', `${bucket?.adoptionCount ?? 0} 次`],
        ['被引张数', `${bucket?.photoCount ?? 0} 张`],
        ['涉及选题', `${bucket?.projectCount ?? 0} 个`],
        ['摄影师', `${bucket?.photographerCount ?? 0} 人`],
      ]} />
    }} />
}

export function AdoptionRankingChart({ items, label, onSelect }: {
  items: RankingItem[]
  label: string
  onSelect?: (item: RankingItem) => void
}) {
  const byKey = new Map(items.map(item => [item.key, item]))
  return <RankingChart buckets={toChartBuckets(items, item => item.photoCount)} series={PHOTOS} label={label}
    onSelect={onSelect && (chart => {
      const item = byKey.get(chart.key)
      if (item && !item.key.startsWith('__')) onSelect(item)
    })}
    tooltip={chart => {
      const item = byKey.get(chart.key)
      const rows: [string, string][] = [
        ['被引张数', `${item?.photoCount ?? 0} 张`],
        ['被引次数', `${item?.adoptionCount ?? 0} 次`],
      ]
      if (item?.extra) rows.push(['', item.extra])
      return <Tip title={chart.label} rows={rows} />
    }} />
}
