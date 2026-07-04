import {
  App, Button, Card, DatePicker, Form, Input, InputNumber, Modal, Pagination, Select, Space, Table,
} from 'antd'
import { CheckOutlined, ClockCircleOutlined, PlusOutlined, StopOutlined } from '@ant-design/icons'
import { useState } from 'react'
import dayjs from 'dayjs'
import { api, emptyPage, qs } from '../api'
import { useAuth } from '../auth'
import type { PageData, Worklog } from '../types'
import { DataState, formatMinutes, PageTitle, StatusTag } from '../components'
import { useLoad } from '../hooks'

export default function WorklogsPage() {
  const { user } = useAuth()
  const { message } = App.useApp()
  const [form] = Form.useForm()
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [filters, setFilters] = useState({ page: 1, status: '' })
  const { data, loading, error, reload } = useLoad(
    () => api<PageData<Worklog>>({ url: '/worklogs', params: qs({ ...filters, pageSize: 20 }) }),
    emptyPage<Worklog>(), [filters.page, filters.status],
  )
  const create = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const { requestId, workDate, ...rest } = values
      await api({ method: 'POST', url: `/requests/${requestId}/worklogs`, data: { ...rest, workDate: workDate.format('YYYY-MM-DD') } })
      message.success('工时记录已保存'); setOpen(false); form.resetFields(); await reload()
    } catch (e) { message.error((e as Error).message) } finally { setSaving(false) }
  }
  const action = async (item: Worklog, type: 'confirm' | 'reject' | 'submit') => {
    try {
      const data = type === 'reject' ? { reason: '请重新核对工时', version: item.version } : { version: item.version }
      await api({ method: 'POST', url: `/worklogs/${item.id}/${type}`, data })
      message.success(type === 'confirm' ? '工时已确认' : type === 'reject' ? '工时已退回' : '工时已提交')
      await reload()
    } catch (e) { message.error((e as Error).message) }
  }
  return <>
    <PageTitle eyebrow="WORKLOGS" title="工时记录" description={user?.role === 'CAMPUS_MANAGER' ? '记录每一次拍摄与修图投入。' : '审核成员工时，让每份投入都有迹可循。'}
      extra={<Button type="primary" size="large" icon={<PlusOutlined />} onClick={() => setOpen(true)}>填报工时</Button>} />
    <Card className="filter-card">
      <Space wrap><Select allowClear placeholder="全部状态" style={{ width: 160 }} options={[
        { value: 'DRAFT', label: '草稿' }, { value: 'SUBMITTED', label: '待确认' },
        { value: 'CONFIRMED', label: '已确认' }, { value: 'REJECTED', label: '已退回' },
      ]} onChange={(status = '') => setFilters({ ...filters, page: 1, status })} /></Space>
      <span className="summary-chip"><ClockCircleOutlined /> 当前页共 {formatMinutes(data.items.reduce((sum, i) => sum + i.shootingMinutes + i.retouchingMinutes, 0))}</span>
    </Card>
    <Card>
      <DataState loading={loading} error={error} empty={!data.items.length} onRetry={reload}>
        <Table rowKey="id" dataSource={data.items} pagination={false} scroll={{ x: 'max-content' }} columns={[
          { title: '日期', dataIndex: 'workDate', render: value => dayjs(value).format('YYYY-MM-DD') },
          { title: '需求', dataIndex: 'requestId', render: value => `需求 #${value}` },
          { title: '填报人', dataIndex: 'userId', render: value => value === user?.id ? '我' : `成员 #${value}` },
          { title: '拍摄', dataIndex: 'shootingMinutes', render: formatMinutes },
          { title: '修图', dataIndex: 'retouchingMinutes', render: formatMinutes },
          { title: '说明', dataIndex: 'remark', ellipsis: true },
          { title: '状态', dataIndex: 'status', render: value => <StatusTag value={value} /> },
          { title: '操作', fixed: 'right', width: 320, render: (_, item) => <Space>
            {user?.role === 'CAMPUS_MANAGER' && ['DRAFT', 'REJECTED'].includes(item.status) && <Button onClick={() => void action(item, 'submit')}>提交</Button>}
            {user?.role !== 'CAMPUS_MANAGER' && item.status === 'SUBMITTED' && <>
              <Button type="primary" icon={<CheckOutlined />} onClick={() => void action(item, 'confirm')}>确认</Button>
              <Button danger icon={<StopOutlined />} onClick={() => void action(item, 'reject')}>退回</Button>
            </>}
          </Space> },
        ]} />
        <Pagination current={filters.page} total={data.total} pageSize={20} hideOnSinglePage onChange={page => setFilters({ ...filters, page })} />
      </DataState>
    </Card>
    <Modal title="填报工时" open={open} onCancel={() => setOpen(false)} onOk={create} okText="保存记录" confirmLoading={saving}>
      <Form form={form} layout="vertical" initialValues={{ shootingMinutes: 0, retouchingMinutes: 0, status: 'SUBMITTED' }} requiredMark={false}>
        <Form.Item label="需求编号" name="requestId" rules={[
          { required: true, message: '请输入需求编号' },
          { pattern: /^\d+$/, message: '需求编号只能包含数字' },
        ]}><Input style={{ width: '100%' }} placeholder="请输入已接受的需求 ID" /></Form.Item>
        <Form.Item label="工作日期" name="workDate" rules={[{ required: true }]}><DatePicker maxDate={dayjs()} style={{ width: '100%' }} /></Form.Item>
        <Space size={16} align="start" className="form-grid">
          <Form.Item label="拍摄时长" name="shootingMinutes"><InputNumber min={0} suffix="分钟" /></Form.Item>
          <Form.Item label="修图时长" name="retouchingMinutes"><InputNumber min={0} suffix="分钟" /></Form.Item>
        </Space>
        <Form.Item label="工作说明" name="remark"><Input.TextArea rows={3} placeholder="简要说明完成的工作" /></Form.Item>
        <Form.Item label="保存为" name="status"><Select options={[{ value: 'DRAFT', label: '草稿' }, { value: 'SUBMITTED', label: '直接提交' }]} /></Form.Item>
      </Form>
    </Modal>
  </>
}
