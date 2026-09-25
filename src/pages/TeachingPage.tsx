import {
  DeleteOutlined, DownloadOutlined, EditOutlined, FilePdfOutlined, FilePptOutlined, FileWordOutlined,
  PlusOutlined, ReloadOutlined, SearchOutlined, SwapOutlined, TagsOutlined, UploadOutlined,
} from '@ant-design/icons'
import {
  Alert, App as AntApp, AutoComplete, Button, Card, Empty, Form, Input, List, Modal,
  Popconfirm, Select, Skeleton, Space, Tag, Typography, Upload,
} from 'antd'
import dayjs from 'dayjs'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, http, qs } from '../api'
import { useAuth } from '../auth'
import { useLoad } from '../hooks'
import { hasPermission } from '../permissions'
import type { TeachingMaterial, TeachingMaterialFormat } from '../types'

interface AuthorOption {
  id: number
  displayName: string
}

interface MaterialFormValues {
  title: string
  description?: string
  category: string
  authorId?: number
}

const FILE_ACCEPT = '.pdf,.docx,.pptx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.presentationml.presentation'

const formatExtension = (format: TeachingMaterialFormat) =>
  format === 'PDF' ? 'pdf' : format === 'WORD' ? 'docx' : 'pptx'

const formatLabel = (format: TeachingMaterialFormat) =>
  format === 'PDF' ? 'PDF' : format === 'WORD' ? 'Word' : 'PPT'

const formatIcon = (format: TeachingMaterialFormat) =>
  format === 'PDF'
    ? <FilePdfOutlined style={{ fontSize: 20 }} />
    : format === 'WORD'
      ? <FileWordOutlined style={{ fontSize: 20 }} />
      : <FilePptOutlined style={{ fontSize: 20 }} />

const formatSize = (bytes: number) => (bytes < 1024
  ? `${bytes} B`
  : bytes < 1024 * 1024
    ? `${Math.round(bytes / 1024)} KB`
    : `${(bytes / 1024 / 1024).toFixed(1)} MB`)

const fileListOf = (file: File | null) =>
  (file ? [{ uid: 'selected', name: file.name, status: 'done' as const }] : [])

/**
 * PDF 走 `/file`（inline，不计数）在线预览；Word/PPT 不支持预览，只给下载。
 * 下载一律走 `/download`（attachment，计数 +1）。
 * 和文档中心一样必须带令牌取 Blob：`<iframe src>` 不会带 localStorage 里的令牌。
 */
function FilePreview({ material, onDownload }: {
  material: TeachingMaterial
  onDownload: () => void
}) {
  const isPdf = material.format === 'PDF'
  const [objectUrl, setObjectUrl] = useState<string>()
  const [error, setError] = useState<string>()
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (!isPdf) {
      setObjectUrl(undefined)
      setError(undefined)
      return
    }
    let active = true
    let currentUrl: string | undefined
    setObjectUrl(undefined)
    setError(undefined)
    void http.get<Blob>(`/teaching/materials/${material.publicId}/file`, { responseType: 'blob' })
      .then(response => {
        if (!active) return
        currentUrl = URL.createObjectURL(response.data)
        setObjectUrl(currentUrl)
      })
      .catch(() => { if (active) setError('PDF 没能加载出来，请稍后重试') })
    return () => {
      active = false
      if (currentUrl) URL.revokeObjectURL(currentUrl)
    }
  }, [material.publicId, material.format, isPdf, attempt])

  if (!isPdf) {
    return <div className="docs-pdf">
      <Space className="docs-pdf-actions">
        <Button type="primary" icon={<DownloadOutlined />} onClick={onDownload}>
          下载 {formatLabel(material.format)} 文件
        </Button>
      </Space>
      <Typography.Text type="secondary">
        该格式暂不支持在线预览，下载后用 Word / PowerPoint 打开。
      </Typography.Text>
    </div>
  }

  if (error) return <Alert type="warning" showIcon message={error}
    action={<Button size="small" icon={<ReloadOutlined />}
      onClick={() => setAttempt(value => value + 1)}>重试</Button>} />
  if (!objectUrl) return <Skeleton active paragraph={{ rows: 10 }} />

  return <div className="docs-pdf">
    <Space className="docs-pdf-actions">
      <Button icon={<DownloadOutlined />} onClick={onDownload}>下载</Button>
      <Typography.Text type="secondary">浏览器里看不了的，下载后用本地阅读器打开。</Typography.Text>
    </Space>
    <iframe className="docs-pdf-frame" style={{ height: '60vh' }} src={objectUrl} title={material.title} />
  </div>
}

