import {
  App, Button, Card, DatePicker, Form, Input, InputNumber, Modal, Select, Space, Tag,
} from 'antd'
import {
  CheckOutlined, ClockCircleOutlined, DeleteOutlined, DownloadOutlined, EditOutlined, PlusOutlined, StopOutlined,
} from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { api, emptyPage, qs } from '../api'
import { useAuth } from '../auth'
import type { Campus, CampusMember, EntityId, PageData, PhotoRequest, Project, Worklog } from '../types'
import { DataState, formatMinutes, PageTitle, StatusTag } from '../components'
import { ContentFitTable, TableEllipsisText } from '../ContentFitTable'
import { useLoad } from '../hooks'
import { canViewProjects, hasPermission } from '../permissions'
import { campusNameOf, membersForRequest, requestSelectOptions } from '../worklogForm'
import {
  WORKLOG_OWNER_ACTION_MIN_WIDTH,
  WORKLOG_REVIEW_ACTION_MIN_WIDTH,
} from '../tableActionWidths'
import ListPagination from '../ListPagination'
import WorklogCorrectionModal from '../WorklogCorrectionModal'
import { TABLE_PAGE_SIZES, turnPage } from '../pagination'
import { keepSelectedRows } from '../rowSelection'

interface ExportJobView {
  job: { status: 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED'; errorMessage?: string }
  downloadUrl?: string
}

interface WorklogFilters {
  page: number
  pageSize: number
  status: '' | Worklog['status']
  source: '' | 'IN_APP' | 'SHARE_LINK'
  keyword: string
  campusId: string
  /** 工作日期区间，YYYY-MM-DD，两端都含。 */
  from: string
  to: string
}

const EMPTY_FILTERS: WorklogFilters = {
  page: 1, pageSize: 20, status: '', source: '', keyword: '', campusId: '', from: '', to: '',
}

const STATUS_OPTIONS = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'SUBMITTED', label: '待确认' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'REJECTED', label: '已退回' },
]

const SOURCE_OPTIONS = [
  { value: 'IN_APP', label: '站内填报' },
  { value: 'SHARE_LINK', label: '上传链接' },
]

const datePresets = (): { label: string; value: [dayjs.Dayjs, dayjs.Dayjs] }[] => {
  const today = dayjs()
  const lastMonth = today.subtract(1, 'month')
  return [
    { label: '最近 7 天', value: [today.subtract(6, 'day'), today] },
    { label: '本月', value: [today.startOf('month'), today] },
    { label: '上个月', value: [lastMonth.startOf('month'), lastMonth.endOf('month')] },
    { label: '今年', value: [today.startOf('year'), today] },
  ]
}

const wait = (milliseconds: number) => new Promise(resolve => window.setTimeout(resolve, milliseconds))

