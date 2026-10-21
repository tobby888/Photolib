import { Alert, Button, Col, Descriptions, Modal, Row, Space, Statistic, Tag, Typography } from 'antd'
import { DownloadOutlined } from '@ant-design/icons'
import { Link } from 'react-router-dom'
import { api } from './api'
import { DataState } from './components'
import { ContentFitTable, TableEllipsisText } from './ContentFitTable'
import { useLoad } from './hooks'
import { clientTablePagination } from './pagination'
import { AdoptionColumnChart, trendAxisLabel } from './AdoptionCharts'
import {
  BASIS_LABELS, PROJECT_STATUS_LABELS, adoptionRowsToCsv, formatDateTime, toParams,
} from './adoptionAnalysis'
import type { AdoptionAnalysisResult, AdoptionBasis, AdoptionProjectBucket, AdoptionRow } from './adoptionAnalysis'
import { saveBlobAs } from './exportDownload'

export interface AdoptionMember {
  studentId: string
  displayName: string
  campus?: string
}

interface Props {
  member: AdoptionMember | null
  range: [string, string]
  /** 成员核算点进来用 `PROJECT_COMPLETED`（和表里的被引张数同口径）；被引分析页签沿用它自己的口径。 */
  basis: AdoptionBasis
  projectId?: string
  campusId?: string
  onClose: () => void
}

/**
 * 某位摄影师的被引详情：在哪些选题里被引了几张、什么时候被引的、具体是哪些图。
 * 只在打开时挂载内容，换一个人就是一次新的请求，不会先闪一下上一个人的数。
 */
export default function MemberAdoptionModal({ member, onClose, ...query }: Props) {
  return <Modal open={member !== null} width={1000} onCancel={onClose} destroyOnHidden
    title={member ? `${member.displayName}的被引详情` : '被引详情'}
    footer={<Button onClick={onClose}>关闭</Button>}>
    {member && <MemberAdoptionContent member={member} {...query} />}
  </Modal>
}

function MemberAdoptionContent({ member, range, basis, projectId, campusId }: Omit<Props, 'onClose' | 'member'> & {
  member: AdoptionMember
}) {
  const params = toParams({ from: range[0], to: range[1], basis, projectId, campusId, studentId: member.studentId })
  const { data, loading, error, reload } = useLoad<AdoptionAnalysisResult | null>(
    () => api<AdoptionAnalysisResult>({ url: '/statistics/adoption-analysis', params }),
    null, [JSON.stringify(params)],
  )
  const summary = data?.summary
  const exportCsv = () => {
    if (!data?.rows.length) return
    saveBlobAs(new Blob([adoptionRowsToCsv(data.rows)], { type: 'text/csv;charset=utf-8' }),
      `被引详情-${member.displayName}-${range[0]}-${range[1]}.csv`)
  }

  return <div className="member-adoption-detail">
    <Descriptions size="small" column={{ xs: 1, sm: 3 }} items={[
      { key: 'studentId', label: '学号', children: member.studentId },
      { key: 'campus', label: '校区', children: member.campus || '—' },
      { key: 'range', label: `按${BASIS_LABELS[basis]}`, children: `${range[0]} ~ ${range[1]}` },
    ]} />
    <DataState loading={loading && !data} error={error} onRetry={reload}
      empty={!!data && data.rows.length === 0} emptyText="这个时间范围内没有被引记录"
      emptyHint={basis === 'PROJECT_COMPLETED' ? '这里只看所选时间范围内结束的已完成选题，与成员核算一致。' : undefined}>
      {data && summary && <Space orientation="vertical" size={16} style={{ width: '100%' }}>
        <Row gutter={[12, 12]} className="analysis-metrics member-adoption-metrics">
          <Col xs={12} md={6}><Statistic title="被引张数（按图去重）" value={summary.photoCount} suffix="张" /></Col>
          <Col xs={12} md={6}><Statistic title="被引次数" value={summary.adoptionCount} suffix="次" /></Col>
          <Col xs={12} md={6}><Statistic title="涉及选题" value={summary.projectCount} suffix="个" /></Col>
          <Col xs={12} md={6}><Statistic title="被多个选题引用" value={summary.reusedPhotoCount} suffix="张" /></Col>
        </Row>
        {summary.reusedPhotoCount > 0 && <Typography.Text type="secondary">
          有 {summary.reusedPhotoCount} 张图被不止一个选题引用：下面按选题分开数时各算一次，所以各选题加起来会比被引张数多。
        </Typography.Text>}

        <section>
          <Typography.Title level={5} className="member-adoption-heading">被引时间分布</Typography.Title>
          <AdoptionColumnChart buckets={data.trend} label={`${member.displayName}的被引时间分布`} height={180}
            axisLabel={trendAxisLabel(data.granularity)} />
        </section>

        <section>
          <Typography.Title level={5} className="member-adoption-heading">按选题</Typography.Title>
          <ContentFitTable<AdoptionProjectBucket> rowKey="projectId" size="small" dataSource={data.projects}
            pagination={clientTablePagination(data.projects.length, { size: 'small' })} columns={[
              { title: '选题', dataIndex: 'title', render: (value, project) =>
                <Link to={`/projects/${project.projectId}`}><TableEllipsisText value={value} maxWidth={240} /></Link> },
              { title: '状态', dataIndex: 'status', render: value => <Tag>{PROJECT_STATUS_LABELS[value] ?? value}</Tag> },
              { title: '选题结束', dataIndex: 'completedAt', render: value => formatDateTime(value).slice(0, 10) },
              { title: '被引张数', dataIndex: 'photoCount', render: value => <strong>{value} 张</strong>,
                sorter: (a, b) => a.photoCount - b.photoCount, defaultSortOrder: 'descend' },
              { title: '首次被引', dataIndex: 'firstAdoptedAt', render: formatDateTime },
              { title: '最近被引', dataIndex: 'lastAdoptedAt', render: formatDateTime },
            ]} />
        </section>

        <section>
          <div className="member-adoption-section-head">
            <Typography.Title level={5} className="member-adoption-heading">被引图片</Typography.Title>
            <Button size="small" icon={<DownloadOutlined />} onClick={exportCsv}>导出 CSV</Button>
          </div>
          {data.rowsTruncated && <Alert type="info" showIcon style={{ marginBottom: 12 }}
            message={`共 ${summary.adoptionCount} 次被引，这里列出最近的 ${data.rows.length} 次。`} />}
          <ContentFitTable<AdoptionRow> rowKey="adoptionId" size="small" dataSource={data.rows}
            pagination={clientTablePagination(data.rows.length, { size: 'small' })} columns={[
              { title: '被引时间', dataIndex: 'adoptedAt', render: formatDateTime },
              { title: '图片', dataIndex: 'photoTitle', render: (value, row) =>
                <Link to={`/photos/${row.photoId}`}><TableEllipsisText value={value ?? `#${row.photoId}`} maxWidth={180} /></Link> },
              { title: '拍摄时间', dataIndex: 'takenAt', render: formatDateTime },
              { title: '选题', dataIndex: 'projectTitle', render: value => <TableEllipsisText value={value} maxWidth={180} /> },
              { title: '校区', dataIndex: 'campusName', render: value => value || '—' },
              { title: '标记人', dataIndex: 'adoptedByName', render: value => value || '—' },
              { title: '备注', dataIndex: 'remark', render: value => <TableEllipsisText value={value} maxWidth={160} /> },
            ]} />
        </section>
      </Space>}
    </DataState>
  </div>
}
