import { App, Button, DatePicker, Descriptions, Drawer, Form, Input, Select, Space, Table, Tag, Typography } from 'antd'
import { DownloadOutlined, SearchOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useState } from 'react'
import { api, emptyPage, http, qs } from './api'
import type { AuditLog, PageData } from './types'

interface LogFilters {
  keyword?: string
  action?: string
  resourceType?: string
  range?: [Dayjs, Dayjs]
}

const actionColors: Record<string, string> = { POST: 'green', PUT: 'blue', PATCH: 'gold', DELETE: 'red' }

export default function AuditLogsPanel() {
  const { message } = App.useApp()
  const [form] = Form.useForm<LogFilters>()
  const [filters, setFilters] = useState<LogFilters>({})
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(20)
  const [data, setData] = useState<PageData<AuditLog>>(emptyPage())
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState<AuditLog>()
  const params = qs({
    keyword: filters.keyword,
    action: filters.action,
    resourceType: filters.resourceType,
    from: filters.range?.[0].format('YYYY-MM-DD'),
    to: filters.range?.[1].format('YYYY-MM-DD'),
  })

  useEffect(() => {
    setLoading(true)
    api<PageData<AuditLog>>({ url: '/audit-logs', params: { ...params, page, pageSize } })
      .then(setData)
      .catch(error => message.error((error as Error).message))
      .finally(() => setLoading(false))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters, page, pageSize])

  const exportLogs = async () => {
    try {
      const response = await http.get<Blob>('/audit-logs/export', { params, responseType: 'blob' })
      const url = URL.createObjectURL(response.data)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `photolib-audit-logs-${dayjs().format('YYYY-MM-DD')}.csv`
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
        <Typography.Text type="secondary">查询系统内的写入操作，并将当前筛选结果导出为 CSV 文件。</Typography.Text>
      </div>
      <Button icon={<DownloadOutlined />} onClick={() => void exportLogs()}>导出日志</Button>
    </div>
    <Form form={form} layout="inline" style={{ gap: 8, marginBottom: 20 }}>
      <Form.Item name="keyword"><Input allowClear prefix={<SearchOutlined />} placeholder="账号、姓名、资源 ID、请求 ID 或 IP" style={{ width: 280 }} /></Form.Item>
      <Form.Item name="action"><Select allowClear placeholder="操作类型" style={{ width: 120 }}
        options={['POST', 'PUT', 'PATCH', 'DELETE'].map(value => ({ value, label: value }))} /></Form.Item>
      <Form.Item name="resourceType"><Input allowClear placeholder="资源类型" style={{ width: 140 }} /></Form.Item>
      <Form.Item name="range"><DatePicker.RangePicker allowClear /></Form.Item>
      <Button type="primary" onClick={() => { setPage(1); setFilters(form.getFieldsValue()) }}>查询</Button>
      <Button onClick={() => { form.resetFields(); setPage(1); setFilters({}) }}>重置</Button>
    </Form>
    <Table rowKey="id" loading={loading} dataSource={data.items} scroll={{ x: 1000 }}
      pagination={{ current: page, pageSize, total: data.total, showSizeChanger: true,
        showTotal: total => `共 ${total} 条`,
        onChange: (nextPage, nextSize) => { setPage(nextSize === pageSize ? nextPage : 1); setPageSize(nextSize) } }}
      onRow={record => ({ onClick: () => setSelected(record), style: { cursor: 'pointer' } })}
      columns={[
        { title: '时间', dataIndex: 'createdAt', width: 180, render: value => dayjs(value).format('YYYY-MM-DD HH:mm:ss') },
        { title: '操作者', width: 180, render: (_, item) =>
          <div className="table-title"><strong>{item.operatorDisplayName || '系统'}</strong><span>{item.operatorUsername ? `@${item.operatorUsername}` : '-'}</span></div> },
        { title: '动作', dataIndex: 'action', width: 90, render: value => <Tag color={actionColors[value]}>{value}</Tag> },
        { title: '资源', render: (_, item) => <Space><Tag>{item.resourceType}</Tag><Typography.Text code>{item.resourceId || '-'}</Typography.Text></Space> },
        { title: 'IP 地址', dataIndex: 'ipAddress', width: 150 },
        { title: '请求 ID', dataIndex: 'requestId', width: 220, ellipsis: true },
      ]} />
    <Drawer title="日志详情" width={560} open={Boolean(selected)} onClose={() => setSelected(undefined)}>
      {selected && <Descriptions column={1} bordered size="small" items={[
        { key: 'time', label: '时间', children: dayjs(selected.createdAt).format('YYYY-MM-DD HH:mm:ss') },
        { key: 'operator', label: '操作者', children: selected.operatorDisplayName ? `${selected.operatorDisplayName} (@${selected.operatorUsername})` : '系统' },
        { key: 'action', label: '动作', children: selected.action },
        { key: 'resource', label: '资源', children: `${selected.resourceType} / ${selected.resourceId || '-'}` },
        { key: 'ip', label: 'IP 地址', children: selected.ipAddress || '-' },
        { key: 'request', label: '请求 ID', children: <Typography.Text copyable>{selected.requestId}</Typography.Text> },
        { key: 'detail', label: '详情', children: <Typography.Text code>{selected.detailJson || '-'}</Typography.Text> },
      ]} />}
    </Drawer>
  </>
}
