import {
  CloudUploadOutlined, DeleteOutlined, DownloadOutlined, EditOutlined, GlobalOutlined, InboxOutlined,
  LockOutlined, ReloadOutlined, SearchOutlined, TeamOutlined,
} from '@ant-design/icons'
import {
  Alert, App, Button, Empty, Form, Input, Modal, Popconfirm, Progress, Segmented, Space, Table, Tag,
  Tooltip, Typography, Upload,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import dayjs from 'dayjs'
import { useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError, api, emptyPage, largeUploadConfig } from './api'
import { useAuth } from './auth'
import DocAudiencePicker from './DocAudiencePicker'
import {
  AUDIENCE_LABELS, audienceError, audienceOf, audiencePayload, formatFileSize, type AudienceValue,
} from './docAudience'
import { useLoad } from './hooks'
import ListPagination from './ListPagination'
import { TABLE_PAGE_SIZES, turnPage } from './pagination'
import { hasAnyPermission, hasPermission } from './permissions'
import type { DocFile, DocFileDownload, DocFileUsage, PageData } from './types'
import { describeBytes } from './uploadLimits'
import UploadProgress from './UploadProgress'
import { useUploadLimits } from './useUploadLimits'

/**
 * 文档中心的「文件」页：上传任意格式的文件，由上传者指定谁能下载。登录页前面的 `/docs/files`
 * 和工作台里的 `/documents/files` 共用这一个组件，能看到哪些文件完全由服务端按令牌判定。
 *
 * <p>下载拿的是服务端签的短命直链，交给浏览器自己去取：不经过 axios，大文件不会撞上
 * 20 秒的请求超时，也不把几百 MiB 读进页面内存。上传走 `largeUploadConfig`（不设超时、
 * 带进度），单文件上限、每人空间和每日个数都来自管理员的上传限额。</p>
 */
interface Filters {
  page: number
  pageSize: number
  keyword: string
  mine: boolean
}

const VISIBILITY_TAG: Record<DocFile['visibility'], { color: string; icon: ReactNode }> = {
  PUBLIC: { color: 'green', icon: <GlobalOutlined /> },
  MEMBERS: { color: 'gold', icon: <LockOutlined /> },
  RESTRICTED: { color: 'purple', icon: <TeamOutlined /> },
}

/** 让浏览器按附件下载签名地址：地址本身带着 Content-Disposition，不需要读进内存。 */
function startBrowserDownload(url: string) {
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.rel = 'noopener'
  anchor.style.display = 'none'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
}

export default function DocFilesPanel() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const { user } = useAuth()
  const limits = useUploadLimits()
  const canUpload = hasPermission(user, 'FILE_UPLOAD')
  const canSeeOwn = hasAnyPermission(user, 'FILE_UPLOAD', 'FILE_MANAGE')
  const [filters, setFilters] = useState<Filters>({ page: 1, pageSize: TABLE_PAGE_SIZES[1], keyword: '', mine: false })
  const [keywordDraft, setKeywordDraft] = useState('')
  const [downloading, setDownloading] = useState<string | null>(null)
  const [uploadOpen, setUploadOpen] = useState(false)
  const [editing, setEditing] = useState<DocFile | null>(null)

  const files = useLoad(
    () => api<PageData<DocFile>>({
      url: '/public/doc-files',
      params: {
        page: filters.page, pageSize: filters.pageSize,
        keyword: filters.keyword || undefined, mine: filters.mine || undefined,
      },
    }),
    emptyPage<DocFile>(), [filters, user?.id])

  const download = async (file: DocFile) => {
    setDownloading(file.publicId)
    try {
      const signed = await api<DocFileDownload>({ method: 'POST', url: `/public/doc-files/${file.publicId}/download` })
      startBrowserDownload(signed.downloadUrl)
      void files.refresh()
    } catch (reason) {
      const needsLogin = reason instanceof ApiError && reason.code === 'FORBIDDEN' && !user
      if (needsLogin) {
        message.warning({ content: <span>{(reason as Error).message}
          <Button type="link" size="small" onClick={() => navigate('/login')}>去登录</Button></span> })
      } else {
        message.error((reason as Error).message)
      }
    } finally {
      setDownloading(null)
    }
  }

  const remove = async (file: DocFile) => {
    try {
      await api({ method: 'DELETE', url: `/doc-files/${file.id}`, params: { version: file.version } })
      message.success('文件已删除')
      void files.reload()
    } catch (reason) {
      message.error((reason as Error).message)
    }
  }

  const columns: ColumnsType<DocFile> = [
    {
      title: '文件', key: 'title', render: (_, file) => <div style={{ minWidth: 180 }}>
        <Typography.Text strong>{file.title}</Typography.Text>
        <div><Typography.Text type="secondary" style={{ fontSize: 12 }}>{file.fileName}</Typography.Text></div>
        {file.description && <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }}
          style={{ margin: '4px 0 0', fontSize: 12 }}>{file.description}</Typography.Paragraph>}
      </div>,
    },
    { title: '大小', dataIndex: 'size', width: 100, render: (size: number) => formatFileSize(size) },
    {
      title: '谁能下载', key: 'visibility', width: 120, render: (_, file) => {
        const tag = VISIBILITY_TAG[file.visibility] ?? VISIBILITY_TAG.MEMBERS
        return <Tag color={tag.color} icon={tag.icon}>{AUDIENCE_LABELS[file.visibility]}</Tag>
      },
    },
    {
      title: '上传', key: 'uploader', width: 160, render: (_, file) => <Space orientation="vertical" size={0}>
        <span>{file.uploaderDisplayName || '-'}</span>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {file.createdAt ? dayjs(file.createdAt).format('YYYY-MM-DD HH:mm') : ''}
        </Typography.Text>
      </Space>,
    },
    { title: '下载次数', dataIndex: 'downloadCount', width: 90 },
    {
      title: '操作', key: 'actions', width: 150, fixed: 'right', render: (_, file) => <Space size={4}>
        <Tooltip title="下载">
          <Button type="primary" size="small" icon={<DownloadOutlined />} aria-label={`下载 ${file.title}`}
            loading={downloading === file.publicId} onClick={() => void download(file)} />
        </Tooltip>
        {file.canManage && <>
          <Tooltip title="编辑与下载范围">
            <Button size="small" icon={<EditOutlined />} aria-label={`编辑 ${file.title}`}
              onClick={() => setEditing(file)} />
          </Tooltip>
          <Popconfirm title="删除这个文件？" description="删除后列表里看不到，也不能再下载。"
            okText="删除" cancelText="取消" okButtonProps={{ danger: true }} onConfirm={() => void remove(file)}>
            <Button size="small" danger icon={<DeleteOutlined />} aria-label={`删除 ${file.title}`} />
          </Popconfirm>
        </>}
      </Space>,
    },
  ]

  return <div className="doc-files-panel">
    <div className="doc-files-toolbar">
      <Space wrap>
        <Input allowClear prefix={<SearchOutlined />} placeholder="按标题或文件名搜索" style={{ width: 240 }}
          value={keywordDraft} aria-label="搜索文件"
          onChange={event => {
            setKeywordDraft(event.target.value)
            if (!event.target.value) setFilters(current => ({ ...current, keyword: '', page: 1 }))
          }}
          onPressEnter={() => setFilters(current => ({ ...current, keyword: keywordDraft.trim(), page: 1 }))} />
        {canSeeOwn && <Segmented value={filters.mine ? 'mine' : 'all'}
          onChange={value => setFilters(current => ({ ...current, mine: value === 'mine', page: 1 }))}
          options={[{ value: 'all', label: '全部文件' }, { value: 'mine', label: '我上传的' }]} />}
        <Button icon={<ReloadOutlined />} onClick={() => void files.reload()}>刷新</Button>
      </Space>
      {canUpload && <Button type="primary" icon={<CloudUploadOutlined />} onClick={() => setUploadOpen(true)}>
        上传文件
      </Button>}
    </div>

    {!user && <Alert type="info" showIcon style={{ marginBottom: 12 }}
      title="登录后能看到更多文件" description="有的文件只对登录成员或指定成员开放下载。" />}
    {files.error && <Alert type="warning" showIcon style={{ marginBottom: 12 }} title="文件列表没能加载出来"
      description={files.error} action={<Button size="small" onClick={() => void files.reload()}>重试</Button>} />}

    <Table<DocFile> rowKey="publicId" size="middle" columns={columns} dataSource={files.data.items}
      loading={files.loading} pagination={false} scroll={{ x: 820 }}
      locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={filters.keyword ? '没有符合条件的文件' : '还没有你能下载的文件'} /> }} />
    <ListPagination page={filters.page} pageSize={filters.pageSize} total={files.data.total}
      sizes={TABLE_PAGE_SIZES} showTotal={total => `共 ${total} 个文件`}
      onChange={(page, pageSize) => setFilters(current => turnPage(current, page, pageSize))} />

    {uploadOpen && <UploadModal maxBytes={limits.FILE_MAX_BYTES}
      onClose={() => setUploadOpen(false)}
      onUploaded={() => {
        setUploadOpen(false)
        setFilters(current => ({ ...current, page: 1 }))
        void files.reload()
      }} />}
    {editing && <EditModal file={editing} onClose={() => setEditing(null)}
      onSaved={() => {
        setEditing(null)
        void files.reload()
      }} />}
  </div>
}

