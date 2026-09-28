import {
  ArrowRightOutlined,
  CalendarOutlined,
  CheckCircleOutlined,
  FormOutlined,
  PlusOutlined,
  SearchOutlined,
  TeamOutlined,
} from '@ant-design/icons'
import { Button, Card, Col, Input, Row, Select, Space, Tabs, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, qs } from '../api'
import { useAuth } from '../auth'
import { DataState, PageTitle } from '../components'
import { useLoad } from '../hooks'
import ListPagination from '../ListPagination'
import { GRID_PAGE_SIZES, turnPage } from '../pagination'
import { hasAnyPermission, hasPermission } from '../permissions'
import { normalizeRecruitmentPage } from '../recruitmentTypes'
import SurveyEditorModal from '../SurveyEditorModal'
import {
  normalizeAssignedSurvey,
  normalizeSurvey,
  surveyStatusDisplay,
  type AssignedSurvey,
  type Survey,
} from '../surveyTypes'

const statusOptions = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'PUBLISHED', label: '进行中' },
  { value: 'CLOSED', label: '已结束' },
]

export function deadlineText(endsAt: string | null) {
  return endsAt ? `${dayjs(endsAt).format('YYYY-MM-DD HH:mm')} 截止` : '不设截止时间'
}

const iconStyle = {
  width: 48, height: 48, display: 'grid', placeItems: 'center', borderRadius: 13,
  background: '#edf3f0', color: '#28594f', fontSize: 22,
} as const

function MySurveys() {
  const navigate = useNavigate()
  const surveys = useLoad(async () => {
    const value = await api<unknown>({ url: '/surveys/assigned' })
    return Array.isArray(value) ? value.map(normalizeAssignedSurvey) : []
  }, [] as AssignedSurvey[], [])
  const pending = surveys.data.filter(survey => survey.open && !survey.submittedAt)

  return <DataState loading={surveys.loading} error={surveys.error} empty={!surveys.data.length} onRetry={surveys.reload}
    emptyText="还没有发给你的问卷" emptyHint="有新问卷时，消息中心会通知你。">
    {pending.length > 0 && <Typography.Paragraph type="secondary">还有 {pending.length} 份问卷等你填写。</Typography.Paragraph>}
    <Row gutter={[16, 16]}>
      {surveys.data.map(survey => {
        const state = survey.submittedAt
          ? { label: '已提交', color: 'green' }
          : survey.open ? { label: '待填写', color: 'orange' } : { label: '已结束', color: 'default' }
        return <Col xs={24} md={12} xl={8} key={survey.id}>
          <Card hoverable style={{ height: '100%' }} onClick={() => navigate(`/surveys/${survey.id}/fill`)}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
              <span style={iconStyle}>{survey.submittedAt ? <CheckCircleOutlined /> : <FormOutlined />}</span>
              <Tag color={state.color}>{state.label}</Tag>
            </div>
            <Typography.Title level={4} style={{ margin: '18px 0 7px' }}>{survey.title}</Typography.Title>
            <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }} style={{ minHeight: 44 }}>
              {survey.description || '没有描述'}
            </Typography.Paragraph>
            <Space orientation="vertical" size={5} style={{ width: '100%', margin: '8px 0 16px' }}>
              <Typography.Text type="secondary"><CalendarOutlined /> {deadlineText(survey.endsAt)}</Typography.Text>
              {survey.submittedAt && <Typography.Text type="secondary">
                <CheckCircleOutlined /> {dayjs(survey.submittedAt).format('YYYY-MM-DD HH:mm')} 提交
              </Typography.Text>}
            </Space>
            <Button block type={!survey.submittedAt && survey.open ? 'primary' : 'default'}>
              {!survey.submittedAt && survey.open ? '去填写' : '查看'} <ArrowRightOutlined />
            </Button>
          </Card>
        </Col>
      })}
    </Row>
  </DataState>
}

