import {
  Alert, App, Button, Card, Col, Collapse, DatePicker, Empty, Input, Popconfirm, Row, Segmented, Space, Statistic, Tag,
  Tooltip, Typography,
} from 'antd'
import type { TableColumnsType } from 'antd'
import {
  ArrowLeftOutlined, CalculatorOutlined, DeleteOutlined, DownloadOutlined, EditOutlined, EyeOutlined, SearchOutlined,
} from '@ant-design/icons'
import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { api, http } from './api'
import type {
  SalaryCalculationDetail, SalaryCalculationSummary, SalaryFormulaReference, SalaryLine, SalaryMemberTotal,
} from './types'
import { DataState } from './components'
import { ContentFitTable, TableEllipsisText } from './ContentFitTable'
import { useLoad } from './hooks'
import { clientTablePagination } from './pagination'
import { blobErrorMessage, fileNameFromContentDisposition, saveBlobAs } from './exportDownload'
import { filterProjects, formatYuan, linesOfMember, minutesToHours, salaryRule } from './salary'
import SalaryFormulaEditor from './SalaryFormulaEditor'
import type { FormulaStatus } from './SalaryFormulaEditor'

/**
 * 统计面板「工资计算」页签：选题结束时间范围 + 工资公式 → 计算 → 存成一份快照；
 * 计算记录里可以看明细、导出 XLSX、删除，或者把那次的公式拿回编辑框再用。
 * 明细用 `?tab=salary&calculation=<id>` 打开，刷新、复制链接都还在这一份上。
 */
export default function SalaryCalculationPanel() {
  const [searchParams, setSearchParams] = useSearchParams()
  const calculationId = searchParams.get('calculation')
  const open = (id?: string) => setSearchParams(id ? { tab: 'salary', calculation: id } : { tab: 'salary' })
  return calculationId && /^\d+$/.test(calculationId)
    ? <SalaryDetail id={calculationId} onBack={() => open()} />
    : <SalaryHistory onOpen={id => open(id)} />
}

const DEFAULT_RANGE: [string, string] = [
  dayjs().subtract(1, 'month').startOf('month').format('YYYY-MM-DD'),
  dayjs().subtract(1, 'month').endOf('month').format('YYYY-MM-DD'),
]

async function downloadXlsx(id: string, fallbackName: string) {
  const response = await http.get<Blob>(`/salary-calculations/${id}/export`, { responseType: 'blob' })
  saveBlobAs(response.data, fileNameFromContentDisposition(response.headers['content-disposition']) || fallbackName)
}

function useExport() {
  const { message } = App.useApp()
  const [exportingId, setExportingId] = useState<string | null>(null)
  const exportXlsx = async (calculation: SalaryCalculationSummary) => {
    const id = String(calculation.id)
    setExportingId(id)
    try {
      await downloadXlsx(id, `工资计算-${calculation.from}至${calculation.to}.xlsx`)
      message.success('工资明细已导出')
    } catch (error) {
      message.error(await blobErrorMessage(error, '工资明细导出失败，请稍后重试'))
    } finally {
      setExportingId(null)
    }
  }
  return { exportingId, exportXlsx }
}

