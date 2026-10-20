import {
  Alert, App, Button, Card, Col, DatePicker, Empty, Row, Segmented, Select, Space, Statistic, Tag, Typography,
} from 'antd'
import { DownloadOutlined, FilterOutlined, ReloadOutlined } from '@ant-design/icons'
import { useMemo, useState } from 'react'
import dayjs from 'dayjs'
import { api } from './api'
import { DataState, StatusTag, formatMinutes } from './components'
import { ContentFitTable, TableEllipsisText } from './ContentFitTable'
import { useLoad } from './hooks'
import { clientTablePagination } from './pagination'
import WorklogConditionBuilder from './WorklogConditionBuilder'
import { CalendarHeatmap, ChartLegend, ColumnChart, HOUR_SERIES, RankingChart, ShareBar } from './WorklogCharts'
import type { ChartSeries } from './WorklogCharts'
import {
  GROUP_LABELS, STATUS_LABELS, defaultCondition, foldTail, newGroup, newLeaf, toHours, toPayload,
} from './worklogAnalysis'
import type {
  AnalysisBucket, AnalysisOptions, AnalysisResult, AnalysisRow, ConditionGroup, Granularity, GroupDimension,
} from './worklogAnalysis'
import { rowsToCsv } from './worklogCsv'
import { saveBlobAs } from './exportDownload'

const EMPTY_OPTIONS: AnalysisOptions = { projects: [], campuses: [], members: [], submitters: [] }
const COUNT_SERIES: ChartSeries[] = [{ key: 'worklogCount', label: '工时条数', color: 'var(--viz-series-1)' }]
const RANKING_LIMIT = 15

/** 常用条件，一键套用；套用之后照样能在编辑器里改。 */
const PRESETS: { key: string; label: string; build: () => ConditionGroup }[] = [
  { key: 'confirmed', label: '已确认', build: () => defaultCondition() },
  { key: 'pending', label: '待确认（催审核）', build: () => newGroup('AND', [
    { ...newLeaf('STATUS'), operator: 'IN', values: ['SUBMITTED'] }]) },
  { key: 'link', label: '上传链接申报', build: () => newGroup('AND', [
    { ...newLeaf('SOURCE'), operator: 'IN', values: ['SHARE_LINK'] }]) },
  { key: 'long', label: '单条超过 8 小时（核对异常）', build: () => newGroup('AND', [
    { ...newLeaf('STATUS'), operator: 'IN', values: ['CONFIRMED', 'SUBMITTED'] },
    { ...newLeaf('TOTAL_MINUTES'), operator: 'GT', values: ['480'] }]) },
  { key: 'all', label: '全部（不含草稿）', build: () => newGroup('AND') },
]

const GROUP_OPTIONS = (Object.keys(GROUP_LABELS) as GroupDimension[])
  .map(value => ({ value, label: GROUP_LABELS[value]! }))
const TIME_DIMENSIONS: GroupDimension[] = ['MONTH', 'WEEKDAY', 'WEEK', 'DAY']

function trendAxisLabel(granularity: Granularity) {
  return (bucket: AnalysisBucket) => granularity === 'MONTH'
    ? bucket.key.slice(2).replace('-', '/')
    : bucket.key.slice(5).replace('-', '/')
}

/**
 * 统计面板的「工时分析」页签：按工时日期取工时，按用户拼的条件（可嵌套的「同时满足 / 满足其一」）
 * 筛出来，再用几张图把时长的走势、分布和构成画出来。
 *
 * 口径写在页面上：这里按**工作日期**算，成员核算页签按**选题结束时间**算（核工资用），两边数字不同是正常的。
 */
