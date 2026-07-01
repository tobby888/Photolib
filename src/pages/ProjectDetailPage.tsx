import {
  App, Breadcrumb, Button, Card, Col, DatePicker, Descriptions, Form, Input, InputNumber,
  Modal, Row, Select, Space, Statistic, Table, Tag, Typography,
} from 'antd'
import {
  ArrowLeftOutlined, CameraOutlined, CheckCircleOutlined, EditOutlined, FileImageOutlined,
  PlusOutlined, RocketOutlined, StopOutlined, UnorderedListOutlined,
} from '@ant-design/icons'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { useAuth } from '../auth'
import { api, emptyPage } from '../api'
import type { Campus, PageData, PhotoRequest, Project } from '../types'
import { DataState, StatusTag } from '../components'
import { useLoad } from '../hooks'

const projectStateCopy = {
  DRAFT: {
    title: '项目仍在准备中',
    description: '请先补充选题说明和执行边界。启动后，才可以发布图片需求。',
  },
  ACTIVE: {
    title: '项目正在执行',
    description: '可以继续创建和发布图片需求，收集素材并记录图片采纳。',
  },
  COMPLETED: {
    title: '项目已经完成',
    description: '需求与采纳记录已锁定。如需补充素材，管理员可以重新开放项目。',
  },
  CANCELLED: {
    title: '项目已经取消',
    description: '项目不再接受新需求，已有记录仍会保留用于追溯。',
  },
}

