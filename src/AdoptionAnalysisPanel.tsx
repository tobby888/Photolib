import {
  Alert, App, Button, Card, Col, DatePicker, Empty, Row, Segmented, Select, Space, Statistic, Tag, Typography,
} from 'antd'
import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons'
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import dayjs from 'dayjs'
import { api } from './api'
import { DataState } from './components'
import { ContentFitTable, TableEllipsisText } from './ContentFitTable'
import { useLoad } from './hooks'
import { clientTablePagination } from './pagination'
import { AdoptionColumnChart, AdoptionRankingChart, trendAxisLabel } from './AdoptionCharts'
import type { RankingItem } from './AdoptionCharts'
import MemberAdoptionModal from './MemberAdoptionModal'
import type { AdoptionMember } from './MemberAdoptionModal'
import {
  PROJECT_STATUS_LABELS, adoptionRowsToCsv, foldRanking, formatDateTime, toParams,
} from './adoptionAnalysis'
import type {
  AdoptionAnalysisResult, AdoptionBasis, AdoptionGranularity, AdoptionOptions, AdoptionPhotographerBucket,
  AdoptionProjectBucket, AdoptionRow,
} from './adoptionAnalysis'
import { saveBlobAs } from './exportDownload'

const EMPTY_OPTIONS: AdoptionOptions = { projects: [], campuses: [], photographers: [] }
const RANKING_LIMIT = 12

type TableTab = 'photographers' | 'projects' | 'campuses' | 'rows'

function rest(items: readonly RankingItem[], noun: string): RankingItem {
  return {
    key: '__rest', label: `其余 ${items.length} ${noun}`,
    photoCount: items.reduce((sum, item) => sum + item.photoCount, 0),
    adoptionCount: items.reduce((sum, item) => sum + item.adoptionCount, 0),
  }
}

/**
 * 统计面板的「被引分析」页签：图在什么时候被引、被哪些选题引、谁的图被引得多。
 *
 * 默认按**被引时间**取；切到「选题结束时间」就和成员核算同一口径（只看已完成选题），
 * 两边的被引张数可以直接对。走势、星期分布始终按被引时间画。
 */
