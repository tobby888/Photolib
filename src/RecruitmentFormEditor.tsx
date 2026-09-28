import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  CloseOutlined,
  DeleteOutlined,
  FileImageOutlined,
  HolderOutlined,
  IdcardOutlined,
  PlusOutlined,
} from '@ant-design/icons'
import { Alert, Button, Card, Checkbox, Col, Input, Row, Select, Space, Switch, Tag, Typography } from 'antd'
import { useState, type DragEvent, type KeyboardEvent } from 'react'
import {
  FORM_FILE_LIMITS,
  RECRUITMENT_FIELD_TYPES,
  createRecruitmentField,
  isChoiceField,
  moveItem,
  nextOptionLabel,
  normalizeRecruitmentFormSchema,
  type RecruitmentFieldType,
  type RecruitmentFormField,
  type RecruitmentFormSchema,
} from './recruitmentForm'
import { describeBytes, type RecruitmentUploadLimits } from './recruitmentUpload'
import { useUploadLimits } from './useUploadLimits'

const fieldTypeOptions: { value: RecruitmentFieldType; label: string }[] = [
  { value: 'SHORT_TEXT', label: '单行文本' },
  { value: 'LONG_TEXT', label: '多行文本' },
  { value: 'SINGLE_CHOICE', label: '单选' },
  { value: 'MULTIPLE_CHOICE', label: '多选' },
  { value: 'DATE', label: '日期' },
  { value: 'FILE_UPLOAD', label: '上传文件' },
]

/**
 * 选择题的选项：一行一个输入框，拖动左边的把手调整顺序（键盘上 Alt + ↑/↓ 也行），
 * 末尾「添加选项」新建一项。空白和重复的选项在编辑时保留，保存时由页面统一整理。
 */
export function ChoiceOptionsEditor({ options, onChange, disabled = false }: {
  options: string[]
  onChange: (options: string[]) => void
  disabled?: boolean
}) {
  const [dragging, setDragging] = useState<number>()
  const [over, setOver] = useState<number>()

  const endDrag = () => {
    setDragging(undefined)
    setOver(undefined)
  }
  const drop = (event: DragEvent, target: number) => {
    event.preventDefault()
    if (dragging !== undefined) onChange(moveItem(options, dragging, target))
    endDrag()
  }
  const keyboardMove = (event: KeyboardEvent, index: number) => {
    if (!event.altKey || (event.key !== 'ArrowUp' && event.key !== 'ArrowDown')) return
    event.preventDefault()
    onChange(moveItem(options, index, event.key === 'ArrowUp' ? index - 1 : index + 1))
  }

  return <div className="choice-options-editor">
    {options.map((option, index) => <div key={index}
      className={`choice-option-row${dragging === index ? ' is-dragging' : ''}${over === index && dragging !== index ? ' is-drop-target' : ''}`}
      onDragOver={event => {
        if (dragging === undefined) return
        event.preventDefault()
        event.dataTransfer.dropEffect = 'move'
        if (over !== index) setOver(index)
      }}
      onDrop={event => drop(event, index)}>
      <span className="choice-option-handle" draggable={!disabled} tabIndex={disabled ? -1 : 0}
        role="button" aria-label={`拖动调整第 ${index + 1} 个选项的位置（Alt + 上下方向键也可以移动）`}
        aria-disabled={disabled}
        onDragStart={event => {
          event.dataTransfer.effectAllowed = 'move'
          event.dataTransfer.setData('text/plain', String(index))
          setDragging(index)
        }}
        onDragEnd={endDrag}
        onKeyDown={event => keyboardMove(event, index)}><HolderOutlined /></span>
      <Input value={option} disabled={disabled} maxLength={100} placeholder={`选项 ${index + 1}`}
        aria-label={`第 ${index + 1} 个选项`}
        onChange={event => onChange(options.map((current, position) => position === index ? event.target.value : current))}
        onPressEnter={() => !disabled && onChange([...options, nextOptionLabel(options)])} />
      {!disabled && <Button type="text" aria-label={`删除第 ${index + 1} 个选项`} icon={<CloseOutlined />}
        disabled={options.length <= 2} onClick={() => onChange(options.filter((_, position) => position !== index))} />}
    </div>)}
    {!disabled && <Button type="dashed" size="small" icon={<PlusOutlined />} disabled={options.length >= 50}
      onClick={() => onChange([...options, nextOptionLabel(options)])}>添加选项</Button>}
  </div>
}