export default function WorklogsPage() {
  const { user } = useAuth()
  const { message, modal } = App.useApp()
  const [form] = Form.useForm()
  // 管理员更正已报上来的工时（姓名、学号、日期、时长、说明），后端只放行权限组 ADMIN。
  const [correcting, setCorrecting] = useState<Worklog | null>(null)
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [exportOpen, setExportOpen] = useState(false)
  const [exportRange, setExportRange] = useState<[dayjs.Dayjs, dayjs.Dayjs]>([
    dayjs().startOf('year'),
    dayjs(),
  ])
  // 勾选跨页累加，存的是整行而不只是 id：批量批准要读状态和版本号，而翻到第 2 页之后
  // 第 1 页的那几行已经不在 data.items 里了（见 src/rowSelection.ts）。
  const [selectedItems, setSelectedItems] = useState<Worklog[]>([])
  const [filters, setFilters] = useState<WorklogFilters>(EMPTY_FILTERS)
  // 关键词输入框单独存：边打字边查太吵，回车或点搜索才生效。
  const [keywordText, setKeywordText] = useState('')
  const [projectSearch, setProjectSearch] = useState('')
  const [projectKeyword, setProjectKeyword] = useState('')
  // 从选题详情页「本选题工时」跳过来时带着 ?projectId=，只看这个选题的工时；清掉就回到全部。
  const [searchParams, setSearchParams] = useSearchParams()
  const projectFilter = /^\d+$/.test(searchParams.get('projectId') || '') ? searchParams.get('projectId')! : ''
  const clearProjectFilter = () => {
    const next = new URLSearchParams(searchParams)
    next.delete('projectId')
    setSearchParams(next, { replace: true })
    setFilters(current => ({ ...current, page: 1 }))
  }
  // 选题筛选仍放在地址栏里：选题详情页「本选题工时」靠它跳过来，刷新、转发链接也还在。
  const setProjectFilter = (projectId?: string) => {
    if (!projectId) {
      clearProjectFilter()
      return
    }
    const next = new URLSearchParams(searchParams)
    next.set('projectId', projectId)
    setSearchParams(next, { replace: true })
    setFilters(current => ({ ...current, page: 1 }))
  }
  const updateFilters = (changes: Partial<WorklogFilters>) =>
    setFilters(current => ({ ...current, ...changes, page: 1 }))
  const filtered = !!(projectFilter || filters.status || filters.source || filters.keyword
    || filters.campusId || filters.from || filters.to)
  const resetFilters = () => {
    setKeywordText('')
    setFilters(current => ({ ...EMPTY_FILTERS, pageSize: current.pageSize }))
    if (projectFilter) clearProjectFilter()
  }
  const reviewer = hasPermission(user, 'WORKLOG_CONFIRM')
  const canExport = hasPermission(user, 'WORKLOG_EXPORT')
  const canSubmit = hasPermission(user, 'WORKLOG_SUBMIT')
  const canSubmitAny = hasPermission(user, 'WORKLOG_SUBMIT_ANY')
  const isAdmin = user?.permissionGroupCode === 'ADMIN'
  // /projects 要选题查看权限；没有的人（只管报工时的账号）就不显示选题下拉，免得一打开就报错。
  const canPickProject = canViewProjects(user)
  const { data, loading, error, reload } = useLoad(
    () => api<PageData<Worklog>>({ url: '/worklogs', params: qs({ ...filters, projectId: projectFilter }) }),
    emptyPage<Worklog>(), [filters.page, filters.pageSize, filters.status, filters.source, filters.keyword,
      filters.campusId, filters.from, filters.to, projectFilter],
  )
  useEffect(() => {
    const timer = window.setTimeout(() => setProjectKeyword(projectSearch.trim()), 300)
    return () => window.clearTimeout(timer)
  }, [projectSearch])
  const { data: projectOptions, loading: projectsLoading } = useLoad(
    () => canPickProject
      ? api<PageData<Project>>({ url: '/projects', params: qs({ page: 1, pageSize: 20, keyword: projectKeyword }) })
      : Promise.resolve(emptyPage<Project>()),
    emptyPage<Project>(), [canPickProject, projectKeyword],
  )
  // 从选题详情页带 ?projectId= 跳来时，候选里未必有它：单独取一次标题，下拉才显示名字而不是一串数字。
  const { data: pickedProject } = useLoad(
    () => canPickProject && projectFilter
      ? api<Project>({ url: `/projects/${projectFilter}` }).catch(() => null)
      : Promise.resolve(null),
    null as Project | null, [canPickProject, projectFilter],
  )
  const projectSelectOptions = [
    ...(pickedProject && !projectOptions.items.some(item => item.id === pickedProject.id) ? [pickedProject] : []),
    ...projectOptions.items,
  ].map(project => ({ value: String(project.id), label: project.title }))
  const selectedIds = selectedItems.map(item => item.id)
  // 列表重取之后把勾着的行换成新数据：批准和删除都带版本号，拿着旧快照去提交会被乐观锁拒掉。
  useEffect(() => {
    setSelectedItems(current => current.length
      ? keepSelectedRows(current.map(item => item.id), [current, data.items], item => item.id)
      : current)
  }, [data.items])
  const { data: requestOptions, loading: requestsLoading } = useLoad(
    async () => {
      if (!canSubmit || !user) return []
      if (canSubmitAny) {
        // 「向任意需求申报工时」不要求参与、也不看选题是否结束，所以列出所有看得到的需求；
        // 校区范围由 /requests 按授权校区裁剪，后端填报时也会再校验一次。
        const items: PhotoRequest[] = []
        for (let page = 1; ; page++) {
          const result = await api<PageData<PhotoRequest>>({ url: '/requests', params: { page, pageSize: 100 } })
          items.push(...result.items)
          if (result.items.length === 0 || items.length >= Number(result.total)) return items
        }
      }
      // 后端拒绝在已结束（已完成/已取消）项目的需求下填报工时，所以这里先把这些需求
      // 从下拉里去掉，别让人选完才吃一个报错。只查已结束的两种状态，是为了让 100 条的
      // 上限花在真正需要屏蔽的项目上；万一漏掉一个，后端仍会挡住。
      const [requests, completed, cancelled] = await Promise.all([
        api<PageData<PhotoRequest>>({
          url: '/requests',
          params: { page: 1, pageSize: 100, participantId: user.id },
        }),
        api<PageData<Project>>({ url: '/projects', params: { page: 1, pageSize: 100, status: 'COMPLETED' } }),
        api<PageData<Project>>({ url: '/projects', params: { page: 1, pageSize: 100, status: 'CANCELLED' } }),
      ])
      const endedProjectIds = new Set([...completed.items, ...cancelled.items].map(project => project.id))
      return requests.items.filter(item =>
        item.status !== 'CANCELLED' && !endedProjectIds.has(item.projectId))
    },
    [] as PhotoRequest[], [user?.id, canSubmit, canSubmitAny],
  )
  const { data: directory, loading: directoryLoading } = useLoad(
    () => canSubmit
      ? api<CampusMember[]>({ url: '/campus-members', params: { enabled: true } })
      : Promise.resolve([]),
    [] as CampusMember[], [user?.id, canSubmit],
  )
  // 不带 enabled 过滤：只是拿来显示需求所属校区的名字，已停用校区也要叫得出名字。
  // 审核的人还拿它做校区筛选。
  const { data: campuses } = useLoad(
    () => canSubmit || reviewer ? api<Campus[]>({ url: '/campuses' }) : Promise.resolve([]),
    [] as Campus[], [canSubmit, reviewer],
  )
  // 校区范围的账号只列授权校区：后端照样按授权裁剪，这里只是别让人选到注定为空的校区。
  const campusFilterOptions = campuses
    .filter(campus => user?.dataScope !== 'CAMPUS'
      || (user.campusIds || []).some(id => String(id) === String(campus.id)))
    .map(campus => ({ value: String(campus.id), label: campus.enabled ? campus.name : `${campus.name}（已停用）` }))
  const selectedRequestId = Form.useWatch('requestId', form) as EntityId | undefined
  const selectedRequest = requestOptions.find(item => item.id === selectedRequestId)
  const selectedCampusName = selectedRequest ? campusNameOf(campuses, selectedRequest.campusId) : ''
  const memberOptions = membersForRequest(directory, selectedRequest)

  // 换了需求之后，已选的工作人员如果不在新需求的校区里就清掉，否则保存时才被后端拒掉。
  const onFormValuesChange = (changed: { requestId?: EntityId }) => {
    if (!('requestId' in changed)) return
    const request = requestOptions.find(item => item.id === changed.requestId)
    const memberId = form.getFieldValue('memberContactId') as EntityId | undefined
    if (memberId && !membersForRequest(directory, request).some(member => member.id === memberId)) {
      form.setFieldValue('memberContactId', undefined)
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

  const action = async (item: Worklog, type: 'confirm' | 'submit') => {
    await api({ method: 'POST', url: `/worklogs/${item.id}/${type}`, data: { version: item.version } })
  }

  const runAction = async (item: Worklog, type: 'confirm' | 'submit') => {
    try {
      await action(item, type)
      message.success(type === 'confirm' ? '工时已批准' : '工时已提交')
      await reload()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  // 后端把退回理由存进 rejectReason 并作为通知正文发出，填报人只会看到这段话。
  // 之前前端写死一句“请重新核对工时”，等于把这个字段浪费掉了。
  const rejectWorklog = (item: Worklog) => {
    let reason = ''
    modal.confirm({
      title: '退回这条工时？',
      content: <Input.TextArea rows={4} maxLength={500} showCount
        placeholder="说明需要改什么，填报人会收到这段话"
        onChange={event => { reason = event.target.value }} />,
      okText: '确认退回',
      cancelText: '取消',
      onOk: async () => {
        if (!reason.trim()) throw new Error('请填写退回原因')
        try {
          await api({
            method: 'POST', url: `/worklogs/${item.id}/reject`,
            data: { reason: reason.trim(), version: item.version },
          })
          message.success(item.shareLinkId
            ? '工时已退回，申报人在上传页能看到原因，开链接的成员也会收到通知'
            : '工时已退回，填报人会收到通知')
          await reload()
        } catch (e) {
          message.error((e as Error).message)
          throw e
        }
      },
    })
  }

  const deleteItems = async (items: Worklog[]) => {
    try {
      await Promise.all(items.map(item => api({ method: 'DELETE', url: `/worklogs/${item.id}` })))
      message.success(`已删除 ${items.length} 条工时`)
      setSelectedItems([])
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
    // 勾选是跨页的，所以按勾选本身筛，不能再按当前页筛——翻页之后那几条不在 data.items 里。
    const items = selectedItems.filter(item => item.status === 'SUBMITTED')
    if (!items.length) {
      message.warning('请选择状态为“待确认”的工时')
      return
    }
    try {
      await Promise.all(items.map(item => action(item, 'confirm')))
      message.success(`已批准 ${items.length} 条工时`)
      setSelectedItems([])
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
        if (result.job.status === 'FAILED') throw new Error(result.job.errorMessage
          || '工时文件没能生成。这个日期范围内可能没有工时记录，换个范围再试一次。')
        await wait(1000)
      }
      message.info('导出任务仍在处理中，请稍后重试')
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setExporting(false)
    }
  }

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
    <Card className="filter-card worklog-filter-card">
      <Space wrap>
        <Input.Search allowClear placeholder="搜索成员姓名、学号、说明或需求" style={{ width: 260 }}
          value={keywordText}
          onChange={event => {
            setKeywordText(event.target.value)
            if (!event.target.value) updateFilters({ keyword: '' })
          }}
          onSearch={value => updateFilters({ keyword: value.trim() })} />
        <Select allowClear placeholder="全部状态" style={{ width: 120 }} value={filters.status || undefined}
          options={STATUS_OPTIONS} onChange={(status?: Worklog['status']) => updateFilters({ status: status ?? '' })} />
        <Select allowClear placeholder="全部来源" style={{ width: 120 }} value={filters.source || undefined}
          options={SOURCE_OPTIONS} onChange={(source?: WorklogFilters['source']) => updateFilters({ source: source ?? '' })} />
        <DatePicker.RangePicker allowEmpty={[true, true]} placeholder={['开始日期', '结束日期']} style={{ width: 250 }}
          value={[filters.from ? dayjs(filters.from) : null, filters.to ? dayjs(filters.to) : null]}
          presets={datePresets()}
          onChange={values => updateFilters({
            from: values?.[0]?.format('YYYY-MM-DD') || '',
            to: values?.[1]?.format('YYYY-MM-DD') || '',
          })} />
        {canPickProject && <Select allowClear showSearch placeholder="全部选题" style={{ width: 200 }}
          value={projectFilter || undefined} options={projectSelectOptions} loading={projectsLoading}
          filterOption={false} onSearch={setProjectSearch}
          notFoundContent={projectsLoading ? '加载中…' : '没有匹配的选题'}
          onChange={(projectId?: string) => { setProjectSearch(''); setProjectFilter(projectId) }} />}
        {!canPickProject && projectFilter && <Tag closable color="blue"
          onClose={event => { event.preventDefault(); clearProjectFilter() }}>只看选题 #{projectFilter} 的工时</Tag>}
        {reviewer && campusFilterOptions.length > 1 && <Select allowClear showSearch optionFilterProp="label"
          placeholder="全部校区" style={{ width: 140 }} value={filters.campusId || undefined}
          options={campusFilterOptions} onChange={(campusId = '') => updateFilters({ campusId })} />}
        {filtered && <Button type="link" onClick={resetFilters}>清空筛选</Button>}
      </Space>
      <Space wrap>
        {reviewer && <>
          <span>已选择 {selectedItems.length} 条{
            selectedItems.some(item => !data.items.some(row => row.id === item.id)) ? '（含其他页）' : ''}</span>
          {!!selectedItems.length && <Button type="link" onClick={() => setSelectedItems([])}>清空选择</Button>}
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
      <DataState loading={loading} error={error} empty={!data.items.length} onRetry={reload}
        emptyText={filtered ? '没有符合筛选条件的工时记录' : reviewer ? '还没有待审核的工时' : '还没有工时记录'}
        emptyHint={filtered
          ? '换个条件，或点「清空筛选」看全部。'
          : reviewer
            ? '成员提交工时、或有人通过上传链接申报工时后，会出现在这里等你确认或退回。'
            : '拍完、修完就来填一笔，导出统计时用的就是这些记录。'}>
        <ContentFitTable
          rowKey="id"
          dataSource={data.items}
          pagination={false}
          rowSelection={reviewer ? {
            // preserveSelectedRowKeys 不是可选的：没有它，antd 会在每次勾选时把不在当前页
            // （dataSource）里的 key 全部滤掉，翻页回来勾选就没了。见 src/rowSelection.ts。
            preserveSelectedRowKeys: true,
            selectedRowKeys: selectedIds,
            onChange: keys => setSelectedItems(
              keepSelectedRows(keys, [selectedItems, data.items], item => item.id)),
          } : undefined}
          columns={[
            // 每列给定宽度并禁止折行：之前全靠内容撑开，「需求」里的标签和标题、姓名和学号会
            // 各自乱换行，同一行高低不齐，看起来参差。
            { title: '日期', dataIndex: 'workDate', width: 112,
              render: value => <span className="worklog-nowrap">{dayjs(value).format('YYYY-MM-DD')}</span> },
            // 通过上传链接申报的工时没有需求：标出来源和所属选题，审核的人才知道该对着哪批照片看。
            { title: '需求 / 来源', dataIndex: 'requestTitle', width: 280, render: (value, item: Worklog) => item.shareLinkId
              ? <div className="worklog-source">
                <Tag color="purple" variant="filled">上传链接</Tag>
                <TableEllipsisText maxWidth={250}
                  value={`${item.projectTitle || '已删除的选题'}${item.shareLinkName ? ` · ${item.shareLinkName}` : ''}`} />
              </div>
              : <TableEllipsisText value={value} maxWidth={250} /> },
            { title: '成员', dataIndex: 'memberName', width: 130, render: (value, item: Worklog) =>
              <div className="worklog-member">
                <span className="worklog-nowrap">{value || '—'}</span>
                <span className="worklog-student-id">{item.memberStudentId || '—'}</span>
              </div> },
            { title: '拍摄', dataIndex: 'shootingMinutes', width: 100,
              render: value => <span className="worklog-nowrap">{formatMinutes(value)}</span> },
            { title: '修图', dataIndex: 'retouchingMinutes', width: 100,
              render: value => <span className="worklog-nowrap">{formatMinutes(value)}</span> },
            { title: '说明', dataIndex: 'remark', width: 260,
              render: value => <TableEllipsisText value={value} maxWidth={230} /> },
            { title: '状态', dataIndex: 'status', width: 96, render: value => <StatusTag value={value} /> },
            { title: '操作', fixed: 'right' as const, minWidth: reviewer ? WORKLOG_REVIEW_ACTION_MIN_WIDTH : WORKLOG_OWNER_ACTION_MIN_WIDTH,
              className: 'table-action-cell', render: (_, item: Worklog) => <Space>
              {/* 链接申报的工时记在开链接的成员名下，但他不是干活的人：被退回的由申报人在上传页重新申报。 */}
              {canSubmit && !item.shareLinkId && item.userId === user?.id && ['DRAFT', 'REJECTED'].includes(item.status) && (
                <Button onClick={() => void runAction(item, 'submit')}>提交</Button>
              )}
              {/* 草稿还没交上来，由填报人自己改；其余状态管理员都能直接更正，状态不变。 */}
              {isAdmin && item.status !== 'DRAFT' && (
                <Button icon={<EditOutlined />} onClick={() => setCorrecting(item)}>修改</Button>
              )}
              {reviewer && item.status === 'SUBMITTED' && <>
                <Button type="primary" icon={<CheckOutlined />} onClick={() => void runAction(item, 'confirm')}>批准</Button>
                <Button icon={<StopOutlined />} onClick={() => rejectWorklog(item)}>退回</Button>
              </>}
              {reviewer && (
                <Button danger type="text" icon={<DeleteOutlined />} onClick={() => confirmDelete([item])}>删除</Button>
              )}
              {!reviewer && canSubmit && item.userId === user?.id && ['DRAFT', 'REJECTED'].includes(item.status) &&
                <Button danger type="text" icon={<DeleteOutlined />} onClick={() => confirmDelete([item])}>删除</Button>}
            </Space> },
          ]}
        />
        {/* 翻页不清空勾选：跨页选完一批再统一批准是审核时的常规动作。 */}
        <ListPagination page={filters.page} pageSize={filters.pageSize} total={data.total}
          sizes={TABLE_PAGE_SIZES} showTotal={total => `共 ${total} 条`}
          onChange={(page, pageSize) => setFilters(turnPage(filters, page, pageSize))} />
      </DataState>
    </Card>
    <Modal title="填报工时" open={open} onCancel={() => setOpen(false)} onOk={create} okText="保存记录" confirmLoading={saving}>
      <Form form={form} layout="vertical" initialValues={{ shootingMinutes: 0, retouchingMinutes: 0, status: 'SUBMITTED' }} requiredMark={false}
        onValuesChange={onFormValuesChange}>
        <Form.Item label="关联需求" name="requestId" rules={[{ required: true, message: '请选择需求' }]}
          extra={selectedRequest ? <>需求所属校区：<strong>{selectedCampusName}</strong></> : undefined}>
          <Select
            showSearch
            optionFilterProp="label"
            loading={requestsLoading}
            placeholder={requestOptions.length ? (canSubmitAny ? '请选择需求' : '请选择已接受的需求') : '暂无可填报工时的需求'}
            notFoundContent={requestsLoading ? '正在加载需求…' : canSubmitAny ? '暂无可填报工时的需求' : '暂无可填报工时的需求（已结束项目的需求不会出现在这里）'}
            options={requestSelectOptions(requestOptions, campuses)}
          />
        </Form.Item>
        <Form.Item label="工作人员" name="memberContactId" rules={[{ required: true, message: '请从校区通讯录选择工作人员' }]}
          extra={selectedRequest
            ? `只列出「${selectedCampusName}」通讯录中的成员，成员在「通讯录」页维护`
            : '工作人员按需求所属校区的通讯录列出，请先选择关联需求'}>
          <Select
            showSearch
            optionFilterProp="label"
            loading={directoryLoading}
            disabled={!selectedRequest}
            placeholder={!selectedRequest
              ? '请先选择关联需求'
              : memberOptions.length || directoryLoading ? '按姓名或学号选择' : '该校区通讯录为空，请先添加成员'}
            notFoundContent={directoryLoading ? '正在加载通讯录…' : `「${selectedCampusName}」通讯录中没有可用成员`}
            options={memberOptions.map(member => ({
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
    {correcting && <WorklogCorrectionModal worklog={correcting} currentUserId={user?.id}
      onClose={() => setCorrecting(null)} onSaved={reload} />}
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