function MaterialFields({ categories, authors }: { categories: string[]; authors: AuthorOption[] }) {
  return <>
    <Form.Item name="title" label="标题"
      rules={[{ required: true, message: '请填写标题' }, { max: 200, message: '标题最多 200 个字符' }]}>
      <Input placeholder="例如：摄影基础 · 第一讲" />
    </Form.Item>
    <Form.Item name="category" label="分类"
      rules={[{ required: true, message: '请填写分类' }, { max: 100, message: '分类最多 100 个字符' }]}>
      <AutoComplete placeholder="选择已有分类，或直接输入新分类"
        options={categories.map(value => ({ value }))} />
    </Form.Item>
    <Form.Item name="description" label="简介"
      rules={[{ max: 1000, message: '简介最多 1000 个字符' }]}>
      <Input.TextArea rows={3} placeholder="这份资料讲什么，选填" />
    </Form.Item>
    <Form.Item name="authorId" label="作者">
      {/* showSearch + optionFilterProp="label"：下拉里直接敲名字过滤（按显示名匹配，大小写不敏感）。
          候选是全体图库成员，数量在部门规模内，前端过滤足够；真到几百人以上再换服务端搜索。 */}
      <Select allowClear showSearch optionFilterProp="label"
        placeholder="输入姓名搜索图库成员，选填"
        options={authors.map(author => ({ value: author.id, label: author.displayName }))} />
    </Form.Item>
  </>
}

/**
 * 教学资料页。图库成员（持有 `PHOTO_VIEW`）在这里浏览、筛选、预览、下载；
 * 持有 `TEACHING_MANAGE` 的人多出上传、编辑、换文件、删除、重命名分类。
 */
