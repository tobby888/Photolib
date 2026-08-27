import { Alert, App, Button, Input, Modal, Space, Tag, Tooltip, Typography } from 'antd'
import { CloudDownloadOutlined, DatabaseOutlined, HistoryOutlined, RedoOutlined, SaveOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useEffect, useMemo, useRef, useState } from 'react'
import { api, emptyPage } from './api'
import { ContentFitTable, TableEllipsisText } from './ContentFitTable'
import { DataState } from './components'
import { useLoad } from './hooks'
import type { DatabaseBackup, DatabaseBackupDownload, DatabaseRestore, PageData } from './types'

const typeLabels: Record<DatabaseBackup['type'], string> = {
  SCHEDULED: '每日自动',
  MANUAL: '手动',
  PRE_RESTORE: '回滚前兜底',
}
const statusLabels: Record<DatabaseBackup['status'], { text: string; color: string }> = {
  RUNNING: { text: '进行中', color: 'processing' },
  SUCCEEDED: { text: '成功', color: 'green' },
  FAILED: { text: '失败', color: 'red' },
  EXPIRED: { text: '已过期', color: 'default' },
}
/** 回滚是不可逆的整库覆盖，必须让管理员亲手打出这四个字才放行。 */
const RESTORE_CONFIRMATION = '确认回滚'

function formatSize(bytes?: number | null) {
  if (bytes === null || bytes === undefined) return '-'
  const units = ['B', 'KB', 'MB', 'GB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${unit === 0 ? value : value.toFixed(1)} ${units[unit]}`
}

function formatTime(value?: string | null) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
}

