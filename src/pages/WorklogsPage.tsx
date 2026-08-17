import {
  App, Button, Card, DatePicker, Form, Input, InputNumber, Modal, Pagination, Select, Space,
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
import { ContentFitTable, TableEllipsisText } from '../ContentFitTable'
import { useLoad } from '../hooks'
import { hasPermission } from '../permissions'
import {
  WORKLOG_OWNER_ACTION_MIN_WIDTH,
  WORKLOG_REVIEW_ACTION_MIN_WIDTH,
} from '../tableActionWidths'

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
  const [saving, setSaving] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [exportOpen, setExportOpen] = useState(false)
  const [exportRange, setExportRange] = useState<[dayjs.Dayjs, dayjs.Dayjs]>([
    dayjs().startOf('year'),
    dayjs(),
  ])
  const [selectedIds, setSelectedIds] = useState<EntityId[]>([])
  const [filters, setFilters] = useState({ page: 1, status: '' })
  const reviewer = hasPermission(user, 'WORKLOG_CONFIRM')
  const canExport = hasPermission(user, 'WORKLOG_EXPORT')
  const canSubmit = hasPermission(user, 'WORKLOG_SUBMIT')
  const { data, loading, error, reload } = useLoad(
    () => api<PageData<Worklog>>({ url: '/worklogs', params: qs({ ...filters, pageSize: 20 }) }),
    emptyPage<Worklog>(), [filters.page, filters.status],
  )
  const { data: requestOptions, loading: requestsLoading } = useLoad(
    () => canSubmit && user
      ? api<PageData<PhotoRequest>>({
          url: '/requests',
          params: { page: 1, pageSize: 100, participantId: user.id },
        }).then(result => result.items.filter(item => item.status !== 'CANCELLED'))
      : Promise.resolve([]),
    [] as PhotoRequest[], [user?.id, canSubmit],
  )
  const { data: directory, loading: directoryLoading } = useLoad(
    () => canSubmit
      ? api<CampusMember[]>({ url: '/campus-members', params: { enabled: true } })
      : Promise.resolve([]),
    [] as CampusMember[], [user?.id, canSubmit],
  )

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
      description={canSubmit && !reviewer ? '记录每一次拍摄与修图投入。' : '审核成员工时，让每份投入都有迹可循。'}
      extra={<Space>
        {canExport && (
          <Button size="large" icon={<DownloadOutlined />} loading={exporting} onClick={() => setExportOpen(true)}>
            导出工时
          </Button>
        )}
        {canSubmit && (
          <Button type="primary" size="large" icon={<PlusOutlined />} onClick={() => setOpen(true)}>填报工时</Button>
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
        <ContentFitTable
          rowKey="id"
          dataSource={data.items}
          pagination={false}
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
            { title: '说明', dataIndex: 'remark', minWidth: 320,
              render: value => <TableEllipsisText value={value} maxWidth={288} /> },
            { title: '状态', dataIndex: 'status', render: value => <StatusTag value={value} /> },
            { title: '操作', fixed: 'right' as const, minWidth: reviewer ? WORKLOG_REVIEW_ACTION_MIN_WIDTH : WORKLOG_OWNER_ACTION_MIN_WIDTH,
              className: 'table-action-cell', render: (_, item: Worklog) => <Space>
              {canSubmit && item.userId === user?.id && ['DRAFT', 'REJECTED'].includes(item.status) && (
                <Button onClick={() => void runAction(item, 'submit')}>提交</Button>
              )}
              {reviewer && item.status === 'SUBMITTED' && <>
                <Button type="primary" icon={<CheckOutlined />} onClick={() => void runAction(item, 'confirm')}>批准</Button>
                <Button icon={<StopOutlined />} onClick={() => void runAction(item, 'reject')}>退回</Button>
              </>}
              {reviewer && (
                <Button danger type="text" icon={<DeleteOutlined />} onClick={() => confirmDelete([item])}>删除</Button>
              )}
              {!reviewer && canSubmit && item.userId === user?.id && ['DRAFT', 'REJECTED'].includes(item.status) &&
                <Button danger type="text" icon={<DeleteOutlined />} onClick={() => confirmDelete([item])}>删除</Button>}
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
        <Form.Item label="工作人员" name="memberContactId" extra="通讯录成员在「通讯录」页维护" rules={[{ required: true, message: '请从校区通讯录选择工作人员' }]}>
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
  </>
}
