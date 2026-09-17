import { Select, Space, Typography } from 'antd'
import { StatusTag, statusText } from './components'
import { batchStatusCounts } from './requestBatch'
import type { RequestBatchRow } from './requestBatch'
import type { EntityId, PhotoRequest } from './types'

/** 需求列表的「校区」列：单校区直接显示名称，多校区用下拉切换，选项里带上各校区的状态。 */
export function RequestBatchCampusCell({ batch, selected, campusName, onSelect }: {
  batch: RequestBatchRow
  selected: PhotoRequest
  campusName: (campusId: EntityId) => string
  onSelect: (requestId: EntityId) => void
}) {
  if (batch.requests.length === 1) return <>{campusName(batch.representative.campusId)}</>
  return <Select size="small" style={{ minWidth: 150 }} value={selected.id}
    options={batch.requests.map(request => ({ value: request.id, label: campusName(request.campusId) }))}
    optionRender={option => {
      const request = batch.requests.find(item => item.id === option.value)
      return <Space size={6}>
        <span>{option.label}</span>
        {request && <StatusTag value={request.status} />}
      </Space>
    }}
    onChange={onSelect} />
}

/**
 * 需求列表的「状态」列：显示当前选中校区的状态；批次内状态不一致时再列出各状态的校区数，
 * 免得其他校区待确认的需求被默认选中的那一个挡住。
 */
export function RequestBatchStatusCell({ batch, selected }: {
  batch: RequestBatchRow
  selected: PhotoRequest
}) {
  const counts = batchStatusCounts(batch)
  if (counts.length <= 1) return <StatusTag value={selected.status} />
  return <Space direction="vertical" size={2}>
    <StatusTag value={selected.status} />
    <Typography.Text type="secondary" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>
      {counts.map(({ status, count }) => `${statusText(status)} ${count}`).join(' · ')}
    </Typography.Text>
  </Space>
}
