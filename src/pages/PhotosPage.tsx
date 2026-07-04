import {
  App, Button, Card, Col, DatePicker, Descriptions, Drawer, Form, Image, Input, Modal,
  Pagination, Row, Select, Space, Tag, Typography, Upload,
} from 'antd'
import { CloudUploadOutlined, DownloadOutlined, InboxOutlined, SearchOutlined } from '@ant-design/icons'
import { useState } from 'react'
import dayjs from 'dayjs'
import axios from 'axios'
import { api, emptyPage, qs } from '../api'
import type { PageData, Photo } from '../types'
import { DataState, formatBytes, PageTitle, StatusTag } from '../components'
import { useLoad } from '../hooks'

export default function PhotosPage() {
  const { message } = App.useApp()
  const [uploadForm] = Form.useForm()
  const [uploadOpen, setUploadOpen] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [selected, setSelected] = useState<Photo | null>(null)
  const [filters, setFilters] = useState({ page: 1, keyword: '', status: 'AVAILABLE' })
  const { data, loading, error, reload } = useLoad(
    () => api<PageData<Photo>>({ url: '/photos', params: qs({ ...filters, pageSize: 24 }) }),
    emptyPage<Photo>(), [filters.page, filters.keyword, filters.status],
  )
  const submitUpload = async () => {
    const values = await uploadForm.validateFields()
    const file = values.file?.[0]?.originFileObj as File | undefined
    if (!file) return
    setUploading(true)
    try {
      const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await file.arrayBuffer())))
        .map(b => b.toString(16).padStart(2, '0')).join('')
      const ticket = await api<{ photoId: string; uploadUrl: string; method: string; contentType: string }>({
        method: 'POST', url: '/photos/upload-tickets', data: {
          requestId: values.requestId || null, projectId: values.projectId || null,
          fileName: file.name, contentType: file.type, size: file.size, sha256: hash,
          photographerStudentId: values.photographerStudentId, photographerName: values.photographerName,
          takenAt: values.takenAt.format('YYYY-MM-DDTHH:mm:ss'),
        },
      })
      await axios.request({ method: ticket.method || 'PUT', url: ticket.uploadUrl, data: file,
        headers: { 'Content-Type': ticket.contentType }, transformRequest: [(value) => value] })
      await api({ method: 'POST', url: `/photos/${ticket.photoId}/complete-upload`,
        data: { title: values.title, description: values.description, tags: values.tags || [] } })
      message.success('图片已上传，后台正在处理'); setUploadOpen(false); uploadForm.resetFields(); await reload()
    } catch (e) { message.error((e as Error).message) } finally { setUploading(false) }
  }
  const download = async (photo: Photo) => {
    try {
      const result = await api<{ downloadUrl: string }>({ method: 'POST', url: `/photos/${photo.id}/download-url` })
      window.open(result.downloadUrl, '_blank', 'noopener')
    } catch (e) { message.error((e as Error).message) }
  }
  return <>
    <PageTitle eyebrow="LIBRARY" title="图片库" description="检索、查看并下载团队沉淀的每一帧。"
      extra={<Button type="primary" size="large" icon={<CloudUploadOutlined />} onClick={() => setUploadOpen(true)}>上传图片</Button>} />
    <Card className="filter-card">
      <Space wrap>
        <Input.Search allowClear placeholder="搜索标题、描述或标签" enterButton={<SearchOutlined />} style={{ width: 320 }}
          onSearch={keyword => setFilters({ ...filters, page: 1, keyword })} />
        <Select value={filters.status} style={{ width: 150 }} options={[
          { value: 'AVAILABLE', label: '可用图片' }, { value: 'PROCESSING', label: '处理中' }, { value: 'ARCHIVED', label: '已归档' },
        ]} onChange={status => setFilters({ ...filters, page: 1, status })} />
      </Space>
      <Typography.Text type="secondary">共 {data.total} 张图片</Typography.Text>
    </Card>
    <DataState loading={loading} error={error} empty={!data.items.length} onRetry={reload}>
      <Row gutter={[16, 20]} className="photo-grid">
        {data.items.map(photo => <Col xs={24} sm={12} lg={8} xxl={6} key={photo.id}>
          <Card className="photo-card" hoverable cover={<div className="photo-cover" onClick={() => setSelected(photo)}>
            {photo.thumbnailUrl ? <Image preview={false} src={photo.thumbnailUrl} alt={photo.title} /> : <div className="image-placeholder"><span>{photo.title?.slice(0, 1) || '图'}</span></div>}
            <div className="photo-overlay"><Space><StatusTag value={photo.status} />{!!photo.adoptionCount && <Tag color="gold">已采用 × {photo.adoptionCount}</Tag>}</Space><Button shape="circle" icon={<DownloadOutlined />} onClick={e => { e.stopPropagation(); void download(photo) }} /></div>
          </div>}>
            <Typography.Title level={5} ellipsis>{photo.title || '未命名图片'}</Typography.Title>
            <Space size={4} wrap>{photo.tags?.slice(0, 3).map(tag => <Tag variant="filled" key={tag}>{tag}</Tag>)}</Space>
            <div className="photo-meta"><span>{photo.photographerName}</span><span>{dayjs(photo.takenAt).format('YYYY.MM.DD')}</span></div>
          </Card>
        </Col>)}
      </Row>
      <Pagination current={filters.page} pageSize={24} total={data.total} hideOnSinglePage onChange={page => setFilters({ ...filters, page })} />
    </DataState>
    <Modal title="上传单张图片" width={680} open={uploadOpen} onCancel={() => setUploadOpen(false)} onOk={submitUpload}
      okText="开始上传" confirmLoading={uploading} destroyOnHidden>
      <Form form={uploadForm} layout="vertical" requiredMark={false}>
        <Form.Item name="file" valuePropName="fileList" getValueFromEvent={e => e.fileList} rules={[{ required: true, message: '请选择图片' }]}>
          <Upload.Dragger accept=".jpg,.jpeg,.png" maxCount={1} beforeUpload={() => false}>
            <p className="ant-upload-drag-icon"><InboxOutlined /></p><p className="ant-upload-text">拖拽图片到这里，或点击选择</p><p className="ant-upload-hint">仅支持 JPG / PNG，单张不超过 100 MiB</p>
          </Upload.Dragger>
        </Form.Item>
        <Row gutter={16}><Col span={12}><Form.Item label="图片标题" name="title" rules={[{ required: true }]}><Input /></Form.Item></Col>
          <Col span={12}><Form.Item label="拍摄时间" name="takenAt" rules={[{ required: true }]}><DatePicker showTime style={{ width: '100%' }} /></Form.Item></Col></Row>
        <Row gutter={16}><Col span={12}><Form.Item label="拍摄者姓名" name="photographerName" rules={[{ required: true }]}><Input /></Form.Item></Col>
          <Col span={12}><Form.Item label="拍摄者学号" name="photographerStudentId" rules={[{ required: true }]}><Input /></Form.Item></Col></Row>
        <Form.Item label="标签" name="tags"><Select mode="tags" maxCount={30} placeholder="输入后回车添加标签" /></Form.Item>
        <Form.Item label="图片说明" name="description"><Input.TextArea rows={3} /></Form.Item>
      </Form>
    </Modal>
    <Drawer title="图片详情" width={520} open={!!selected} onClose={() => setSelected(null)}
      extra={selected && <Button type="primary" icon={<DownloadOutlined />} onClick={() => void download(selected)}>下载原图</Button>}>
      {selected && <>
        <div className="detail-image">{selected.thumbnailUrl ? <Image src={selected.thumbnailUrl} /> : <div className="image-placeholder"><span>{selected.title.slice(0, 1)}</span></div>}</div>
        <Typography.Title level={3}>{selected.title}</Typography.Title>
        <Typography.Paragraph type="secondary">{selected.description || '暂无图片说明'}</Typography.Paragraph>
        <Descriptions column={1} size="small" items={[
          { key: 'status', label: '状态', children: <StatusTag value={selected.status} /> },
          { key: 'adoption', label: '采用状态', children: selected.adoptionCount ? <Tag color="gold">已采用 × {selected.adoptionCount}</Tag> : '未采用' },
          { key: 'photographer', label: '拍摄者', children: `${selected.photographerName} · ${selected.photographerStudentId}` },
          { key: 'taken', label: '拍摄时间', children: dayjs(selected.takenAt).format('YYYY-MM-DD HH:mm') },
          { key: 'size', label: '文件信息', children: `${selected.width || '-'} × ${selected.height || '-'} · ${formatBytes(selected.size)}` },
          { key: 'file', label: '归档文件名', children: selected.storedFileName },
        ]} />
      </>}
    </Drawer>
  </>
}
