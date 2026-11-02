import {
  Alert, App, Button, Checkbox, DatePicker, Empty, Input, Modal, Progress, Segmented, Select, Space, Table, Tabs, Tag,
  Tooltip, Typography,
} from 'antd'
import {
  CheckCircleOutlined, EyeOutlined, FilePdfOutlined, HistoryOutlined, MailOutlined, ReloadOutlined, SearchOutlined,
  SendOutlined,
} from '@ant-design/icons'
import { useEffect, useMemo, useState } from 'react'
import dayjs from 'dayjs'
import { api } from './api'
import { DataState } from './components'
import MailPreviewFrame from './MailPreviewFrame'
import { useLoad, useStableCallback } from './hooks'
import { clientTablePagination } from './pagination'
import {
  CHANNEL_STATUS_COLORS, CHANNEL_STATUS_LABELS, DELIVERY_STATUS_COLORS, DELIVERY_STATUS_LABELS,
  EMPTY_RECIPIENT_FILTER, batchProgress, channelsLabel, distinctValues, failedChannels, failedUserIds,
  filterRecipients, formatMinutes, isSelectable, keepSelectable, statementFileName, unavailableReason, uniqueNames,
  userKey,
} from './settlementMail'
import type {
  RecipientFilter, RecordPresence, SettlementBatch, SettlementChannels, SettlementChannelStatus, SettlementDelivery,
  SettlementPreview, SettlementRecipient, SettlementStatus,
} from './settlementMail'

/** 一次最多导出多少人的 PDF：每份都要在浏览器里排版截图，太多会卡很久。 */
const MAX_PDF_EXPORT = 50

interface Props {
  open: boolean
  /** 打开时的默认时间范围（统计面板当前的范围）。 */
  initialRange: [string, string]
  onClose: () => void
}

/**
 * 统计面板「发送清算单」：按筛选器挑出收件人，给每人发一份只含本人记录的清算单（参与的选题、
 * 期内全部工时含每次申报与退回原因、被引明细），以站内信和 / 或邮件发送；也可以导出成 PDF。
 * 只在打开时挂载内容，关掉再开会重新拉名单。
 */
export default function SettlementMailModal({ open, initialRange, onClose }: Props) {
  return <Modal open={open} onCancel={onClose} destroyOnHidden width={1180} footer={null}
    title={<Space><MailOutlined />发送清算单</Space>}>
    {open && <SettlementMailContent initialRange={initialRange} />}
  </Modal>
}

type View = 'compose' | 'history'