function ManagedSurveys({ reloadKey }: { reloadKey: number }) {
  const navigate = useNavigate()
  const [filters, setFilters] = useState({ page: 1, pageSize: GRID_PAGE_SIZES[0], keyword: '', status: '' })
  const surveys = useLoad(async () => normalizeRecruitmentPage(
    await api<unknown>({ url: '/surveys', params: qs({ ...filters }) }), normalizeSurvey, filters.page, filters.pageSize),
  { items: [] as Survey[], page: 1, pageSize: GRID_PAGE_SIZES[0], total: 0, totalPages: 0 },
  [filters.page, filters.pageSize, filters.keyword, filters.status, reloadKey])

  return <>
    <Card className="filter-card">
      <Space wrap>
        <Input allowClear prefix={<SearchOutlined />} placeholder="搜索问卷标题或描述" style={{ width: 260 }}
          onPressEnter={event => setFilters(current => ({ ...current, page: 1, keyword: event.currentTarget.value.trim() }))}
          onClear={() => setFilters(current => ({ ...current, page: 1, keyword: '' }))} />
        <Select allowClear placeholder="全部状态" options={statusOptions} style={{ width: 150 }}
          onChange={(status = '') => setFilters(current => ({ ...current, page: 1, status }))} />
      </Space>
    </Card>
    <DataState loading={surveys.loading} error={surveys.error} empty={!surveys.data.items.length} onRetry={surveys.reload}
      emptyText={filters.keyword || filters.status ? '没有符合筛选条件的问卷' : '还没有问卷'}
      emptyHint={filters.keyword || filters.status ? '换个关键词或状态再看看。' : '新建一份问卷，选好发给谁，就能收集大家的反馈。'}>
      <Row gutter={[16, 16]} style={{ marginBottom: 22 }}>
        {surveys.data.items.map(survey => {
          const status = surveyStatusDisplay(survey)
          return <Col xs={24} md={12} xl={8} key={survey.id}>
            <Card hoverable style={{ height: '100%' }} onClick={() => navigate(`/surveys/${survey.id}`)}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
                <span style={iconStyle}><FormOutlined /></span>
                <Tag color={status.color}>{status.label}</Tag>
              </div>
              <Typography.Title level={4} style={{ margin: '18px 0 7px' }}>{survey.title}</Typography.Title>
              <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }} style={{ minHeight: 44 }}>
                {survey.description || '没有描述'}
              </Typography.Paragraph>
              <Space orientation="vertical" size={5} style={{ width: '100%', margin: '8px 0 16px' }}>
                <Typography.Text type="secondary"><CalendarOutlined /> {deadlineText(survey.endsAt)}</Typography.Text>
                <Typography.Text type="secondary">
                  <TeamOutlined /> 发给 {survey.targetCount} 人，已收回 {survey.responseCount} 份
                </Typography.Text>
              </Space>
              <Button block>看看详情和结果 <ArrowRightOutlined /></Button>
            </Card>
          </Col>
        })}
      </Row>
      <ListPagination page={filters.page} pageSize={filters.pageSize} total={surveys.data.total}
        showTotal={total => `共 ${total} 份问卷`}
        onChange={(page, pageSize) => setFilters(current => turnPage(current, page, pageSize))} />
    </DataState>
  </>
}

export default function SurveysPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const canFill = hasPermission(user, 'SURVEY_ACCESS')
  const canManage = hasAnyPermission(user, 'SURVEY_CREATE', 'SURVEY_RESULT_VIEW')
  const canCreate = hasPermission(user, 'SURVEY_CREATE')
  const [tab, setTab] = useState(canFill ? 'mine' : 'manage')
  const [creating, setCreating] = useState(false)
  const [reloadKey, setReloadKey] = useState(0)

  const items = [
    ...(canFill ? [{ key: 'mine', label: '发给我的', children: <MySurveys /> }] : []),
    ...(canManage ? [{ key: 'manage', label: '问卷管理', children: <ManagedSurveys reloadKey={reloadKey} /> }] : []),
  ]

  return <>
    <PageTitle eyebrow="SURVEY" title="问卷" description="发给部里的成员填写，结果可以在这里查看和导出。"
      extra={canCreate && <Button type="primary" size="large" icon={<PlusOutlined />} onClick={() => setCreating(true)}>新建问卷</Button>} />
    {items.length > 1
      ? <Tabs activeKey={tab} onChange={setTab} items={items} />
      : items[0]?.children}
    {canCreate && <SurveyEditorModal open={creating} onClose={() => setCreating(false)}
      onSaved={survey => {
        setCreating(false)
        setReloadKey(current => current + 1)
        navigate(`/surveys/${survey.id}`)
      }} />}
  </>
}