export default function ProjectDetailPage() {
  const { projectId = '' } = useParams()
  const navigate = useNavigate()
  const { user } = useAuth()
  const { message, modal } = App.useApp()
  const [requestForm] = Form.useForm()
  const [editForm] = Form.useForm()
  const [requestOpen, setRequestOpen] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const { data, loading, error, reload } = useLoad(async () => {
    const [project, requests, campuses] = await Promise.all([
      api<Project>({ url: `/projects/${projectId}` }),
      api<PageData<PhotoRequest>>({ url: '/requests', params: { page: 1, pageSize: 100, projectId } }),
      api<Campus[]>({ url: '/campuses', params: { enabled: true } }),
    ])
    return { project, requests, campuses }
  }, { project: null as Project | null, requests: emptyPage<PhotoRequest>(), campuses: [] as Campus[] }, [projectId])

  const changeStatus = async (status: Project['status']) => {
    if (!data.project) return
    try {
      await api({ method: 'POST', url: `/projects/${projectId}/status`,
        data: { status, version: data.project.version } })
      message.success(status === 'ACTIVE' ? '项目已启动' : status === 'COMPLETED' ? '项目已完成' : '项目已取消')
      await reload()
    } catch (reason) { message.error((reason as Error).message) }
  }

  const reopen = () => {
    if (!data.project) return
    let reason = ''
    modal.confirm({
      title: '重新开放项目',
      content: <Input.TextArea rows={3} placeholder="请填写重新开放的原因" onChange={event => { reason = event.target.value }} />,
      okText: '确认重新开放',
      onOk: async () => {
        if (!reason.trim()) throw new Error('请填写重新开放原因')
        await api({ method: 'POST', url: `/projects/${projectId}/reopen`,
          data: { reason: reason.trim(), version: data.project!.version } })
        message.success('项目已重新开放')
        await reload()
      },
    })
  }

  const createRequest = async () => {
    const values = await requestForm.validateFields()
    setSaving(true)
    try {
      const { deadline, ...rest } = values
      await api({ method: 'POST', url: `/projects/${projectId}/requests`,
        data: { ...rest, deadline: deadline.format('YYYY-MM-DDTHH:mm:ss') } })
      message.success('图片需求草稿已创建')
      setRequestOpen(false)
      requestForm.resetFields()
      await reload()
    } catch (reason) { message.error((reason as Error).message) } finally { setSaving(false) }
  }

  const updateProject = async () => {
    if (!data.project) return
    const values = await editForm.validateFields()
    setSaving(true)
    try {
      await api({ method: 'PUT', url: `/projects/${projectId}`,
        data: { ...values, version: data.project.version } })
      message.success('项目信息已更新')
      setEditOpen(false)
      await reload()
    } catch (reason) { message.error((reason as Error).message) } finally { setSaving(false) }
  }

  const project = data.project
  const canManage = user?.role !== 'CAMPUS_MANAGER'
  return <DataState loading={loading} error={error} empty={!project} onRetry={reload}>
    {project && <>
      <Breadcrumb className="detail-breadcrumb" items={[
        { title: <a onClick={() => navigate('/projects')}>选题项目</a> },
        { title: project.title },
      ]} />
      <section className="project-detail-hero">
        <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/projects')}>返回项目列表</Button>
        <div className="project-detail-heading">
          <div>
            <Space><Tag variant="filled">PROJECT {project.id}</Tag><StatusTag value={project.status} /></Space>
            <Typography.Title>{project.title}</Typography.Title>
            <Typography.Paragraph>{project.description || '尚未添加项目说明。清晰的说明能帮助负责人准确理解拍摄目标。'}</Typography.Paragraph>
          </div>
          {canManage && <Space wrap>
            {project.status !== 'COMPLETED' && project.status !== 'CANCELLED' &&
              <Button icon={<EditOutlined />} onClick={() => {
                editForm.setFieldsValue({ title: project.title, description: project.description })
                setEditOpen(true)
              }}>编辑项目</Button>}
            {(project.status === 'DRAFT' || project.status === 'ACTIVE') &&
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setRequestOpen(true)}>新建图片需求</Button>}
          </Space>}
        </div>
      </section>

      <Row gutter={[16, 16]} className="project-summary">
        <Col xs={12} lg={6}><Card><Statistic title="图片需求" value={project.requestCount || 0} prefix={<UnorderedListOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="项目图片" value={project.photoCount || 0} prefix={<FileImageOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="已采纳" value={project.adoptionCount || 0} prefix={<CheckCircleOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="当前版本" value={project.version} prefix={<CameraOutlined />} /></Card></Col>
      </Row>

      <Card className={`state-guide state-${project.status.toLowerCase()}`}>
        <div>
          <Typography.Text className="eyebrow">STATUS GUIDE</Typography.Text>
          <Typography.Title level={4}>{projectStateCopy[project.status].title}</Typography.Title>
          <Typography.Paragraph>{projectStateCopy[project.status].description}</Typography.Paragraph>
        </div>
        {canManage && <Space wrap>
          {project.status === 'DRAFT' && <Button type="primary" icon={<RocketOutlined />} onClick={() => void changeStatus('ACTIVE')}>启动项目</Button>}
          {project.status === 'ACTIVE' && <Button type="primary" icon={<CheckCircleOutlined />} onClick={() => void changeStatus('COMPLETED')}>标记为已完成</Button>}
          {['DRAFT', 'ACTIVE'].includes(project.status) && <Button danger icon={<StopOutlined />}
            onClick={() => modal.confirm({ title: '确认取消这个项目？', content: '取消后不能再创建需求，已有记录会继续保留。',
              okText: '确认取消', okButtonProps: { danger: true }, onOk: () => changeStatus('CANCELLED') })}>取消项目</Button>}
          {project.status === 'COMPLETED' && user?.role === 'ADMIN' && <Button type="primary" onClick={reopen}>重新开放</Button>}
        </Space>}
      </Card>

      <Card title="项目图片需求" extra={canManage && ['DRAFT', 'ACTIVE'].includes(project.status) &&
        <Button type="link" icon={<PlusOutlined />} onClick={() => setRequestOpen(true)}>新建需求</Button>}>
        <Table rowKey="id" dataSource={data.requests.items} pagination={false} locale={{ emptyText: '这个项目还没有图片需求' }}
          columns={[
            { title: '需求', dataIndex: 'title', render: (value, item) => <div className="table-title"><strong>{value}</strong><span>{item.description || '暂无拍摄说明'}</span></div> },
            { title: '校区', dataIndex: 'campusId', render: value => data.campuses.find(c => c.id === value)?.name || `校区 #${value}` },
            { title: '需要数量', dataIndex: 'requiredCount', render: value => `${value} 张` },
            { title: '截止时间', dataIndex: 'deadline', render: value => dayjs(value).format('YYYY-MM-DD HH:mm') },
            { title: '状态', dataIndex: 'status', render: value => <StatusTag value={value} /> },
            { title: '操作', render: (_, item) => <Button type="link" onClick={() => navigate(`/requests?projectId=${projectId}&requestId=${item.id}`)}>查看需求</Button> },
          ]} />
      </Card>

      <Modal title="新建图片需求" width={640} open={requestOpen} onCancel={() => setRequestOpen(false)}
        onOk={createRequest} okText="保存需求草稿" confirmLoading={saving}>
        <Typography.Paragraph type="secondary">需求将归属到“{project.title}”，保存后可继续检查并发布。</Typography.Paragraph>
        <Form form={requestForm} layout="vertical" requiredMark={false} initialValues={{ requiredCount: 10 }}>
          <Form.Item label="需求标题" name="title" rules={[{ required: true, message: '请输入需求标题' }, { max: 200 }]}>
            <Input placeholder="例如：毕业典礼现场图片" />
          </Form.Item>
          <Form.Item label="拍摄说明" name="description"><Input.TextArea rows={3} placeholder="说明需要的场景、人物、构图和交付标准" /></Form.Item>
          <Row gutter={16}>
            <Col xs={24} sm={8}><Form.Item label="拍摄校区" name="campusId" rules={[{ required: true, message: '请选择校区' }]}>
              <Select options={data.campuses.map(c => ({ value: c.id, label: c.name }))} placeholder="选择校区" />
            </Form.Item></Col>
            <Col xs={24} sm={7}><Form.Item label="需要数量" name="requiredCount" rules={[{ required: true }]}>
              <InputNumber min={1} max={10000} suffix="张" style={{ width: '100%' }} />
            </Form.Item></Col>
            <Col xs={24} sm={9}><Form.Item label="截止时间" name="deadline" rules={[{ required: true, message: '请选择截止时间' }]}>
              <DatePicker showTime format="YYYY-MM-DD HH:mm" disabledDate={date => date.isBefore(dayjs(), 'day')} style={{ width: '100%' }} />
            </Form.Item></Col>
          </Row>
        </Form>
      </Modal>

      <Modal title="编辑项目信息" open={editOpen} onCancel={() => setEditOpen(false)} onOk={updateProject}
        okText="保存修改" confirmLoading={saving}>
        <Form form={editForm} layout="vertical" requiredMark={false}>
          <Form.Item label="项目名称" name="title" rules={[{ required: true, message: '请输入项目名称' }, { max: 200 }]}><Input /></Form.Item>
          <Form.Item label="项目说明" name="description"><Input.TextArea rows={5} placeholder="说明选题方向、内容范围和交付目标" /></Form.Item>
        </Form>
      </Modal>
    </>}
  </DataState>
}