function SettlementMailContent({ initialRange }: { initialRange: [string, string] }) {
  const { message, modal } = App.useApp()
  const [view, setView] = useState<View>('compose')
  const [range, setRange] = useState<[string, string]>(initialRange)
  const [filter, setFilter] = useState<RecipientFilter>(EMPTY_RECIPIENT_FILTER)
  const patchFilter = (patch: Partial<RecipientFilter>) => setFilter(current => ({ ...current, ...patch }))
  const [selected, setSelected] = useState<string[]>([])
  const [preview, setPreview] = useState<{ recipient: SettlementRecipient; data?: SettlementPreview; error?: string } | null>(null)
  const [sending, setSending] = useState(false)
  const [exporting, setExporting] = useState<string | null>(null)
  const [batchId, setBatchId] = useState<number | null>(null)
  /** 用户自己选过发送方式之前，按「邮件配好了就两种都发，否则只发站内信」。 */
  const [chosenChannels, setChosenChannels] = useState<SettlementChannels | null>(null)

  const status = useLoad(() => api<SettlementStatus>({ url: '/settlement-mails/status' }), null as SettlementStatus | null, [])
  const recipients = useLoad(
    () => api<SettlementRecipient[]>({ url: '/settlement-mails/recipients', params: { from: range[0], to: range[1] } }),
    [] as SettlementRecipient[], [range[0], range[1]])
  const configured = status.data?.configured ?? false
  const channels = useMemo<SettlementChannels>(() => chosenChannels
    ? { inbox: chosenChannels.inbox, email: chosenChannels.email && configured }
    : { inbox: true, email: configured }, [chosenChannels, configured])
  const busy = status.data?.activeBatchId != null

  // 名单刷新、或改成只发邮件后，去掉已经不能收的人（例如刚被停用、刚撞了名、没有邮箱）。
  useEffect(() => {
    setSelected(current => keepSelectable(current, recipients.data, channels))
  }, [recipients.data, channels])

  const rows = useMemo(() => filterRecipients(recipients.data, filter), [recipients.data, filter])
  const campuses = useMemo(() => distinctValues(recipients.data, 'campus'), [recipients.data])
  const groups = useMemo(() => distinctValues(recipients.data, 'groupName'), [recipients.data])
  const selectableRows = rows.filter(recipient => isSelectable(recipient, channels))
  const unavailableInView = rows.length - selectableRows.length
  const selectedRecipients = recipients.data.filter(recipient => selected.includes(userKey(recipient.userId)))

  const openPreview = async (recipient: SettlementRecipient) => {
    setPreview({ recipient })
    try {
      const data = await loadPreview(recipient)
      setPreview(current => current?.recipient.userId === recipient.userId ? { recipient, data } : current)
    } catch (e) {
      setPreview(current => current?.recipient.userId === recipient.userId
        ? { recipient, error: (e as Error).message } : current)
    }
  }
  const loadPreview = (recipient: SettlementRecipient) => api<SettlementPreview>({
    method: 'POST', url: '/settlement-mails/preview', data: { userId: recipient.userId, from: range[0], to: range[1] },
  })

  /** 一人一份 PDF；多于一人时打成 ZIP。内容和预览一样（当前模板）。 */
  const exportPdf = async (targets: { recipient: SettlementRecipient; data?: SettlementPreview }[]) => {
    if (!targets.length || exporting) return
    if (targets.length > MAX_PDF_EXPORT) {
      message.warning(`一次最多导出 ${MAX_PDF_EXPORT} 人的 PDF，请分批导出`)
      return
    }
    setExporting('正在准备…')
    try {
      const { statementPdf, saveBlob, zipFiles } = await import('./settlementPdf')
      const files: { name: string; data: Blob }[] = []
      for (const [index, target] of targets.entries()) {
        const prefix = targets.length > 1 ? `${index + 1} / ${targets.length} ` : ''
        setExporting(`正在生成 ${prefix}${target.recipient.displayName}`)
        const data = target.data ?? await loadPreview(target.recipient)
        files.push({
          name: statementFileName(target.recipient.displayName, range[0], range[1]),
          data: await statementPdf(data.html, { title: data.subject }),
        })
      }
      if (files.length === 1) {
        saveBlob(files[0].data, files[0].name)
      } else {
        const names = uniqueNames(files.map(file => file.name))
        saveBlob(await zipFiles(files.map((file, index) => ({ name: names[index], data: file.data }))),
          `清算单-${range[0]}至${range[1]}-${files.length}人.zip`)
      }
      message.success(files.length === 1 ? 'PDF 已生成' : `已打包 ${files.length} 份 PDF`)
    } catch (e) {
      message.error(`PDF 没能生成：${(e as Error).message}`)
    } finally {
      setExporting(null)
    }
  }

  const send = (userIds: string[]) => {
    if (!userIds.length) return
    const chosen = recipients.data.filter(r => userIds.includes(userKey(r.userId)))
    const withoutEmail = channels.email ? chosen.filter(r => !r.email).length : 0
    const how = channels.email && channels.inbox ? '站内信和邮件' : channels.inbox ? '站内信' : '邮件'
    modal.confirm({
      title: `给 ${userIds.length} 人发送清算单？`,
      icon: <SendOutlined />,
      content: <div>
        <p>清算时间：{range[0]} 至 {range[1]}。每人通过{how}收到一份只含本人记录的清算单。</p>
        {withoutEmail > 0 && <p>其中 {withoutEmail} 人没有邮箱，只收站内信。</p>}
        <Typography.Paragraph type="secondary" ellipsis={{ rows: 3, expandable: true, symbol: '展开' }}>
          {chosen.map(r => r.displayName).join('、')}
        </Typography.Paragraph>
      </div>,
      okText: '发送',
      onOk: async () => {
        setSending(true)
        try {
          const batch = await api<SettlementBatch>({ method: 'POST', url: '/settlement-mails/batches',
            data: { from: range[0], to: range[1], userIds, sendEmail: channels.email, sendInbox: channels.inbox } })
          message.success('已开始发送，可以在发送记录里看进度')
          setSelected([])
          setBatchId(batch.id)
          setView('history')
          status.reload()
        } catch (e) {
          message.error((e as Error).message)
          status.reload()
        } finally { setSending(false) }
      },
    })
  }

  const noChannel = !channels.email && !channels.inbox
  const sendBlocked = noChannel ? '请至少选择一种发送方式' : busy ? '已有一批清算单正在发送，请等它发完' : undefined

  return <div className="settlement-mail">
    {status.data && !configured && <Alert type="info" showIcon className="settlement-mail-alert"
      title="邮件服务还没有配置，现在只能发站内信"
      description="要同时发邮件，请管理员在服务器的 .env 中填写 SMTP_HOST、SMTP_USERNAME（发信邮箱）和 SMTP_PASSWORD（授权码）后重启服务。" />}
    {status.data && configured && <Typography.Text type="secondary" className="settlement-mail-from">
      发信邮箱：{status.data.fromAddress}
    </Typography.Text>}
    <Tabs activeKey={view} onChange={key => setView(key as View)} items={[
      { key: 'compose', label: <span><SendOutlined /> 选择收件人</span> },
      { key: 'history', label: <span><HistoryOutlined /> 发送记录</span> },
    ]} />
    {view === 'compose' && <>
      <div className="settlement-mail-filters">
        <Space wrap>
          <span>清算时间</span>
          <DatePicker.RangePicker allowClear={false} value={[dayjs(range[0]), dayjs(range[1])]}
            onChange={values => values?.[0] && values[1]
              && setRange([values[0].format('YYYY-MM-DD'), values[1].format('YYYY-MM-DD')])} />
          <Input allowClear prefix={<SearchOutlined />} placeholder="姓名、账号或邮箱" style={{ width: 180 }}
            value={filter.keyword} onChange={event => patchFilter({ keyword: event.target.value })} />
          <Select allowClear placeholder="全部校区" style={{ width: 140 }} value={filter.campus}
            options={campuses.map(value => ({ value, label: value }))} onChange={campus => patchFilter({ campus })} />
          <Select allowClear placeholder="全部权限组" style={{ width: 150 }} value={filter.group}
            options={groups.map(value => ({ value, label: value }))} onChange={group => patchFilter({ group })} />
        </Space>
        <Space wrap>
          <Segmented<RecordPresence> value={filter.records} onChange={records => patchFilter({ records })} options={[
            { value: 'with', label: '期内有记录' }, { value: 'all', label: '全部账号' }, { value: 'without', label: '期内无记录' },
          ]} />
          <Checkbox checked={filter.onlyRejected} onChange={event => patchFilter({ onlyRejected: event.target.checked })}>
            只看有退回的
          </Checkbox>
          <Button type="link" onClick={() => setFilter(EMPTY_RECIPIENT_FILTER)}>重置筛选</Button>
        </Space>
      </div>
      <Typography.Paragraph type="secondary" className="settlement-mail-hint">
        记录按账号姓名匹配：工时取成员姓名、被引和上传的图片取拍摄者姓名相同的记录。工时按工作日期、被引按被引时间、
        参与的选题按图片上传时间落在清算时间内计入，列出待审核、已确认和已退回的工时（不含草稿）。
        与别的账号同名的人不能选；只发邮件时，没有邮箱的人也不能选。
      </Typography.Paragraph>
      <DataState loading={recipients.loading} error={recipients.error} onRetry={recipients.reload}>
        <Table<SettlementRecipient> size="small" rowKey={recipient => userKey(recipient.userId)} dataSource={rows} scroll={{ x: 1040 }}
          pagination={clientTablePagination(rows.length, { size: 'small' })}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有符合筛选条件的账号" /> }}
          rowSelection={{
            selectedRowKeys: selected,
            preserveSelectedRowKeys: true,
            onChange: keys => setSelected(keys.map(String)),
            getCheckboxProps: recipient => ({ disabled: !isSelectable(recipient, channels) }),
          }}
          columns={[
            { title: '姓名', dataIndex: 'displayName', fixed: 'left', width: 150, render: (value, recipient) => {
              const reason = unavailableReason(recipient, channels)
              return <Space size={4}>
                <span className="nowrap">{value}</span>
                {recipient.sameNameCount > 0
                  ? <Tooltip title={reason}><Tag color="orange">同名</Tag></Tooltip>
                  : !recipient.email && <Tooltip title={reason ?? '没有邮箱，只能收站内信'}><Tag>无邮箱</Tag></Tooltip>}
              </Space>
            } },
            { title: '邮箱', dataIndex: 'email', ellipsis: true, render: value => value || <Typography.Text type="secondary">—</Typography.Text> },
            { title: '校区', dataIndex: 'campus', width: 110, render: value => value || '—' },
            { title: '权限组', dataIndex: 'groupName', width: 120, render: value => value || '—' },
            { title: '参与选题', key: 'projects', width: 120, render: (_, recipient) => recipient.uploadedPhotos
              ? <span>{recipient.projectCount ?? 0} 个 · {recipient.uploadedPhotos} 张</span>
              : <Typography.Text type="secondary">无</Typography.Text>,
            sorter: (a, b) => (a.uploadedPhotos ?? 0) - (b.uploadedPhotos ?? 0) },
            { title: '工时记录', key: 'worklogs', width: 210, render: (_, recipient) => recipient.worklogCount
              ? <Space size={4} wrap>
                <span>{recipient.worklogCount} 条</span>
                <Typography.Text type="secondary">已确认 {formatMinutes(recipient.confirmedMinutes)}</Typography.Text>
                {recipient.pendingCount > 0 && <Tag color="gold">待审 {recipient.pendingCount}</Tag>}
                {recipient.rejectedCount > 0 && <Tag color="red">退回 {recipient.rejectedCount}</Tag>}
              </Space>
              : <Typography.Text type="secondary">无</Typography.Text>,
            sorter: (a, b) => a.worklogCount - b.worklogCount },
            { title: '被引', key: 'adoptions', width: 90, render: (_, recipient) => recipient.adoptionCount
              ? `${recipient.adoptedPhotos} 张` : <Typography.Text type="secondary">无</Typography.Text>,
            sorter: (a, b) => a.adoptedPhotos - b.adoptedPhotos },
            { title: '预览', key: 'preview', fixed: 'right', width: 72, render: (_, recipient) =>
              <Button type="link" size="small" icon={<EyeOutlined />} aria-label={`预览${recipient.displayName}的清算单`}
                disabled={recipient.sameNameCount > 0} onClick={() => void openPreview(recipient)} /> },
          ]} />
      </DataState>
      <div className="settlement-mail-footer">
        <Space wrap>
          <span>已选 <strong>{selected.length}</strong> 人</span>
          <Button size="small" disabled={!selectableRows.length}
            onClick={() => setSelected(current => [...new Set([...current, ...selectableRows.map(r => userKey(r.userId))])])}>
            全选筛选结果（{selectableRows.length} 人）</Button>
          <Button size="small" disabled={!selected.length} onClick={() => setSelected([])}>清空</Button>
          {unavailableInView > 0 && <Typography.Text type="secondary">筛选结果里有 {unavailableInView} 人不能选</Typography.Text>}
        </Space>
        <Space wrap className="settlement-mail-actions">
          <span className="settlement-mail-channels" role="group" aria-label="发送方式">
            <Typography.Text type="secondary">发送方式</Typography.Text>
            <Checkbox checked={channels.inbox} onChange={event => setChosenChannels({ ...channels, inbox: event.target.checked })}>
              站内信</Checkbox>
            <Tooltip title={configured ? undefined : '邮件服务未配置'}>
              <Checkbox checked={channels.email} disabled={!configured}
                onChange={event => setChosenChannels({ ...channels, email: event.target.checked })}>邮件</Checkbox>
            </Tooltip>
          </span>
          <Tooltip title={selected.length > MAX_PDF_EXPORT ? `一次最多导出 ${MAX_PDF_EXPORT} 人` : '每人一份 PDF，多人时打包成 ZIP'}>
            <Button icon={<FilePdfOutlined />} loading={exporting !== null}
              disabled={!selected.length || selected.length > MAX_PDF_EXPORT}
              onClick={() => void exportPdf(selectedRecipients.map(recipient => ({ recipient })))}>
              {exporting ?? `导出 PDF${selected.length ? `（${selected.length} 人）` : ''}`}
            </Button>
          </Tooltip>
          <Tooltip title={sendBlocked}>
            <Button type="primary" icon={<SendOutlined />} loading={sending}
              disabled={!selected.length || !!sendBlocked} onClick={() => send(selected)}>
              发送清算单{selected.length ? `（${selected.length} 人）` : ''}
            </Button>
          </Tooltip>
        </Space>
      </div>
    </>}
    {view === 'history' && <SettlementHistory focusId={batchId} onFocus={setBatchId}
      onResend={(batch) => {
        const retry = failedChannels(batch)
        setRange([batch.from, batch.to])
        setChosenChannels({ email: retry.email && configured, inbox: retry.inbox })
        setSelected(failedUserIds(batch))
        setView('compose')
        message.info(retry.email && !configured
          ? '已选中上次失败的人；邮件服务现在没有配置，只能改发站内信'
          : '已选中上次失败的人，只补发失败的那种方式，确认后再点发送')
      }}
      onFinished={status.reload} />}
    <Modal open={preview !== null} onCancel={() => setPreview(null)} width={760} destroyOnHidden
      title={preview ? `${preview.recipient.displayName}的清算单预览` : '预览'}
      footer={<Space>
        <Button icon={<FilePdfOutlined />} disabled={!preview?.data} loading={exporting !== null}
          onClick={() => preview?.data && void exportPdf([{ recipient: preview.recipient, data: preview.data }])}>
          {exporting ?? '下载 PDF'}</Button>
        <Button onClick={() => setPreview(null)}>关闭</Button>
      </Space>}>
      {preview && <DataState loading={!preview.data && !preview.error} error={preview.error}
        onRetry={() => void openPreview(preview.recipient)}>
        {preview.data && <MailPreviewFrame subject={preview.data.subject} html={preview.data.html} />}
      </DataState>}
    </Modal>
  </div>
}