function SalaryHistory({ onOpen }: { onOpen: (id: string) => void }) {
  const { message } = App.useApp()
  const [range, setRange] = useState<[string, string]>(DEFAULT_RANGE)
  const [calculating, setCalculating] = useState(false)
  const [reference, setReference] = useState<SalaryFormulaReference | null>(null)
  // 公式先空着，参考取回来后填上最近一次用的（没算过就是默认公式）。
  const [formula, setFormula] = useState<string | null>(null)
  const [formulaStatus, setFormulaStatus] = useState<FormulaStatus>('checking')
  const { exportingId, exportXlsx } = useExport()
  const { data, loading, error, reload } = useLoad(
    () => api<SalaryCalculationSummary[]>({ url: '/salary-calculations', params: { limit: 100 } }),
    [] as SalaryCalculationSummary[], [])

  useEffect(() => {
    let alive = true
    api<SalaryFormulaReference>({ url: '/salary-calculations/formula' })
      .then(result => {
        if (!alive) return
        setReference(result)
        setFormula(current => current ?? (result.lastFormula || result.defaultFormula))
      })
      .catch(e => {
        if (!alive) return
        message.error(`没能取到公式参考：${(e as Error).message}`)
        setFormula(current => current ?? '')
      })
    return () => { alive = false }
  }, [message])

  const calculate = async () => {
    setCalculating(true)
    try {
      const result = await api<SalaryCalculationSummary>({ method: 'POST', url: '/salary-calculations',
        data: { from: range[0], to: range[1], formula: formula?.trim() || null } })
      message.success(result.lineCount
        ? `已算出 ${result.projectCount} 个选题、${result.memberCount} 人的工资`
        : '这个时间范围内没有结束的选题或没有记录，已保存一份空的计算')
      onOpen(String(result.id))
    } catch (e) { message.error((e as Error).message) }
    finally { setCalculating(false) }
  }

  const remove = async (calculation: SalaryCalculationSummary) => {
    try {
      await api({ method: 'DELETE', url: `/salary-calculations/${calculation.id}` })
      message.success('已删除这次计算')
      reload()
    } catch (e) { message.error((e as Error).message) }
  }

  const reuse = (calculation: SalaryCalculationSummary) => {
    setFormula(calculation.formula)
    message.info('已把这次的公式填进编辑框')
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  return <div className="salary-panel">
    <Card className="filter-card member-filter-card">
      <div className="member-filter-bar">
        <Space wrap>
          <span>选题结束时间</span>
          <DatePicker.RangePicker allowClear={false} value={[dayjs(range[0]), dayjs(range[1])]}
            onChange={values => values?.[0] && values[1]
              && setRange([values[0].format('YYYY-MM-DD'), values[1].format('YYYY-MM-DD')])} />
          <Tooltip title={formulaStatus === 'invalid' ? '公式有误，改好后才能计算' : undefined}>
            <Button type="primary" icon={<CalculatorOutlined />} loading={calculating}
              disabled={formula === null || formulaStatus !== 'valid'} onClick={() => void calculate()}>
              开始计算</Button>
          </Tooltip>
        </Space>
      </div>
      <div style={{ width: '100%' }}>
        <Typography.Text strong>工资公式</Typography.Text>
        <Typography.Paragraph type="secondary" style={{ margin: '2px 0 8px' }}>
          每个选题里每位有工时或被引记录的成员按这条公式算一次（单位元）；工时只算已确认的，单位小时。
          可以用 + - * / 、括号、比较（成立为 1，不成立为 0）和下面的函数，结果小于 0 按 0 计、四舍五入到分。
        </Typography.Paragraph>
        {formula !== null && <SalaryFormulaEditor reference={reference} value={formula} onChange={setFormula}
          onStatusChange={setFormulaStatus} />}
        <Typography.Paragraph type="secondary" style={{ margin: '8px 0 0' }}>
          只算选题结束时间落在所选范围内（两端日期都含）的选题；管理员给工时填了自定义选题的，按工作日期落在范围内算，同名合并成一个选题。成员按学号归并。计算结果连同公式存成快照，之后再审核工时不会改变它，需要时重新计算即可。
        </Typography.Paragraph>
      </div>
    </Card>
    <Card title="计算记录" className="member-table-card">
      <DataState loading={loading} error={error} onRetry={reload} empty={!data.length}
        emptyText="还没有计算过工资" emptyHint="选好选题结束时间和公式，点「开始计算」。">
        <ContentFitTable<SalaryCalculationSummary> rowKey={item => String(item.id)} dataSource={data}
          pagination={clientTablePagination(data.length, { size: 'small' })} columns={[
            { title: '选题结束时间', key: 'range', render: (_, item) => <a onClick={() => onOpen(String(item.id))}
              className="nowrap">{item.from} 至 {item.to}</a> },
            { title: '公式', dataIndex: 'formula', render: value => <TableEllipsisText value={value} maxWidth={260} /> },
            { title: '选题', dataIndex: 'projectCount', render: value => `${value} 个` },
            { title: '人数', dataIndex: 'memberCount', render: value => `${value} 人` },
            { title: '应发合计（元）', dataIndex: 'totalAmount', align: 'right',
              render: value => <strong>{formatYuan(value)}</strong> },
            { title: '待审核工时', dataIndex: 'pendingWorklogCount', render: value => value
              ? <Tooltip title="计算时这些工时还没审核"><Tag color="orange">{value} 条</Tag></Tooltip>
              : '—' },
            { title: '计算人', dataIndex: 'createdByName' },
            { title: '计算时间', dataIndex: 'createdAt', render: value => dayjs(value).format('YYYY-MM-DD HH:mm') },
            { title: '操作', key: 'actions', fixed: 'right', render: (_, item) => <Space size={0} wrap={false}>
              <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => onOpen(String(item.id))}>明细</Button>
              <Button type="link" size="small" icon={<DownloadOutlined />} loading={exportingId === String(item.id)}
                onClick={() => void exportXlsx(item)}>导出 XLSX</Button>
              <Button type="link" size="small" icon={<EditOutlined />} onClick={() => reuse(item)}>用这个公式</Button>
              <Popconfirm title="删除这次计算？" description="删除后计算记录和明细都不再显示，可以重新计算。"
                okText="删除" okButtonProps={{ danger: true }} onConfirm={() => remove(item)}>
                <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
              </Popconfirm>
            </Space> },
          ]} />
      </DataState>
    </Card>
  </div>
}

