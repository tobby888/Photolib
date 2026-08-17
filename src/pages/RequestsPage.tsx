import {
  App, Button, Card, DatePicker, Descriptions, Drawer, Form, Input, Modal,
  Pagination, Radio, Select, Space, Tag, Typography,
} from 'antd'
import {
  CheckOutlined, DeleteOutlined, EyeOutlined, PlusOutlined, RollbackOutlined, SearchOutlined,
} from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { api, emptyPage, qs } from '../api'
import { useAuth } from '../auth'
import type { BatchPublishResult, Campus, PageData, PhotoRequest, Project } from '../types'
import { DataState, PageTitle, StatusTag } from '../components'
import { ContentFitTable } from '../ContentFitTable'
import { useLoad } from '../hooks'
import MarkdownEditor from '../MarkdownEditor'
import MarkdownRenderer from '../MarkdownRenderer'
import { hasPermission } from '../permissions'
import { REQUEST_ACTION_MIN_WIDTH } from '../tableActionWidths'

const statuses = [
  ['DRAFT', '草稿'], ['PUBLISHED', '待接单'], ['ACCEPTED', '执行中'],
  ['SUBMITTED', '待确认'], ['COMPLETED', '已完成'], ['CANCELLED', '已取消'],
].map(([value, label]) => ({ value, label }))

