import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  DeleteOutlined,
  FileImageOutlined,
  IdcardOutlined,
  PlusOutlined,
} from '@ant-design/icons'
import { Alert, Button, Card, Checkbox, Col, Input, Row, Select, Space, Switch, Tag, Typography } from 'antd'
import {
  RECRUITMENT_FIELD_TYPES,
  createRecruitmentField,
  normalizeRecruitmentFormSchema,
  type RecruitmentFieldType,
  type RecruitmentFormField,
  type RecruitmentFormSchema,
} from './recruitmentForm'
import {
  RECRUITMENT_FALLBACK_UPLOAD_LIMITS,
  describeBytes,
  type RecruitmentUploadLimits,
} from './recruitmentUpload'

const fieldTypeOptions: { value: RecruitmentFieldType; label: string }[] = [
  { value: 'SHORT_TEXT', label: '单行文本' },
  { value: 'LONG_TEXT', label: '多行文本' },
  { value: 'SINGLE_CHOICE', label: '单选' },
  { value: 'MULTIPLE_CHOICE', label: '多选' },
  { value: 'DATE', label: '日期' },
]

const choiceTypes = new Set<RecruitmentFieldType>(['SINGLE_CHOICE', 'MULTIPLE_CHOICE'])

export default function RecruitmentFormEditor({
  value, onChange, disabled = false, limits = RECRUITMENT_FALLBACK_UPLOAD_LIMITS,
}: {
  value?: RecruitmentFormSchema | string | null
  onChange?: (value: RecruitmentFormSchema) => void
  disabled?: boolean
  /**
   * Quota shown to the person building the form. Pass the task's own limits when
   * one exists; the fallback matches the shipped defaults and is only right for a
   * deployment that has not overridden them.
   */
  limits?: RecruitmentUploadLimits
}) {
  const schema = normalizeRecruitmentFormSchema(value)

  const updateField = (index: number, updates: Partial<RecruitmentFormField>) => {
    const fields = schema.fields.map((field, current) => current === index ? { ...field, ...updates } : field)
    onChange?.({ ...schema, fields })
  }

  const changeType = (index: number, type: RecruitmentFieldType) => {
    const current = schema.fields[index]
    updateField(index, {
      type,
      options: choiceTypes.has(type) ? current.options?.length ? current.options : ['选项 1', '选项 2'] : undefined,
      placeholder: type === 'SHORT_TEXT' || type === 'LONG_TEXT' ? current.placeholder : undefined,
    })
  }

  const moveField = (from: number, to: number) => {
    if (to < 0 || to >= schema.fields.length) return
    const fields = [...schema.fields]
    const [field] = fields.splice(from, 1)
    fields.splice(to, 0, field)
    onChange?.({ ...schema, fields })
  }

  const removeField = (index: number) => {
    onChange?.({ ...schema, fields: schema.fields.filter((_, current) => current !== index) })
  }

  const addField = (type: RecruitmentFieldType = 'SHORT_TEXT') => {
    const field = createRecruitmentField(type, schema.fields.map(item => item.id))
    onChange?.({ ...schema, fields: [...schema.fields, field] })
  }

  return <Space orientation="vertical" size={14} style={{ width: '100%' }}>
    <Alert type="info" showIcon title="学号和作品上传是固定项"
      description="学号用来防止同一个人重复报名，删不掉。同学传上来的照片会按原图保存，我们不压缩、不转格式。" />

    <Card size="small" title={<Space><IdcardOutlined /><span>学号</span><Tag color="red">必填</Tag></Space>}>
      <Space orientation="vertical" size={10} style={{ width: '100%' }}>
        <Input value={schema.studentId.label} disabled={disabled} maxLength={100} placeholder="这一栏叫什么，比如「学号」"
          onChange={event => onChange?.({ ...schema, studentId: { ...schema.studentId, label: event.target.value } })} />
        <Input value={schema.studentId.helpText} disabled={disabled} maxLength={500} placeholder="给同学的提示（可以不写）"
          onChange={event => onChange?.({ ...schema, studentId: { ...schema.studentId, helpText: event.target.value } })} />
        <Typography.Text type="secondary">永远排在表单第一项。学号按文本保存，开头的 0 不会丢。</Typography.Text>
      </Space>
    </Card>

    {schema.fields.map((field, index) => <Card key={field.id} size="small"
      title={<Space wrap><span>问题 {index + 1}</span><Tag>{fieldTypeOptions.find(item => item.value === field.type)?.label}</Tag></Space>}
      extra={!disabled && <Space size={2}>
        <Button type="text" aria-label="上移问题" icon={<ArrowUpOutlined />} disabled={index === 0}
          onClick={() => moveField(index, index - 1)} />
        <Button type="text" aria-label="下移问题" icon={<ArrowDownOutlined />}
          disabled={index === schema.fields.length - 1} onClick={() => moveField(index, index + 1)} />
        <Button type="text" danger aria-label="删除问题" icon={<DeleteOutlined />} onClick={() => removeField(index)} />
      </Space>}>
      <Row gutter={[12, 12]}>
        <Col xs={24} sm={9}>
          <Typography.Text type="secondary">题型</Typography.Text>
          <Select value={field.type} disabled={disabled} options={fieldTypeOptions} style={{ width: '100%', marginTop: 6 }}
            onChange={type => changeType(index, type)} />
        </Col>
        <Col xs={24} sm={15}>
          <Typography.Text type="secondary">题目</Typography.Text>
          <Input value={field.label} disabled={disabled} maxLength={100} style={{ marginTop: 6 }}
            placeholder="比如：为什么想加入摄影部？" onChange={event => updateField(index, { label: event.target.value })} />
        </Col>
      </Row>
      <div style={{ marginTop: 12 }}>
        <Typography.Text type="secondary">补充说明（可以不写）</Typography.Text>
        <Input value={field.helpText} disabled={disabled} maxLength={500} style={{ marginTop: 6 }}
          placeholder="想提醒同学注意什么，写在这里" onChange={event => updateField(index, { helpText: event.target.value })} />
      </div>
      {(field.type === 'SHORT_TEXT' || field.type === 'LONG_TEXT') && <div style={{ marginTop: 12 }}>
        <Typography.Text type="secondary">输入框里的灰字提示（可以不写）</Typography.Text>
        <Input value={field.placeholder} disabled={disabled} maxLength={200} style={{ marginTop: 6 }}
          placeholder="比如：说说这张照片是在哪拍的" onChange={event => updateField(index, { placeholder: event.target.value })} />
      </div>}
      {choiceTypes.has(field.type) && <div style={{ marginTop: 12 }}>
        <Typography.Text type="secondary">选项（至少两个）</Typography.Text>
        <Select mode="tags" value={field.options || []} disabled={disabled} style={{ width: '100%', marginTop: 6 }}
          tokenSeparators={[',', '，']} placeholder="打一个选项按一下回车"
          onChange={options => updateField(index, { options: options.map(option => option.trim()).filter(Boolean) })} />
      </div>}
      <Checkbox checked={field.required} disabled={disabled} style={{ marginTop: 14 }}
        onChange={event => updateField(index, { required: event.target.checked })}>这题必须填</Checkbox>
    </Card>)}

    {!disabled && <Button block type="dashed" icon={<PlusOutlined />} onClick={() => addField()}>
      加一道题
    </Button>}

    <Card size="small" title={<Space><FileImageOutlined /><span>作品上传</span><Tag>固定区域</Tag></Space>}>
      <Space orientation="vertical" size={12} style={{ width: '100%' }}>
        <div>
          <Typography.Text type="secondary">这一栏叫什么</Typography.Text>
          <Input value={schema.upload.label} disabled={disabled} maxLength={100} style={{ marginTop: 6 }}
            onChange={event => onChange?.({ ...schema, upload: { ...schema.upload, label: event.target.value } })} />
        </div>
        <div>
          <Typography.Text type="secondary">想让同学传什么，在这里说清楚</Typography.Text>
          <Input.TextArea value={schema.upload.prompt} disabled={disabled} maxLength={500} autoSize={{ minRows: 2, maxRows: 5 }}
            style={{ marginTop: 6 }} onChange={event => onChange?.({
              ...schema, upload: { ...schema.upload, prompt: event.target.value },
            })} />
        </div>
        <Space>
          <Switch checked={schema.upload.required} disabled={disabled} onChange={required => onChange?.({
            ...schema, upload: { ...schema.upload, required },
          })} />
          <Typography.Text>{schema.upload.required ? '必须交作品才能报名' : '不交作品也能报名'}</Typography.Text>
        </Space>
        <Typography.Text type="secondary">
          同学可以一次传 1–{limits.maxImageCount} 张 JPG / PNG（单张不超过 {describeBytes(limits.maxImageBytes)}），
          也可以打包成一个不超过 {describeBytes(limits.maxArchiveBytes)} 的 ZIP。原图保存，不压缩。
        </Typography.Text>
      </Space>
    </Card>
  </Space>
}

export { fieldTypeOptions, RECRUITMENT_FIELD_TYPES }