const lineColumns = (withProject: boolean): TableColumnsType<SalaryLine> => [
  withProject
    ? { title: '选题', dataIndex: 'projectTitle', render: (value, line) => line.projectId
      ? <a href={`#/projects/${line.projectId}`} target="_blank" rel="noreferrer">
        <TableEllipsisText value={value} maxWidth={220} /></a>
      // 自定义选题是管理员手写的名字，没有选题页可跳。
      : <Space size={4}><Tag color="gold">自定义</Tag><TableEllipsisText value={value} maxWidth={180} /></Space> }
    : { title: '成员', dataIndex: 'memberName', fixed: 'left', render: value => <span className="nowrap">{value}</span> },
  ...(withProject ? [] : [
    { title: '学号', dataIndex: 'studentId', render: (value: string | null) => value || '—' },
    { title: '校区', dataIndex: 'campus', render: (value: string | null) => <TableEllipsisText value={value} maxWidth={160} /> },
  ] as TableColumnsType<SalaryLine>),
  { title: '拍摄', dataIndex: 'shootingMinutes', render: value => `${minutesToHours(value)} 小时` },
  { title: '修图', dataIndex: 'retouchingMinutes', render: value => `${minutesToHours(value)} 小时` },
  { title: '已确认工时', key: 'hours', render: (_, line) => <Tooltip title={`共 ${line.confirmedWorklogCount} 条`}>
    <span className="nowrap">{minutesToHours(line.shootingMinutes + line.retouchingMinutes)} 小时</span></Tooltip> },
  { title: '被引张数', dataIndex: 'adoptedPhotos', render: value => `${value} 张` },
  { title: '上传张数', dataIndex: 'uploadedPhotos', render: value => `${value} 张` },
  { title: '待审核', dataIndex: 'pendingWorklogCount', render: (value, line) => value
    ? <Tooltip title={`${minutesToHours(line.pendingMinutes)} 小时`}><Tag color="orange">{value} 条</Tag></Tooltip>
    : '—' },
  { title: '计算过程', dataIndex: 'process', render: value => <Typography.Text type="secondary" className="nowrap">
    {value || '—'}</Typography.Text> },
  { title: '应发工资（元）', dataIndex: 'salaryAmount', align: 'right', fixed: 'right',
    render: value => <strong>{formatYuan(value)}</strong>, sorter: (a, b) => a.salaryAmount - b.salaryAmount },
]

type DetailView = 'projects' | 'members'