/** 上传弹窗：先选文件，标题默认取文件名，下载范围默认「登录后」——和后端的安全默认值一致。 */
function UploadModal({ maxBytes, onClose, onUploaded }: {
  maxBytes: number
  onClose: () => void
  onUploaded: () => void
}) {
  const { message } = App.useApp()
  const [file, setFile] = useState<File | null>(null)
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [audience, setAudience] = useState<AudienceValue>({ visibility: 'MEMBERS', groupIds: [], userIds: [] })
  const [progress, setProgress] = useState<number | null>(null)
  const usage = useLoad(() => api<DocFileUsage>({ url: '/doc-files/usage' }), null as DocFileUsage | null, [])

  const tooLarge = !!file && file.size > maxBytes
  const overQuota = !!file && !!usage.data && usage.data.usedBytes + file.size > usage.data.quotaBytes
  const dailyFull = !!usage.data && usage.data.uploadedToday >= usage.data.dailyUploads
  const problem = !file ? '请先选择一个文件'
    : file.size === 0 ? '不能上传空文件'
      : tooLarge ? `单个文件不能超过 ${describeBytes(maxBytes)}`
        : overQuota ? '存储空间不够了，请先删掉不用的文件'
          : dailyFull ? `今天已经上传了 ${usage.data?.uploadedToday} 个文件，明天再来吧`
            : audienceError(audience)

  const submit = async () => {
    if (!file || problem) {
      message.error(problem || '请先选择一个文件')
      return
    }
    const form = new FormData()
    form.append('file', file)
    if (title.trim()) form.append('title', title.trim())
    if (description.trim()) form.append('description', description.trim())
    const payload = audiencePayload(audience)
    form.append('visibility', payload.visibility)
    payload.groupIds.forEach(id => form.append('groupIds', id))
    payload.userIds.forEach(id => form.append('userIds', id))
    setProgress(0)
    try {
      await api<DocFile>({ method: 'POST', url: '/doc-files', data: form, ...largeUploadConfig(setProgress) })
      message.success('文件已上传')
      onUploaded()
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setProgress(null)
    }
  }

  return <Modal open title="上传文件" okText="上传" cancelText="取消" width={640} destroyOnHidden
    okButtonProps={{ disabled: !!problem, loading: progress !== null }}
    cancelButtonProps={{ disabled: progress !== null }} closable={progress === null} maskClosable={false}
    onOk={() => void submit()} onCancel={() => { if (progress === null) onClose() }}>
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      <Upload.Dragger multiple={false} showUploadList={false} disabled={progress !== null}
        beforeUpload={picked => {
          setFile(picked)
          setTitle(current => current || picked.name.replace(/\.[^.]+$/, '').slice(0, 200))
          return false
        }}>
        <p className="ant-upload-drag-icon"><InboxOutlined /></p>
        <p className="ant-upload-text">{file ? file.name : '点击或拖入一个文件'}</p>
        <p className="ant-upload-hint">
          {file ? formatFileSize(file.size) : `任意格式，单个不超过 ${describeBytes(maxBytes)}`}
        </p>
      </Upload.Dragger>
      {usage.data && <div>
        <Typography.Text type="secondary">
          我的空间：已用 {formatFileSize(usage.data.usedBytes)} / {describeBytes(usage.data.quotaBytes)}
          ；今天已上传 {usage.data.uploadedToday} / {usage.data.dailyUploads} 个
        </Typography.Text>
        <Progress size="small" showInfo={false}
          percent={Math.min(100, Math.round((usage.data.usedBytes / Math.max(1, usage.data.quotaBytes)) * 100))} />
      </div>}
      <Form layout="vertical">
        <Form.Item label="标题" required>
          <Input value={title} maxLength={200} placeholder="默认用文件名" onChange={event => setTitle(event.target.value)} />
        </Form.Item>
        <Form.Item label="说明">
          <Input.TextArea value={description} maxLength={1000} autoSize={{ minRows: 2, maxRows: 5 }}
            onChange={event => setDescription(event.target.value)} />
        </Form.Item>
        <Form.Item label="谁能下载">
          <DocAudiencePicker value={audience} onChange={setAudience} subject="下载" disabled={progress !== null} />
        </Form.Item>
      </Form>
      {/* 名单的问题选择器自己会提示，这里只说文件本身的问题，免得同一句话出现两遍。 */}
      {problem && file && problem !== audienceError(audience) &&
        <Typography.Text type="danger">{problem}</Typography.Text>}
      <UploadProgress percent={progress} />
    </Space>
  </Modal>
}

