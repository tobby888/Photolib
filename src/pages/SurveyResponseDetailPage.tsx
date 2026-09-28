import { ArrowLeftOutlined, UserOutlined } from '@ant-design/icons'
import { Button, Card, Result, Skeleton, Space, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { FormAnswerView } from '../FormFields'
import { useLoad, useRefreshOnResume } from '../hooks'
import { normalizeResponseDetail, type SurveyResponseDetail } from '../surveyTypes'

export default function SurveyResponseDetailPage() {
  const { responseId = '' } = useParams()
  const navigate = useNavigate()
  const state = useLoad(async () => normalizeResponseDetail(await api<unknown>({ url: `/surveys/responses/${responseId}` })),
    null as SurveyResponseDetail | null, [responseId])
  // 下载地址是临时签发的，回到页面时刷新一下，免得点下载拿到过期链接。
  useRefreshOnResume(state.refresh)
  const detail = state.data

  if (state.error) return <Result status="403" title="这份答卷看不了" subTitle={state.error}
    extra={<Button onClick={() => navigate('/surveys')}>返回问卷列表</Button>} />

  return <>
    <Button type="text" icon={<ArrowLeftOutlined />}
      onClick={() => navigate(detail ? `/surveys/${detail.surveyId}` : '/surveys')}>返回这份问卷</Button>
    {state.loading && <Card style={{ marginTop: 12 }}><Skeleton active paragraph={{ rows: 10 }} /></Card>}
    {!state.loading && detail && <>
      <section className="project-detail-hero" style={{ marginTop: 10 }}>
        <Space wrap><Typography.Text className="eyebrow">SURVEY RESPONSE</Typography.Text><Tag color="green">已提交</Tag></Space>
        <Typography.Title>{detail.surveyTitle}</Typography.Title>
        <Space wrap size="large">
          <Typography.Text><UserOutlined /> <strong>{detail.displayName}</strong>（{detail.username}）</Typography.Text>
          {detail.permissionGroupName && <Tag>{detail.permissionGroupName}</Tag>}
          <Typography.Text type="secondary">提交时间：{dayjs(detail.submittedAt).format('YYYY-MM-DD HH:mm:ss')}</Typography.Text>
        </Space>
      </section>
      <Card title="答卷内容">
        <FormAnswerView fields={detail.formSchema.fields} answers={detail.answers} files={detail.files} />
      </Card>
    </>}
  </>
}
