import { App, Button, DatePicker, Descriptions, Drawer, Empty, Form, Input, Select, Space, Tag, Tooltip, Typography } from 'antd'
import { DownloadOutlined, FilterOutlined, PaperClipOutlined, SearchOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, emptyPage, http, qs } from './api'
import { describeBytes } from './uploadLimits'
import { ContentFitTable, TableEllipsisText } from './ContentFitTable'
import { roleName } from './components'
import type { AuditLog, AuditLogCatalog, PageData } from './types'
import { serverTablePagination, turnPage } from './pagination'
import { describeUserAgent, formatDuration, isFlatRecord, parseAuditDetail } from './auditLogs'

interface LogFilters {
  keyword?: string
  actionName?: string
  resourceType?: string
  outcome?: 'success' | 'failure'
  range?: [Dayjs, Dayjs]
}

/** 从详情里点「只看此人 / 只看此 IP」加上的条件，显示成可关掉的标签。 */
interface PinnedFilters {
  operator?: { id: AuditLog['operatorId']; label: string }
  ipAddress?: string
}

const methodColors: Record<string, string> = { GET: 'default', POST: 'green', PUT: 'blue', PATCH: 'gold', DELETE: 'red' }

function operatorName(item: AuditLog) {
  if (item.operatorDisplayName) return item.operatorDisplayName
  if (item.operatorUsername) return `@${item.operatorUsername}`
  return item.operatorId ? `已删除的账号 #${item.operatorId}` : '未登录访客'
}

function actionTitle(item: AuditLog) {
  return item.actionName || `${item.action} ${parseAuditDetail(item.detailJson).path ?? ''}`.trim()
}

function OutcomeTag({ item }: { item: AuditLog }) {
  if (item.success === null || item.success === undefined) return <Tag>未知</Tag>
  if (item.success) return <Tag color="success">成功</Tag>
  const reason = item.errorMessage || (item.httpStatus ? `HTTP ${item.httpStatus}` : '失败')
  return <Tooltip title={reason}><Tag color="error">失败{item.httpStatus ? ` ${item.httpStatus}` : ''}</Tag></Tooltip>
}

function JsonBlock({ value }: { value: unknown }) {
  return <pre className="audit-json">{JSON.stringify(value, null, 2)}</pre>
}

/** 平对象用键值表，嵌套的、数组和说明文字用缩进 JSON。 */
function PayloadView({ value }: { value: unknown }) {
  if (typeof value === 'string') return <Typography.Text type="secondary">{value}</Typography.Text>
  if (isFlatRecord(value) && Object.keys(value).length > 0) {
    return <Descriptions column={1} size="small" bordered items={Object.entries(value).map(([key, item]) => ({
      key, label: key,
      children: item === null ? <Typography.Text type="secondary">空</Typography.Text> : String(item),
    }))} />
  }
  return <JsonBlock value={value} />
}

