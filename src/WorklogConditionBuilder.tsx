import { Button, Checkbox, DatePicker, Input, InputNumber, Select, Space, Typography } from 'antd'
import { BranchesOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import {
  FIELDS, MAX_UI_DEPTH, MAX_UI_NODES, OPERATOR_LABELS, changeField, changeOperator, countNodes, isComplete,
  isGroup, needsValues, newGroup, newLeaf,
} from './worklogAnalysis'
import type {
  AnalysisOptions, ConditionField, ConditionGroup, ConditionLeaf, ConditionNode, ConditionOperator, Logic,
} from './worklogAnalysis'

const FIELD_OPTIONS = (Object.keys(FIELDS) as ConditionField[]).map(field => ({ value: field, label: FIELDS[field].label }))

/**
 * 工时分析的条件编辑器：一棵「同时满足 / 满足其一」的条件树，分组可以取反、可以嵌套。
 *
 * 它只负责把树拼出来。取值按字段类型给对应的输入控件（下拉 / 日期 / 分钟数 / 文本），
 * 但这只是体验——真正的校验和防注入都在后端：字段和运算符是枚举白名单，取值一律绑定成参数。
 */
export default function WorklogConditionBuilder({ value, onChange, options, optionsLoading }: {
  value: ConditionGroup
  onChange: (next: ConditionGroup) => void
  options: AnalysisOptions
  optionsLoading?: boolean
}) {
  const full = countNodes(value) >= MAX_UI_NODES
  return <div className="condition-builder">
    <GroupEditor group={value} depth={1} root full={full} options={options} optionsLoading={optionsLoading}
      onChange={next => onChange(next as ConditionGroup)} />
  </div>
}

function GroupEditor({ group, depth, root, full, options, optionsLoading, onChange, onRemove }: {
  group: ConditionGroup
  depth: number
  root?: boolean
  full: boolean
  options: AnalysisOptions
  optionsLoading?: boolean
  onChange: (next: ConditionNode) => void
  onRemove?: () => void
}) {
  const replaceChild = (index: number, child: ConditionNode) =>
    onChange({ ...group, children: group.children.map((item, i) => i === index ? child : item) })
  const removeChild = (index: number) =>
    onChange({ ...group, children: group.children.filter((_, i) => i !== index) })
  return <div className={`condition-group${root ? ' condition-group-root' : ''}`}>
    <div className="condition-group-head">
      <Select<Logic> size="small" value={group.logic} style={{ width: 196 }} aria-label="条件之间的关系"
        options={[{ value: 'AND', label: '同时满足以下全部条件' }, { value: 'OR', label: '满足以下任一条件' }]}
        onChange={logic => onChange({ ...group, logic })} />
      <Checkbox checked={!!group.not} onChange={event => onChange({ ...group, not: event.target.checked })}>
        取反（不满足这一组）</Checkbox>
      {onRemove && <Button size="small" type="text" danger icon={<DeleteOutlined />} onClick={onRemove}
        aria-label="删除这一组">删除分组</Button>}
    </div>
    <div className="condition-group-body">
      {!group.children.length && <Typography.Text type="secondary">
        这一组还没有条件，不起筛选作用。</Typography.Text>}
      {group.children.map((child, index) => isGroup(child)
        ? <GroupEditor key={child.key} group={child} depth={depth + 1} full={full} options={options}
            optionsLoading={optionsLoading}
            onChange={next => replaceChild(index, next)} onRemove={() => removeChild(index)} />
        : <LeafEditor key={child.key} leaf={child} options={options} optionsLoading={optionsLoading}
            onChange={next => replaceChild(index, next)} onRemove={() => removeChild(index)} />)}
      <Space size={4}>
        <Button size="small" type="dashed" icon={<PlusOutlined />} disabled={full}
          onClick={() => onChange({ ...group, children: [...group.children, newLeaf()] })}>添加条件</Button>
        {depth < MAX_UI_DEPTH && <Button size="small" type="dashed" icon={<BranchesOutlined />} disabled={full}
          onClick={() => onChange({ ...group, children: [...group.children,
            newGroup(group.logic === 'AND' ? 'OR' : 'AND', [newLeaf()])] })}>添加分组</Button>}
        {full && <Typography.Text type="secondary">条件已达上限 {MAX_UI_NODES} 条</Typography.Text>}
      </Space>
    </div>
  </div>
}

function LeafEditor({ leaf, options, optionsLoading, onChange, onRemove }: {
  leaf: ConditionLeaf
  options: AnalysisOptions
  optionsLoading?: boolean
  onChange: (next: ConditionLeaf) => void
  onRemove: () => void
}) {
  const meta = FIELDS[leaf.field]
  const need = needsValues(leaf.operator)
  return <div className={`condition-leaf${isComplete(leaf) ? '' : ' condition-leaf-incomplete'}`}>
    <Select<ConditionField> size="small" value={leaf.field} style={{ width: 150 }} options={FIELD_OPTIONS}
      aria-label="字段" showSearch optionFilterProp="label" onChange={field => onChange(changeField(leaf, field))} />
    <Select<ConditionOperator> size="small" value={leaf.operator} style={{ width: 96 }} aria-label="比较方式"
      options={meta.operators.map(operator => ({ value: operator, label: OPERATOR_LABELS[operator] }))}
      onChange={operator => onChange(changeOperator(leaf, operator))} />
    {need !== 0 && <ValueEditor leaf={leaf} options={options} optionsLoading={optionsLoading}
      onChange={values => onChange({ ...leaf, values })} />}
    <Button size="small" type="text" icon={<DeleteOutlined />} aria-label="删除这条条件" onClick={onRemove} />
    {!isComplete(leaf) && <Typography.Text type="secondary" className="condition-leaf-hint">未填完，暂不生效</Typography.Text>}
  </div>
}

function ValueEditor({ leaf, options, optionsLoading, onChange }: {
  leaf: ConditionLeaf
  options: AnalysisOptions
  optionsLoading?: boolean
  onChange: (values: string[]) => void
}) {
  const meta = FIELDS[leaf.field]
  const need = needsValues(leaf.operator)
  const multiple = need === -1
  if (meta.kind === 'enum' || meta.kind === 'option') {
    const source = meta.kind === 'enum' ? meta.enumOptions ?? [] : options[meta.optionSource!] ?? []
    // 同名同姓常有：成员按学号筛，下拉里把学号也写上。
    const choices = meta.optionSource === 'members'
      ? source.map(choice => ({ value: choice.value, label: `${choice.label}（${choice.value}）` }))
      : source
    // 已选的值可能不在候选里（换了日期范围之后那个人这段时间没有工时）：仍然摆出来，免得条件看不见、清不掉。
    const known = new Set(choices.map(choice => choice.value))
    const merged = [...choices, ...leaf.values.filter(value => !known.has(value)).map(value => ({ value, label: value }))]
    return <Select size="small" mode={multiple ? 'multiple' : undefined} allowClear showSearch
      optionFilterProp="label" maxTagCount={4} style={{ minWidth: 200, maxWidth: 420, flex: 1 }}
      loading={optionsLoading} placeholder="选择取值" options={merged}
      value={multiple ? leaf.values : leaf.values[0]}
      onChange={(value: string | string[] | undefined) =>
        onChange(value == null ? [] : Array.isArray(value) ? value : [value])} />
  }
  if (meta.kind === 'date') {
    if (need === 2) {
      return <DatePicker.RangePicker size="small"
        value={[leaf.values[0] ? dayjs(leaf.values[0]) : null, leaf.values[1] ? dayjs(leaf.values[1]) : null]}
        onChange={values => onChange(values?.[0] && values[1]
          ? [values[0].format('YYYY-MM-DD'), values[1].format('YYYY-MM-DD')] : [])} />
    }
    return <DatePicker size="small" value={leaf.values[0] ? dayjs(leaf.values[0]) : null}
      onChange={value => onChange(value ? [value.format('YYYY-MM-DD')] : [])} />
  }
  if (meta.kind === 'number') {
    const input = (index: number) => <InputNumber size="small" min={0} max={100000} precision={0} suffix="分钟"
      style={{ width: 130 }} value={leaf.values[index] === undefined || leaf.values[index] === ''
        ? null : Number(leaf.values[index])}
      onChange={value => {
        const next = [...leaf.values]
        next[index] = value == null ? '' : String(value)
        onChange(next)
      }} />
    return need === 2 ? <Space size={4}>{input(0)}<span>至</span>{input(1)}</Space> : input(0)
  }
  if (multiple) {
    // 文本的「属于」：一行一个也行，逗号分隔也行。
    return <Select size="small" mode="tags" style={{ minWidth: 200, flex: 1 }} value={leaf.values}
      tokenSeparators={[',', '，', '\n']} placeholder="输入后回车，可填多个" open={false}
      onChange={(values: string[]) => onChange(values.map(value => value.trim()).filter(Boolean))} />
  }
  return <Input size="small" style={{ width: 200 }} maxLength={100} value={leaf.values[0] ?? ''}
    placeholder="输入关键词" onChange={event => onChange(event.target.value ? [event.target.value] : [])} />
}
