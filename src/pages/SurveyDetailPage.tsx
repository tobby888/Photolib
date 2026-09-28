import {
  ArrowLeftOutlined,
  CalendarOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  EyeOutlined,
  FormOutlined,
  RocketOutlined,
  SearchOutlined,
  StopOutlined,
  TeamOutlined,
} from '@ant-design/icons'
import {
  App, Button, Card, Col, Empty, Input, Progress, Result, Row, Segmented, Space, Statistic, Table, Tabs, Tag, Typography,
} from 'antd'
import dayjs from 'dayjs'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, http, qs } from '../api'
import { useAuth } from '../auth'
import { DataState } from '../components'
import { blobErrorMessage, fileNameFromContentDisposition, saveBlobAs } from '../exportDownload'
import { useLoad } from '../hooks'
import ListPagination from '../ListPagination'
import MarkdownRenderer from '../MarkdownRenderer'
import { clientTablePagination, TABLE_PAGE_SIZES, turnPage } from '../pagination'
import { hasPermission } from '../permissions'
import RecruitmentFormEditor from '../RecruitmentFormEditor'
import { normalizeRecruitmentPage } from '../recruitmentTypes'
import SurveyEditorModal from '../SurveyEditorModal'
import {
  normalizeResponseSummary,
  normalizeSurvey,
  normalizeSurveySummary,
  normalizeTargetStatus,
  surveyStatusDisplay,
  type Survey,
  type SurveyResponseSummary,
  type SurveySummary,
  type SurveyTargetStatus,
} from '../surveyTypes'
import { deadlineText } from './SurveysPage'

