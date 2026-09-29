import { Checkbox, Form, Input, InputNumber, Modal, Radio, Select, Space, Typography } from 'antd'
import { useEffect } from 'react'
import type { DashboardWidget, DashboardWidgetSize, DashboardWidgetType, TrafficRange } from '../../types'
import {
  TRAFFIC_RANGES, WIDGET_SIZES, WIDGET_TYPES, metricChoices, metricOf, seriesOf, widgetTitle, widgetTypeOf,
} from '../../adminDashboard'
import { ADMIN_SECTIONS } from '../../adminNav'

interface EditorValues {
  type: DashboardWidgetType
  title?: string
  size: DashboardWidgetSize
  metric?: string
  range?: TrafficRange
  warnAt?: number | null
  criticalAt?: number | null
  text?: string
  links?: string[]
  limit?: number
}

const SHORTCUT_OPTIONS = ADMIN_SECTIONS.filter(section => section.key)
  .map(section => ({ value: section.key, label: section.label }))

/** 添加 / 设置一个小面板。只收这个类型用得上的字段，别的一概不存。 */
export default function DashboardWidgetEditor({ open, widget, isNew, onCancel, onSubmit }: {
  open: boolean
  widget: DashboardWidget
  isNew: boolean
  onCancel: () => void
  onSubmit: (widget: DashboardWidget) => void
}) {
  const [form] = Form.useForm<EditorValues>()
  const type = Form.useWatch('type', form) ?? widget.type
  const metric = Form.useWatch('metric', form)
  const choices = metricChoices(type)
  const series = type === 'trend' ? seriesOf(metric) : undefined
  const scalar = type === 'stat' || type === 'gauge' ? metricOf(metric) : undefined
  const unit = type === 'gauge' || scalar?.kind === 'percent' ? '%' : scalar?.kind === 'ms' ? 'ms' : undefined

  useEffect(() => {
    if (!open) return
    form.resetFields()
    form.setFieldsValue({
      type: widget.type, title: widget.title, size: widget.size, metric: widget.metric,
      range: widget.range ?? '1h', warnAt: widget.warnAt, criticalAt: widget.criticalAt,
      text: widget.text, links: widget.links ?? [], limit: widget.limit ?? 8,
    })
  }, [open, widget, form])

  const onTypeChange = (next: DashboardWidgetType) => {
    const nextChoices = metricChoices(next)
    const current = form.getFieldValue('metric') as string | undefined
    form.setFieldsValue({
      size: widgetTypeOf(next)?.size ?? 'small',
      metric: nextChoices.some(choice => choice.key === current) ? current : nextChoices[0]?.key,
      warnAt: undefined, criticalAt: undefined,
    })
  }

  const submit = async () => {
    const values = await form.validateFields()
    const next: DashboardWidget = {
      id: widget.id, type: values.type, size: values.size,
      title: values.title?.trim() || undefined,
    }
    if (values.type === 'stat' || values.type === 'gauge') {
      next.metric = values.metric
      if (values.warnAt != null) next.warnAt = values.warnAt
      if (values.criticalAt != null) next.criticalAt = values.criticalAt
    }
    if (values.type === 'trend') {
      next.metric = values.metric
      if (seriesOf(values.metric)?.source === 'traffic') next.range = values.range ?? '1h'
    }
    if (values.type === 'endpoints') next.limit = values.limit ?? 8
    if (values.type === 'shortcuts') next.links = values.links ?? []
    if (values.type === 'note') next.text = values.text ?? ''
    onSubmit(next)
  }

  const groupedChoices = [...new Set(choices.map(choice => choice.group))].map(group => ({
    label: group,
    options: choices.filter(choice => choice.group === group).map(choice => ({ value: choice.key, label: choice.label })),
  }))

  return <Modal open={open} title={isNew ? '添加小面板' : `设置「${widgetTitle(widget)}」`} okText={isNew ? '添加' : '确定'}
    cancelText="取消" onCancel={onCancel} onOk={() => void submit()} destroyOnHidden width={560}>
    <Form form={form} layout="vertical" requiredMark={false}>
      <Form.Item label="类型" name="type" rules={[{ required: true }]}
        extra={widgetTypeOf(type)?.description}>
        <Select onChange={onTypeChange}
          options={WIDGET_TYPES.map(item => ({ value: item.type, label: item.label }))} />
      </Form.Item>
      {choices.length > 0 && <Form.Item label={type === 'trend' ? '曲线' : '指标'} name="metric"
        rules={[{ required: true, message: '请选择一个指标' }]}
        extra={(series ?? scalar)?.description}>
        <Select showSearch optionFilterProp="label" options={groupedChoices} placeholder="选择要显示的指标" />
      </Form.Item>}
      {type === 'trend' && series?.source === 'traffic' && <Form.Item label="时间范围" name="range">
        <Radio.Group optionType="button" options={TRAFFIC_RANGES.map(item => ({ value: item.value, label: item.label }))} />
      </Form.Item>}
      {(type === 'stat' || type === 'gauge') && <Form.Item label="告警阈值"
        extra="达到即标黄 / 标红（值越大越糟）。留空表示不告警。">
        <Space wrap>
          <Form.Item name="warnAt" noStyle>
            <InputNumber placeholder="告警" addonBefore="告警" addonAfter={unit} style={{ width: 190 }} />
          </Form.Item>
          <Form.Item name="criticalAt" noStyle dependencies={['warnAt']} rules={[({ getFieldValue }) => ({
            validator: (_, value?: number | null) => {
              const warn = getFieldValue('warnAt') as number | null | undefined
              return value != null && warn != null && value < warn
                ? Promise.reject(new Error('严重阈值不能低于告警阈值')) : Promise.resolve()
            },
          })]}>
            <InputNumber placeholder="严重" addonBefore="严重" addonAfter={unit} style={{ width: 190 }} />
          </Form.Item>
        </Space>
      </Form.Item>}
      {type === 'endpoints' && <Form.Item label="显示条数" name="limit" rules={[{ required: true }]}>
        <InputNumber min={3} max={20} precision={0} />
      </Form.Item>}
      {type === 'shortcuts' && <Form.Item label="快捷入口" name="links"
        rules={[{ validator: (_, value: string[] = []) => value.length > 12
          ? Promise.reject(new Error('最多 12 个')) : Promise.resolve() }]}>
        <Checkbox.Group className="dashboard-link-options" options={SHORTCUT_OPTIONS} />
      </Form.Item>}
      {type === 'note' && <Form.Item label="内容" name="text" rules={[{ max: 2000, message: '便签不能超过 2000 个字符' }]}>
        <Input.TextArea rows={5} showCount maxLength={2000} placeholder="例如：本周值班：周一张三 · 周三李四" />
      </Form.Item>}
      <Form.Item label="标题" name="title" rules={[{ max: 40, message: '标题不能超过 40 个字符' }]}
        extra={<Typography.Text type="secondary">留空则使用「{widgetTitle({ ...widget, type, metric, title: '' })}」</Typography.Text>}>
        <Input maxLength={40} showCount placeholder="可选" />
      </Form.Item>
      <Form.Item label="大小" name="size" rules={[{ required: true }]}>
        <Radio.Group optionType="button" options={WIDGET_SIZES.map(item => ({ value: item.value, label: item.label }))} />
      </Form.Item>
    </Form>
  </Modal>
}
