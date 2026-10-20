import {
  App, Button, Card, Col, DatePicker, Descriptions, Input, InputNumber, Modal, Progress, Row, Segmented, Select, Space,
  Statistic, Tabs, Tag, Typography,
} from 'antd'
import {
  CameraOutlined, ClockCircleOutlined, DownloadOutlined, FolderOutlined, SearchOutlined, TrophyOutlined,
} from '@ant-design/icons'
import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { api } from '../api'
import type { MemberStats, MemberWorklogDetail } from '../types'
import { DataState, PageTitle } from '../components'
import { ContentFitTable, TableEllipsisText } from '../ContentFitTable'
import { useLoad } from '../hooks'
import { clientTablePagination } from '../pagination'
import WorklogAnalysisPanel from '../WorklogAnalysisPanel'
import AdoptionAnalysisPanel from '../AdoptionAnalysisPanel'
import MemberAdoptionModal from '../MemberAdoptionModal'
import type { AdoptionMember } from '../MemberAdoptionModal'
import {
  EMPTY_FILTER, MEMBER_CSV_HEADER, bucketSelected, campusOptions, citedDistribution, filterMembers, isFiltering,
  memberCsvRows, minutesPerCited, summarize,
} from '../memberStats'
import type { MemberFilter, WorklogPresence } from '../memberStats'
import { tableToCsv } from '../worklogCsv'
import { saveBlobAs } from '../exportDownload'