function ResponsesTab({ survey }: { survey: Survey }) {
  const navigate = useNavigate()
  const { message } = App.useApp()
  const [paging, setPaging] = useState({ page: 1, pageSize: TABLE_PAGE_SIZES[1] })
  const [input, setInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [exporting, setExporting] = useState(false)
  const responses = useLoad(async () => normalizeRecruitmentPage(await api<unknown>({
    url: `/surveys/${survey.id}/responses`, params: qs({ ...paging, keyword }),
  }), normalizeResponseSummary, paging.page, paging.pageSize),
  { items: [] as SurveyResponseSummary[], page: 1, pageSize: TABLE_PAGE_SIZES[1], total: 0, totalPages: 0 },
  [survey.id, paging.page, paging.pageSize, keyword])

  const search = (value: string) => {
    setPaging(current => ({ ...current, page: 1 }))
    setKeyword(value.trim())
  }

  const exportExcel = async () => {
    setExporting(true)
    try {
      const response = await http.get<Blob>(`/surveys/${survey.id}/responses/export`, { responseType: 'blob' })
      saveBlobAs(response.data, fileNameFromContentDisposition(response.headers['content-disposition'])
        || `${survey.title}-问卷结果-${dayjs().format('YYYY-MM-DD')}.xlsx`)
      message.success('问卷结果已导出')
    } catch (error) {
      message.error(await blobErrorMessage(error, '导出失败，请稍后重试'))
    } finally {
      setExporting(false)
    }
  }

  return <Card title={<Space><TeamOutlined />收到的答卷</Space>} extra={<Space wrap>
    <Input allowClear prefix={<SearchOutlined />} placeholder="按姓名或账号搜索" style={{ width: 200 }}
      value={input} onChange={event => setInput(event.target.value)}
      onPressEnter={event => search(event.currentTarget.value)} onClear={() => search('')} />
    <Button onClick={() => search(input)}>搜索</Button>
    <Button type="primary" icon={<DownloadOutlined />} loading={exporting} onClick={() => void exportExcel()}>
      导出 Excel
    </Button>
  </Space>}>
    <DataState loading={responses.loading} error={responses.error} empty={!responses.data.items.length && !keyword}
      onRetry={responses.reload} emptyText="还没有人交" emptyHint="发放对象提交后，答卷会实时出现在这里。">
      {!responses.data.items.length
        ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={`没有姓名或账号包含“${keyword}”的答卷`} />
        : <>
          <Table rowKey="id" pagination={false} dataSource={responses.data.items} scroll={{ x: 560 }}
            columns={[
              { title: '姓名', dataIndex: 'displayName', render: (value: string, row) => <Space orientation="vertical" size={0}>
                <Typography.Text strong>{value}</Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>{row.username}</Typography.Text>
              </Space> },
              { title: '权限组', dataIndex: 'permissionGroupName', render: (value: string | null) => value || '—' },
              { title: '提交时间', dataIndex: 'submittedAt', render: (value: string) => dayjs(value).format('YYYY-MM-DD HH:mm:ss') },
              { title: '', key: 'action', width: 110, render: (_, row) => <Button type="link" icon={<EyeOutlined />}
                onClick={() => navigate(`/survey-responses/${row.id}`)}>查看</Button> },
            ]} />
          <ListPagination className="pager" page={paging.page} pageSize={paging.pageSize} total={responses.data.total}
            sizes={TABLE_PAGE_SIZES} showTotal={total => `共 ${total} 份答卷`}
            onChange={(page, pageSize) => setPaging(current => turnPage(current, page, pageSize))} />
        </>}
    </DataState>
  </Card>
}

function SummaryTab({ survey }: { survey: Survey }) {
  const summary = useLoad(async () => normalizeSurveySummary(await api<unknown>({ url: `/surveys/${survey.id}/summary` })),
    { responseCount: 0, fields: [] } as SurveySummary, [survey.id])
  return <DataState loading={summary.loading} error={summary.error} empty={!summary.data.fields.length}
    onRetry={summary.reload} emptyText="没有选择题可以统计" emptyHint="单选题和多选题会在这里按选项计数，其他题目请看答卷或导出 Excel。">
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      {summary.data.fields.map((field, index) => <Card key={field.fieldId} size="small"
        title={<Space wrap><span>{index + 1}. {field.label}</span><Tag>{field.type === 'MULTIPLE_CHOICE' ? '多选' : '单选'}</Tag></Space>}
        extra={<Typography.Text type="secondary">{field.answeredCount} 人作答</Typography.Text>}>
        <Space orientation="vertical" size={10} style={{ width: '100%' }}>
          {field.options.map(option => {
            const percent = field.answeredCount ? Math.round(option.count / field.answeredCount * 1000) / 10 : 0
            return <div key={option.option}>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
                <Typography.Text>{option.option}</Typography.Text>
                <Typography.Text type="secondary">{option.count} 人 · {percent}%</Typography.Text>
              </div>
              <Progress percent={percent} showInfo={false} strokeColor="#3f7b65" />
            </div>
          })}
        </Space>
      </Card>)}
    </Space>
  </DataState>
}

function TargetsTab({ survey, canViewResults }: { survey: Survey; canViewResults: boolean }) {
  const navigate = useNavigate()
  const [filter, setFilter] = useState<'all' | 'pending' | 'done'>('all')
  const targets = useLoad(async () => {
    const value = await api<unknown>({ url: `/surveys/${survey.id}/targets` })
    return Array.isArray(value) ? value.map(normalizeTargetStatus) : []
  }, [] as SurveyTargetStatus[], [survey.id, survey.version])
  const rows = targets.data.filter(target => filter === 'all'
    || (filter === 'done' ? !!target.submittedAt : !target.submittedAt))
  const done = targets.data.filter(target => target.submittedAt).length

  return <Card title={<Space><TeamOutlined />发放名单</Space>} extra={<Segmented value={filter}
    onChange={value => setFilter(value as typeof filter)} options={[
      { value: 'all', label: `全部 ${targets.data.length}` },
      { value: 'pending', label: `未提交 ${targets.data.length - done}` },
      { value: 'done', label: `已提交 ${done}` },
    ]} />}>
    <DataState loading={targets.loading} error={targets.error} empty={!targets.data.length} onRetry={targets.reload}
      emptyText="还没有选发给谁" emptyHint="编辑问卷，在「发给谁」里挑人。">
      <Table rowKey="userId" dataSource={rows} pagination={clientTablePagination(rows.length, { showTotal: total => `共 ${total} 人` })} scroll={{ x: 560 }}
        columns={[
          { title: '姓名', dataIndex: 'displayName', render: (value: string, row) => <Space orientation="vertical" size={0}>
            <Typography.Text strong>{value}</Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{row.username}</Typography.Text>
          </Space> },
          { title: '权限组', dataIndex: 'permissionGroupName', render: (value: string | null) => value || '—' },
          { title: '校区', dataIndex: 'campusNames', render: (value: string[]) => value.length ? value.join('、') : '—' },
          { title: '状态', key: 'status', render: (_, row) => row.submittedAt
            ? <Tag color="green">{dayjs(row.submittedAt).format('MM-DD HH:mm')} 已提交</Tag>
            : <Tag color="orange">未提交</Tag> },
          ...(canViewResults ? [{ title: '', key: 'action', width: 100, render: (_: unknown, row: SurveyTargetStatus) =>
            row.responseId && <Button type="link" icon={<EyeOutlined />}
              onClick={() => navigate(`/survey-responses/${row.responseId}`)}>答卷</Button> }] : []),
        ]} />
    </DataState>
  </Card>
}

export default function SurveyDetailPage() {
  const { surveyId = '' } = useParams()
  const navigate = useNavigate()
  const { user } = useAuth()
  const { message, modal } = App.useApp()
  const canCreate = hasPermission(user, 'SURVEY_CREATE')
  const canViewResults = hasPermission(user, 'SURVEY_RESULT_VIEW')
  const [editing, setEditing] = useState(false)
  const [actioning, setActioning] = useState(false)
  const surveyState = useLoad(async () => normalizeSurvey(await api<unknown>({ url: `/surveys/${surveyId}` })),
    null as Survey | null, [surveyId])
  const survey = surveyState.data

  const act = (action: 'publish' | 'close' | 'delete') => {
    if (!survey) return
    const copy = {
      publish: { title: '现在发布这份问卷吗？', content: `会给名单上的 ${survey.targetCount} 人发站内通知，发布后题目不能再改。`, ok: '发布' },
      close: { title: '结束这份问卷吗？', content: '结束后不能再提交，已经收到的答卷不受影响。', ok: '结束问卷' },
      delete: { title: '删除这份草稿吗？', content: '删除后无法恢复。', ok: '删除' },
    }[action]
    modal.confirm({
      title: copy.title,
      content: copy.content,
      okText: copy.ok,
      okButtonProps: action === 'publish' ? undefined : { danger: true },
      cancelText: '再想想',
      onOk: async () => {
        setActioning(true)
        try {
          if (action === 'delete') {
            await api({ method: 'DELETE', url: `/surveys/${survey.id}`, params: { version: survey.version } })
            message.success('草稿已删除')
            navigate('/surveys')
            return
          }
          await api({ method: 'POST', url: `/surveys/${survey.id}/${action}`, data: { version: survey.version } })
          message.success(action === 'publish' ? '已发布，名单上的人会收到通知' : '问卷已结束')
          await surveyState.reload()
        } catch (error) {
          message.error((error as Error).message)
        } finally {
          setActioning(false)
        }
      },
    })
  }

  if (surveyState.error) return <Result status="403" title="这份问卷看不了" subTitle={surveyState.error}
    extra={<Button onClick={() => navigate('/surveys')}>返回问卷列表</Button>} />

  const status = survey ? surveyStatusDisplay(survey) : { label: '', color: 'default' }
  const rate = survey?.targetCount ? Math.round(survey.responseCount / survey.targetCount * 100) : 0

  return <DataState loading={surveyState.loading} error={undefined} empty={!survey} onRetry={surveyState.reload}
    emptyText="找不到这份问卷" emptyHint="它可能已经被删除了，回列表看看。">
    {survey && <>
      <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/surveys')}>返回问卷列表</Button>
      <section className="project-detail-hero" style={{ marginTop: 10 }}>
        <div className="project-detail-heading">
          <div>
            <Space wrap><Typography.Text className="eyebrow">SURVEY</Typography.Text>
              <Tag color={status.color}>{status.label}</Tag></Space>
            <Typography.Title>{survey.title}</Typography.Title>
            {survey.description && <Typography.Paragraph type="secondary">{survey.description}</Typography.Paragraph>}
          </div>
          {canCreate && <Space wrap>
            {survey.status !== 'CLOSED' && <Button size="large" icon={<EditOutlined />} onClick={() => setEditing(true)}>编辑</Button>}
            {survey.status === 'DRAFT' && <Button size="large" type="primary" icon={<RocketOutlined />}
              loading={actioning} onClick={() => act('publish')}>发布</Button>}
            {survey.status === 'DRAFT' && <Button size="large" danger icon={<DeleteOutlined />}
              loading={actioning} onClick={() => act('delete')}>删除</Button>}
            {survey.status === 'PUBLISHED' && <Button size="large" danger icon={<StopOutlined />}
              loading={actioning} onClick={() => act('close')}>结束问卷</Button>}
          </Space>}
        </div>
      </section>

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={12} lg={6}><Card><Statistic title="发给" value={survey.targetCount} suffix="人" prefix={<TeamOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="已收回" value={survey.responseCount} suffix="份" prefix={<CheckCircleOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="回收率" value={rate} suffix="%" prefix={<FormOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="截止" value={deadlineText(survey.endsAt)} prefix={<CalendarOutlined />}
          styles={{ content: { fontSize: 16 } }} /></Card></Col>
      </Row>

      <Tabs items={[
        ...(canViewResults && survey.status !== 'DRAFT' ? [
          { key: 'responses', label: '答卷', children: <ResponsesTab survey={survey} /> },
          { key: 'summary', label: '选择题统计', children: <SummaryTab survey={survey} /> },
        ] : []),
        { key: 'targets', label: '发放名单', children: <TargetsTab survey={survey} canViewResults={canViewResults} /> },
        { key: 'form', label: '问卷内容', children: <Card>
          {survey.introMarkdown
            ? <MarkdownRenderer value={survey.introMarkdown} />
            : <Typography.Paragraph type="secondary">没有写简介</Typography.Paragraph>}
          <div style={{ marginTop: 20 }}>
            <RecruitmentFormEditor value={survey.formSchema} disabled variant="survey" />
          </div>
        </Card> },
      ]} />

      {canCreate && <SurveyEditorModal open={editing} survey={survey} onClose={() => setEditing(false)}
        onSaved={() => {
          setEditing(false)
          void surveyState.reload()
        }} />}
    </>}
  </DataState>
}
