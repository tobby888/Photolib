import { Alert, Button, Card, Col, Drawer, Row, Segmented, Space, Statistic, Tag, Typography } from 'antd'
import { BarChartOutlined, ClockCircleOutlined, UnorderedListOutlined } from '@ant-design/icons'
import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import { api } from './api'
import { useAuth } from './auth'
import type { EntityId, PageData, Worklog } from './types'
import { DataState, StatusTag, formatMinutes } from './components'
import { ContentFitTable, TableEllipsisText } from './ContentFitTable'
import { useLoad } from './hooks'
import { hasAnyPermission, hasPermission } from './permissions'
import { clientTablePagination } from './pagination'
import { PROJECT_WORKLOG_LIMIT, summarizeProjectWorklogs } from './projectWorklogs'
import type { ProjectWorklogMember } from './projectWorklogs'

type StatusFilter = 'ALL' | Worklog['status']

/**
 * 选题详情页的「本选题工时」：一键列出这个选题下的全部工时（站内填报的 + 上传链接申报的），
 * 按成员汇总，并能跳到工时页 / 统计面板接着看。
 *
 * 取的是 `GET /worklogs?projectId=`，可见范围与工时页完全一致：审核人看全部，
 * 只有填报权限的人只看到自己填的，校区范围账号只看授权校区的。
 */