interface ExportJobView {
  job: { status: 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED'; errorMessage?: string }
  downloadUrl?: string
}

const wait = (milliseconds: number) => new Promise(resolve => window.setTimeout(resolve, milliseconds))
const formatHours = (minutes = 0) => `${Number((minutes / 60).toFixed(2))} 小时`

type StatisticsTab = 'members' | 'adoptions' | 'analysis'
const TABS: StatisticsTab[] = ['members', 'adoptions', 'analysis']

const DESCRIPTIONS: Record<StatisticsTab, string> = {
  members: '按选题结束时间统计工时，并对应每位成员的被引张数；可按被引张数、校区、工时情况筛选。',
  adoptions: '看图片在什么时候、被哪些选题引用，谁的图被引得多；可按选题、校区、摄影师筛选。',
  analysis: '按工作日期筛选、汇总工时，条件可以自由组合，并用图表看走势与分布。',
}

export default function StatisticsPage() {
  const { message } = App.useApp()
  // 页签进 URL：选题详情页「在统计面板分析」带着 ?tab=analysis&projectId= 跳过来，直接落在分析页签并预填选题条件。
  const [searchParams, setSearchParams] = useSearchParams()
  const requestedTab = searchParams.get('tab') as StatisticsTab | null
  const tab: StatisticsTab = requestedTab && TABS.includes(requestedTab) ? requestedTab : 'members'
  const [initialProjectId] = useState(() => {
    const value = searchParams.get('projectId') || ''
    return /^\d+$/.test(value) ? value : undefined
  })
  const switchTab = (next: StatisticsTab) => setSearchParams(next === 'members' ? {} : { tab: next }, { replace: true })
  const [range, setRange] = useState<[string, string]>([dayjs().startOf('year').format('YYYY-MM-DD'), dayjs().format('YYYY-MM-DD')])
  const [filter, setFilter] = useState<MemberFilter>(EMPTY_FILTER)
  const patchFilter = (patch: Partial<MemberFilter>) => setFilter(current => ({ ...current, ...patch }))
  const [exporting, setExporting] = useState(false)
  const [detailMember, setDetailMember] = useState<MemberStats | null>(null)
  const [details, setDetails] = useState<MemberWorklogDetail[]>([])
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState('')
  const [adoptionMember, setAdoptionMember] = useState<AdoptionMember | null>(null)
  const { data, loading, error, reload } = useLoad(async () => {
    const params = { from: range[0], to: range[1] }
    const [overview, members] = await Promise.all([
      api<Record<string, number>>({ url: '/statistics/overview', params }),
      api<MemberStats[]>({ url: '/statistics/members', params }),
    ])
    return { overview, members }
  }, { overview: {} as Record<string, number>, members: [] as MemberStats[] }, [range[0], range[1]])
  const campuses = useMemo(() => campusOptions(data.members), [data.members])
  const members = useMemo(() => filterMembers(data.members, filter), [data.members, filter])
  const summary = useMemo(() => summarize(members), [members])
  const distribution = useMemo(() => citedDistribution(data.members, filter), [data.members, filter])
  const filtering = isFiltering(filter)
  // 明细按学号取：统计表的一行本来就是按学号归并出来的，同一个人在几个校区的工时算在一起。
  const openDetail = async (member: MemberStats) => {
    setDetailMember(member)
    setDetails([])
    setDetailError('')
    setDetailLoading(true)
    try {
      setDetails(await api<MemberWorklogDetail[]>({ url: '/statistics/members/worklogs',
        params: { studentId: member.studentId, from: range[0], to: range[1] } }))
    } catch (e) { setDetailError((e as Error).message) }
    finally { setDetailLoading(false) }
  }
  // 被引详情按选题结束时间取，与表里的被引张数同一口径，点进去看到的张数和这一行对得上。
  const openAdoptions = (member: MemberStats) =>
    setAdoptionMember({ studentId: member.studentId, displayName: member.displayName, campus: member.campus })
  const exportData = async () => {
    setExporting(true)
    try {
      const job = await api<{ id?: string; jobId?: string }>({ method: 'POST', url: '/statistics/members/export',
        data: { from: range[0], to: range[1], projectId: null, campusId: null, format: 'XLSX' } })
      const jobId = job.id || job.jobId
      for (let attempt = 0; attempt < 30; attempt += 1) {
        const result = await api<ExportJobView>({ url: `/export-jobs/${jobId}` })
        if (result.job.status === 'SUCCEEDED' && result.downloadUrl) {
          window.location.assign(result.downloadUrl)
          message.success('统计文件已生成')
          return
        }
        if (result.job.status === 'FAILED') throw new Error(result.job.errorMessage
          || '统计文件没能生成。这个时间范围内可能没有可统计的记录，换个范围再试一次。')
        await wait(1000)
      }
      message.info('导出任务仍在处理中，请稍后重试')
    } catch (e) { message.error((e as Error).message) }
    finally { setExporting(false) }
  }
  // XLSX 由后端按时间范围出全表；筛过的结果在前端直接导成 CSV，和屏幕上看到的一致。
  const exportFiltered = () => {
    saveBlobAs(new Blob([tableToCsv(MEMBER_CSV_HEADER, memberCsvRows(members))], { type: 'text/csv;charset=utf-8' }),
      `成员核算-${range[0]}-${range[1]}${filtering ? '-筛选结果' : ''}.csv`)
  }
  const ranked = members.filter(member => member.adoptedCount > 0)
    .sort((a, b) => b.adoptedCount - a.adoptedCount).slice(0, 6)
  const maxAdopted = Math.max(1, ...ranked.map(m => m.adoptedCount))
  const maxBucket = Math.max(1, ...distribution.map(bucket => bucket.count))
  return <div className="statistics-page">
    <PageTitle eyebrow="INSIGHTS" title="数据统计" description={DESCRIPTIONS[tab]}
      extra={tab === 'members' && <Button type="primary" size="large" icon={<DownloadOutlined />} loading={exporting} onClick={() => void exportData()}>导出 XLSX</Button>} />
    <Tabs className="statistics-tabs" activeKey={tab} onChange={key => switchTab(key as StatisticsTab)} items={[
      { key: 'members', label: '成员核算' },
      { key: 'adoptions', label: '被引分析' },
      { key: 'analysis', label: '工时分析' },
    ]} />
    {tab === 'analysis' && <WorklogAnalysisPanel initialProjectId={initialProjectId} />}
    {tab === 'adoptions' && <AdoptionAnalysisPanel />}
    {tab === 'members' && <>
    <Card className="filter-card member-filter-card">
      <div className="member-filter-bar">
        <Space wrap>
          <span>选题结束时间</span>
          <DatePicker.RangePicker allowClear={false} value={[dayjs(range[0]), dayjs(range[1])]}
            onChange={values => values?.[0] && values[1] && setRange([values[0].format('YYYY-MM-DD'), values[1].format('YYYY-MM-DD')])} />
          <Input allowClear prefix={<SearchOutlined />} placeholder="姓名或学号" style={{ width: 160 }}
            value={filter.keyword} onChange={event => patchFilter({ keyword: event.target.value })} />
          <Select allowClear placeholder="全部校区" style={{ width: 140 }} value={filter.campus}
            options={campuses.map(name => ({ value: name, label: name }))}
            onChange={campus => patchFilter({ campus })} />
        </Space>
        <Space wrap>
          <span>被引张数</span>
          <Space.Compact>
            <InputNumber min={0} precision={0} placeholder="不限" style={{ width: 84 }} aria-label="被引张数下限"
              value={filter.citedMin} onChange={value => patchFilter({ citedMin: value ?? null })} />
            <Input disabled value="~" style={{ width: 32, textAlign: 'center', pointerEvents: 'none' }} />
            <InputNumber min={0} precision={0} placeholder="不限" style={{ width: 84 }} aria-label="被引张数上限"
              value={filter.citedMax} onChange={value => patchFilter({ citedMax: value ?? null })} />
          </Space.Compact>
          <Segmented<WorklogPresence> value={filter.worklog} onChange={worklog => patchFilter({ worklog })} options={[
            { value: 'all', label: '全部' }, { value: 'with', label: '有工时' }, { value: 'without', label: '未报工时' },
          ]} />
          <Button type="link" disabled={!filtering} onClick={() => setFilter(EMPTY_FILTER)}>清空筛选</Button>
        </Space>
      </div>
      {filter.citedMin != null && filter.citedMax != null && filter.citedMin > filter.citedMax &&
        <Typography.Text type="warning">被引张数的下限大于上限，没有人能同时满足。</Typography.Text>}
    </Card>
    <DataState loading={loading} error={error} onRetry={reload}>
      <div className="member-stats-body">
      <Row gutter={[16, 16]} className="metric-grid">
        {/* 前三张是全库累计、不随时间范围变；被引记录按次数算（一图多选题算多次），和下面「本期」的被引张数不是一回事。 */}
        <Col xs={12} xl={6}><Card><Statistic title="选题总数 · 全部" value={data.overview.projects || 0} prefix={<FolderOutlined />} /></Card></Col>
        <Col xs={12} xl={6}><Card><Statistic title="图库图片 · 全部" value={data.overview.photos || 0} prefix={<CameraOutlined />} /></Card></Col>
        <Col xs={12} xl={6}><Card><Statistic title="被引记录 · 全部（按次）" value={data.overview.adoptions || 0} prefix={<TrophyOutlined />} /></Card></Col>
        <Col xs={12} xl={6}><Card><Statistic title="已确认工时 · 本期" value={Math.round(((data.overview.shootingMinutes || 0) + (data.overview.retouchingMinutes || 0)) / 60)} suffix="小时" prefix={<ClockCircleOutlined />} /></Card></Col>
      </Row>
      <Card className="member-summary-card" title={filtering ? '筛选结果' : '本期概况'}
        extra={filtering && <Typography.Text type="secondary">{summary.memberCount} / {data.members.length} 人</Typography.Text>}>
        <Row gutter={[16, 16]}>
          <Col xs={12} md={8} xl={4}><Statistic title={`成员（${summary.citedMembers} 人有被引）`} value={summary.memberCount} suffix="人" /></Col>
          <Col xs={12} md={8} xl={4}><Statistic title="被引张数合计" value={summary.citedTotal} suffix="张" /></Col>
          <Col xs={12} md={8} xl={4}><Statistic title="有被引的人人均" value={summary.averageCited} suffix="张" /></Col>
          <Col xs={12} md={8} xl={4}><Statistic title="已确认工时合计" value={Number((summary.totalMinutes / 60).toFixed(1))} suffix="小时" /></Col>
          <Col xs={12} md={8} xl={4}><Statistic title="平均每张被引对应工时"
            value={summary.minutesPerCited == null ? '—' : Number((summary.minutesPerCited / 60).toFixed(2))}
            suffix={summary.minutesPerCited == null ? undefined : '小时'} /></Col>
          <Col xs={12} md={8} xl={4}><Statistic title="有被引但未报工时" value={summary.citedWithoutWorklog} suffix="人"
            styles={summary.citedWithoutWorklog ? { content: { color: '#b26a00' } } : undefined} />
            {summary.citedWithoutWorklog > 0 && filter.worklog !== 'without' &&
              <Button type="link" size="small" style={{ padding: 0 }}
                onClick={() => patchFilter({ worklog: 'without', citedMin: filter.citedMin ?? 1 })}>只看这些人</Button>}
          </Col>
        </Row>
      </Card>
      <Row gutter={[16, 16]} className="statistics-equal-row">
        <Col xs={24} lg={12}><Card title="被引张数分布" extra={<Typography.Text type="secondary">点一档只看这一档</Typography.Text>}>
          <div className="cited-distribution">{distribution.map(bucket => {
            const active = bucketSelected(bucket, filter)
            return <button type="button" key={bucket.key} className={`cited-bucket${active ? ' cited-bucket-active' : ''}`}
              aria-pressed={active}
              onClick={() => patchFilter(active ? { citedMin: null, citedMax: null } : { citedMin: bucket.min, citedMax: bucket.max })}>
              <span>{bucket.label}</span>
              <Progress percent={Math.round(bucket.count / maxBucket * 100)} showInfo={false} strokeColor="#4682B4" />
              <strong>{bucket.count} 人</strong>
            </button>
          })}</div>
        </Card></Col>
        <Col xs={24} lg={12}><Card title="被引排行" extra={<Typography.Text type="secondary">点名字看被引详情</Typography.Text>}>
          {ranked.length
            ? <ol className="stat-ranking">{ranked.map((member, index) => <li key={member.studentId}>
              <button type="button" className="stat-ranking-row" onClick={() => openAdoptions(member)}
                aria-label={`${member.displayName}，被引 ${member.adoptedCount} 张，查看被引详情`}>
                <span className={`rank rank-${index + 1}`}>{index + 1}</span>
                <span className="stat-ranking-name"><strong>{member.displayName}</strong>
                  <small title={member.campus}>{member.campus || '—'}</small></span>
                <Progress percent={Math.round(member.adoptedCount / maxAdopted * 100)} showInfo={false} strokeColor="#4682B4" />
                <strong className="stat-ranking-value">{member.adoptedCount} 张</strong>
              </button>
            </li>)}</ol>
            : <Typography.Text type="secondary">当前条件下没有被引图片。</Typography.Text>}
        </Card></Col>
      </Row>
      <Card className="member-table-card" title="成员工作统计"
        extra={<Button icon={<DownloadOutlined />} disabled={!members.length} onClick={exportFiltered}>
          {filtering ? '导出筛选结果 CSV' : '导出 CSV'}</Button>}>
        <ContentFitTable rowKey={member => member.studentId} dataSource={members}
          pagination={clientTablePagination(members.length, { size: 'small' })}
          locale={{ emptyText: filtering ? '没有符合筛选条件的成员' : '这个时间范围内没有可统计的成员' }} columns={[
          { title: '成员', dataIndex: 'displayName', fixed: 'left', render: (value, member) => <Space size={4} wrap={false}>
            <span className="nowrap">{value}</span>
            {member.totalMinutes <= 0 && member.adoptedCount > 0 && <Tag color="orange">未报工时</Tag>}
          </Space> },
          { title: '学号', dataIndex: 'studentId' },
          { title: '校区', dataIndex: 'campus', render: value => <TableEllipsisText value={value} maxWidth={180} /> },
          { title: '被引张数', dataIndex: 'adoptedCount', render: value => `${value} 张`,
            sorter: (a, b) => a.adoptedCount - b.adoptedCount },
          { title: '拍摄', dataIndex: 'shootingMinutes', render: formatHours,
            sorter: (a, b) => a.shootingMinutes - b.shootingMinutes },
          { title: '修图', dataIndex: 'retouchingMinutes', render: formatHours,
            sorter: (a, b) => a.retouchingMinutes - b.retouchingMinutes },
          { title: '合计', dataIndex: 'totalMinutes', render: value => <strong>{formatHours(value)}</strong>,
            sorter: (a, b) => a.totalMinutes - b.totalMinutes },
          { title: '每张被引工时', key: 'perCited', render: (_, member) => {
            const value = minutesPerCited(member)
            return value == null ? '—' : formatHours(value)
          }, sorter: (a, b) => (minutesPerCited(a) ?? -1) - (minutesPerCited(b) ?? -1) },
          { title: '详情', key: 'detail', fixed: 'right', render: (_, member) => <Space size={0} wrap={false}>
            <Button type="link" size="small" disabled={member.totalMinutes <= 0}
              onClick={() => void openDetail(member)}>工时</Button>
            <Button type="link" size="small" disabled={member.adoptedCount <= 0}
              onClick={() => openAdoptions(member)}>被引</Button>
          </Space> },
        ]} />
      </Card>
      </div>
    </DataState>
    </>}
    <Modal open={detailMember !== null} width={880} onCancel={() => setDetailMember(null)}
      title={detailMember ? `${detailMember.displayName}的工时详情` : '工时详情'}
      footer={<Button onClick={() => setDetailMember(null)}>关闭</Button>}>
      {detailMember && <Descriptions size="small" column={3} style={{ marginBottom: 16 }} items={[
        { key: 'studentId', label: '学号', children: detailMember.studentId },
        { key: 'campus', label: '校区', children: detailMember.campus || '—' },
        { key: 'total', label: '合计工时', children: formatHours(detailMember.totalMinutes) },
      ]} />}
      <DataState loading={detailLoading} error={detailError}
        onRetry={() => { if (detailMember) void openDetail(detailMember) }}
        empty={details.length === 0} emptyText="这个时间范围内没有已确认的工时"
        emptyHint="统计只看已确认工时，且需求所属选题要在所选结束时间范围内结束。">
        <ContentFitTable rowKey={detail => detail.worklogId} dataSource={details}
          pagination={clientTablePagination(details.length, { size: 'small' })} columns={[
          { title: '工作日期', dataIndex: 'workDate' },
          // 通过上传链接申报的工时没有需求（也没有校区），如实标出来源，不留一个空格子让人猜。
          { title: '需求', dataIndex: 'requestTitle', render: (value, detail) => detail.viaShareLink
            ? <Tag color="purple">上传链接申报</Tag>
            : <TableEllipsisText value={value} maxWidth={220} /> },
          { title: '所属选题', dataIndex: 'projectTitle', render: value => <TableEllipsisText value={value} maxWidth={220} /> },
          { title: '校区', dataIndex: 'campus', render: value => value || '—' },
          { title: '拍摄（小时）', dataIndex: 'shootingMinutes', render: formatHours },
          { title: '修图（小时）', dataIndex: 'retouchingMinutes', render: formatHours },
          { title: '合计（小时）', dataIndex: 'totalMinutes', render: value => <strong>{formatHours(value)}</strong> },
        ]} />
      </DataState>
    </Modal>
    <MemberAdoptionModal member={adoptionMember} range={range} basis="PROJECT_COMPLETED"
      onClose={() => setAdoptionMember(null)} />
  </div>
}