export default function DatabaseBackupPanel() {
  const { message, modal } = App.useApp()
  const [starting, setStarting] = useState(false)
  const [restoreTarget, setRestoreTarget] = useState<DatabaseBackup | null>(null)
  const [confirmation, setConfirmation] = useState('')
  const [restoring, setRestoring] = useState(false)

  const { data: backups, loading, error, reload } = useLoad(
    () => api<PageData<DatabaseBackup>>({ url: '/database-backups', params: { page: 1, pageSize: 50 } }),
    emptyPage<DatabaseBackup>(), [],
  )
  const { data: restores, reload: reloadRestores } = useLoad(
    () => api<PageData<DatabaseRestore>>({ url: '/database-restores', params: { page: 1, pageSize: 10 } }),
    emptyPage<DatabaseRestore>(), [],
  )

  const busy = useMemo(
    () => backups.items.some(item => item.status === 'RUNNING')
      || restores.items.some(item => item.status === 'RUNNING'),
    [backups.items, restores.items],
  )

  // 备份与回滚都是异步任务，进行中时轮询到出结果为止。
  const refresh = useRef(() => {})
  refresh.current = () => { void reload(); void reloadRestores() }
  useEffect(() => {
    if (!busy) return
    const timer = window.setInterval(() => refresh.current(), 3000)
    return () => window.clearInterval(timer)
  }, [busy])

  const startBackup = async () => {
    setStarting(true)
    try {
      await api<DatabaseBackup>({ method: 'POST', url: '/database-backups' })
      message.success('备份任务已启动，完成后列表会自动刷新')
      refresh.current()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setStarting(false)
    }
  }

  const download = async (backup: DatabaseBackup) => {
    try {
      const link = await api<DatabaseBackupDownload>({ url: `/database-backups/${backup.id}/download` })
      window.open(link.url, '_blank', 'noopener')
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const submitRestore = async () => {
    if (!restoreTarget) return
    setRestoring(true)
    try {
      await api<DatabaseRestore>({ method: 'POST', url: `/database-backups/${restoreTarget.id}/restore` })
      setRestoreTarget(null)
      setConfirmation('')
      modal.info({
        title: '回滚已开始',
        content: '系统会先自动生成一份"回滚前兜底备份"，再把数据库写回所选备份。完成前请不要操作其他功能。',
      })
      refresh.current()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setRestoring(false)
    }
  }

  return <>
    <div className="tab-toolbar">
      <div>
        <Typography.Title level={4}><DatabaseOutlined /> 数据库备份</Typography.Title>
        <Typography.Text type="secondary">
          每天凌晨 0 点自动把整库业务数据备份到对象存储；该能力仅系统管理员可见。
        </Typography.Text>
      </div>
      <Space wrap>
        <Button icon={<RedoOutlined />} onClick={() => refresh.current()}>刷新</Button>
        <Button type="primary" icon={<SaveOutlined />} loading={starting} disabled={busy}
          onClick={() => void startBackup()}>立即备份</Button>
      </Space>
    </div>
    <Alert type="warning" showIcon style={{ marginBottom: 16 }}
      message="回滚会用备份内容整体替换当前数据库"
      description={'回滚期间及之后，所有人在备份时间点之后产生的数据都会消失，登录会话也可能失效。'
        + '系统会在回滚前自动生成一份兜底备份，可用它再退回来。备份只包含数据不包含表结构，'
        + '因此只能回滚到与当前数据库结构版本一致的备份。'} />
    <DataState loading={loading} error={error} empty={!backups.items.length} onRetry={reload}
      emptyText="还没有任何备份" emptyHint="每天凌晨 0 点会自动生成，也可以点右上角立即备份。">
      <ContentFitTable rowKey="id" dataSource={backups.items} pagination={{ pageSize: 10 }} columns={[
        {
          title: '备份时间',
          render: (_, item) => <div className="table-title">
            <strong>{formatTime(item.startedAt)}</strong>
            <span>{typeLabels[item.type] || item.type}</span>
          </div>,
        },
        {
          title: '状态',
          render: (_, item) => {
            const status = statusLabels[item.status] || { text: item.status, color: 'default' }
            return item.errorMessage
              ? <Tooltip title={item.errorMessage}><Tag color={status.color}>{status.text}</Tag></Tooltip>
              : <Tag color={status.color}>{status.text}</Tag>
          },
        },
        { title: '数据量', render: (_, item) => item.status === 'RUNNING' ? '统计中'
          : `${item.tableCount ?? '-'} 张表 / ${item.rowCount ?? '-'} 行` },
        { title: '文件大小', render: (_, item) => formatSize(item.sizeBytes) },
        { title: '结构版本', render: (_, item) => item.schemaVersion ? `v${item.schemaVersion}` : '-' },
        { title: '触发人', render: (_, item) => item.createdByName || '系统' },
        {
          title: '操作', fixed: 'right', minWidth: 180, className: 'table-action-cell',
          render: (_, item) => <Space>
            <Button type="link" icon={<CloudDownloadOutlined />} disabled={!item.downloadable}
              onClick={() => void download(item)}>下载</Button>
            <Tooltip title={item.downloadable && !item.restorable
              ? '该备份对应的数据库结构版本与当前不一致，无法回滚' : undefined}>
              <Button type="link" danger disabled={!item.restorable || busy}
                onClick={() => { setConfirmation(''); setRestoreTarget(item) }}>回滚</Button>
            </Tooltip>
          </Space>,
        },
      ]} />
    </DataState>

    {restores.items.length > 0 && <div style={{ marginTop: 24 }}>
      <Typography.Title level={5}><HistoryOutlined /> 回滚记录</Typography.Title>
      <ContentFitTable rowKey="id" dataSource={restores.items} pagination={false} columns={[
        { title: '回滚时间', render: (_, item) => formatTime(item.startedAt) },
        {
          title: '状态',
          render: (_, item) => {
            const status = statusLabels[item.status] || { text: item.status, color: 'default' }
            return item.errorMessage
              ? <Tooltip title={item.errorMessage}><Tag color={status.color}>{status.text}</Tag></Tooltip>
              : <Tag color={status.color}>{status.text}</Tag>
          },
        },
        { title: '目标备份', render: (_, item) => <TableEllipsisText value={item.backupId} maxWidth={200} /> },
        { title: '兜底备份', render: (_, item) => <TableEllipsisText value={item.safetyBackupId} maxWidth={200} /> },
        { title: '写回数据', render: (_, item) => item.rowCount === null || item.rowCount === undefined
          ? '-' : `${item.tableCount ?? '-'} 张表 / ${item.rowCount} 行` },
        { title: '操作人', render: (_, item) => item.createdByName || '-' },
      ]} />
    </div>}

    <Modal
      title="回滚数据库"
      open={restoreTarget !== null}
      okText="开始回滚"
      okButtonProps={{ danger: true, disabled: confirmation.trim() !== RESTORE_CONFIRMATION, loading: restoring }}
      onCancel={() => { setRestoreTarget(null); setConfirmation('') }}
      onOk={() => void submitRestore()}
    >
      <Typography.Paragraph>
        即将把数据库整体写回到 <Typography.Text strong>{formatTime(restoreTarget?.startedAt)}</Typography.Text> 的备份
        （{restoreTarget?.tableCount ?? '-'} 张表 / {restoreTarget?.rowCount ?? '-'} 行）。
        该时间点之后新增的选题、需求、图片记录、工时和账号变更都会丢失。
      </Typography.Paragraph>
      <Typography.Paragraph type="secondary">
        系统会先生成一份回滚前的兜底备份，误操作后可以用它再退回来。图片文件本身保存在对象存储里，
        不会被回滚删除，但数据库里已不存在的图片记录将无法再访问。
      </Typography.Paragraph>
      <Typography.Paragraph>请输入 <Typography.Text code>{RESTORE_CONFIRMATION}</Typography.Text> 以继续：</Typography.Paragraph>
      <Input value={confirmation} onChange={event => setConfirmation(event.target.value)}
        placeholder={RESTORE_CONFIRMATION} />
    </Modal>
  </>
}
