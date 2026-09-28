import { SearchOutlined } from '@ant-design/icons'
import { Alert, Button, Checkbox, Col, Empty, Input, Row, Select, Skeleton, Space, Switch, Tag, Typography } from 'antd'
import { useMemo, useState } from 'react'
import { api } from './api'
import { useLoad } from './hooks'
import {
  addToSelection,
  filterAudience,
  normalizeAudienceOptions,
  removeFromSelection,
  type AudienceOptions,
} from './surveyTypes'

/**
 * 问卷发放对象选择器：按权限组、校区筛选，按姓名 / 账号搜索，筛选结果可以一键全选，
 * 也可以一键选中所有人。候选名单由后端给出——只有持有「问卷访问」权限的启用账号，
 * 校区范围的发起人只看得到自己校区的人；最终仍由后端再校验一遍。
 */
export default function SurveyAudiencePicker({ value = [], onChange, disabled = false }: {
  value?: string[]
  onChange?: (value: string[]) => void
  disabled?: boolean
}) {
  const options = useLoad(async () => normalizeAudienceOptions(await api<unknown>({ url: '/surveys/audience' })),
    { candidates: [], permissionGroups: [], campuses: [] } as AudienceOptions, [])
  const [groupIds, setGroupIds] = useState<string[]>([])
  const [campusIds, setCampusIds] = useState<string[]>([])
  const [keyword, setKeyword] = useState('')
  const [onlySelected, setOnlySelected] = useState(false)

  const candidates = options.data.candidates
  const campusName = useMemo(() => new Map(options.data.campuses.map(campus => [campus.id, campus.name])),
    [options.data.campuses])
  const filtered = useMemo(() => filterAudience(candidates, { groupIds, campusIds, keyword }),
    [candidates, groupIds, campusIds, keyword])
  const selected = useMemo(() => new Set(value), [value])
  const shown = onlySelected ? filtered.filter(candidate => selected.has(candidate.id)) : filtered
  const filteredIds = filtered.map(candidate => candidate.id)
  const selectedInFilter = filteredIds.filter(id => selected.has(id)).length
  const allIds = candidates.map(candidate => candidate.id)
  const knownIds = new Set(allIds)
  // 编辑时名单上可能有当前发起人选不到的人（别的校区的发起人加的），原样保留、单独计数。
  const retainedOutside = value.filter(id => !knownIds.has(id)).length
  const filtering = groupIds.length > 0 || campusIds.length > 0 || keyword.trim().length > 0

  const change = (next: string[]) => onChange?.(next)

  if (options.loading) return <Skeleton active paragraph={{ rows: 4 }} />
  if (options.error) return <Alert type="error" showIcon title="名单没能加载出来" description={options.error}
    action={<Button size="small" onClick={() => void options.reload()}>重试</Button>} />

  return <Space orientation="vertical" size={10} style={{ width: '100%' }}>
    <Row gutter={[8, 8]}>
      <Col xs={24} md={8}>
        <Select mode="multiple" allowClear placeholder="全部权限组" value={groupIds} onChange={setGroupIds}
          style={{ width: '100%' }} maxTagCount="responsive" aria-label="按权限组筛选"
          options={options.data.permissionGroups.map(group => ({ value: group.id, label: group.name }))} />
      </Col>
      <Col xs={24} md={8}>
        <Select mode="multiple" allowClear placeholder="全部校区" value={campusIds} onChange={setCampusIds}
          style={{ width: '100%' }} maxTagCount="responsive" aria-label="按校区筛选"
          options={options.data.campuses.map(campus => ({ value: campus.id, label: campus.name }))} />
      </Col>
      <Col xs={24} md={8}>
        <Input allowClear prefix={<SearchOutlined />} placeholder="按姓名或账号搜索" value={keyword}
          aria-label="按姓名搜索" onChange={event => setKeyword(event.target.value)} />
      </Col>
    </Row>

    <Space wrap size={[8, 8]}>
      <Button size="small" type="primary" ghost disabled={disabled || !filtered.length}
        onClick={() => change(addToSelection(value, filteredIds))}>
        全选筛选结果（{filtered.length}）
      </Button>
      <Button size="small" disabled={disabled || !selectedInFilter}
        onClick={() => change(removeFromSelection(value, filteredIds))}>
        取消选择筛选结果
      </Button>
      <Button size="small" disabled={disabled || !candidates.length}
        onClick={() => change(addToSelection(value, allIds))}>
        全选所有人（{candidates.length}）
      </Button>
      <Button size="small" danger disabled={disabled || !value.length} onClick={() => change([])}>清空</Button>
      <Space size={6}>
        <Switch size="small" checked={onlySelected} onChange={setOnlySelected} />
        <Typography.Text type="secondary">只看已选</Typography.Text>
      </Space>
    </Space>

    <Typography.Text type="secondary">
      已选 <Typography.Text strong>{value.length}</Typography.Text> 人
      {filtering && <>，当前筛选出 {filtered.length} 人（其中已选 {selectedInFilter} 人）</>}
      ，共 {candidates.length} 人可选。{retainedOutside > 0 && `另有 ${retainedOutside} 人不在你的可选范围内，会原样保留。`}
    </Typography.Text>

    {!candidates.length
      ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="还没有人有问卷访问权限，请先在「系统管理 → 权限组」里给相应的组勾上「访问和填写问卷」" />
      : <div className="audience-picker-list" role="group" aria-label="发放对象名单">
        {shown.length ? shown.map(candidate => <label key={candidate.id} className="audience-picker-row">
          <Checkbox checked={selected.has(candidate.id)} disabled={disabled}
            onChange={event => change(event.target.checked
              ? addToSelection(value, [candidate.id])
              : removeFromSelection(value, [candidate.id]))} />
          <div style={{ minWidth: 0, flex: 1 }}>
            <Typography.Text>{candidate.displayName}</Typography.Text>
            <span className="audience-picker-meta"> · {candidate.username}</span>
          </div>
          <Space size={4} wrap style={{ justifyContent: 'flex-end' }}>
            <Tag>{candidate.permissionGroupName}</Tag>
            {candidate.campusIds.map(id => campusName.get(id)).filter(Boolean).map(name =>
              <Tag key={name} color="green">{name}</Tag>)}
          </Space>
        </label>) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有符合条件的人" />}
      </div>}
  </Space>
}
