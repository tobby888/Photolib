import {
  App, Button, Card, DatePicker, Descriptions, Drawer, Form, Input, InputNumber, Modal,
  Pagination, Select, Space, Table, Tag, Typography,
} from 'antd'
import { CheckOutlined, EyeOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { api, emptyPage, qs } from '../api'
import { useAuth } from '../auth'
import type { Campus, PageData, PhotoRequest, Project } from '../types'
import { DataState, PageTitle, StatusTag } from '../components'
import { useLoad } from '../hooks'

const statuses = [
  ['DRAFT', '草稿'], ['PUBLISHED', '待接单'], ['ACCEPTED', '执行中'],
  ['SUBMITTED', '待确认'], ['COMPLETED', '已完成'], ['CANCELLED', '已取消'],
].map(([value, label]) => ({ value, label }))

export default function RequestsPage() {
  const { user } = useAuth()
  const { message } = App.useApp()
  const [searchParams] = useSearchParams()
  const [form] = Form.useForm()
  const [open, setOpen] = useState(false)
  const [detail, setDetail] = useState<PhotoRequest | null>(null)
  const [saving, setSaving] = useState(false)
  const [filters, setFilters] = useState({
    page: 1,
    status: '',
    projectId: searchParams.get('projectId') || undefined,
  })
  const { data, loading, error, reload } = useLoad(
    () => api<PageData<PhotoRequest>>({ url: '/requests', params: qs({ ...filters, pageSize: 20 }) }),
    emptyPage<PhotoRequest>(), [filters.page, filters.status, filters.projectId],
  )
  const { data: options } = useLoad(async () => {
    const [projects, campuses] = await Promise.all([
      api<PageData<Project>>({ url: '/projects', params: { page: 1, pageSize: 100 } }),
      api<Campus[]>({ url: '/campuses', params: { enabled: true } }),
    ])
    return { projects: projects.items, campuses }
  }, { projects: [] as Project[], campuses: [] as Campus[] }, [])
  useEffect(() => {
    const requestId = searchParams.get('requestId')
    if (requestId) setDetail(data.items.find(item => item.id === requestId) || null)
  }, [data.items, searchParams])

  const create = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const { projectId, deadline, ...rest } = values
      await api({ method: 'POST', url: `/projects/${projectId}/requests`, data: { ...rest, deadline: deadline.format('YYYY-MM-DDTHH:mm:ss') } })
      message.success('需求草稿已创建'); setOpen(false); form.resetFields(); await reload()
    } catch (e) { message.error((e as Error).message) } finally { setSaving(false) }
  }
  const action = async (item: PhotoRequest, type: string) => {
    try {
      await api({ method: 'POST', url: `/requests/${item.id}/${type}`, data: type === 'accept' ? undefined : { version: item.version } })
      message.success(type === 'accept' ? '已接下这项拍摄任务' : type === 'publish' ? '需求已发布' : type === 'submit' ? '需求已提交' : '需求已完成')
      setDetail(null); await reload()
    } catch (e) { message.error((e as Error).message) }
  }
  const actions = (item: PhotoRequest) => <Space>
    <Button type="text" icon={<EyeOutlined />} onClick={() => setDetail(item)}>详情</Button>
    {user?.role === 'CAMPUS_MANAGER' && item.status === 'PUBLISHED' && <Button type="primary" onClick={() => void action(item, 'accept')}>接受任务</Button>}
    {user?.role !== 'CAMPUS_MANAGER' && item.status === 'DRAFT' && <Button onClick={() => void action(item, 'publish')}>发布</Button>}
    {user?.role === 'CAMPUS_MANAGER' && item.status === 'ACCEPTED' && <Button onClick={() => void action(item, 'submit')}>提交</Button>}
    {user?.role !== 'CAMPUS_MANAGER' && item.status === 'SUBMITTED' && <Button type="primary" onClick={() => void action(item, 'complete')}>确认完成</Button>}
  </Space>
  return <>
    <PageTitle eyebrow="REQUESTS" title="图片需求" description={user?.role === 'CAMPUS_MANAGER' ? '查看所在校区的拍摄任务，并跟进拍摄交付。' : '把选题拆成清晰、可执行的拍摄任务。'}
      extra={user?.role !== 'CAMPUS_MANAGER' && <Button type="primary" size="large" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建需求</Button>} />
    <Card className="filter-card">
      <Space wrap>
        <Select allowClear placeholder="全部项目" showSearch optionFilterProp="label" style={{ width: 220 }}
          value={filters.projectId}
          options={options.projects.map(p => ({ value: p.id, label: p.title }))}
          onChange={(projectId) => setFilters({ ...filters, page: 1, projectId })} />
        <Select allowClear placeholder="全部状态" style={{ width: 160 }} options={statuses}
          onChange={(status = '') => setFilters({ ...filters, page: 1, status })} />
        <Button icon={<SearchOutlined />} onClick={reload}>刷新</Button>
      </Space>
    </Card>
    <Card>
      <DataState loading={loading} error={error} empty={!data.items.length} onRetry={reload}>
        <Table rowKey="id" dataSource={data.items} pagination={false} scroll={{ x: 900 }} columns={[
          { title: '需求', dataIndex: 'title', render: (value, item) => <div className="table-title"><strong>{value}</strong><span>项目 #{item.projectId}</span></div> },
          { title: '校区', dataIndex: 'campusId', render: value => options.campuses.find(c => c.id === value)?.name || `校区 #${value}` },
          { title: '数量', dataIndex: 'requiredCount', render: value => `${value} 张` },
          { title: '截止时间', dataIndex: 'deadline', render: value => <span className={dayjs(value).isBefore(dayjs()) ? 'danger-text' : ''}>{dayjs(value).format('MM-DD HH:mm')}</span> },
          { title: '状态', dataIndex: 'status', render: value => <StatusTag value={value} /> },
          { title: '操作', key: 'action', fixed: 'right', render: (_, item) => actions(item) },
        ]} />
        <Pagination current={filters.page} total={data.total} pageSize={20} hideOnSinglePage onChange={page => setFilters({ ...filters, page })} />
      </DataState>
    </Card>
    <Modal title="新建图片需求" width={620} open={open} onCancel={() => setOpen(false)} onOk={create} okText="保存草稿" confirmLoading={saving}>
      <Form layout="vertical" form={form} requiredMark={false}>
        <Form.Item label="所属项目" name="projectId" rules={[{ required: true, message: '请选择项目' }]}>
          <Select showSearch optionFilterProp="label" options={options.projects.filter(p => p.status === 'ACTIVE').map(p => ({ value: p.id, label: p.title }))} placeholder="选择进行中的项目" />
        </Form.Item>
        <Form.Item label="需求标题" name="title" rules={[{ required: true, message: '请输入标题' }]}><Input placeholder="例如：毕业典礼现场图" /></Form.Item>
        <Form.Item label="拍摄说明" name="description"><Input.TextArea rows={3} placeholder="需要哪些场景、人物和构图？" /></Form.Item>
        <Space align="start" size={16} className="form-grid">
          <Form.Item label="校区" name="campusId" rules={[{ required: true, message: '请选择校区' }]}><Select style={{ width: 180 }} options={options.campuses.map(c => ({ value: c.id, label: c.name }))} /></Form.Item>
          <Form.Item label="需要数量" name="requiredCount" initialValue={10} rules={[{ required: true }]}><InputNumber min={1} max={10000} suffix="张" /></Form.Item>
          <Form.Item label="截止时间" name="deadline" rules={[{ required: true, message: '请选择截止时间' }]}><DatePicker showTime format="YYYY-MM-DD HH:mm" /></Form.Item>
        </Space>
      </Form>
    </Modal>
    <Drawer title="需求详情" width={480} open={!!detail} onClose={() => setDetail(null)}
      extra={detail && actions(detail)}>
      {detail && <>
        <div className="detail-hero"><Tag variant="filled">REQ-{detail.id}</Tag><Typography.Title level={3}>{detail.title}</Typography.Title><Typography.Paragraph>{detail.description || '暂无拍摄说明'}</Typography.Paragraph></div>
        <Descriptions column={1} bordered size="small" items={[
          { key: 'status', label: '状态', children: <StatusTag value={detail.status} /> },
          { key: 'project', label: '所属项目', children: `#${detail.projectId}` },
          { key: 'campus', label: '拍摄校区', children: options.campuses.find(c => c.id === detail.campusId)?.name || `#${detail.campusId}` },
          { key: 'count', label: '需要数量', children: `${detail.requiredCount} 张` },
          { key: 'deadline', label: '截止时间', children: dayjs(detail.deadline).format('YYYY 年 M 月 D 日 HH:mm') },
        ]} />
        <div className="workflow-steps">
          {['需求发布', '负责人接单', '图片提交', '确认完成'].map((text, index) => <div className={index <= ['DRAFT','PUBLISHED','ACCEPTED','SUBMITTED','COMPLETED'].indexOf(detail.status) - 1 ? 'done' : ''} key={text}><i>{index < 3 ? index + 1 : <CheckOutlined />}</i><span>{text}</span></div>)}
        </div>
      </>}
    </Drawer>
  </>
}