export default function AuditLogsPanel() {
  const { message } = App.useApp()
  const [form] = Form.useForm<LogFilters>()
  const [filters, setFilters] = useState<LogFilters>({})
  const [pinned, setPinned] = useState<PinnedFilters>({})
  // 每页条数由这里持有并进请求：交给 antd 自己记会被下面传进去的 pageSize 盖回去（见 src/pagination.ts）。
  const [paging, setPaging] = useState({ page: 1, pageSize: 20 })
  const { page, pageSize } = paging
  const toFirstPage = () => setPaging(current => ({ ...current, page: 1 }))
  const [data, setData] = useState<PageData<AuditLog>>(emptyPage())
  const [catalog, setCatalog] = useState<AuditLogCatalog>({ actions: [], resourceTypes: [] })
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState<AuditLog>()
  const params = qs({
    keyword: filters.keyword,
    actionName: filters.actionName,
    resourceType: filters.resourceType,
    success: filters.outcome === undefined ? undefined : filters.outcome === 'success',
    operatorId: pinned.operator?.id,
    ipAddress: pinned.ipAddress,
    from: filters.range?.[0].format('YYYY-MM-DD'),
    to: filters.range?.[1].format('YYYY-MM-DD'),
  })

  useEffect(() => {
    api<AuditLogCatalog>({ url: '/audit-logs/catalog' })
      .then(setCatalog)
      .catch(() => { /* 选项拿不到时下拉框为空，列表照常可用 */ })
  }, [])

  useEffect(() => {
    setLoading(true)
    api<PageData<AuditLog>>({ url: '/audit-logs', params: { ...params, page, pageSize } })
      .then(setData)
      .catch(error => message.error((error as Error).message))
      .finally(() => setLoading(false))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters, pinned, page, pageSize])

  const actionOptions = useMemo(() => {
    const groups = new Map<string, Array<{ value: string; label: string }>>()
    for (const action of catalog.actions) {
      const group = groups.get(action.resourceLabel) ?? []
      group.push({ value: action.value, label: action.value })
      groups.set(action.resourceLabel, group)
    }
    return Array.from(groups.entries()).map(([label, options]) => ({ label, title: label, options }))
  }, [catalog.actions])

  const pin = (next: PinnedFilters) => {
    toFirstPage()
    setPinned(current => ({ ...current, ...next }))
    setSelected(undefined)
  }

  const exportLogs = async () => {
    try {
      const response = await http.get<Blob>('/audit-logs/export', { params, responseType: 'blob' })
      const url = URL.createObjectURL(response.data)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `audit-logs-${dayjs().format('YYYY-MM-DD')}.csv`
      anchor.click()
      URL.revokeObjectURL(url)
      message.success('日志已导出')
    } catch {
      message.error('日志导出失败，请稍后重试')
    }
  }

  return <>
    <div className="tab-toolbar">
      <div>
        <Typography.Title level={4}>操作日志</Typography.Title>
        <Typography.Text type="secondary">谁在什么时候对什么做了什么、成功没有。点一行看请求参数和失败原因。</Typography.Text>
      </div>
      <Button icon={<DownloadOutlined />} onClick={() => void exportLogs()}>导出日志</Button>
    </div>
    <Form form={form} layout="inline" style={{ gap: 8, marginBottom: 12 }}
      onFinish={values => { toFirstPage(); setFilters(values) }}>
      <Form.Item name="keyword">
        <Input allowClear prefix={<SearchOutlined />} placeholder="姓名、账号、对象名称、失败原因、IP" style={{ width: 280 }} />
      </Form.Item>
      <Form.Item name="actionName">
        <Select allowClear showSearch placeholder="操作" style={{ width: 200 }} options={actionOptions}
          optionFilterProp="label" popupMatchSelectWidth={260} />
      </Form.Item>
      <Form.Item name="resourceType">
        <Select allowClear showSearch placeholder="模块" style={{ width: 150 }} optionFilterProp="label"
          options={catalog.resourceTypes.map(item => ({ value: item.value, label: item.label }))} />
      </Form.Item>
      <Form.Item name="outcome">
        <Select allowClear placeholder="结果" style={{ width: 100 }}
          options={[{ value: 'success', label: '成功' }, { value: 'failure', label: '失败' }]} />
      </Form.Item>
      <Form.Item name="range"><DatePicker.RangePicker allowClear /></Form.Item>
      <Button type="primary" htmlType="submit">查询</Button>
      <Button onClick={() => { form.resetFields(); toFirstPage(); setFilters({}); setPinned({}) }}>重置</Button>
    </Form>
    {(pinned.operator || pinned.ipAddress) && <Space wrap style={{ marginBottom: 12 }}>
      <FilterOutlined />
      {pinned.operator && <Tag closable onClose={() => { toFirstPage(); setPinned(current => ({ ...current, operator: undefined })) }}>
        操作者：{pinned.operator.label}
      </Tag>}
      {pinned.ipAddress && <Tag closable onClose={() => { toFirstPage(); setPinned(current => ({ ...current, ipAddress: undefined })) }}>
        IP：{pinned.ipAddress}
      </Tag>}
    </Space>}
    <ContentFitTable rowKey="id" loading={loading} dataSource={data.items}
      pagination={serverTablePagination(paging, data.total,
        (nextPage, nextSize) => setPaging(current => turnPage(current, nextPage, nextSize)),
        { showTotal: total => `共 ${total} 条` })}
      onRow={record => ({ onClick: () => setSelected(record), style: { cursor: 'pointer' } })}
      columns={[
        { title: '时间', dataIndex: 'createdAt', width: 170, render: value => dayjs(value).format('YYYY-MM-DD HH:mm:ss') },
        { title: '操作者', width: 170, render: (_, item) =>
          <div className="table-title">
            <strong>{operatorName(item)}</strong>
            <span>{[item.operatorUsername && `@${item.operatorUsername}`, item.operatorGroupName].filter(Boolean).join(' · ') || '-'}</span>
          </div> },
        { title: '操作', minWidth: 200, render: (_, item) =>
          <Space size={6}>
            <Typography.Text strong={Boolean(item.actionName)} type={item.actionName ? undefined : 'secondary'}>
              {actionTitle(item)}
            </Typography.Text>
            {item.action === 'GET' && <Tag>读取</Tag>}
          </Space> },
        { title: '对象', minWidth: 220, render: (_, item) =>
          <Space size={6}>
            <Tag>{item.resourceLabel || item.resourceType}</Tag>
            {item.resourceName
              ? <TableEllipsisText value={item.resourceName} maxWidth={180} />
              : item.resourceId ? <Typography.Text code>#{item.resourceId}</Typography.Text> : <Typography.Text type="secondary">-</Typography.Text>}
          </Space> },
        { title: '结果', width: 110, render: (_, item) => <OutcomeTag item={item} /> },
        { title: '来源', width: 200, render: (_, item) =>
          <div className="table-title">
            <strong>{item.ipAddress || '-'}</strong>
            <span>{item.userAgent ? describeUserAgent(item.userAgent).label : '-'}</span>
          </div> },
      ]} />
    <Drawer title="日志详情" width={640} open={Boolean(selected)} onClose={() => setSelected(undefined)}>
      {selected && <AuditLogDetail item={selected}
        onPinOperator={selected.operatorId ? () => pin({ operator: { id: selected.operatorId, label: operatorName(selected) } }) : undefined}
        onPinIp={selected.ipAddress ? () => pin({ ipAddress: selected.ipAddress ?? undefined }) : undefined} />}
    </Drawer>
  </>
}

function AuditLogDetail({ item, onPinOperator, onPinIp }: {
  item: AuditLog
  onPinOperator?: () => void
  onPinIp?: () => void
}) {
  const detail = parseAuditDetail(item.detailJson)
  const device = describeUserAgent(item.userAgent)
  const sections: Array<{ title: string; content: ReactNode }> = []
  if (detail.body !== undefined) sections.push({ title: '提交的内容', content: <PayloadView value={detail.body} /> })
  if (detail.params) sections.push({ title: '查询与表单参数', content: <PayloadView value={detail.params} /> })
  if (detail.files.length > 0) {
    sections.push({ title: '上传的文件', content: <Space direction="vertical" size={4}>
      {detail.files.map((file, index) => <span key={index}>
        <PaperClipOutlined /> {file.name || '(无文件名)'}
        <Typography.Text type="secondary"> · {typeof file.size === 'number' ? describeBytes(file.size) : '-'}
          {file.contentType ? ` · ${file.contentType}` : ''}</Typography.Text>
      </span>)}
    </Space> })
  }
  if (Object.keys(detail.extra).length > 0) sections.push({ title: '附加信息', content: <PayloadView value={detail.extra} /> })

  return <Space direction="vertical" size={20} style={{ width: '100%' }}>
    <Descriptions column={1} bordered size="small" items={[
      { key: 'time', label: '时间', children: dayjs(item.createdAt).format('YYYY-MM-DD HH:mm:ss') },
      { key: 'operator', label: '操作者', children: <Space wrap>
        <span>{operatorName(item)}{item.operatorUsername ? ` (@${item.operatorUsername})` : ''}</span>
        {item.operatorRole && <Tag>{roleName[item.operatorRole] ?? item.operatorRole}</Tag>}
        {item.operatorGroupName && <Tag>{item.operatorGroupName}</Tag>}
        {onPinOperator && <Button size="small" type="link" onClick={onPinOperator}>只看此人</Button>}
      </Space> },
      { key: 'action', label: '操作', children: <Space wrap>
        <strong>{actionTitle(item)}</strong>
        {item.actionNameInferred && <Tooltip title="这条是升级前记的，操作名按接口路径推断"><Tag>推断</Tag></Tooltip>}
        <Tag color={methodColors[item.action]}>{item.action}</Tag>
        {detail.path && <Typography.Text type="secondary" code>{detail.path}</Typography.Text>}
      </Space> },
      { key: 'resource', label: '对象', children: <Space wrap>
        <Tag>{item.resourceLabel || item.resourceType}</Tag>
        {item.resourceName && <span>{item.resourceName}</span>}
        {item.resourceId && <Typography.Text code copyable>{item.resourceId}</Typography.Text>}
        {!item.resourceName && !item.resourceId && <Typography.Text type="secondary">-</Typography.Text>}
      </Space> },
      { key: 'outcome', label: '结果', children: <Space wrap>
        <OutcomeTag item={item} />
        {item.httpStatus && <Typography.Text type="secondary">HTTP {item.httpStatus}</Typography.Text>}
        {item.errorCode && <Typography.Text type="secondary" code>{item.errorCode}</Typography.Text>}
      </Space> },
      ...(item.errorMessage ? [{ key: 'error', label: '失败原因', children: <Typography.Text type="danger">{item.errorMessage}</Typography.Text> }] : []),
      { key: 'ip', label: 'IP 地址', children: <Space>
        <span>{item.ipAddress || '-'}</span>
        {onPinIp && <Button size="small" type="link" onClick={onPinIp}>只看此 IP</Button>}
      </Space> },
      { key: 'device', label: '设备', children: item.userAgent
        ? <Space direction="vertical" size={0}>
          <span>{device.label}</span>
          <Typography.Text type="secondary" style={{ fontSize: 12, wordBreak: 'break-all' }}>{item.userAgent}</Typography.Text>
        </Space>
        : '-' },
      { key: 'duration', label: '耗时', children: formatDuration(item.durationMs) },
      { key: 'request', label: '请求 ID', children: <Typography.Text copyable>{item.requestId}</Typography.Text> },
    ]} />
    {sections.length === 0
      ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="这次请求没有带参数" />
      : sections.map(section => <div key={section.title}>
        <Typography.Title level={5} style={{ marginTop: 0 }}>{section.title}</Typography.Title>
        {section.content}
      </div>)}
    <details>
      <summary><Typography.Text type="secondary">原始记录</Typography.Text></summary>
      <JsonBlock value={(() => { try { return JSON.parse(item.detailJson || '{}') } catch { return item.detailJson } })()} />
    </details>
  </Space>
}