function EditModal({ file, onClose, onSaved }: { file: DocFile; onClose: () => void; onSaved: () => void }) {
  const { message } = App.useApp()
  const [title, setTitle] = useState(file.title)
  const [description, setDescription] = useState(file.description || '')
  const [audience, setAudience] = useState<AudienceValue>(() => audienceOf(file))
  const [saving, setSaving] = useState(false)
  const problem = !title.trim() ? '标题不能为空' : audienceError(audience)

  const save = async () => {
    if (problem) {
      message.error(problem)
      return
    }
    setSaving(true)
    try {
      await api<DocFile>({
        method: 'PUT', url: `/doc-files/${file.id}`,
        data: { title: title.trim(), description: description.trim() || null, ...audiencePayload(audience), version: file.version },
      })
      message.success('已保存')
      onSaved()
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setSaving(false)
    }
  }

  return <Modal open title={`编辑「${file.title}」`} okText="保存" cancelText="取消" width={640} destroyOnHidden
    okButtonProps={{ disabled: !!problem, loading: saving }} onOk={() => void save()} onCancel={onClose}>
    <Form layout="vertical">
      <Form.Item label="标题" required>
        <Input value={title} maxLength={200} onChange={event => setTitle(event.target.value)} />
      </Form.Item>
      <Form.Item label="说明">
        <Input.TextArea value={description} maxLength={1000} autoSize={{ minRows: 2, maxRows: 5 }}
          onChange={event => setDescription(event.target.value)} />
      </Form.Item>
      <Form.Item label="谁能下载">
        <DocAudiencePicker value={audience} onChange={setAudience} subject="下载" disabled={saving} />
      </Form.Item>
    </Form>
    <Typography.Text type="secondary">{file.fileName} · {formatFileSize(file.size)}</Typography.Text>
  </Modal>
}