function SalaryDetail({ id, onBack }: { id: string; onBack: () => void }) {
  const [view, setView] = useState<DetailView>('projects')
  const [keyword, setKeyword] = useState('')
  const { exportingId, exportXlsx } = useExport()
  const { data, loading, error, reload } = useLoad(
    () => api<SalaryCalculationDetail>({ url: `/salary-calculations/${id}` }),
    null as SalaryCalculationDetail | null, [id])
  const projects = useMemo(() => filterProjects(data?.projects ?? [], keyword), [data, keyword])
  const members = useMemo(() => {
    const key = keyword.trim().toLowerCase()
    const all = data?.members ?? []
    return key ? all.filter(member => member.memberName.toLowerCase().includes(key)
      || (member.studentId ?? '').toLowerCase().includes(key)) : all
  }, [data, keyword])
  const calculation = data?.calculation

  return <div className="salary-panel">
    <Card className="filter-card member-filter-card">
      <div className="member-filter-bar">
        <Space wrap>
          <Button icon={<ArrowLeftOutlined />} onClick={onBack}>计算记录</Button>
          {calculation && <Typography.Text strong>
            选题结束时间 {calculation.from} 至 {calculation.to} 的工资明细</Typography.Text>}
        </Space>
        <Space wrap>
          <Input allowClear prefix={<SearchOutlined />} placeholder="选题、姓名或学号" style={{ width: 200 }}
            value={keyword} onChange={event => setKeyword(event.target.value)} />
          <Segmented<DetailView> value={view} onChange={setView} options={[
            { value: 'projects', label: '按选题' }, { value: 'members', label: '按成员' },
          ]} />
          <Button type="primary" icon={<DownloadOutlined />} disabled={!calculation}
            loading={calculation != null && exportingId === String(calculation.id)}
            onClick={() => calculation && void exportXlsx(calculation)}>导出 XLSX</Button>
        </Space>
      </div>
    </Card>
    <DataState loading={loading} error={error} onRetry={reload}>
      {data && calculation && <>
        <Card className="member-summary-card">
          <Row gutter={[16, 16]}>
            <Col xs={12} md={6}><Statistic title="结算的选题" value={calculation.projectCount} suffix="个" /></Col>
            <Col xs={12} md={6}><Statistic title="计算的成员" value={calculation.memberCount} suffix="人" /></Col>
            <Col xs={12} md={6}><Statistic title="应发合计" value={formatYuan(calculation.totalAmount)} suffix="元" /></Col>
            <Col xs={12} md={6}><Statistic title="计算人" value={calculation.createdByName}
              suffix={<Typography.Text type="secondary" style={{ fontSize: 13 }}>
                {dayjs(calculation.createdAt).format('MM-DD HH:mm')}</Typography.Text>} /></Col>
          </Row>
          <Typography.Paragraph type="secondary" style={{ margin: '12px 0 0' }}>
            {salaryRule(calculation.formula)}；工时只算已确认的，单位小时。</Typography.Paragraph>
        </Card>
        {calculation.pendingWorklogCount > 0 && <Alert showIcon type="warning" style={{ marginBottom: 16 }}
          title={`计算时还有 ${calculation.pendingWorklogCount} 条待审核的工时`}
          description="公式里没用「待审核时长 / 待审核条数」的话，它们没有算进工资。审核完之后回到计算记录重新计算一次，新的结果会另存一份。" />}
        {view === 'projects'
          ? projects.length
            ? <Collapse className="salary-projects" defaultActiveKey={projects.slice(0, 5).map(item => item.key)}
              items={projects.map(project => ({
                key: project.key,
                label: <Space wrap size={[12, 4]}>
                  <Typography.Text strong>{project.projectTitle}</Typography.Text>
                  {project.projectId
                    ? <Typography.Text type="secondary">{dayjs(project.completedAt).format('YYYY-MM-DD')} 结束</Typography.Text>
                    : <><Tag color="gold">自定义选题</Tag>
                      <Typography.Text type="secondary">按工作日期计入</Typography.Text></>}
                  <Typography.Text type="secondary">{project.lines.length} 人</Typography.Text>
                  {project.pendingWorklogCount > 0 && <Tag color="orange">{project.pendingWorklogCount} 条待审核</Tag>}
                </Space>,
                extra: <Typography.Text strong className="nowrap">{formatYuan(project.salaryAmount)} 元</Typography.Text>,
                children: <ContentFitTable<SalaryLine> size="small" rowKey={line => line.memberKey}
                  dataSource={project.lines} pagination={false} columns={lineColumns(false)} />,
              }))} />
            : <Card><Empty description={keyword ? '没有符合条件的选题或成员' : '这个时间范围内没有结束的选题或没有记录'} /></Card>
          : <Card className="member-table-card" title="按成员汇总"
            extra={<Typography.Text type="secondary">点行首展开看各选题明细</Typography.Text>}>
            <ContentFitTable<SalaryMemberTotal> rowKey={member => member.memberKey} dataSource={members}
              pagination={clientTablePagination(members.length, { size: 'small' })}
              locale={{ emptyText: keyword ? '没有符合条件的成员' : '没有需要计算的成员' }}
              expandable={{ expandedRowRender: member => <ContentFitTable<SalaryLine> size="small"
                rowKey={line => line.projectId ? `p:${line.projectId}` : `c:${line.projectTitle}`}
                dataSource={linesOfMember(data, member.memberKey)}
                pagination={false} columns={lineColumns(true)} /> }}
              columns={[
                { title: '成员', dataIndex: 'memberName', fixed: 'left', render: value => <span className="nowrap">{value}</span> },
                { title: '学号', dataIndex: 'studentId', render: value => value || '—' },
                { title: '校区', dataIndex: 'campus', render: value => <TableEllipsisText value={value} maxWidth={160} /> },
                { title: '选题', dataIndex: 'projectCount', render: value => `${value} 个` },
                { title: '已确认工时', key: 'hours',
                  render: (_, member) => `${minutesToHours(member.shootingMinutes + member.retouchingMinutes)} 小时`,
                  sorter: (a, b) => a.shootingMinutes + a.retouchingMinutes - b.shootingMinutes - b.retouchingMinutes },
                { title: '被引张数', dataIndex: 'adoptedPhotos', render: value => `${value} 张`,
                  sorter: (a, b) => a.adoptedPhotos - b.adoptedPhotos },
                { title: '上传张数', dataIndex: 'uploadedPhotos', render: value => `${value} 张`,
                  sorter: (a, b) => a.uploadedPhotos - b.uploadedPhotos },
                { title: '应发合计（元）', dataIndex: 'salaryAmount', align: 'right',
                  render: value => <strong>{formatYuan(value)}</strong>,
                  sorter: (a, b) => a.salaryAmount - b.salaryAmount, defaultSortOrder: 'descend' },
                { title: '待审核', dataIndex: 'pendingWorklogCount', render: value => value
                  ? <Tag color="orange">{value} 条</Tag> : '—' },
              ]} />
          </Card>}
      </>}
    </DataState>
  </div>
}