export default function ProjectWorklogsDrawer({ open, projectId, projectTitle, onClose }: {
  open: boolean
  projectId: EntityId
  projectTitle: string
  onClose: () => void
}) {
  const navigate = useNavigate()
  const { user } = useAuth()
  const reviewer = hasAnyPermission(user, 'WORKLOG_CONFIRM', 'WORKLOG_EXPORT')
  const canAnalyze = hasPermission(user, 'STATISTICS_DOWNLOAD')
  const [status, setStatus] = useState<StatusFilter>('ALL')
  const { data, loading, error, reload } = useLoad(async () => {
    if (!open) return { items: [] as Worklog[], total: 0 }
    const items: Worklog[] = []
    let total = 0
    for (let page = 1; items.length < PROJECT_WORKLOG_LIMIT; page += 1) {
      const result = await api<PageData<Worklog>>({ url: '/worklogs', params: { page, pageSize: 100, projectId } })
      total = Number(result.total) || 0
      items.push(...result.items)
      if (!result.items.length || items.length >= total) break
    }
    return { items, total }
  }, { items: [] as Worklog[], total: 0 }, [open, projectId])
  const summary = useMemo(() => summarizeProjectWorklogs(data.items), [data.items])
  const visible = status === 'ALL' ? data.items : data.items.filter(item => item.status === status)
  const counts = useMemo(() => {
    const result: Record<string, number> = {}
    for (const item of data.items) result[item.status] = (result[item.status] || 0) + 1
    return result
  }, [data.items])

  return <Drawer open={open} onClose={onClose} width={960} destroyOnHidden
    title={<Space><ClockCircleOutlined />{`「${projectTitle}」的工时`}</Space>}
    extra={<Space>
      <Button icon={<UnorderedListOutlined />} onClick={() => navigate(`/worklogs?projectId=${projectId}`)}>
        在工时页打开</Button>
      {canAnalyze && <Button type="primary" icon={<BarChartOutlined />}
        onClick={() => navigate(`/statistics?tab=analysis&projectId=${projectId}`)}>在统计面板分析</Button>}
    </Space>}>
    {!reviewer && <Alert type="info" showIcon style={{ marginBottom: 16 }}
      message="这里只列出你自己填报的工时；审核人能看到这个选题的全部工时。" />}
    {data.total > data.items.length && <Alert type="warning" showIcon style={{ marginBottom: 16 }}
      message={`这个选题有 ${data.total} 条工时，这里只汇总了最近的 ${data.items.length} 条，完整列表请到工时页查看。`} />}
    <DataState loading={loading} error={error} onRetry={reload} empty={!data.items.length}
      emptyText="这个选题还没有工时记录"
      emptyHint="成员在这个选题的需求下填报工时、或有人通过上传链接申报后，会出现在这里。">
      <Row gutter={[12, 12]} className="project-worklog-metrics">
        <Col xs={12} md={6}><Card size="small"><Statistic title="已确认时长"
          value={Number((summary.confirmedMinutes / 60).toFixed(1))} suffix="小时" /></Card></Col>
        <Col xs={12} md={6}><Card size="small"><Statistic
          title={summary.pendingCount ? `待确认（${formatMinutes(summary.pendingMinutes)}）` : '待确认'}
          value={summary.pendingCount} suffix="条" /></Card></Col>
        <Col xs={12} md={6}><Card size="small"><Statistic title="已退回" value={summary.rejectedCount} suffix="条" /></Card></Col>
        <Col xs={12} md={6}><Card size="small"><Statistic title="参与成员" value={summary.memberCount} suffix="人" /></Card></Col>
      </Row>
      <Typography.Title level={5} style={{ marginTop: 20 }}>按成员汇总</Typography.Title>
      <ContentFitTable<ProjectWorklogMember> rowKey={member => member.studentId || member.name} size="small"
        dataSource={summary.members} pagination={clientTablePagination(summary.members.length, { size: 'small' })}
        columns={[
          { title: '成员', dataIndex: 'name' },
          { title: '学号', dataIndex: 'studentId', render: value => value || '—' },
          { title: '已确认拍摄', dataIndex: 'confirmedShooting', render: formatMinutes },
          { title: '已确认修图', dataIndex: 'confirmedRetouching', render: formatMinutes },
          { title: '已确认合计', dataIndex: 'confirmedTotal', render: value => <strong>{formatMinutes(value)}</strong>,
            sorter: (a, b) => a.confirmedTotal - b.confirmedTotal, defaultSortOrder: 'descend' },
          { title: '待确认', dataIndex: 'pendingTotal', render: value => value ? formatMinutes(value) : '—' },
          { title: '条数', dataIndex: 'worklogCount' },
        ]} />
      <Space style={{ marginTop: 20, marginBottom: 12 }} wrap>
        <Typography.Title level={5} style={{ margin: 0 }}>工时明细</Typography.Title>
        <Segmented<StatusFilter> value={status} onChange={setStatus} options={[
          { value: 'ALL', label: `全部 ${data.items.length}` },
          { value: 'CONFIRMED', label: `已确认 ${counts.CONFIRMED || 0}` },
          { value: 'SUBMITTED', label: `待确认 ${counts.SUBMITTED || 0}` },
          { value: 'REJECTED', label: `已退回 ${counts.REJECTED || 0}` },
          ...(counts.DRAFT ? [{ value: 'DRAFT' as const, label: `草稿 ${counts.DRAFT}` }] : []),
        ]} />
      </Space>
      <ContentFitTable<Worklog> rowKey="id" size="small" dataSource={visible}
        pagination={clientTablePagination(visible.length, { size: 'small' })}
        columns={[
          { title: '日期', dataIndex: 'workDate', render: value => dayjs(value).format('YYYY-MM-DD'),
            sorter: (a, b) => a.workDate.localeCompare(b.workDate) },
          { title: '成员', dataIndex: 'memberName', render: (value, item) =>
            <div className="table-title"><strong>{value || '—'}</strong><span>{item.memberStudentId}</span></div> },
          // 链接申报的工时没有需求：如实标出来源。
          { title: '来源', dataIndex: 'requestTitle', render: (value, item) => item.shareLinkId
            ? <Tag color="purple">上传链接{item.shareLinkName ? ` · ${item.shareLinkName}` : ''}</Tag>
            : <TableEllipsisText value={value || '—'} maxWidth={200} /> },
          { title: '拍摄', dataIndex: 'shootingMinutes', render: formatMinutes },
          { title: '修图', dataIndex: 'retouchingMinutes', render: formatMinutes },
          { title: '状态', dataIndex: 'status', render: value => <StatusTag value={value} /> },
          { title: '说明', dataIndex: 'remark', render: value => <TableEllipsisText value={value} maxWidth={220} /> },
        ]} />
    </DataState>
  </Drawer>
}
