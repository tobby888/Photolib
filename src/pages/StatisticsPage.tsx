import { App, Button, Card, Col, DatePicker, Progress, Row, Space, Statistic, Table, Typography } from 'antd'
import { CameraOutlined, ClockCircleOutlined, DownloadOutlined, FolderOutlined, TrophyOutlined } from '@ant-design/icons'
import { useState } from 'react'
import dayjs from 'dayjs'
import { api } from '../api'
import type { MemberStats } from '../types'
import { DataState, formatMinutes, PageTitle } from '../components'
import { useLoad } from '../hooks'

interface ExportJobView {
  job: { status: 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED'; errorMessage?: string }
  downloadUrl?: string
}

const wait = (milliseconds: number) => new Promise(resolve => window.setTimeout(resolve, milliseconds))

export default function StatisticsPage() {
  const { message } = App.useApp()
  const [range, setRange] = useState<[string, string]>([dayjs().startOf('year').format('YYYY-MM-DD'), dayjs().format('YYYY-MM-DD')])
  const [exporting, setExporting] = useState(false)
  const { data, loading, error, reload } = useLoad(async () => {
    const params = { from: range[0], to: range[1] }
    const [overview, members] = await Promise.all([
      api<Record<string, number>>({ url: '/statistics/overview', params }),
      api<MemberStats[]>({ url: '/statistics/members', params }),
    ])
    return { overview, members }
  }, { overview: {} as Record<string, number>, members: [] as MemberStats[] }, [range[0], range[1]])
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
        if (result.job.status === 'FAILED') throw new Error(result.job.errorMessage || '导出失败')
        await wait(1000)
      }
      message.info('导出任务仍在处理中，请稍后重试')
    } catch (e) { message.error((e as Error).message) }
    finally { setExporting(false) }
  }
  const maxAdopted = Math.max(1, ...data.members.map(m => m.adoptedCount))
  return <>
    <PageTitle eyebrow="INSIGHTS" title="数据统计" description="按选题结束时间统计工时，并对应每位成员的图片被引数。"
      extra={<Button type="primary" size="large" icon={<DownloadOutlined />} loading={exporting} onClick={() => void exportData()}>导出 XLSX</Button>} />
    <Card className="filter-card">
      <Space><span>选题结束时间</span><DatePicker.RangePicker value={[dayjs(range[0]), dayjs(range[1])]}
        onChange={values => values?.[0] && values[1] && setRange([values[0].format('YYYY-MM-DD'), values[1].format('YYYY-MM-DD')])} /></Space>
    </Card>
    <DataState loading={loading} error={error} onRetry={reload}>
      <Row gutter={[16, 16]} className="metric-grid">
        <Col xs={12} xl={6}><Card><Statistic title="项目数" value={data.overview.projectCount || 0} prefix={<FolderOutlined />} /></Card></Col>
        <Col xs={12} xl={6}><Card><Statistic title="图库图片" value={data.overview.photoCount || 0} prefix={<CameraOutlined />} /></Card></Col>
        <Col xs={12} xl={6}><Card><Statistic title="被采纳图片" value={data.overview.adoptedPhotoCount || data.overview.adoptionCount || 0} prefix={<TrophyOutlined />} /></Card></Col>
        <Col xs={12} xl={6}><Card><Statistic title="总工时" value={Math.round(((data.overview.shootingMinutes || 0) + (data.overview.retouchingMinutes || 0)) / 60)} suffix="小时" prefix={<ClockCircleOutlined />} /></Card></Col>
      </Row>
      <Row gutter={[16, 16]}>
        <Col xs={24} xl={9}><Card title="采纳贡献">
          <div className="ranking-list">{data.members.slice().sort((a,b) => b.adoptedCount - a.adoptedCount).slice(0, 6).map((member, index) => <div className="ranking-item" key={`${member.studentId}-${member.campus}`}>
            <span className={`rank rank-${index + 1}`}>{index + 1}</span><div><strong>{member.displayName}</strong><Typography.Text type="secondary">{member.campus}</Typography.Text></div>
            <Progress percent={Math.round(member.adoptedCount / maxAdopted * 100)} showInfo={false} strokeColor="#e9b16c" />
            <strong>{member.adoptedCount} 张</strong>
          </div>)}</div>
        </Card></Col>
        <Col xs={24} xl={15}><Card title="成员工作统计">
          <Table rowKey={member => `${member.studentId}-${member.campus}`} dataSource={data.members} pagination={false} columns={[
            { title: '成员', dataIndex: 'displayName' }, { title: '学号', dataIndex: 'studentId' }, { title: '校区', dataIndex: 'campus' },
            { title: '被采纳', dataIndex: 'adoptedCount', render: value => `${value} 张`, sorter: (a,b) => a.adoptedCount - b.adoptedCount },
            { title: '拍摄', dataIndex: 'shootingMinutes', render: formatMinutes },
            { title: '修图', dataIndex: 'retouchingMinutes', render: formatMinutes },
            { title: '合计', dataIndex: 'totalMinutes', render: value => <strong>{formatMinutes(value)}</strong> },
          ]} />
        </Card></Col>
      </Row>
    </DataState>
  </>
}