export default function RequestsPage() {
  const { user } = useAuth()
  const { message, modal } = App.useApp()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [form] = Form.useForm()
  const publishMode = Form.useWatch('publishMode', form) || 'publish'
  const campusScoped = user?.dataScope === 'CAMPUS'
  const canCreate = hasPermission(user, 'REQUEST_CREATE')
  const canConfirm = hasPermission(user, 'REQUEST_CONFIRM')
  const canClose = hasPermission(user, 'REQUEST_CLOSE')
  const canDelete = hasPermission(user, 'REQUEST_DELETE')
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
      hasPermission(user, 'PROJECT_VIEW')
        ? api<PageData<Project>>({ url: '/projects', params: { page: 1, pageSize: 100 } })
        : Promise.resolve(emptyPage<Project>()),
      canCreate ? api<Campus[]>({ url: '/campuses', params: { enabled: true } }) : Promise.resolve([] as Campus[]),
    ])
    return { projects: projects.items, campuses }
  }, { projects: [] as Project[], campuses: [] as Campus[] }, [canCreate, user?.permissionGroupId])
  useEffect(() => {
    const requestId = searchParams.get('requestId')
    if (requestId) setDetail(data.items.find(item => item.id === requestId) || null)
  }, [data.items, searchParams])

  const create = async () => {
    const values = await form.validateFields()
    if (values.publishMode === 'draft' && values.campusIds.length !== 1) {
      message.warning('保存草稿时只能选择一个校区')
      return
    }
    setSaving(true)
    try {
      const { projectId, deadline, title, description, campusIds } = values
      if (values.publishMode === 'draft') {
        await api({
          method: 'POST', url: `/projects/${projectId}/requests`,
          data: { title, description, campusId: campusIds[0], deadline: deadline.format('YYYY-MM-DDTHH:mm:ss') },
        })
        message.success('需求草稿已创建')
        setOpen(false)
        form.resetFields()
        await reload()
        return
      }
      const results = await api<BatchPublishResult[]>({
        method: 'POST', url: `/projects/${projectId}/requests/batch-publish`,
        data: { title, description, campusIds, deadline: deadline.format('YYYY-MM-DDTHH:mm:ss') },
      })
      const succeeded = results.filter(item => item.success)
      const failed = results.filter(item => !item.success)
      if (failed.length) {
        form.setFieldValue('campusIds', failed.map(item => item.campusId))
        const details = failed.map(item => {
          const campus = options.campuses.find(value => value.id === item.campusId)
          return `${campus?.name || `校区 #${item.campusId}`}：${item.message || '发布失败'}`
        }).join('；')
        message.warning({ content: `已成功发布 ${succeeded.length} 个，失败 ${failed.length} 个。${details}`, duration: 8 })
      } else {
        message.success(`已向 ${succeeded.length} 个校区分别发布需求`)
        setOpen(false)
        form.resetFields()
      }
      await reload()
    } catch (e) { message.error((e as Error).message) } finally { setSaving(false) }
  }
  const action = async (item: PhotoRequest, type: string) => {
    try {
      await api({ method: 'POST', url: `/requests/${item.id}/${type}`, data: type === 'accept' ? undefined : { version: item.version } })
      message.success(type === 'accept' ? '已接下这项拍摄任务' : type === 'publish' ? '需求已发布' : type === 'submit' ? '需求已提交' : '需求已完成')
      setDetail(null); await reload()
    } catch (e) { message.error((e as Error).message) }
  }
  const deleteRequest = (item: PhotoRequest) => {
    modal.confirm({
      title: '删除图片需求',
      content: <>确定删除需求“<strong>{item.title}</strong>”吗？需求会从项目和需求列表中移除，已有图片及工时历史仍会保留。</>,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await api({ method: 'DELETE', url: `/requests/${item.id}` })
          if (detail?.id === item.id) setDetail(null)
          message.success('需求已删除')
          await reload()
        } catch (e) {
          message.error((e as Error).message)
          throw e
        }
      },
    })
  }
  const returnForRevision = (item: PhotoRequest) => {
    let reason = ''
    modal.confirm({
      title: '打回图片需求',
      content: <Input.TextArea rows={4} maxLength={500} showCount
        placeholder="请说明需要负责人修改或补充的内容"
        onChange={event => { reason = event.target.value }} />,
      okText: '确认打回',
      cancelText: '取消',
      onOk: async () => {
        if (!reason.trim()) throw new Error('请填写打回原因')
        try {
          await api({
            method: 'POST', url: `/requests/${item.id}/return`,
            data: { reason: reason.trim(), version: item.version },
          })
          if (detail?.id === item.id) setDetail(null)
          message.success('需求已打回，参与负责人将收到通知')
          await reload()
        } catch (e) {
          message.error((e as Error).message)
          throw e
        }
      },
    })
  }
  const closeRequest = (item: PhotoRequest) => {
    let reason = ''
    modal.confirm({
      title: '关闭图片需求',
      content: <Input.TextArea rows={3} maxLength={500} showCount placeholder="请填写关闭原因"
        onChange={event => { reason = event.target.value }} />,
      okText: '确认关闭', okButtonProps: { danger: true }, cancelText: '取消',
      onOk: async () => {
        if (!reason.trim()) throw new Error('请填写关闭原因')
        await api({ method: 'POST', url: `/requests/${item.id}/cancel`,
          data: { reason: reason.trim(), version: item.version } })
        setDetail(null); message.success('需求已关闭'); await reload()
      },
    })
  }
  const actions = (item: PhotoRequest) => <Space>
    {(!campusScoped || item.status !== 'PUBLISHED') && <Button type="text" icon={<EyeOutlined />} onClick={() => navigate(`/requests/${item.id}`)}>
      {campusScoped ? '交付图片' : '详情'}
    </Button>}
    {campusScoped && item.status === 'PUBLISHED' && <Button type="primary" onClick={() => void action(item, 'accept')}>接受任务</Button>}
    {canCreate && item.status === 'DRAFT' && <Button onClick={() => void action(item, 'publish')}>发布</Button>}
    {campusScoped && item.status === 'ACCEPTED' && <Button onClick={() => void action(item, 'submit')}>提交</Button>}
    {canConfirm && item.status === 'SUBMITTED' &&
      <Button icon={<RollbackOutlined />} onClick={() => returnForRevision(item)}>打回</Button>}
    {canConfirm && item.status === 'SUBMITTED' &&
      <Button type="primary" onClick={() => void action(item, 'complete')}>确认完成</Button>}
    {canClose && !['COMPLETED', 'CANCELLED'].includes(item.status) &&
      <Button type="text" danger onClick={() => closeRequest(item)}>关闭</Button>}
    {canDelete && <Button type="text" danger icon={<DeleteOutlined />} onClick={() => deleteRequest(item)}>删除</Button>}
  </Space>
  return <>
    <PageTitle eyebrow="REQUESTS" title="图片需求" description={campusScoped ? '查看授权校区的拍摄任务；接受后才能进入需求并管理交付图片。' : '把选题拆成清晰、可执行的拍摄任务。'}
      extra={canCreate && <Button type="primary" size="large" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建需求</Button>} />
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
        <ContentFitTable rowKey="id" dataSource={data.items} pagination={false} columns={[
          { title: '需求', dataIndex: 'title', render: (value, item) => <div className="table-title"><strong>{value}</strong><span>项目 #{item.projectId}</span></div> },
          { title: '校区', dataIndex: 'campusId', render: value => options.campuses.find(c => c.id === value)?.name || `校区 #${value}` },
          { title: '截止时间', dataIndex: 'deadline', render: value => <span className={dayjs(value).isBefore(dayjs()) ? 'danger-text' : ''}>{dayjs(value).format('MM-DD HH:mm')}</span> },
          { title: '状态', dataIndex: 'status', render: value => <StatusTag value={value} /> },
          { title: '操作', key: 'action', fixed: 'right', minWidth: REQUEST_ACTION_MIN_WIDTH,
            className: 'table-action-cell', render: (_, item) => actions(item) },
        ]} />
        <Pagination current={filters.page} total={data.total} pageSize={20} hideOnSinglePage onChange={page => setFilters({ ...filters, page })} />
      </DataState>
    </Card>
    <Modal title="新建图片需求" width={780} open={open} onCancel={() => setOpen(false)} onOk={create}
      okText={publishMode === 'publish' ? '创建并发布' : '保存草稿'} confirmLoading={saving}>
      <Form layout="vertical" form={form} initialValues={{ publishMode: 'publish' }} requiredMark={false}>
        <Form.Item label="建立方式" name="publishMode">
          <Radio.Group optionType="button" buttonStyle="solid" options={[
            { value: 'publish', label: '立即向多校区发布' },
            { value: 'draft', label: '保存单校区草稿' },
          ]} />
        </Form.Item>
        <Form.Item label="所属项目" name="projectId" rules={[{ required: true, message: '请选择项目' }]}>
          <Select showSearch optionFilterProp="label" options={options.projects.filter(p => p.status === 'ACTIVE').map(p => ({ value: p.id, label: p.title }))} placeholder="选择进行中的项目" />
        </Form.Item>
        <Form.Item label="需求标题" name="title" rules={[{ required: true, message: '请输入标题' }, { max: 200 }]}><Input placeholder="例如：毕业典礼现场图" /></Form.Item>
        <Form.Item label="拍摄说明" name="description">
          <MarkdownEditor placeholder="使用 Markdown 说明场景、人物、构图和交付标准；可上传说明图片" />
        </Form.Item>
        <Form.Item label={publishMode === 'publish' ? '发布校区' : '草稿校区'} name="campusIds"
          extra={publishMode === 'publish' ? '每个校区会建立一条独立需求；个别校区失败不会影响其他校区' : '草稿仍按原流程保存，检查后可单独发布'}
          rules={[{ required: true, message: '请至少选择一个校区' }]}>
          <Select mode="multiple" maxCount={publishMode === 'draft' ? 1 : undefined}
            showSearch optionFilterProp="label" maxTagCount="responsive"
            options={options.campuses.map(c => ({ value: c.id, label: c.name }))}
            placeholder={publishMode === 'publish' ? '可同时选择多个校区' : '选择一个校区'} />
        </Form.Item>
        <Form.Item label="截止时间" name="deadline" rules={[{ required: true, message: '请选择截止时间' }]}>
          <DatePicker showTime format="YYYY-MM-DD HH:mm" disabledDate={date => date.isBefore(dayjs(), 'day')} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </Modal>
    <Drawer title="需求详情" width={480} open={!!detail} onClose={() => setDetail(null)}
      extra={detail && actions(detail)}>
      {detail && <>
        <div className="detail-hero"><Tag variant="filled">REQ-{detail.id}</Tag><Typography.Title level={3}>{detail.title}</Typography.Title>
          {detail.description ? <MarkdownRenderer value={detail.description} /> : <Typography.Paragraph>暂无拍摄说明</Typography.Paragraph>}
        </div>
        <Descriptions column={1} bordered size="small" items={[
          { key: 'status', label: '状态', children: <StatusTag value={detail.status} /> },
          { key: 'project', label: '所属项目', children: `#${detail.projectId}` },
          { key: 'campus', label: '拍摄校区', children: options.campuses.find(c => c.id === detail.campusId)?.name || `#${detail.campusId}` },
          { key: 'deadline', label: '截止时间', children: dayjs(detail.deadline).format('YYYY 年 M 月 D 日 HH:mm') },
          ...(detail.status === 'ACCEPTED' && detail.returnReason ? [{
            key: 'returnReason', label: '最近打回原因',
            children: <Space direction="vertical" size={0}>
              <span>{detail.returnReason}</span>
              {detail.returnedAt && <Typography.Text type="secondary">
                {dayjs(detail.returnedAt).format('YYYY-MM-DD HH:mm')}
              </Typography.Text>}
            </Space>,
          }] : []),
        ]} />
        <div className="workflow-steps">
          {['需求发布', '负责人接单', '图片提交', '确认完成'].map((text, index) => <div className={index <= ['DRAFT','PUBLISHED','ACCEPTED','SUBMITTED','COMPLETED'].indexOf(detail.status) - 1 ? 'done' : ''} key={text}><i>{index < 3 ? index + 1 : <CheckOutlined />}</i><span>{text}</span></div>)}
        </div>
      </>}
    </Drawer>
  </>
}