export default function TeachingPage() {
  const { publicId } = useParams<{ publicId?: string }>()
  const navigate = useNavigate()
  const { user } = useAuth()
  const { message } = AntApp.useApp()
  const canManage = hasPermission(user, 'TEACHING_MANAGE')

  const [category, setCategory] = useState<string>()
  const [keyword, setKeyword] = useState('')
  const [reloadToken, setReloadToken] = useState(0)
  const [submitting, setSubmitting] = useState(false)

  const [uploadOpen, setUploadOpen] = useState(false)
  const [uploadFile, setUploadFile] = useState<File | null>(null)
  const [uploadForm] = Form.useForm<MaterialFormValues>()

  const [editTarget, setEditTarget] = useState<TeachingMaterial | null>(null)
  const [editForm] = Form.useForm<MaterialFormValues>()

  const [replaceTarget, setReplaceTarget] = useState<TeachingMaterial | null>(null)
  const [replaceFile, setReplaceFile] = useState<File | null>(null)

  const [renameOpen, setRenameOpen] = useState(false)
  const [renameForm] = Form.useForm<{ from: string; to: string }>()

  const list = useLoad(
    () => api<TeachingMaterial[]>({ url: '/teaching/materials', params: qs({ category, q: keyword }) }),
    [] as TeachingMaterial[], [category, keyword, reloadToken])
  const categories = useLoad(
    () => api<string[]>({ url: '/teaching/materials/categories' }), [] as string[], [reloadToken])
  const authors = useLoad(
    () => (canManage ? api<AuthorOption[]>({ url: '/teaching/authors' }) : Promise.resolve([])),
    [] as AuthorOption[], [canManage])

  const selectedId = publicId ?? list.data[0]?.publicId
  const selected = useMemo(
    () => list.data.find(material => material.publicId === selectedId),
    [list.data, selectedId])

  useEffect(() => {
    if (!publicId && list.data.length) {
      navigate(`/teaching/${list.data[0].publicId}`, { replace: true })
    }
  }, [publicId, list.data, navigate])

  const refresh = () => setReloadToken(token => token + 1)
  const reportError = (reason: unknown) =>
    message.error(reason instanceof Error ? reason.message : '操作失败，请稍后重试')

  const download = async (material: TeachingMaterial) => {
    try {
      const blob = await http.get<Blob>(`/teaching/materials/${material.publicId}/download`,
        { responseType: 'blob' }).then(response => response.data)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `${material.title}.${formatExtension(material.format)}`
      document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
      refresh()
    } catch (reason) {
      reportError(reason)
    }
  }

  const submitUpload = async () => {
    const values = await uploadForm.validateFields()
    if (!uploadFile) {
      message.warning('请选择要上传的文件')
      return
    }
    setSubmitting(true)
    try {
      const form = new FormData()
      form.append('title', values.title)
      if (values.description) form.append('description', values.description)
      form.append('category', values.category)
      if (values.authorId != null) form.append('authorId', String(values.authorId))
      form.append('file', uploadFile)
      const created = await api<TeachingMaterial>({
        method: 'post', url: '/teaching', data: form,
      })
      message.success('教学资料已发布')
      setUploadOpen(false)
      setUploadFile(null)
      uploadForm.resetFields()
      refresh()
      navigate(`/teaching/${created.publicId}`)
    } catch (reason) {
      reportError(reason)
    } finally {
      setSubmitting(false)
    }
  }

  const openEdit = (material: TeachingMaterial) => {
    setEditTarget(material)
    editForm.setFieldsValue({
      title: material.title,
      description: material.description ?? undefined,
      category: material.category,
      authorId: material.authorId ?? undefined,
    })
  }

  const submitEdit = async () => {
    if (!editTarget) return
    const values = await editForm.validateFields()
    setSubmitting(true)
    try {
      await api<TeachingMaterial>({
        method: 'put',
        url: `/teaching/${editTarget.id}`,
        data: { ...values, authorId: values.authorId ?? null, version: editTarget.version },
      })
      message.success('已保存')
      setEditTarget(null)
      refresh()
    } catch (reason) {
      reportError(reason)
    } finally {
      setSubmitting(false)
    }
  }

  const submitReplace = async () => {
    if (!replaceTarget) return
    if (!replaceFile) {
      message.warning('请选择新的文件')
      return
    }
    setSubmitting(true)
    try {
      const form = new FormData()
      form.append('file', replaceFile)
      form.append('version', String(replaceTarget.version))
      await api<TeachingMaterial>({
        method: 'put', url: `/teaching/${replaceTarget.id}/file`, data: form,
      })
      message.success('文件已替换')
      setReplaceTarget(null)
      setReplaceFile(null)
      refresh()
    } catch (reason) {
      reportError(reason)
    } finally {
      setSubmitting(false)
    }
  }

  const remove = async (material: TeachingMaterial) => {
    try {
      await api<void>({
        method: 'delete',
        url: `/teaching/${material.id}`,
        params: { version: material.version },
      })
      message.success('已删除')
      if (publicId === material.publicId) navigate('/teaching', { replace: true })
      refresh()
    } catch (reason) {
      reportError(reason)
    }
  }

  const submitRename = async () => {
    const values = await renameForm.validateFields()
    setSubmitting(true)
    try {
      const result = await api<{ updated: number }>({
        method: 'put', url: '/teaching/categories', data: values,
      })
      message.success(`已更新 ${result.updated} 份资料`)
      setRenameOpen(false)
      renameForm.resetFields()
      setCategory(undefined)
      refresh()
    } catch (reason) {
      reportError(reason)
    } finally {
      setSubmitting(false)
    }
  }

  const filePicker = (file: File | null, setFile: (value: File | null) => void) =>
    <Upload accept={FILE_ACCEPT} maxCount={1} fileList={fileListOf(file)}
      beforeUpload={next => { setFile(next); return false }}
      onRemove={() => setFile(null)}>
      <Button icon={<UploadOutlined />}>选择文件（PDF / Word / PPT）</Button>
    </Upload>

  return <div className="documents-page">
    <div className="documents-toolbar">
      <div>
        <Typography.Title level={4} style={{ margin: 0 }}>教学资料</Typography.Title>
        <Typography.Text type="secondary">
        教学文件，摄影分享与后期工具
        </Typography.Text>
      </div>
      {canManage && <Space>
        <Button icon={<TagsOutlined />} onClick={() => setRenameOpen(true)}>重命名分类</Button>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setUploadOpen(true)}>上传资料</Button>
      </Space>}
    </div>

    <div className="teaching-body">
      <Card className="teaching-sidebar" size="small">
        <Space direction="vertical" style={{ width: '100%' }} size="small">
          <Input allowClear prefix={<SearchOutlined />} placeholder="按标题或简介搜索"
            value={keyword} onChange={event => setKeyword(event.target.value)} />
          <Select allowClear style={{ width: '100%' }} placeholder="按分类筛选"
            value={category} onChange={value => setCategory(value)}
            options={categories.data.map(value => ({ value, label: value }))} />
        </Space>
        <div className="teaching-list">
          {list.loading && <Skeleton active paragraph={{ rows: 6 }} />}
          {!list.loading && list.error && <Alert type="warning" showIcon message="资料没能加载出来"
            description={list.error} action={<Button size="small" onClick={() => void list.reload()}>重试</Button>} />}
          {!list.loading && !list.error && !list.data.length &&
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有教学资料" />}
          {!list.loading && !list.error && !!list.data.length && <List
            dataSource={list.data}
            renderItem={material => <List.Item
              className={material.publicId === selectedId
                ? 'teaching-item teaching-item-active' : 'teaching-item'}
              onClick={() => navigate(`/teaching/${material.publicId}`)}
              actions={canManage ? [
                <Button key="edit" size="small" type="text" aria-label="编辑信息" icon={<EditOutlined />}
                  onClick={event => { event.stopPropagation(); openEdit(material) }} />,
                <Button key="replace" size="small" type="text" aria-label="替换文件" icon={<SwapOutlined />}
                  onClick={event => { event.stopPropagation(); setReplaceTarget(material) }} />,
                <Popconfirm key="delete" title="删除这份教学资料？" okText="删除" cancelText="取消"
                  onConfirm={() => void remove(material)}>
                  <Button size="small" type="text" danger aria-label="删除资料" icon={<DeleteOutlined />}
                    onClick={event => event.stopPropagation()} />
                </Popconfirm>,
              ] : undefined}>
              <List.Item.Meta
                avatar={formatIcon(material.format)}
                title={material.title}
                description={<Space size="small" wrap>
                  <Tag>{material.category}</Tag>
                  {material.authorName && <span>作者 {material.authorName}</span>}
                </Space>} />
            </List.Item>} />}
        </div>
      </Card>

      <div className="teaching-content">
        {selected ? <>
          <Typography.Title level={3} style={{ marginTop: 0 }}>{selected.title}</Typography.Title>
          <Space size="small" wrap style={{ marginBottom: 8 }}>
            <Tag color="blue">{formatLabel(selected.format)}</Tag>
            <Tag>{selected.category}</Tag>
            {/* 作者/上传人/大小/下载次数/时间只给有编辑权限的人看：这些是管理信息，
                普通图库成员只需要标题、分类和简介。 */}
            {canManage && <>
              {selected.authorName && <Typography.Text type="secondary">作者：{selected.authorName}</Typography.Text>}
              {selected.uploaderName && <Typography.Text type="secondary">上传人：{selected.uploaderName}</Typography.Text>}
              <Typography.Text type="secondary">{formatSize(selected.size)}</Typography.Text>
              <Typography.Text type="secondary">下载 {selected.downloadCount} 次</Typography.Text>
              {selected.createdAt && <Typography.Text type="secondary">
                上传 {dayjs(selected.createdAt).format('YYYY-MM-DD HH:mm')}</Typography.Text>}
              {selected.updatedAt && <Typography.Text type="secondary">
                更新 {dayjs(selected.updatedAt).format('YYYY-MM-DD HH:mm')}</Typography.Text>}
            </>}
          </Space>
          {selected.description && <Typography.Paragraph type="secondary">
            {selected.description}</Typography.Paragraph>}
          <FilePreview material={selected} onDownload={() => void download(selected)} />
        </> : (!list.loading && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="从左边选一份资料开始阅读" />)}
      </div>
    </div>

    <Modal open={uploadOpen} title="上传教学资料" okText="发布" confirmLoading={submitting}
      onOk={() => void submitUpload()}
      onCancel={() => { setUploadOpen(false); setUploadFile(null); uploadForm.resetFields() }}>
      <Form form={uploadForm} layout="vertical">
        <MaterialFields categories={categories.data} authors={authors.data} />
        <Form.Item label="文件" required>{filePicker(uploadFile, setUploadFile)}</Form.Item>
      </Form>
    </Modal>

    <Modal open={!!editTarget} title="编辑资料信息" okText="保存" confirmLoading={submitting}
      onOk={() => void submitEdit()} onCancel={() => setEditTarget(null)}>
      <Form form={editForm} layout="vertical">
        <MaterialFields categories={categories.data} authors={authors.data} />
      </Form>
    </Modal>

    <Modal open={!!replaceTarget} title="替换文件" okText="替换" confirmLoading={submitting}
      onOk={() => void submitReplace()}
      onCancel={() => { setReplaceTarget(null); setReplaceFile(null) }}>
      <Typography.Paragraph type="secondary">
        替换后资料的 id 与链接不变，读者刷新即可看到新版本。
      </Typography.Paragraph>
      {filePicker(replaceFile, setReplaceFile)}
    </Modal>

    <Modal open={renameOpen} title="重命名分类" okText="重命名" confirmLoading={submitting}
      onOk={() => void submitRename()} onCancel={() => setRenameOpen(false)}>
      <Form form={renameForm} layout="vertical">
        <Form.Item name="from" label="原分类" rules={[{ required: true, message: '请选择原分类' }]}>
          <Select placeholder="选择要改名的分类"
            options={categories.data.map(value => ({ value, label: value }))} />
        </Form.Item>
        <Form.Item name="to" label="新名称"
          rules={[{ required: true, message: '请填写新名称' }, { max: 100, message: '最多 100 个字符' }]}>
          <Input placeholder="输入新的分类名称" />
        </Form.Item>
      </Form>
    </Modal>
  </div>
}