export default function AdoptionAnalysisPanel() {
  const { message } = App.useApp()
  const [range, setRange] = useState<[string, string]>([
    dayjs().startOf('year').format('YYYY-MM-DD'), dayjs().format('YYYY-MM-DD')])
  const [basis, setBasis] = useState<AdoptionBasis>('ADOPTED')
  const [projectId, setProjectId] = useState<string>()
  const [campusId, setCampusId] = useState<string>()
  const [studentId, setStudentId] = useState<string>()
  const [granularity, setGranularity] = useState<AdoptionGranularity>('AUTO')
  const [tableTab, setTableTab] = useState<TableTab>('photographers')
  const [detail, setDetail] = useState<AdoptionMember | null>(null)
  const params = toParams({ from: range[0], to: range[1], basis, projectId, campusId, studentId, granularity })

  const { data: options, loading: optionsLoading } = useLoad(
    () => api<AdoptionOptions>({ url: '/statistics/adoption-analysis/options', params: { from: range[0], to: range[1], basis } }),
    EMPTY_OPTIONS, [range[0], range[1], basis],
  )
  const { data: result, loading, error, reload } = useLoad<AdoptionAnalysisResult | null>(
    () => api<AdoptionAnalysisResult>({ url: '/statistics/adoption-analysis', params }),
    null, [JSON.stringify(params)],
  )

  const photographerRanking = useMemo(() => foldRanking<RankingItem>((result?.photographers ?? []).map(person => ({
    key: person.studentId, label: person.name, photoCount: person.photoCount, adoptionCount: person.adoptionCount,
    extra: `${person.projectCount} 个选题`,
  })), RANKING_LIMIT, items => rest(items, '人')), [result])
  const projectRanking = useMemo(() => foldRanking<RankingItem>((result?.projects ?? []).map(project => ({
    key: String(project.projectId), label: project.title, photoCount: project.photoCount,
    adoptionCount: project.adoptionCount, extra: `${project.photographerCount} 位摄影师`,
  })), RANKING_LIMIT, items => rest(items, '个选题')), [result])
  const campusRanking = useMemo(() => (result?.campuses ?? []).map(campus => ({
    key: campus.key, label: campus.label, photoCount: campus.photoCount, adoptionCount: campus.adoptionCount,
    extra: `${campus.photographerCount} 位摄影师`,
  })), [result])

  const openPerson = (studentId: string, name: string, campus?: string) =>
    setDetail({ studentId, displayName: name, campus })
  const exportCsv = () => {
    if (!result?.rows.length) return
    saveBlobAs(new Blob([adoptionRowsToCsv(result.rows)], { type: 'text/csv;charset=utf-8' }),
      `被引分析-${range[0]}-${range[1]}.csv`)
    if (result.rowsTruncated) message.info(`明细只导出了最近的 ${result.rows.length} 条；需要全部请缩小日期范围分几次导出。`)
  }
  const summary = result?.summary
  const filtered = Boolean(projectId || campusId || studentId)

  return <div className="worklog-analysis adoption-analysis">
    <Card className="filter-card analysis-filter-card">
      <div className="analysis-filter-head">
        <Space wrap>
          <Segmented<AdoptionBasis> value={basis} onChange={setBasis} options={[
            { value: 'ADOPTED', label: '按被引时间' }, { value: 'PROJECT_COMPLETED', label: '按选题结束时间' },
          ]} />
          <DatePicker.RangePicker allowClear={false} maxDate={dayjs()} value={[dayjs(range[0]), dayjs(range[1])]}
            presets={[
              { label: '最近 30 天', value: [dayjs().subtract(29, 'day'), dayjs()] },
              { label: '本月', value: [dayjs().startOf('month'), dayjs()] },
              { label: '本学期（近 6 个月）', value: [dayjs().subtract(6, 'month'), dayjs()] },
              { label: '今年', value: [dayjs().startOf('year'), dayjs()] },
            ]}
            onChange={values => values?.[0] && values[1]
              && setRange([values[0].format('YYYY-MM-DD'), values[1].format('YYYY-MM-DD')])} />
        </Space>
        <Space wrap>
          <Select allowClear showSearch optionFilterProp="label" placeholder="全部选题" style={{ width: 180 }}
            loading={optionsLoading} value={projectId} options={options.projects} onChange={setProjectId}
            aria-label="按选题筛选" />
          <Select allowClear placeholder="全部校区" style={{ width: 130 }} loading={optionsLoading}
            value={campusId} options={options.campuses} onChange={setCampusId} aria-label="按校区筛选" />
          <Select allowClear showSearch optionFilterProp="label" placeholder="全部摄影师" style={{ width: 150 }}
            loading={optionsLoading} value={studentId} onChange={setStudentId} aria-label="按摄影师筛选"
            options={options.photographers.map(option => ({ value: option.value, label: `${option.label}（${option.value}）` }))} />
          <Button type="link" disabled={!filtered} onClick={() => {
            setProjectId(undefined); setCampusId(undefined); setStudentId(undefined)
          }}>清空筛选</Button>
        </Space>
      </div>
      <Typography.Text type="secondary" className="analysis-scope-note">
        {basis === 'ADOPTED'
          ? '按图片被标记为被引的时间统计（两端都含），包括进行中的选题。'
          : '按选题结束时间统计，只看已完成的选题——与「成员核算」的被引张数同一口径。'}
        「被引张数」按图片去重，「被引次数」一张图被几个选题引用就算几次。校区按图片所属校区，校区范围账号只看授权校区。
      </Typography.Text>
    </Card>

    <DataState loading={loading && !result} error={error} onRetry={reload}>
      {result && summary && <>
        <Row gutter={[16, 16]} className="analysis-metrics">
          <Col xs={12} md={8} xl={4}><Card><Statistic title="被引张数（按图去重）" value={summary.photoCount} suffix="张" /></Card></Col>
          <Col xs={12} md={8} xl={4}><Card><Statistic title="被引次数" value={summary.adoptionCount} suffix="次" /></Card></Col>
          <Col xs={12} md={8} xl={4}><Card><Statistic title="涉及选题" value={summary.projectCount} suffix="个" /></Card></Col>
          <Col xs={12} md={8} xl={4}><Card><Statistic
            title={summary.photographerCount ? `被引摄影师（人均 ${Math.round(summary.photoCount / summary.photographerCount * 10) / 10} 张）` : '被引摄影师'}
            value={summary.photographerCount} suffix="人" /></Card></Col>
          <Col xs={12} md={8} xl={4}><Card><Statistic title="被多个选题引用" value={summary.reusedPhotoCount} suffix="张" /></Card></Col>
          <Col xs={12} md={8} xl={4}><Card><Statistic title="有被引的天数" value={summary.activeDays} suffix="天" /></Card></Col>
        </Row>

        {!summary.adoptionCount
          ? <Card><Empty description="没有符合条件的被引记录。换个时间口径或日期范围再看看。" /></Card>
          : <>
            <Card className="analysis-card" title="被引走势"
              extra={<Space wrap>
                {loading && <ReloadOutlined spin />}
                <Segmented<AdoptionGranularity> size="small" value={granularity} onChange={setGranularity} options={[
                  { value: 'AUTO', label: '自动' }, { value: 'DAY', label: '按日' },
                  { value: 'WEEK', label: '按周' }, { value: 'MONTH', label: '按月' },
                ]} />
              </Space>}>
              {basis === 'PROJECT_COMPLETED' && <Typography.Text type="secondary">
                横轴是被引时间：选题进行中选的图也画在它被选中的那一天，所以可能早于所选的结束时间范围。</Typography.Text>}
              {result.granularity !== granularity && granularity !== 'AUTO' && <Typography.Text type="secondary">
                {' '}时间跨度较长，已自动改为按{result.granularity === 'WEEK' ? '周' : '月'}显示。</Typography.Text>}
              <AdoptionColumnChart buckets={result.trend} label="被引走势（按被引次数）"
                axisLabel={trendAxisLabel(result.granularity)} />
            </Card>

            <Row gutter={[16, 16]} className="statistics-equal-row">
              <Col xs={24} xl={12}><Card className="analysis-card" title="摄影师被引排行">
                <Typography.Text type="secondary" className="analysis-card-hint">点一行看这位摄影师的被引详情</Typography.Text>
                <AdoptionRankingChart items={photographerRanking} label="摄影师被引排行（按被引张数）"
                  onSelect={item => openPerson(item.key, item.label,
                    result.photographers.find(person => person.studentId === item.key)?.campus)} />
              </Card></Col>
              <Col xs={24} xl={12}><Card className="analysis-card" title="选题被引排行">
                <AdoptionRankingChart items={projectRanking} label="选题被引排行（按被引张数）" />
              </Card></Col>
            </Row>

            <Row gutter={[16, 16]} className="statistics-equal-row">
              <Col xs={24} lg={12}><Card className="analysis-card" title="星期分布">
                <AdoptionColumnChart buckets={result.weekdays} label="被引的星期分布" height={200} />
              </Card></Col>
              <Col xs={24} lg={12}><Card className="analysis-card" title="从拍摄到被引">
                <Typography.Text type="secondary" className="analysis-card-hint">现拍现用，还是从图库翻出来的老图</Typography.Text>
                <AdoptionColumnChart buckets={result.lags} label="从拍摄到被引的间隔" height={200}
                  axisLabel={bucket => bucket.label.replace(' 天', '天').replace('以上', '+').replace(' ~ ', '-')} />
              </Card></Col>
            </Row>

            <Card className="analysis-card" activeTabKey={tableTab} onTabChange={key => setTableTab(key as TableTab)}
              tabList={[
                { key: 'photographers', label: `按摄影师（${result.photographers.length}）` },
                { key: 'projects', label: `按选题（${result.projects.length}）` },
                { key: 'campuses', label: `按校区（${result.campuses.length}）` },
                { key: 'rows', label: `被引明细（${summary.adoptionCount}）` },
              ]}
              tabBarExtraContent={tableTab === 'rows' &&
                <Button size="small" icon={<DownloadOutlined />} onClick={exportCsv}>导出明细 CSV</Button>}>
              {tableTab === 'photographers' && <ContentFitTable<AdoptionPhotographerBucket> rowKey="studentId" size="small"
                dataSource={result.photographers}
                pagination={clientTablePagination(result.photographers.length, { size: 'small' })} columns={[
                  { title: '摄影师', dataIndex: 'name', render: (value, person) =>
                    <div className="table-title"><strong>{value}</strong><span>{person.studentId}</span></div> },
                  { title: '校区', dataIndex: 'campus', render: value => value || '—' },
                  { title: '被引张数', dataIndex: 'photoCount', render: value => <strong>{value} 张</strong>,
                    sorter: (a, b) => a.photoCount - b.photoCount },
                  { title: '被引次数', dataIndex: 'adoptionCount', render: value => `${value} 次`,
                    sorter: (a, b) => a.adoptionCount - b.adoptionCount },
                  { title: '涉及选题', dataIndex: 'projectCount', render: value => `${value} 个`,
                    sorter: (a, b) => a.projectCount - b.projectCount },
                  { title: '占比', key: 'share', render: (_, person) =>
                    `${summary.photoCount ? Math.round(person.photoCount / summary.photoCount * 1000) / 10 : 0}%` },
                  { title: '详情', key: 'detail', render: (_, person) => <Button type="link" size="small"
                    onClick={() => openPerson(person.studentId, person.name, person.campus)}>被引详情</Button> },
                ]} />}
              {tableTab === 'projects' && <ContentFitTable<AdoptionProjectBucket> rowKey="projectId" size="small"
                dataSource={result.projects}
                pagination={clientTablePagination(result.projects.length, { size: 'small' })} columns={[
                  { title: '选题', dataIndex: 'title', render: (value, project) =>
                    <Link to={`/projects/${project.projectId}`}><TableEllipsisText value={value} maxWidth={240} /></Link> },
                  { title: '状态', dataIndex: 'status', render: value => <Tag>{PROJECT_STATUS_LABELS[value] ?? value}</Tag> },
                  { title: '选题结束', dataIndex: 'completedAt', render: value => value ? formatDateTime(value).slice(0, 10) : '—' },
                  { title: '被引张数', dataIndex: 'photoCount', render: value => <strong>{value} 张</strong>,
                    sorter: (a, b) => a.photoCount - b.photoCount },
                  { title: '摄影师', dataIndex: 'photographerCount', render: value => `${value} 人`,
                    sorter: (a, b) => a.photographerCount - b.photographerCount },
                  { title: '首次被引', dataIndex: 'firstAdoptedAt', render: formatDateTime },
                  { title: '最近被引', dataIndex: 'lastAdoptedAt', render: formatDateTime },
                ]} />}
              {tableTab === 'campuses' && <AdoptionRankingChart items={campusRanking} label="各校区被引张数" />}
              {tableTab === 'rows' && <>
                {result.rowsTruncated && <Alert type="info" showIcon style={{ marginBottom: 12 }}
                  message={`共 ${summary.adoptionCount} 条，这里列出最近的 ${result.rows.length} 条；图表和合计按全部 ${summary.adoptionCount} 条计算。`} />}
                <ContentFitTable<AdoptionRow> rowKey="adoptionId" size="small" dataSource={result.rows}
                  pagination={clientTablePagination(result.rows.length, { size: 'small' })} columns={[
                    { title: '被引时间', dataIndex: 'adoptedAt', render: formatDateTime },
                    { title: '摄影师', dataIndex: 'photographerName', render: (value, row) =>
                      <div className="table-title"><strong>{value}</strong><span>{row.photographerStudentId}</span></div> },
                    { title: '图片', dataIndex: 'photoTitle', render: (value, row) =>
                      <Link to={`/photos/${row.photoId}`}><TableEllipsisText value={value ?? `#${row.photoId}`} maxWidth={160} /></Link> },
                    { title: '拍摄时间', dataIndex: 'takenAt', render: formatDateTime },
                    { title: '选题', dataIndex: 'projectTitle', render: value => <TableEllipsisText value={value} maxWidth={180} /> },
                    { title: '校区', dataIndex: 'campusName', render: value => value || '—' },
                    { title: '标记人', dataIndex: 'adoptedByName', render: value => value || '—' },
                  ]} />
              </>}
            </Card>
          </>}
      </>}
    </DataState>
    <MemberAdoptionModal member={detail} range={range} basis={basis} projectId={projectId} campusId={campusId}
      onClose={() => setDetail(null)} />
  </div>
}