function ChannelTag({ status }: { status?: SettlementChannelStatus | null }) {
  if (!status) return <Typography.Text type="secondary">—</Typography.Text>
  return <Tag color={CHANNEL_STATUS_COLORS[status]}>{CHANNEL_STATUS_LABELS[status]}</Tag>
}

function SettlementHistory({ focusId, onFocus, onResend, onFinished }: {
  focusId: number | null
  onFocus: (id: number | null) => void
  onResend: (batch: SettlementBatch) => void
  onFinished: () => void
}) {
  const batches = useLoad(() => api<SettlementBatch[]>({ url: '/settlement-mails/batches', params: { limit: 20 } }),
    [] as SettlementBatch[], [])
  const [detail, setDetail] = useState<SettlementBatch | null>(null)
  const [detailError, setDetailError] = useState('')
  const activeId = focusId ?? batches.data[0]?.id ?? null
  const reloadBatches = batches.refresh
  const finished = useStableCallback(onFinished)

  // 进行中的批次每 1.5 秒刷一次；发完后再刷一次列表，并让外面更新「是否有批次在发」。
  useEffect(() => {
    if (activeId == null) return
    let cancelled = false
    let timer: number | undefined
    const load = async () => {
      try {
        const next = await api<SettlementBatch>({ url: `/settlement-mails/batches/${activeId}` })
        if (cancelled) return
        setDetail(next)
        setDetailError('')
        if (next.status === 'RUNNING') timer = window.setTimeout(() => void load(), 1500)
        else { reloadBatches(); finished() }
      } catch (e) {
        if (!cancelled) setDetailError((e as Error).message)
      }
    }
    void load()
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [activeId, reloadBatches, finished])

  return <div className="settlement-history">
    <div className="settlement-history-list">
      <div className="settlement-history-list-head">
        <Typography.Text strong>最近 20 次</Typography.Text>
        <Button size="small" type="text" icon={<ReloadOutlined />} aria-label="刷新发送记录" onClick={batches.reload} />
      </div>
      <DataState loading={batches.loading} error={batches.error} onRetry={batches.reload}
        empty={!batches.data.length} emptyText="还没有发送过清算单">
        {batches.data.map(batch => <button type="button" key={batch.id}
          className={`settlement-history-item${batch.id === activeId ? ' active' : ''}`} onClick={() => onFocus(batch.id)}>
          <span>{batch.from} 至 {batch.to}</span>
          <small>{batch.createdByName} · {dayjs(batch.createdAt).format('MM-DD HH:mm')} · {channelsLabel(batch)}</small>
          <small>{batch.status === 'RUNNING' ? <Tag color="processing">发送中</Tag>
            : batch.failed ? <Tag color="error">{batch.failed} 人失败</Tag>
              : <Tag color="success" icon={<CheckCircleOutlined />}>全部发出</Tag>}{batch.sent} / {batch.total}</small>
        </button>)}
      </DataState>
    </div>
    <div className="settlement-history-detail">
      {detailError && <Alert type="error" showIcon title="发送记录没能加载出来" description={detailError} />}
      {!detail && !detailError && activeId != null && <Typography.Text type="secondary">正在加载…</Typography.Text>}
      {detail && detail.id === activeId && <>
        <div className="settlement-history-summary">
          <div>
            <Typography.Title level={5} style={{ margin: 0 }}>{detail.from} 至 {detail.to}</Typography.Title>
            <Typography.Text type="secondary">
              {detail.createdByName} 于 {dayjs(detail.createdAt).format('YYYY-MM-DD HH:mm')} 发起（{channelsLabel(detail)}）
              {detail.finishedAt ? `，${dayjs(detail.finishedAt).format('HH:mm:ss')} 发完` : ''}
            </Typography.Text>
          </div>
          {detail.status === 'COMPLETED' && detail.failed > 0 &&
            <Button icon={<SendOutlined />} onClick={() => onResend(detail)}>重发失败的（{detail.failed} 人）</Button>}
        </div>
        <Progress percent={batchProgress(detail)} status={detail.status === 'RUNNING' ? 'active'
          : detail.failed ? 'exception' : 'success'}
          format={() => `${detail.sent + detail.failed} / ${detail.total}`} />
        <Space size={16} className="settlement-history-counts">
          <span>已发送 <strong>{detail.sent}</strong></span>
          <span>失败 <strong className={detail.failed ? 'text-danger' : undefined}>{detail.failed}</strong></span>
          <span>共 <strong>{detail.total}</strong></span>
        </Space>
        <Table size="small" rowKey="id" dataSource={detail.deliveries ?? []} scroll={{ x: 820 }}
          pagination={clientTablePagination(detail.deliveries?.length ?? 0, { size: 'small' })} columns={[
            { title: '收件人', dataIndex: 'recipientName', width: 120 },
            { title: '邮箱', dataIndex: 'recipientEmail', ellipsis: true, render: value => value || '—' },
            { title: '记录', key: 'records', width: 130, render: (_, d) => `工时 ${d.worklogCount} · 被引 ${d.adoptionCount}` },
            { title: '状态', dataIndex: 'status', width: 90, render: (value: keyof typeof DELIVERY_STATUS_LABELS) =>
              <Tag color={DELIVERY_STATUS_COLORS[value]}>{DELIVERY_STATUS_LABELS[value]}</Tag> },
            ...(detail.sendInbox ? [{ title: '站内信', key: 'inbox', width: 120, render: (_: unknown, d: SettlementDelivery) =>
              <Space size={4}>
                <ChannelTag status={d.inboxStatus} />
                {d.statementId != null && <a href={`#/settlements/${d.statementId}`} target="_blank" rel="noreferrer">查看</a>}
              </Space> }] : []),
            ...(detail.sendEmail ?? true ? [{ title: '邮件', key: 'email', width: 80, render: (_: unknown, d: SettlementDelivery) =>
              <ChannelTag status={d.emailStatus} /> }] : []),
            { title: '说明', key: 'note', render: (_, d) => d.errorMessage
              ? <Typography.Text type="danger">{d.errorMessage}</Typography.Text>
              : d.sentAt ? <Typography.Text type="secondary">{dayjs(d.sentAt).format('HH:mm:ss')} 发出</Typography.Text> : null },
          ]} />
      </>}
      {activeId == null && !batches.loading && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选一次发送查看详情" />}
    </div>
  </div>
}
