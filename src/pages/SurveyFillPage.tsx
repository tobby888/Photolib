import { ArrowLeftOutlined, CalendarOutlined, CheckCircleOutlined, EditOutlined, SendOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Form, Progress, Result, Space, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { DataState } from '../components'
import { FormAnswerField, FormAnswerView } from '../FormFields'
import { answersForEditing, uploadFileAnswers, type FormFileTicket } from '../formFiles'
import { useLoad } from '../hooks'
import MarkdownRenderer from '../MarkdownRenderer'
import { normalizeRecruitmentAnswers, validateFormAnswers } from '../recruitmentForm'
import { normalizeSurveyFill, type SurveyFill } from '../surveyTypes'
import { deadlineText } from './SurveysPage'

/** 「什么时候之前还能改」：有截止时间说截止时间，没有就说问卷结束。 */
function revisableUntil(survey: SurveyFill) {
  return survey.endsAt ? '截止时间前' : '问卷结束前'
}

/**
 * 填写 / 修改答卷的表单。第一次填和修改是同一张表，区别只在初始值和保存走哪个接口。
 * 单独成一个组件，是为了每次进入都拿到一个全新的表单实例：共用实例的话，上一轮留在表单里的值
 * （尤其是已经选过的文件）会压过这一轮的初始值。
 */
function AnswerForm({ survey, revising, onSaved, onCancel }: {
  survey: SurveyFill
  /** 已经交过、现在是在改：初始值取上一版答卷，保存走 PUT。 */
  revising: boolean
  onSaved: () => Promise<void>
  onCancel: () => void
}) {
  const { message } = App.useApp()
  const [form] = Form.useForm<{ answers?: Record<string, unknown> }>()
  const [submitting, setSubmitting] = useState(false)
  const [phase, setPhase] = useState('')
  const [progress, setProgress] = useState(0)
  const [inlineError, setInlineError] = useState('')
  const [initialValues] = useState(() => ({
    answers: revising && survey.myResponse
      ? answersForEditing(survey.formSchema.fields, survey.myResponse.answers)
      : {},
  }))

  const submit = async () => {
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
      setPhase(revising ? '正在保存…' : '正在提交…')
      setProgress(95)
      await api({
        method: revising ? 'PUT' : 'POST',
        url: revising ? `/surveys/${survey.id}/responses/mine` : `/surveys/${survey.id}/responses`,
        data: { answers: normalizeRecruitmentAnswers({ ...survey.formSchema }, answers) },
      })
      setProgress(100)
      message.success(revising ? '修改已保存' : '提交成功，谢谢！')
      await onSaved()
    } catch (error) {
      const reason = (error as Error).message || (revising ? '修改没保存成功，请稍后再试一次' : '提交没成功，请稍后再试一次')
      setInlineError(reason)
      message.error(reason)
    } finally {
      setSubmitting(false)
      setPhase('')
    }
  }

  return <Card title={revising ? <Space><EditOutlined />修改答卷</Space> : undefined}>
    <Form form={form} layout="vertical" size="large" requiredMark="optional" disabled={submitting}
      initialValues={initialValues} onFinish={() => void submit()}>
      {survey.formSchema.fields.map(field => <FormAnswerField key={field.id} field={field} limits={survey.uploadLimits} />)}
      {inlineError && <Alert type="error" showIcon title="还差一点就好了" description={inlineError} style={{ marginBottom: 18 }} />}
      {submitting && <Space orientation="vertical" style={{ width: '100%', marginBottom: 18 }}>
        <Progress percent={progress} status="active" />
        <Typography.Text type="secondary">{phase}</Typography.Text>
      </Space>}
      <Typography.Paragraph type="secondary">
        {revising
          ? `保存后会替换你之前交的答卷；${revisableUntil(survey)}还可以再改。`
          : `每人交一份，提交后在${revisableUntil(survey)}还可以回来修改。`}
      </Typography.Paragraph>
      {revising
        ? <Space style={{ width: '100%' }} styles={{ item: { flex: 1 } }}>
            <Button block size="large" disabled={submitting} onClick={onCancel}>不改了</Button>
            <Button block size="large" type="primary" htmlType="submit" icon={<SendOutlined />} loading={submitting}>保存修改</Button>
          </Space>
        : <Button block size="large" type="primary" htmlType="submit" icon={<SendOutlined />} loading={submitting}>提交</Button>}
    </Form>
  </Card>
}

export default function SurveyFillPage() {
  const { surveyId = '' } = useParams()
  const navigate = useNavigate()
  const [editing, setEditing] = useState(false)
  const state = useLoad(async () => normalizeSurveyFill(await api<unknown>({ url: `/surveys/${surveyId}/fill` })),
    null as SurveyFill | null, [surveyId])
  const survey = state.data
  const mine = survey?.myResponse
  // 能不能改只看问卷还开不开着（进行中、没过截止时间），和第一次提交是同一个条件；后端按同样的规则再判一遍。
  const revising = !!survey && !!mine && survey.open && editing

  const afterSaved = async () => {
    setEditing(false)
    await state.reload()
  }

  if (state.error) return <Result status="404" title="这份问卷打不开" subTitle={state.error}
    extra={<Button onClick={() => navigate('/surveys')}>返回问卷列表</Button>} />

  return <DataState loading={state.loading} error={undefined} empty={!survey} onRetry={state.reload}
    emptyText="找不到这份问卷" emptyHint="它可能没有发给你，或者已经被删除了。">
    {survey && <>
      <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/surveys')}>返回问卷列表</Button>
      <section className="project-detail-hero" style={{ marginTop: 10 }}>
        <Space wrap><Typography.Text className="eyebrow">SURVEY</Typography.Text>
          {mine ? <Tag color="green">已提交</Tag> : survey.open ? <Tag color="orange">待填写</Tag> : <Tag>已结束</Tag>}
        </Space>
        <Typography.Title>{survey.title}</Typography.Title>
        {survey.description && <Typography.Paragraph type="secondary">{survey.description}</Typography.Paragraph>}
        <Typography.Text type="secondary"><CalendarOutlined /> {deadlineText(survey.endsAt)}</Typography.Text>
      </section>

      <div style={{ maxWidth: 860 }}>
        {survey.introMarkdown && <Card style={{ marginBottom: 16 }}><MarkdownRenderer value={survey.introMarkdown} /></Card>}

        {mine && !revising ? <Card title={<Space><CheckCircleOutlined style={{ color: '#3f7b65' }} />你交的答卷</Space>}
          extra={survey.open && <Button icon={<EditOutlined />} onClick={() => setEditing(true)}>修改答卷</Button>}>
          <Typography.Paragraph type="secondary">
            {dayjs(mine.submittedAt).format('YYYY-MM-DD HH:mm:ss')} 提交
            {mine.modifiedAt && `，${dayjs(mine.modifiedAt).format('YYYY-MM-DD HH:mm:ss')} 最后修改`}
            {survey.open ? `。${revisableUntil(survey)}可以修改。` : '。问卷已经结束，答卷不能再修改。'}
          </Typography.Paragraph>
          <FormAnswerView fields={mine.formSchema.fields} answers={mine.answers} files={mine.files} />
        </Card> : !survey.open ? <Result status="info" title="问卷已经结束了" subTitle="你没有在结束前提交，现在不能再填写。" />
          : <AnswerForm key={revising ? 'revise' : 'fill'} survey={survey} revising={revising}
              onSaved={afterSaved} onCancel={() => setEditing(false)} />}
      </div>
    </>}
  </DataState>
}
