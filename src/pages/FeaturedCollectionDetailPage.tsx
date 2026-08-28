import {
  App, Button, Card, Col, Descriptions, Empty, Form, Image, Input, Modal, Pagination, Row, Space,
  Tag, Typography,
} from 'antd'
import {
  ArrowLeftOutlined, DeleteOutlined, DownloadOutlined, EditOutlined, FileWordOutlined,
  PictureOutlined, PlusOutlined, ReloadOutlined, SearchOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, emptyPage, qs } from '../api'
import { DataState, PageTitle } from '../components'
import {
  FEATURED_DOCUMENT_LABELS, featuredStatusDisplay, groupEntriesByCampus, remainingEntryQuota,
} from '../featuredCollections'
import { useLoad } from '../hooks'
import RichTextContent from '../RichTextContent'
import type {
  FeaturedCollection, FeaturedDocumentDownload, FeaturedEntry, PageData, Photo,
} from '../types'

interface EntryValues {
  idea: string
  location: string
}

export default function FeaturedCollectionDetailPage() {
  const { collectionId } = useParams()
  const navigate = useNavigate()
  const { message, modal } = App.useApp()
  const [form] = Form.useForm<EntryValues>()
  const [pickerOpen, setPickerOpen] = useState(false)
  const [pickedPhoto, setPickedPhoto] = useState<Photo | null>(null)
  const [editingEntry, setEditingEntry] = useState<FeaturedEntry | null>(null)
  const [photoFilters, setPhotoFilters] = useState({ page: 1, keyword: '' })
  const [saving, setSaving] = useState(false)
  const [downloading, setDownloading] = useState(false)

  const collection = useLoad(
    () => api<FeaturedCollection>({ url: `/featured-collections/${collectionId}` }),
    null as FeaturedCollection | null, [collectionId],
  )
  const entries = useLoad(
    () => api<FeaturedEntry[]>({ url: `/featured-collections/${collectionId}/entries` }),
    [] as FeaturedEntry[], [collectionId],
  )
  // 选图弹窗打开时才拉图库，避免每次进详情页都多打一次图库查询。
  // selectableOnly 让后端按"精选可选范围"（授权校区内全部图片）返回，而不是按权限组的
  // 图库可见范围：可见范围放宽到全站的账号，选图仍然只限本校区，两边必须对齐，
  // 否则弹窗里会列出点下去必然 403 的图片。
  const photos = useLoad(
    () => pickerOpen
      ? api<PageData<Photo>>({
          url: '/photos',
          params: qs({ ...photoFilters, pageSize: 12, status: 'AVAILABLE', selectableOnly: true }),
        })
      : Promise.resolve(emptyPage<Photo>()),
    emptyPage<Photo>(), [pickerOpen, photoFilters.page, photoFilters.keyword],
  )

  const reloadAll = async () => {
    await Promise.all([collection.reload(), entries.reload()])
  }

  const openPicker = () => {
    setEditingEntry(null)
    setPickedPhoto(null)
    setPhotoFilters({ page: 1, keyword: '' })
    form.resetFields()
    setPickerOpen(true)
  }

  const openEdit = (entry: FeaturedEntry) => {
    setEditingEntry(entry)
    setPickedPhoto(null)
    form.setFieldsValue({ idea: entry.idea, location: entry.location })
  }

  const submitEntry = async () => {
    let values: EntryValues
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    setSaving(true)
    try {
      if (editingEntry) {
        await api<FeaturedEntry>({
          method: 'PUT', url: `/featured-collections/${collectionId}/entries/${editingEntry.id}`,
          data: { photoId: editingEntry.photoId, ...values, version: editingEntry.version },
        })
        message.success('已更新')
        setEditingEntry(null)
      } else {
        if (!pickedPhoto) {
          message.error('请先选择一张图片')
          return
        }
        await api<FeaturedEntry>({
          method: 'POST', url: `/featured-collections/${collectionId}/entries`,
          data: { photoId: pickedPhoto.id, ...values },
        })
        message.success('已加入精选')
        setPickerOpen(false)
      }
      form.resetFields()
      await reloadAll()
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const removeEntry = (entry: FeaturedEntry) => modal.confirm({
    title: '把这张图从精选里撤下？',
    content: '撤下后可以重新选它，也可以换一张。',
    okText: '撤下', okButtonProps: { danger: true }, cancelText: '再想想',
    onOk: async () => {
      try {
        await api<void>({
          method: 'DELETE', url: `/featured-collections/${collectionId}/entries/${entry.id}`,
        })
        message.success('已撤下')
        await reloadAll()
      } catch (error) {
        message.error((error as Error).message)
      }
    },
  })

  const download = async () => {
    setDownloading(true)
    try {
      const target = await api<FeaturedDocumentDownload>({
        url: `/featured-collections/${collectionId}/document`,
      })
      // 交给浏览器直接取签名地址，和备份下载一致：签名走查询串，中文文件名已经写在
      // 对象的 Content-Disposition 里。**不要**用 axios 实例转发——请求拦截器会带上
      // Bearer 头，OSS 见到 Authorization 就改走请求头签名，预签名地址随即失效。
      window.open(target.downloadUrl, '_blank', 'noopener')
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setDownloading(false)
    }
  }

  const regenerate = async () => {
    try {
      await api<FeaturedCollection>({
        method: 'POST', url: `/featured-collections/${collectionId}/document`,
      })
      message.success('已重新排队生成，稍后刷新查看')
      await collection.reload()
    } catch (error) {
      message.error((error as Error).message)
    }
  }

  const close = () => modal.confirm({
    title: '现在就截止这次精选？',
    content: '截止后负责人不能再增删条目，服务器会立即开始生成 Word 文档。',
    okText: '截止并生成文档', cancelText: '再等等',
    onOk: async () => {
      try {
        await api<FeaturedCollection>({ method: 'POST', url: `/featured-collections/${collectionId}/close` })
        message.success('已截止，文档正在后台生成')
        await reloadAll()
      } catch (error) {
        message.error((error as Error).message)
      }
    },
  })

  const current = collection.data
  const status = current ? featuredStatusDisplay(current) : null
  const mine = entries.data.filter(entry => entry.mine)
  const chapters = groupEntriesByCampus(entries.data)
  const quota = current ? remainingEntryQuota(current) : 0

  return <div className="page">
    <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/featured')}>返回好图精选</Button>
    <DataState loading={collection.loading} error={collection.error} empty={!current}
      emptyText="这份精选不在了" emptyHint="可能已经被删除，回列表看看其他的。"
      onRetry={collection.reload}>
      {current && <>
        <PageTitle eyebrow="好图精选" title={current.title}
          description={`${dayjs(current.startsAt).format('YYYY-MM-DD HH:mm')} 至 ${dayjs(current.endsAt).format('YYYY-MM-DD HH:mm')}`}
          extra={<Space wrap>
            {status && <Tag color={status.color} variant="filled">{status.label}</Tag>}
            {current.canManage && current.status === 'PUBLISHED' &&
              <Button onClick={close}>手动截止</Button>}
            {current.documentStatus === 'READY' && <Button type="primary" icon={<DownloadOutlined />}
              loading={downloading} onClick={() => void download()}>下载 Word 文档</Button>}
            {current.canManage && current.status === 'CLOSED' && current.documentStatus !== 'GENERATING' &&
              <Button icon={<ReloadOutlined />} onClick={() => void regenerate()}>重新生成文档</Button>}
          </Space>} />

        <Row gutter={[16, 16]}>
          <Col xs={24} lg={14}>
            <Card title="征集要求">
              {current.requirementHtml
                ? <RichTextContent value={current.requirementHtml} />
                : <Typography.Text type="secondary">部长没有填写额外要求。</Typography.Text>}
            </Card>
          </Col>
          <Col xs={24} lg={10}>
            <Card title="本次征集">
              <Descriptions column={1} size="small" items={[
                { key: 'scope', label: '提交范围', children: current.assignAll ? '全部校区负责人'
                  : `指定 ${current.campusIds.length} 个校区、${current.userIds.length} 位负责人` },
                { key: 'limit', label: '每人上限', children: `${current.entryLimit} 张` },
                { key: 'count', label: '已收作品', children: `${current.entryCount} 张` },
                { key: 'creator', label: '发布人', children: current.creatorDisplayName || '—' },
                { key: 'document', label: 'Word 文档', children: <Space>
                  <Tag icon={<FileWordOutlined />}
                    color={current.documentStatus === 'READY' ? 'blue'
                      : current.documentStatus === 'FAILED' ? 'red' : 'default'}>
                    {FEATURED_DOCUMENT_LABELS[current.documentStatus]}
                  </Tag>
                  {current.documentGeneratedAt &&
                    <Typography.Text type="secondary">
                      {dayjs(current.documentGeneratedAt).format('MM-DD HH:mm')}
                    </Typography.Text>}
                </Space> },
              ]} />
              {current.documentStatus === 'FAILED' && current.documentError &&
                <Typography.Paragraph type="danger" style={{ marginTop: 12, marginBottom: 0 }}>
                  生成失败：{current.documentError}
                </Typography.Paragraph>}
              {current.status === 'PUBLISHED' && !current.assignedToMe &&
                <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
                  本次精选没有指派给你，你可以查看和下载，但不能提交作品。
                </Typography.Paragraph>}
            </Card>
          </Col>
        </Row>

        {current.assignedToMe && <Card title={`我的报送（${mine.length}/${current.entryLimit}）`}
          extra={current.submissionOpen && <Button type="primary" icon={<PlusOutlined />}
            disabled={quota === 0} onClick={openPicker}>
            {quota === 0 ? '已达上限' : '从图库选图'}
          </Button>}>
          {mine.length === 0
            ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={
                <span className="empty-copy">
                  <strong>你还没有报送作品</strong>
                  <span>{current.submissionOpen ? '先去图库挑一张，再补上拍摄思路和地点。' : '这次征集不在可填写的时间内。'}</span>
                </span>} />
            : <Row gutter={[16, 16]}>
              {mine.map(entry => <Col key={entry.id} xs={24} md={12}>
                <EntryCard entry={entry} actions={current.submissionOpen ? <Space>
                  <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(entry)}>编辑</Button>
                  <Button size="small" danger icon={<DeleteOutlined />}
                    onClick={() => removeEntry(entry)}>撤下</Button>
                </Space> : null} />
              </Col>)}
            </Row>}
        </Card>}

        <Card title="全部入选作品" extra={<Typography.Text type="secondary">按校区分章，与 Word 文档一致</Typography.Text>}>
          <DataState loading={entries.loading} error={entries.error} empty={!entries.data.length}
            onRetry={entries.reload} emptyText="还没有人报送作品"
            emptyHint="负责人提交后，这里会按校区分成章节。">
            {chapters.map(chapter => <div key={chapter.campusId ?? 'none'} className="featured-chapter">
              <Typography.Title level={4}>
                {chapter.campusName}
                <Typography.Text type="secondary" style={{ fontSize: 14, marginLeft: 8 }}>
                  {chapter.entries.length} 张
                </Typography.Text>
              </Typography.Title>
              <Row gutter={[16, 16]}>
                {chapter.entries.map(entry => <Col key={entry.id} xs={24} md={12} xl={8}>
                  <EntryCard entry={entry} />
                </Col>)}
              </Row>
            </div>)}
          </DataState>
        </Card>
      </>}
    </DataState>

    <Modal open={pickerOpen} width={880} title="从图库选择图片"
      okText="加入精选" cancelText="取消" confirmLoading={saving}
      okButtonProps={{ disabled: !pickedPhoto }}
      onOk={() => void submitEntry()} onCancel={() => setPickerOpen(false)} destroyOnHidden>
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Input allowClear prefix={<SearchOutlined />} placeholder="按标题、说明或标签搜索"
          onPressEnter={(event) => setPhotoFilters({
            page: 1, keyword: (event.target as HTMLInputElement).value.trim(),
          })}
          onChange={(event) => { if (!event.target.value) setPhotoFilters({ page: 1, keyword: '' }) }} />
        <Typography.Text type="secondary">
          只能选自己上传、且在授权校区内的图片；拍摄人和拍摄时间会直接取图库信息。
        </Typography.Text>
        <DataState loading={photos.loading} error={photos.error} empty={!photos.data.items.length}
          onRetry={photos.reload} emptyText="图库里没有可选的图片"
          emptyHint="先把作品上传到图库，再回来组织精选内容。">
          <Row gutter={[12, 12]}>
            {photos.data.items.map(photo => <Col key={photo.id} xs={12} md={8} lg={6}>
              <Card hoverable size="small"
                className={pickedPhoto?.id === photo.id ? 'featured-photo-picked' : undefined}
                onClick={() => setPickedPhoto(photo)}
                cover={photo.thumbnailUrl
                  ? <img src={photo.thumbnailUrl} alt={photo.title} className="featured-photo-thumb" />
                  : <div className="image-placeholder"><PictureOutlined /></div>}>
                <Card.Meta title={photo.title || '未命名'}
                  description={photo.photographerName} />
              </Card>
            </Col>)}
          </Row>
          {photos.data.total > photos.data.pageSize && <Pagination className="pager"
            current={photos.data.page} pageSize={photos.data.pageSize} total={photos.data.total}
            showSizeChanger={false} onChange={(page) => setPhotoFilters({ ...photoFilters, page })} />}
        </DataState>
        <Form form={form} layout="vertical">
          <Form.Item name="idea" label="拍摄思路" rules={[{ required: true, message: '请写下拍摄思路' }]}>
            <Input.TextArea rows={3} maxLength={2000} showCount
              placeholder="想表达什么、怎么取景、为什么这样处理……" />
          </Form.Item>
          <Form.Item name="location" label="拍摄地点" rules={[{ required: true, message: '请填写拍摄地点' }]}>
            <Input maxLength={200} placeholder="例如：东校区图书馆天台" />
          </Form.Item>
        </Form>
      </Space>
    </Modal>

    <Modal open={!!editingEntry} title="修改拍摄思路与地点"
      okText="保存" cancelText="取消" confirmLoading={saving}
      onOk={() => void submitEntry()} onCancel={() => setEditingEntry(null)} destroyOnHidden>
      {/* 图片本身不能在这里替换：换图等于换一条条目，走撤下再重选更直观。 */}
      <Form form={form} layout="vertical">
        <Form.Item name="idea" label="拍摄思路" rules={[{ required: true, message: '请写下拍摄思路' }]}>
          <Input.TextArea rows={4} maxLength={2000} showCount />
        </Form.Item>
        <Form.Item name="location" label="拍摄地点" rules={[{ required: true, message: '请填写拍摄地点' }]}>
          <Input maxLength={200} />
        </Form.Item>
      </Form>
    </Modal>
  </div>
}

function EntryCard({ entry, actions }: { entry: FeaturedEntry; actions?: React.ReactNode }) {
  return <Card size="small" className="featured-entry-card"
    title={entry.photoTitle || '未命名作品'} extra={actions}>
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      {entry.previewUrl
        ? <Image src={entry.previewUrl} alt={entry.photoTitle || '精选图片'} />
        : <div className="image-placeholder">
          {/* 图片可能在填报之后被删除；文字快照仍然完整保留。 */}
          <span>{entry.photoAvailable ? '预览暂不可用' : '图片已从图库删除'}</span>
        </div>}
      <Descriptions column={1} size="small" items={[
        { key: 'photographer', label: '拍摄人',
          children: `${entry.photographerName || '—'}${entry.photographerStudentId ? `（${entry.photographerStudentId}）` : ''}` },
        { key: 'takenAt', label: '拍摄时间',
          children: entry.takenAt ? dayjs(entry.takenAt).format('YYYY-MM-DD HH:mm') : '—' },
        { key: 'location', label: '拍摄地点', children: entry.location },
        { key: 'idea', label: '拍摄思路', children: entry.idea },
        { key: 'submitter', label: '填报人', children: entry.submitterDisplayName || '—' },
      ]} />
    </Space>
  </Card>
}