export default function WorklogAnalysisPanel({ initialProjectId }: { initialProjectId?: string }) {
  const { message } = App.useApp()
  const [range, setRange] = useState<[string, string]>([
    dayjs().startOf('year').format('YYYY-MM-DD'), dayjs().format('YYYY-MM-DD')])
  const [draft, setDraft] = useState<ConditionGroup>(() => defaultCondition(initialProjectId))
  const [applied, setApplied] = useState<ConditionGroup>(draft)
  const [groupBy, setGroupBy] = useState<GroupDimension>('MEMBER')
  const [granularity, setGranularity] = useState<Granularity>('AUTO')
  const appliedKey = JSON.stringify(toPayload(applied))
  const dirty = JSON.stringify(toPayload(draft)) !== appliedKey

  const { data: options, loading: optionsLoading } = useLoad(
    () => api<AnalysisOptions>({ url: '/statistics/worklog-analysis/options', params: { from: range[0], to: range[1] } }),
    EMPTY_OPTIONS, [range[0], range[1]],
  )
  const { data: result, loading, error, reload } = useLoad<AnalysisResult | null>(
    () => api<AnalysisResult>({
      method: 'POST', url: '/statistics/worklog-analysis',
      data: { from: range[0], to: range[1], filter: toPayload(applied), groupBy, granularity },
    }),
    null, [range[0], range[1], appliedKey, groupBy, granularity],
  )

  const ranking = useMemo(() => {
    if (!result) return []
    // 时间维度按时间顺序摆，排行维度按时长从高到低并把长尾折成「其他」。
    return TIME_DIMENSIONS.includes(result.groupBy) ? result.groups : foldTail(result.groups, RANKING_LIMIT)
  }, [result])

  const exportCsv = () => {
    if (!result?.rows.length) return
    saveBlobAs(new Blob([rowsToCsv(result.rows)], { type: 'text/csv;charset=utf-8' }),
      `工时分析-${range[0]}-${range[1]}.csv`)
    if (result.rowsTruncated) message.info(`明细只导出了最近的 ${result.rows.length} 条；需要全部请缩小日期范围分几次导出。`)
  }

  const summary = result?.summary
  const statusParts = (result?.statuses ?? []).map(bucket => ({
    key: bucket.key, label: STATUS_LABELS[bucket.key] ?? bucket.label, value: bucket.totalMinutes,
    color: bucket.key === 'CONFIRMED' ? 'var(--viz-status-good)'
      : bucket.key === 'SUBMITTED' ? 'var(--viz-status-warning)' : 'var(--viz-status-critical)',
  }))
  const sourceParts = (result?.sources ?? []).map(bucket => ({
    key: bucket.key, label: bucket.label, value: bucket.totalMinutes,
    color: bucket.key === 'IN_APP' ? 'var(--viz-series-1)' : 'var(--viz-series-3)',
  }))
  const hours = (minutes: number) => `${toHours(minutes)} 小时`
  const days = dayjs(range[1]).diff(dayjs(range[0]), 'day') + 1

  return <div className="worklog-analysis">
    <Card className="filter-card analysis-filter-card">
      <div className="analysis-filter-head">
        <Space wrap>
          <span>工作日期</span>
          <DatePicker.RangePicker allowClear={false} maxDate={dayjs()}
            value={[dayjs(range[0]), dayjs(range[1])]}
            presets={[
              { label: '最近 30 天', value: [dayjs().subtract(29, 'day'), dayjs()] },
              { label: '本月', value: [dayjs().startOf('month'), dayjs()] },
              { label: '本学期（近 6 个月）', value: [dayjs().subtract(6, 'month'), dayjs()] },
              { label: '今年', value: [dayjs().startOf('year'), dayjs()] },
            ]}
            onChange={values => values?.[0] && values[1]
              && setRange([values[0].format('YYYY-MM-DD'), values[1].format('YYYY-MM-DD')])} />
          <span>常用条件</span>
          <Select placeholder="一键套用" style={{ width: 210 }} value={null}
            options={PRESETS.map(preset => ({ value: preset.key, label: preset.label }))}
            onChange={key => {
              const preset = PRESETS.find(item => item.key === key)
              if (!preset) return
              const next = preset.build()
              setDraft(next)
              setApplied(next)
            }} />
        </Space>
        <Space wrap>
          {dirty && <Typography.Text type="warning">条件已修改，尚未应用</Typography.Text>}
          <Button onClick={() => setDraft(applied)} disabled={!dirty}>撤销修改</Button>
          <Button type="primary" icon={<FilterOutlined />} onClick={() => setApplied(draft)} disabled={!dirty}>
            应用条件</Button>
        </Space>
      </div>
      <WorklogConditionBuilder value={draft} onChange={setDraft} options={options} optionsLoading={optionsLoading} />
      <Typography.Text type="secondary" className="analysis-scope-note">
        按工作日期统计（两端都含），草稿不计入；校区范围账号只看授权校区。成员核算页签按选题结束时间统计，
        两边数字不同是口径不同。
      </Typography.Text>
    </Card>

    <DataState loading={loading && !result} error={error} onRetry={reload}>
      {result && summary && <>
        <Row gutter={[16, 16]} className="analysis-metrics">
          <Col xs={12} md={8} xl={4}><Card><Statistic title="总时长" value={toHours(summary.totalMinutes)} suffix="小时" /></Card></Col>
          <Col xs={12} md={8} xl={4}><Card><Statistic title="拍摄" value={toHours(summary.shootingMinutes)} suffix="小时" /></Card></Col>
          <Col xs={12} md={8} xl={4}><Card><Statistic title="修图" value={toHours(summary.retouchingMinutes)} suffix="小时" /></Card></Col>
          <Col xs={12} md={8} xl={4}><Card><Statistic
            title={summary.worklogCount
              ? `工时条数（平均每条 ${formatMinutes(Math.round(summary.totalMinutes / summary.worklogCount))}）`
              : '工时条数'}
            value={summary.worklogCount} suffix="条" /></Card></Col>
          <Col xs={12} md={8} xl={4}><Card><Statistic title="参与成员" value={summary.memberCount} suffix="人" /></Card></Col>
          <Col xs={12} md={8} xl={4}><Card><Statistic title={`有工时的天数（共 ${days} 天）`}
            value={summary.activeDays} suffix="天" /></Card></Col>
        </Row>

        {!summary.worklogCount
          ? <Card><Empty description="没有符合条件的工时。放宽条件或换个日期范围再看看。" /></Card>
          : <>
            <Card className="analysis-card" title="时长走势"
              extra={<Space wrap>
                <ChartLegend series={HOUR_SERIES} />
                <Segmented<Granularity> size="small" value={granularity} onChange={setGranularity} options={[
                  { value: 'AUTO', label: '自动' }, { value: 'DAY', label: '按日' },
                  { value: 'WEEK', label: '按周' }, { value: 'MONTH', label: '按月' },
                ]} />
              </Space>}>
              {result.granularity !== granularity && granularity !== 'AUTO' && <Typography.Text type="secondary">
                日期范围较长，已自动改为按{result.granularity === 'WEEK' ? '周' : '月'}显示。</Typography.Text>}
              <ColumnChart buckets={result.trend} series={HOUR_SERIES} label="时长走势"
                axisLabel={trendAxisLabel(result.granularity)} />
            </Card>

            <Row gutter={[16, 16]}>
              <Col xs={24} xl={14}><Card className="analysis-card"
                title={`${GROUP_LABELS[result.groupBy] ?? '分组'}汇总`}
                extra={<Space wrap><ChartLegend series={HOUR_SERIES} />
                  <Select size="small" value={groupBy} style={{ width: 128 }} options={GROUP_OPTIONS}
                    onChange={setGroupBy} aria-label="汇总维度" /></Space>}>
                {TIME_DIMENSIONS.includes(result.groupBy)
                  ? <ColumnChart buckets={ranking} series={HOUR_SERIES} label={`${GROUP_LABELS[result.groupBy]}汇总`} />
                  : <RankingChart buckets={ranking} series={HOUR_SERIES} label={`${GROUP_LABELS[result.groupBy]}汇总`} />}
              </Card></Col>
              <Col xs={24} xl={10}><Card className="analysis-card" title="构成">
                <Space orientation="vertical" size={18} style={{ width: '100%' }}>
                  <div><Typography.Text type="secondary">拍摄与修图</Typography.Text>
                    <ShareBar label="拍摄与修图时长占比" format={hours} parts={[
                      { key: 'shooting', label: '拍摄', value: summary.shootingMinutes, color: 'var(--viz-series-1)' },
                      { key: 'retouching', label: '修图', value: summary.retouchingMinutes, color: 'var(--viz-series-2)' },
                    ]} /></div>
                  <div><Typography.Text type="secondary">审核状态（按时长）</Typography.Text>
                    <ShareBar label="审核状态占比" format={hours} parts={statusParts} /></div>
                  <div><Typography.Text type="secondary">来源（按时长）</Typography.Text>
                    <ShareBar label="来源占比" format={hours} parts={sourceParts} /></div>
                </Space>
              </Card></Col>
            </Row>

            <Row gutter={[16, 16]}>
              <Col xs={24} lg={12}><Card className="analysis-card" title="星期分布" extra={<ChartLegend series={HOUR_SERIES} />}>
                <ColumnChart buckets={result.weekdays} series={HOUR_SERIES} label="按星期分布" height={200} />
              </Card></Col>
              <Col xs={24} lg={12}><Card className="analysis-card" title="单条工时时长分布">
                <ColumnChart buckets={result.durations} series={COUNT_SERIES} label="单条工时时长分布" height={200}
                  axisLabel={bucket => bucket.label.replace(' 小时', 'h').replace('以内', '内').replace('以上', '+')} />
              </Card></Col>
            </Row>

            {days <= 400 && <Card className="analysis-card" title="每日热力图">
              <CalendarHeatmap days={result.daily} from={range[0]} to={range[1]} />
            </Card>}

            <Card className="analysis-card" title={`${GROUP_LABELS[result.groupBy] ?? '分组'}明细`}>
              <ContentFitTable<AnalysisBucket> rowKey="key" size="small" dataSource={result.groups}
                pagination={clientTablePagination(result.groups.length, { size: 'small' })} columns={[
                  { title: '分组', dataIndex: 'label' },
                  { title: '拍摄', dataIndex: 'shootingMinutes', render: formatMinutes,
                    sorter: (a, b) => a.shootingMinutes - b.shootingMinutes },
                  { title: '修图', dataIndex: 'retouchingMinutes', render: formatMinutes,
                    sorter: (a, b) => a.retouchingMinutes - b.retouchingMinutes },
                  { title: '合计', dataIndex: 'totalMinutes', render: value => <strong>{formatMinutes(value)}</strong>,
                    sorter: (a, b) => a.totalMinutes - b.totalMinutes },
                  { title: '占比', key: 'share', render: (_, bucket) =>
                    `${summary.totalMinutes ? Math.round(bucket.totalMinutes / summary.totalMinutes * 1000) / 10 : 0}%` },
                  { title: '条数', dataIndex: 'worklogCount', sorter: (a, b) => a.worklogCount - b.worklogCount },
                  { title: '成员数', dataIndex: 'memberCount' },
                ]} />
            </Card>

            <Card className="analysis-card" title="工时明细"
              extra={<Space>
                {loading && <ReloadOutlined spin />}
                <Button icon={<DownloadOutlined />} onClick={exportCsv}>导出明细 CSV</Button>
              </Space>}>
              {result.rowsTruncated && <Alert type="info" showIcon style={{ marginBottom: 12 }}
                message={`共 ${summary.worklogCount} 条，这里列出最近的 ${result.rows.length} 条；图表和合计按全部 ${summary.worklogCount} 条计算。`} />}
              <ContentFitTable<AnalysisRow> rowKey="worklogId" size="small" dataSource={result.rows}
                pagination={clientTablePagination(result.rows.length, { size: 'small' })} columns={[
                  { title: '日期', dataIndex: 'workDate' },
                  { title: '成员', dataIndex: 'memberName', render: (value, row) =>
                    <div className="table-title"><strong>{value}</strong><span>{row.memberStudentId}</span></div> },
                  { title: '选题', dataIndex: 'projectTitle', render: value => <TableEllipsisText value={value} maxWidth={180} /> },
                  { title: '需求', dataIndex: 'requestTitle', render: (value, row) => row.requestId == null
                    ? <Tag color="purple">上传链接申报</Tag> : <TableEllipsisText value={value} maxWidth={180} /> },
                  { title: '校区', dataIndex: 'campusName', render: value => value || '—' },
                  { title: '拍摄', dataIndex: 'shootingMinutes', render: formatMinutes },
                  { title: '修图', dataIndex: 'retouchingMinutes', render: formatMinutes },
                  { title: '状态', dataIndex: 'status', render: value => <StatusTag value={value} /> },
                  { title: '说明', dataIndex: 'remark', render: value => <TableEllipsisText value={value} maxWidth={200} /> },
                ]} />
            </Card>
          </>}
      </>}
    </DataState>
  </div>
}
