import { App, Button, Input, Modal, Select, Space, Tag, Typography } from 'antd'
import { CheckOutlined, StopOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useState } from 'react'
import { api, emptyPage } from './api'
import { DataState } from './components'
import { ContentFitTable, TableEllipsisText } from './ContentFitTable'
import { useLoad } from './hooks'
import { serverTablePagination, turnPage } from './pagination'
import { keepSelectedRows } from './rowSelection'
import type { PageData, RegistrationApplication, RegistrationReviewResult, RegistrationStatus } from './types'

const statusMeta: Record<RegistrationStatus, { label: string; color?: string }> = {
  PENDING: { label: '待审核', color: 'orange' },
  APPROVED: { label: '已通过', color: 'green' },
  REJECTED: { label: '已驳回' },
}

interface ReviewFilters {
  keyword: string
  status: RegistrationStatus | 'ALL'
  page: number
  pageSize: number
}

export default function RegistrationReviewPanel() {
  const { message, modal } = App.useApp()
  const [searchText, setSearchText] = useState('')
  const [filters, setFilters] = useState<ReviewFilters>({ keyword: '', status: 'PENDING', page: 1, pageSize: 20 })
  const [selected, setSelected] = useState<RegistrationApplication[]>([])
  const [rejecting, setRejecting] = useState<RegistrationApplication[] | null>(null)
  const [rejectReason, setRejectReason] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const { data, loading, error, reload } = useLoad(
    () => api<PageData<RegistrationApplication>>({
      url: '/registration-applications',
      params: {
        page: filters.page,
        pageSize: filters.pageSize,
        keyword: filters.keyword || undefined,
        status: filters.status === 'ALL' ? undefined : filters.status,
      },
    }), emptyPage<RegistrationApplication>(), [filters],
  )
  const selectedIds = selected.map(item => item.id)

  /** 批量接口逐条处理：成功的从勾选里去掉，失败的留着并逐条说明原因。 */
  const review = async (action: 'approve' | 'reject', items: RegistrationApplication[], reason?: string) => {
    setSubmitting(true)
    try {
      const result = await api<RegistrationReviewResult>({
        method: 'POST', url: `/registration-applications/${action}`,
        data: { ids: items.map(item => item.id), reason },
      })
      const done = new Set(result.succeeded.map(String))
      setSelected(current => current.filter(item => !done.has(String(item.id))))
      const verb = action === 'approve' ? '通过' : '驳回'
      if (result.failed.length) {
        modal.warning({
          title: `${result.succeeded.length} 份已${verb}，${result.failed.length} 份未能${verb}`,
          content: <ul className="registration-review-failures">
            {result.failed.map(failure =>
              <li key={failure.id}><strong>{failure.username || `#${failure.id}`}</strong>：{failure.message}</li>)}
          </ul>,
        })
      } else {
        message.success(`已${verb} ${result.succeeded.length} 份申请`)
      }
      void reload()
      return true
    } catch (failure) {
      message.error((failure as Error).message)
      return false
    } finally {
      setSubmitting(false)
    }
  }

  const confirmApprove = (items: RegistrationApplication[]) => modal.confirm({
    title: items.length === 1 ? `通过「${items[0].displayName}」的注册申请？` : `通过选中的 ${items.length} 份注册申请？`,
    content: '通过后立即建出账号，申请人可以用自己设置的账号和密码登录。请先核对姓名是否属实。',
    okText: '通过',
    onOk: () => review('approve', items),
  })

  const openReject = (items: RegistrationApplication[]) => {
    setRejectReason('')
    setRejecting(items)
  }

  const pendingSelected = selected.filter(item => item.status === 'PENDING')

  return <>
    <div className="tab-toolbar">
      <div>
        <Typography.Title level={4}>注册审核</Typography.Title>
        <Typography.Text type="secondary">同学用注册码提交的申请。请核对真实姓名，通过后账号立即生效。</Typography.Text>
      </div>
      <Space wrap>
        <Select value={filters.status} style={{ width: 120 }}
          onChange={status => setFilters(current => ({ ...current, status, page: 1 }))}
          options={[
            { value: 'PENDING', label: '待审核' },
            { value: 'APPROVED', label: '已通过' },
            { value: 'REJECTED', label: '已驳回' },
            { value: 'ALL', label: '全部' },
          ]} />
        <Input.Search allowClear value={searchText} placeholder="搜索姓名、账号或邮箱" style={{ width: 240 }}
          onChange={event => {
            setSearchText(event.target.value)
            if (!event.target.value) setFilters(current => ({ ...current, keyword: '', page: 1 }))
          }}
          onSearch={value => setFilters(current => ({ ...current, keyword: value.trim(), page: 1 }))} />
      </Space>
    </div>
    <Space wrap className="registration-review-actions">
      <Button type="primary" icon={<CheckOutlined />} disabled={!pendingSelected.length} loading={submitting}
        onClick={() => confirmApprove(pendingSelected)}>批量通过（{pendingSelected.length}）</Button>
      <Button icon={<StopOutlined />} disabled={!pendingSelected.length} loading={submitting}
        onClick={() => openReject(pendingSelected)}>批量驳回（{pendingSelected.length}）</Button>
      {/* 勾选跨页保留，看不见的勾选要有个说法（见 src/rowSelection.ts）。 */}
      {!!selected.length && <>
        <Typography.Text type="secondary">已选 {selected.length} 份</Typography.Text>
        <Button type="link" onClick={() => setSelected([])}>清空选择</Button>
      </>}
    </Space>
    <DataState loading={loading} error={error} empty={!data.items.length} onRetry={reload}
      emptyText={filters.keyword ? `没有匹配“${filters.keyword}”的申请`
        : filters.status === 'PENDING' ? '没有待审核的注册申请' : '没有注册申请'}
      emptyHint={filters.keyword ? '姓名、账号和邮箱都会被搜索，换个词再试试。'
        : '同学用注册码提交申请后会出现在这里。'}>
      <ContentFitTable rowKey="id" dataSource={data.items}
        pagination={serverTablePagination(filters, data.total,
          (page, pageSize) => setFilters(current => turnPage(current, page, pageSize)),
          { showTotal: total => `共 ${total} 份申请` })}
        rowSelection={{
          // 跨页勾选必须带 preserveSelectedRowKeys，见 src/rowSelection.ts。
          preserveSelectedRowKeys: true,
          selectedRowKeys: selectedIds,
          getCheckboxProps: (item: RegistrationApplication) => ({ disabled: item.status !== 'PENDING' }),
          onChange: keys => setSelected(keepSelectedRows(keys, [selected, data.items], item => item.id)),
        }}
        columns={[
          { title: '姓名', render: (_, item: RegistrationApplication) => <div className="table-title">
            <strong>{item.displayName}</strong><span>@{item.username}</span>
          </div> },
          { title: '邮箱', dataIndex: 'email' },
          { title: '注册码', render: (_, item: RegistrationApplication) => <Space size={4} wrap>
            <span>{item.codeName || '-'}</span>
            <Tag variant="filled">{item.permissionGroupName || '权限组已删除'}</Tag>
          </Space> },
          { title: '提交时间', dataIndex: 'createdAt', render: (value: string) => dayjs(value).format('YYYY-MM-DD HH:mm') },
          { title: '状态', render: (_, item: RegistrationApplication) => <Space orientation="vertical" size={0}>
            <Tag color={statusMeta[item.status].color} variant="filled">{statusMeta[item.status].label}</Tag>
            {item.reviewedAt && <Typography.Text type="secondary" className="registration-reviewer">
              {item.reviewerName || '管理员'} · {dayjs(item.reviewedAt).format('MM-DD HH:mm')}
            </Typography.Text>}
          </Space> },
          { title: '驳回原因', dataIndex: 'rejectReason', minWidth: 160,
            render: (value?: string | null) => <TableEllipsisText value={value || '-'} maxWidth={200} /> },
          { title: '操作', fixed: 'right' as const, className: 'table-action-cell', render: (_, item: RegistrationApplication) =>
            item.status === 'PENDING' ? <Space>
              <Button type="link" icon={<CheckOutlined />} onClick={() => confirmApprove([item])}>通过</Button>
              <Button type="link" danger icon={<StopOutlined />} onClick={() => openReject([item])}>驳回</Button>
            </Space> : null },
        ]} />
    </DataState>
    <Modal title={rejecting?.length === 1 ? `驳回「${rejecting[0].displayName}」的注册申请` : `驳回 ${rejecting?.length ?? 0} 份注册申请`}
      open={rejecting !== null} onCancel={() => setRejecting(null)} okText="驳回" okButtonProps={{ danger: true }}
      confirmLoading={submitting}
      onOk={async () => {
        if (rejecting && await review('reject', rejecting, rejectReason.trim() || undefined)) setRejecting(null)
      }}>
      <Typography.Paragraph type="secondary">驳回后占用的名额归还给注册码，申请人可以改正后重新提交。</Typography.Paragraph>
      <Input.TextArea value={rejectReason} onChange={event => setRejectReason(event.target.value)}
        placeholder="驳回原因（选填），例如：姓名与部门名单不符" maxLength={500} showCount rows={3} />
    </Modal>
  </>
}
