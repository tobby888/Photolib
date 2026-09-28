import { App, DatePicker, Form, Input, Modal } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useState } from 'react'
import { api } from './api'
import MarkdownEditor from './MarkdownEditor'
import RecruitmentFormEditor from './RecruitmentFormEditor'
import SurveyAudiencePicker from './SurveyAudiencePicker'
import {
  EMPTY_RECRUITMENT_FORM,
  normalizeRecruitmentFormSchema,
  serializeRecruitmentFormSchema,
  validateSurveyFormFields,
  type RecruitmentFormSchema,
} from './recruitmentForm'
import { normalizeSurvey, type Survey } from './surveyTypes'

type Values = {
  title: string
  description?: string
  introMarkdown?: string
  endsAt?: Dayjs | null
  targetUserIds: string[]
  formSchema: RecruitmentFormSchema
}

/** 新建和编辑共用一个弹窗；传 survey 就是编辑。发布后题目锁住，其余照常可改。 */
export default function SurveyEditorModal({ open, survey, onClose, onSaved }: {
  open: boolean
  survey?: Survey | null
  onClose: () => void
  onSaved: (survey: Survey) => void
}) {
  const { message } = App.useApp()
  const [form] = Form.useForm<Values>()
  const [saving, setSaving] = useState(false)
  const questionsLocked = !!survey && survey.status !== 'DRAFT'

  useEffect(() => {
    if (!open) return
    form.setFieldsValue(survey ? {
      title: survey.title,
      description: survey.description || '',
      introMarkdown: survey.introMarkdown || '',
      endsAt: survey.endsAt ? dayjs(survey.endsAt) : null,
      targetUserIds: survey.targetUserIds || [],
      formSchema: survey.formSchema,
    } : {
      title: '',
      description: '',
      introMarkdown: '',
      endsAt: dayjs().add(7, 'day').endOf('day'),
      targetUserIds: [],
      formSchema: { ...structuredClone(EMPTY_RECRUITMENT_FORM), fields: [] },
    })
  }, [open, survey, form])

  const save = async () => {
    let values: Values
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    const schema = normalizeRecruitmentFormSchema(values.formSchema)
    const issue = validateSurveyFormFields(schema.fields)[0]
    if (issue) {
      message.error(issue.message)
      return
    }
    if (values.endsAt && !values.endsAt.isAfter(dayjs())) {
      message.error('截止时间要晚于现在')
      return
    }
    setSaving(true)
    try {
      const data = {
        title: values.title.trim(),
        description: values.description?.trim() || null,
        introMarkdown: values.introMarkdown?.trim() || null,
        formSchema: serializeRecruitmentFormSchema(schema),
        endsAt: values.endsAt ? values.endsAt.format('YYYY-MM-DDTHH:mm:ss') : null,
        targetUserIds: values.targetUserIds || [],
      }
      const saved = normalizeSurvey(await api<unknown>(survey
        ? { method: 'PUT', url: `/surveys/${survey.id}`, data: { ...data, version: survey.version } }
        : { method: 'POST', url: '/surveys', data }))
      message.success(survey ? '修改已保存' : '草稿已保存，检查一遍就可以发布了')
      onSaved(saved)
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setSaving(false)
    }
  }

  return <Modal title={survey ? '编辑问卷' : '新建问卷'} width={960} open={open} destroyOnHidden forceRender
    onCancel={() => !saving && onClose()} onOk={() => void save()} confirmLoading={saving}
    okText={survey ? '保存' : '先存草稿'} cancelText="取消">
    <Form form={form} layout="vertical" requiredMark={false}>
      <Form.Item label="问卷标题" name="title" rules={[
        { required: true, whitespace: true, message: '请填写问卷标题' }, { max: 200, message: '标题最多 200 个字' },
      ]}><Input placeholder="例如：2026 秋季摄影部满意度调查" /></Form.Item>
      <Form.Item label="描述" name="description" extra="一两句话说明这份问卷做什么，会显示在问卷列表和通知里。"
        rules={[{ max: 1000, message: '描述最多 1000 个字' }]}>
        <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} maxLength={1000} showCount
          placeholder="例如：花两分钟说说你对这学期活动的看法" />
      </Form.Item>
      <Form.Item label="简介" name="introMarkdown" extra="支持 Markdown，可以插图。填写页顶部会完整显示。">
        <MarkdownEditor maxLength={20000} placeholder="介绍一下问卷的背景、填写须知……" />
      </Form.Item>
      <Form.Item label="截止时间（北京时间，可以不设）" name="endsAt"
        extra="不设截止时间的话，会一直开放到你手动结束。">
        <DatePicker showTime allowClear style={{ width: '100%' }} placeholder="不设截止时间" />
      </Form.Item>
      <Form.Item label="发给谁" name="targetUserIds"
        extra={survey && survey.status !== 'DRAFT' ? '已发布的问卷补发给新的人时，他们会收到站内通知。' : '发布时名单上的每个人都会收到站内通知。'}>
        <SurveyAudiencePicker />
      </Form.Item>
      <Form.Item label="题目" name="formSchema"
        extra={questionsLocked ? '问卷已经发布，为了不影响正在填写的人，题目不能再改。' : undefined}>
        <RecruitmentFormEditor variant="survey" disabled={questionsLocked} />
      </Form.Item>
    </Form>
  </Modal>
}