export default function RecruitmentFormEditor({
  value, onChange, disabled = false, limits: givenLimits, variant = 'recruitment',
}: {
  value?: RecruitmentFormSchema | string | null
  onChange?: (value: RecruitmentFormSchema) => void
  disabled?: boolean
  /** 问卷没有学号项和作品上传区，只编辑题目。 */
  variant?: 'recruitment' | 'survey'
  /**
   * Quota shown to the person building the form. Pass the task's own limits when
   * one exists; otherwise the administrator-managed values (系统管理 → 上传限额) are shown.
   */
  limits?: RecruitmentUploadLimits
}) {
  const uploadLimits = useUploadLimits()
  const limits: RecruitmentUploadLimits = givenLimits ?? {
    maxImageCount: uploadLimits.RECRUITMENT_MAX_IMAGES,
    maxImageBytes: uploadLimits.RECRUITMENT_IMAGE_MAX_BYTES,
    maxArchiveBytes: uploadLimits.RECRUITMENT_ZIP_MAX_BYTES,
  }
  const schema = normalizeRecruitmentFormSchema(value, { keepDraftOptions: true })
  const recruitment = variant === 'recruitment'

  const updateField = (index: number, updates: Partial<RecruitmentFormField>) => {
    const fields = schema.fields.map((field, current) => current === index ? { ...field, ...updates } : field)
    onChange?.({ ...schema, fields })
  }

  const changeType = (index: number, type: RecruitmentFieldType) => {
    const current = schema.fields[index]
    updateField(index, {
      type,
      options: isChoiceField(type) ? current.options?.length ? current.options : ['选项 1', '选项 2'] : undefined,
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
    {recruitment && <Alert type="info" showIcon title="学号和作品上传是固定项"
      description="学号用来防止同一个人重复报名，删不掉。同学传上来的照片会按原图保存，我们不压缩、不转格式。" />}

    {recruitment && <Card size="small" title={<Space><IdcardOutlined /><span>学号</span><Tag color="red">必填</Tag></Space>}>
      <Space orientation="vertical" size={10} style={{ width: '100%' }}>
        <Input value={schema.studentId.label} disabled={disabled} maxLength={100} placeholder="这一栏叫什么，比如「学号」"
          onChange={event => onChange?.({ ...schema, studentId: { ...schema.studentId, label: event.target.value } })} />
        <Input value={schema.studentId.helpText} disabled={disabled} maxLength={500} placeholder="给同学的提示（可以不写）"
          onChange={event => onChange?.({ ...schema, studentId: { ...schema.studentId, helpText: event.target.value } })} />
        <Typography.Text type="secondary">永远排在表单第一项。学号按文本保存，开头的 0 不会丢。</Typography.Text>
      </Space>
    </Card>}

    {!recruitment && !schema.fields.length && <Alert type="info" showIcon title="还没有题目"
      description="点下面的「加一道题」开始出题。题型和招募报名表一样，还可以让大家上传文件。" />}

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
            placeholder={recruitment ? '比如：为什么想加入摄影部？' : '比如：你对这学期的活动满意吗？'} onChange={event => updateField(index, { label: event.target.value })} />
        </Col>
      </Row>
      <div style={{ marginTop: 12 }}>
        <Typography.Text type="secondary">补充说明（可以不写）</Typography.Text>
        <Input value={field.helpText} disabled={disabled} maxLength={500} style={{ marginTop: 6 }}
          placeholder={recruitment ? '想提醒同学注意什么，写在这里' : '想提醒大家注意什么，写在这里'} onChange={event => updateField(index, { helpText: event.target.value })} />
      </div>
      {(field.type === 'SHORT_TEXT' || field.type === 'LONG_TEXT') && <div style={{ marginTop: 12 }}>
        <Typography.Text type="secondary">输入框里的灰字提示（可以不写）</Typography.Text>
        <Input value={field.placeholder} disabled={disabled} maxLength={200} style={{ marginTop: 6 }}
          placeholder="比如：说说这张照片是在哪拍的" onChange={event => updateField(index, { placeholder: event.target.value })} />
      </div>}
      {isChoiceField(field.type) && <div style={{ marginTop: 12 }}>
        <Typography.Text type="secondary">选项（至少两个，拖动左边的把手调整顺序）</Typography.Text>
        <div style={{ marginTop: 6 }}>
          <ChoiceOptionsEditor options={field.options || []} disabled={disabled}
            onChange={options => updateField(index, { options })} />
        </div>
      </div>}
      {field.type === 'FILE_UPLOAD' && <Typography.Paragraph type="secondary" style={{ margin: '12px 0 0' }}>
        {recruitment ? '同学' : '填写的人'}可以在这道题里传 1–{FORM_FILE_LIMITS.maxFilesPerField} 个文件，
        任意格式，单个不超过 {describeBytes(uploadLimits.FORM_FILE_MAX_BYTES)}。文件原样保存，只有能看结果的人才能下载。
      </Typography.Paragraph>}
      <Checkbox checked={field.required} disabled={disabled} style={{ marginTop: 14 }}
        onChange={event => updateField(index, { required: event.target.checked })}>这题必须填</Checkbox>
    </Card>)}

    {!disabled && <Button block type="dashed" icon={<PlusOutlined />} onClick={() => addField()}>
      加一道题
    </Button>}

    {recruitment && <Card size="small" title={<Space><FileImageOutlined /><span>作品上传</span><Tag>固定区域</Tag></Space>}>
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
    </Card>}
  </Space>
}

export { fieldTypeOptions, RECRUITMENT_FIELD_TYPES }
