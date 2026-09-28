import { ArrowLeftOutlined, CalendarOutlined, CheckCircleOutlined, SendOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Form, Progress, Result, Space, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { DataState } from '../components'
import { FormAnswerField, FormAnswerView } from '../FormFields'
import { uploadFileAnswers, type FormFileTicket } from '../formFiles'
import { useLoad } from '../hooks'
import MarkdownRenderer from '../MarkdownRenderer'
import { normalizeRecruitmentAnswers, validateFormAnswers } from '../recruitmentForm'
import { normalizeSurveyFill, type SurveyFill } from '../surveyTypes'
import { deadlineText } from './SurveysPage'

export default function SurveyFillPage() {
  const { surveyId = '' } = useParams()
  const navigate = useNavigate()
  const { message } = App.useApp()
  const [form] = Form.useForm<{ answers?: Record<string, unknown> }>()
  const [submitting, setSubmitting] = useState(false)
  const [phase, setPhase] = useState('')
  const [progress, setProgress] = useState(0)
  const [inlineError, setInlineError] = useState('')
  const state = useLoad(async () => normalizeSurveyFill(await api<unknown>({ url: `/surveys/${surveyId}/fill` })),
    null as SurveyFill | null, [surveyId])
  const survey = state.data

  const submit = async () => {
    if (!survey) return
    setInlineError('')
    let values: { answers?: Record<string, unknown> }
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    const issue = validateFormAnswers(survey.formSchema.fields, values.answers || {})[0]
    if (issue) {
      if (issue.fieldId) form.scrollToField(['answers', issue.fieldId], { focus: true })
      setInlineError(issue.message)
      return
    }
    setSubmitting(true)
    setProgress(0)
    try {
      const answers = await uploadFileAnswers(survey.formSchema.fields, values.answers || {},
        request => api<FormFileTicket>({ method: 'POST', url: `/surveys/${survey.id}/files`, data: request }),
        (text, percent) => {
          setPhase(text)
          setProgress(Math.round(percent * 0.9))
        })
      setPhase('正在提交…')
      setProgress(95)
      await api({
        method: 'POST',
        url: `/surveys/${survey.id}/responses`,
        data: { answers: normalizeRecruitmentAnswers({ ...survey.formSchema }, answers) },
      })
      setProgress(100)
      message.success('提交成功，谢谢！')
      await state.reload()
    } catch (error) {
      const reason = (error as Error).message || '提交没成功，请稍后再试一次'
      setInlineError(reason)
      message.error(reason)
    } finally {
      setSubmitting(false)
      setPhase('')
    }
  }

  if (state.error) return <Result status="404" title="这份问卷打不开" subTitle={state.error}
    extra={<Button onClick={() => navigate('/surveys')}>返回问卷列表</Button>} />

  return <DataState loading={state.loading} error={undefined} empty={!survey} onRetry={state.reload}
    emptyText="找不到这份问卷" emptyHint="它可能没有发给你，或者已经被删除了。">
    {survey && <>
      <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/surveys')}>返回问卷列表</Button>
      <section className="project-detail-hero" style={{ marginTop: 10 }}>
        <Space wrap><Typography.Text className="eyebrow">SURVEY</Typography.Text>
          {survey.myResponse ? <Tag color="green">已提交</Tag> : survey.open ? <Tag color="orange">待填写</Tag> : <Tag>已结束</Tag>}
        </Space>
        <Typography.Title>{survey.title}</Typography.Title>
        {survey.description && <Typography.Paragraph type="secondary">{survey.description}</Typography.Paragraph>}
        <Typography.Text type="secondary"><CalendarOutlined /> {deadlineText(survey.endsAt)}</Typography.Text>
      </section>

      <div style={{ maxWidth: 860 }}>
        {survey.introMarkdown && <Card style={{ marginBottom: 16 }}><MarkdownRenderer value={survey.introMarkdown} /></Card>}

        {survey.myResponse ? <Card title={<Space><CheckCircleOutlined style={{ color: '#3f7b65' }} />你交的答卷</Space>}
          extra={<Typography.Text type="secondary">{dayjs(survey.myResponse.submittedAt).format('YYYY-MM-DD HH:mm:ss')} 提交</Typography.Text>}>
          <FormAnswerView fields={survey.myResponse.formSchema.fields} answers={survey.myResponse.answers}
            files={survey.myResponse.files} />
        </Card> : !survey.open ? <Result status="info" title="问卷已经结束了" subTitle="你没有在结束前提交，现在不能再填写。" /> : <Card>
          <Form form={form} layout="vertical" size="large" requiredMark="optional" disabled={submitting}
            initialValues={{ answers: {} }} onFinish={() => void submit()}>
            {survey.formSchema.fields.map(field => <FormAnswerField key={field.id} field={field} limits={survey.uploadLimits} />)}
            {inlineError && <Alert type="error" showIcon title="还差一点就好了" description={inlineError} style={{ marginBottom: 18 }} />}
            {submitting && <Space orientation="vertical" style={{ width: '100%', marginBottom: 18 }}>
              <Progress percent={progress} status="active" />
              <Typography.Text type="secondary">{phase}</Typography.Text>
            </Space>}
            <Typography.Paragraph type="secondary">每人只能交一次，提交后不能修改。</Typography.Paragraph>
            <Button block size="large" type="primary" htmlType="submit" icon={<SendOutlined />} loading={submitting}>提交</Button>
          </Form>
        </Card>}
      </div>
    </>}
  </DataState>
}
