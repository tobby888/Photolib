import {
  App, Button, Card, DatePicker, Form, Input, InputNumber, Modal, Pagination, Select, Space, Table,
} from 'antd'
import {
  CheckOutlined, ClockCircleOutlined, DeleteOutlined, DownloadOutlined, PlusOutlined, StopOutlined,
} from '@ant-design/icons'
import { useState } from 'react'
import dayjs from 'dayjs'
import { api, emptyPage, qs } from '../api'
import { useAuth } from '../auth'
import type { CampusMember, EntityId, PageData, PhotoRequest, Worklog } from '../types'
import { DataState, formatMinutes, PageTitle, StatusTag } from '../components'
import { useLoad } from '../hooks'

interface ExportJobView {
  job: { status: 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED'; errorMessage?: string }
  downloadUrl?: string
}

const wait = (milliseconds: number) => new Promise(resolve => window.setTimeout(resolve, milliseconds))

export default function WorklogsPage() {
  const { user } = useAuth()
  const { message, modal } = App.useApp()
  const [form] = Form.useForm()
  const [open, setOpen] = useState(false)
  const [directoryOpen, setDirectoryOpen] = useState(false)
  const [directoryForm] = Form.useForm()
  const [saving, setSaving] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [exportOpen, setExportOpen] = useState(false)
  const [exportRange, setExportRange] = useState<[dayjs.Dayjs, dayjs.Dayjs]>([
    dayjs().startOf('year'),
    dayjs(),
  ])
  const [selectedIds, setSelectedIds] = useState<EntityId[]>([])
  const [filters, setFilters] = useState({ page: 1, status: '' })
  const reviewer = user?.role === 'ADMIN' || user?.role === 'MINISTER'
  const { data, loading, error, reload } = useLoad(
    () => api<PageData<Worklog>>({ url: '/worklogs', params: qs({ ...filters, pageSize: 20 }) }),
    emptyPage<Worklog>(), [filters.page, filters.status],
  )
  const { data: requestOptions, loading: requestsLoading } = useLoad(
    () => user?.role === 'CAMPUS_MANAGER'
      ? api<PageData<PhotoRequest>>({
          url: '/requests',
          params: { page: 1, pageSize: 100, participantId: user.id },
        }).then(result => result.items.filter(item => item.status !== 'CANCELLED'))
      : Promise.resolve([]),
    [] as PhotoRequest[], [user?.id, user?.role],
  )
  const { data: directory, loading: directoryLoading, reload: reloadDirectory } = useLoad(
    () => user?.role === 'CAMPUS_MANAGER'
      ? api<CampusMember[]>({ url: '/campus-members', params: { enabled: true } })
      : Promise.resolve([]),
    [] as CampusMember[], [user?.id, user?.role],
  )

  const createDirectoryMember = async () => {
    try {
      await api({ method: 'POST', url: '/campus-members', data: await directoryForm.validateFields() })
      message.success('成员已加入通讯录')
      directoryForm.resetFields()
      await reloadDirectory()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const deleteDirectoryMember = async (member: CampusMember) => {
    try {
      await api({ method: 'DELETE', url: `/campus-members/${member.id}` })
      message.success('已从通讯录移除')
      await reloadDirectory()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const create = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const { requestId, workDate, ...rest } = values
      await api({
        method: 'POST',
        url: `/requests/${requestId}/worklogs`,
        data: { ...rest, workDate: workDate.format('YYYY-MM-DD') },
      })
      message.success('工时记录已保存')
      setOpen(false)
      form.resetFields()
      await reload()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const action = async (item: Worklog, type: 'confirm' | 'reject' | 'submit') => {
    const actionData = type === 'reject'
      ? { reason: '请重新核对工时', version: item.version }
      : { version: item.version }
    await api({ method: 'POST', url: `/worklogs/${item.id}/${type}`, data: actionData })
  }

  const runAction = async (item: Worklog, type: 'confirm' | 'reject' | 'submit') => {
    try {
      await action(item, type)
      message.success(type === 'confirm' ? '工时已批准' : type === 'reject' ? '工时已退回' : '工时已提交')
      await reload()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const deleteItems = async (items: Worklog[]) => {
    try {
      await Promise.all(items.map(item => api({ method: 'DELETE', url: `/worklogs/${item.id}` })))
      message.success(`已删除 ${items.length} 条工时`)
      setSelectedIds([])
      await reload()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const confirmDelete = (items: Worklog[]) => modal.confirm({
    title: `删除选中的 ${items.length} 条工时？`,
    content: '删除后无法恢复。',
    okText: '删除',
    okButtonProps: { danger: true },
    cancelText: '取消',
    onOk: () => deleteItems(items),
  })

  const approveSelected = async () => {
    const items = data.items.filter(item => selectedIds.includes(item.id) && item.status === 'SUBMITTED')
    if (!items.length) {
      message.warning('请选择状态为“待确认”的工时')
      return
    }
    try {
      await Promise.all(items.map(item => action(item, 'confirm')))
      message.success(`已批准 ${items.length} 条工时`)
      setSelectedIds([])
      await reload()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const exportWorklogs = async () => {
    setExporting(true)
    try {
      const job = await api<{ id: string }>({
        method: 'POST',
        url: '/worklogs/export',
        data: {
          from: exportRange[0].format('YYYY-MM-DD'),
          to: exportRange[1].format('YYYY-MM-DD'),
          format: 'XLSX',
        },
      })
      for (let attempt = 0; attempt < 30; attempt += 1) {
        const result = await api<ExportJobView>({ url: `/export-jobs/${job.id}` })
        if (result.job.status === 'SUCCEEDED' && result.downloadUrl) {
          window.location.assign(result.downloadUrl)
          message.success('工时文件已生成')
          setExportOpen(false)
          return
        }
        if (result.job.status === 'FAILED') throw new Error(result.job.errorMessage || '导出失败')
        await wait(1000)
      }
      message.info('导出任务仍在处理中，请稍后重试')
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setExporting(false)
    }
  }

  const selectedItems = data.items.filter(item => selectedIds.includes(item.id))

  return <>
    <PageTitle
      eyebrow="WORKLOGS"
      title="工时记录"
      description={user?.role === 'CAMPUS_MANAGER' ? '记录每一次拍摄与修图投入。' : '审核成员工时，让每份投入都有迹可循。'}
      extra={<Space>
        {reviewer && (
          <Button size="large" icon={<DownloadOutlined />} loading={exporting} onClick={() => setExportOpen(true)}>
            导出工时
          </Button>
        )}
        {user?.role === 'CAMPUS_MANAGER' && (
          <>
            <Button size="large" onClick={() => setDirectoryOpen(true)}>校区通讯录</Button>
            <Button type="primary" size="large" icon={<PlusOutlined />} onClick={() => setOpen(true)}>填报工时</Button>
          </>
        )}
      </Space>}
    />
    <Card className="filter-card">
      <Space wrap>
        <Select allowClear placeholder="全部状态" style={{ width: 160 }} options={[
          { value: 'DRAFT', label: '草稿' },
          { value: 'SUBMITTED', label: '待确认' },
          { value: 'CONFIRMED', label: '已确认' },
          { value: 'REJECTED', label: '已退回' },
        ]} onChange={(status = '') => {
          setSelectedIds([])
          setFilters({ ...filters, page: 1, status })
        }} />
        {reviewer && <>
          <span>已选择 {selectedItems.length} 条</span>
          <Button
            type="primary"
            icon={<CheckOutlined />}
            disabled={!selectedItems.some(item => item.status === 'SUBMITTED')}
            onClick={() => void approveSelected()}
          >
            批量批准（{selectedItems.filter(item => item.status === 'SUBMITTED').length}）
          </Button>
          <Button
            danger
            icon={<DeleteOutlined />}
            disabled={!selectedItems.length}
            onClick={() => confirmDelete(selectedItems)}
          >
            批量删除（{selectedItems.length}）
          </Button>
        </>}
      </Space>
      <span className="summary-chip">
        <ClockCircleOutlined /> 当前页共 {formatMinutes(data.items.reduce((sum, item) => sum + item.shootingMinutes + item.retouchingMinutes, 0))}
      </span>
    </Card>
    <Card>
      <DataState loading={loading} error={error} empty={!data.items.length} onRetry={reload}>
        <Table
          rowKey="id"
          dataSource={data.items}
          pagination={false}
          scroll={{ x: 'max-content' }}
          rowSelection={reviewer ? {
            selectedRowKeys: selectedIds,
            onChange: keys => setSelectedIds(keys as EntityId[]),
          } : undefined}
          columns={[
            { title: '日期', dataIndex: 'workDate', render: value => dayjs(value).format('YYYY-MM-DD') },
            { title: '需求', dataIndex: 'requestTitle', render: value => value || '—' },
            { title: '姓名', dataIndex: 'memberName', render: value => value || '—' },
            { title: '学号', dataIndex: 'memberStudentId', render: value => value || '—' },
            { title: '拍摄', dataIndex: 'shootingMinutes', render: formatMinutes },
            { title: '修图', dataIndex: 'retouchingMinutes', render: formatMinutes },
            { title: '说明', dataIndex: 'remark', ellipsis: true },
            { title: '状态', dataIndex: 'status', render: value => <StatusTag value={value} /> },
            { title: '操作', fixed: 'right' as const, width: reviewer ? 320 : 120, render: (_, item: Worklog) => <Space>
              {user?.role === 'CAMPUS_MANAGER' && ['DRAFT', 'REJECTED'].includes(item.status) && (
                <Button onClick={() => void runAction(item, 'submit')}>提交</Button>
              )}
              {reviewer && item.status === 'SUBMITTED' && <>
                <Button type="primary" icon={<CheckOutlined />} onClick={() => void runAction(item, 'confirm')}>批准</Button>
                <Button icon={<StopOutlined />} onClick={() => void runAction(item, 'reject')}>退回</Button>
              </>}
              {reviewer && (
                <Button danger type="text" icon={<DeleteOutlined />} onClick={() => confirmDelete([item])}>删除</Button>
              )}
            </Space> },
          ]}
        />
        <Pagination current={filters.page} total={data.total} pageSize={20} hideOnSinglePage onChange={page => {
          setSelectedIds([])
          setFilters({ ...filters, page })
        }} />
      </DataState>
    </Card>
    <Modal title="填报工时" open={open} onCancel={() => setOpen(false)} onOk={create} okText="保存记录" confirmLoading={saving}>
      <Form form={form} layout="vertical" initialValues={{ shootingMinutes: 0, retouchingMinutes: 0, status: 'SUBMITTED' }} requiredMark={false}>
        <Form.Item label="关联需求" name="requestId" rules={[{ required: true, message: '请选择需求' }]}>
          <Select
            showSearch
            optionFilterProp="label"
            loading={requestsLoading}
            placeholder={requestOptions.length ? '请选择已接受的需求' : '暂无已接受的需求'}
            notFoundContent={requestsLoading ? '正在加载需求…' : '暂无可申报工时的需求'}
            options={requestOptions.map(item => ({ value: item.id, label: item.title }))}
          />
        </Form.Item>
        <Form.Item label="工作人员" name="memberContactId" rules={[{ required: true, message: '请从校区通讯录选择工作人员' }]}>
          <Select
            showSearch
            optionFilterProp="label"
            loading={directoryLoading}
            placeholder={directory.length ? '按姓名或学号选择' : '通讯录为空，请先添加成员'}
            notFoundContent={directoryLoading ? '正在加载通讯录…' : '通讯录中没有可用成员'}
            options={directory.map(member => ({
              value: member.id,
              label: `${member.name} · ${member.studentId}`,
            }))}
          />
        </Form.Item>
        <Form.Item label="工作日期" name="workDate" rules={[{ required: true, message: '请选择工作日期' }]}>
          <DatePicker maxDate={dayjs()} style={{ width: '100%' }} />
        </Form.Item>
        <Space size={16} align="start" className="form-grid">
          <Form.Item label="拍摄时长" name="shootingMinutes"><InputNumber min={0} suffix="分钟" /></Form.Item>
          <Form.Item label="修图时长" name="retouchingMinutes"><InputNumber min={0} suffix="分钟" /></Form.Item>
        </Space>
        <Form.Item label="工作说明" name="remark"><Input.TextArea rows={3} placeholder="简要说明完成的工作" /></Form.Item>
        <Form.Item label="保存为" name="status">
          <Select options={[{ value: 'DRAFT', label: '草稿' }, { value: 'SUBMITTED', label: '直接提交' }]} />
        </Form.Item>
      </Form>
    </Modal>
    <Modal
      title="导出工时"
      open={exportOpen}
      onCancel={() => setExportOpen(false)}
      onOk={() => void exportWorklogs()}
      okText="导出 XLSX"
      confirmLoading={exporting}
    >
      <Form layout="vertical" requiredMark={false}>
        <Form.Item label="工时日期范围">
          <DatePicker.RangePicker
            value={exportRange}
            maxDate={dayjs()}
            allowClear={false}
            style={{ width: '100%' }}
            onChange={values => {
              if (values?.[0] && values[1]) setExportRange([values[0], values[1]])
            }}
          />
        </Form.Item>
      </Form>
    </Modal>
    <Modal
      title="校区成员通讯录"
      width={680}
      open={directoryOpen}
      onCancel={() => setDirectoryOpen(false)}
      footer={<Button onClick={() => setDirectoryOpen(false)}>完成</Button>}
    >
      <Form form={directoryForm} layout="inline" style={{ marginBottom: 20 }}>
        <Form.Item name="name" rules={[{ required: true, message: '请输入姓名' }, { max: 100 }]}>
          <Input placeholder="成员姓名" />
        </Form.Item>
        <Form.Item name="studentId" rules={[{ required: true, message: '请输入学号' }, { max: 64 }]}>
          <Input placeholder="成员学号" />
        </Form.Item>
        <Form.Item>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => void createDirectoryMember()}>添加成员</Button>
        </Form.Item>
      </Form>
      <Table
        rowKey="id"
        loading={directoryLoading}
        dataSource={directory}
        pagination={{ pageSize: 8 }}
        columns={[
          { title: '姓名', dataIndex: 'name' },
          { title: '学号', dataIndex: 'studentId' },
          { title: '操作', width: 90, render: (_, member: CampusMember) =>
            <Button danger type="text" icon={<DeleteOutlined />}
              onClick={() => modal.confirm({
                title: `移除“${member.name}”？`,
                content: '历史工时中的姓名和学号不会受影响。',
                okText: '移除',
                okButtonProps: { danger: true },
                onOk: () => deleteDirectoryMember(member),
              })}>移除</Button> },
        ]}
      />
    </Modal>
  </>
}
