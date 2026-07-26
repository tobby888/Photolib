import { App, Button, Card, Empty, Form, Input, Modal, Select, Space, Table, Tag } from 'antd'
import { EditOutlined, PlusOutlined, StopOutlined, CheckCircleOutlined, DeleteOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { api, qs } from '../api'
import { useAuth } from '../auth'
import type { Campus, CampusMember, EntityId } from '../types'
import { DataState, PageTitle } from '../components'
import { useLoad } from '../hooks'
import { hasPermission } from '../permissions'

export default function DirectoryPage() {
  const { user } = useAuth()
  const { message, modal } = App.useApp()
  const [form] = Form.useForm()
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [editing, setEditing] = useState<CampusMember | null>(null)
  const [selectedCampus, setSelectedCampus] = useState<EntityId | undefined>(
    user?.campusIds?.length === 1 ? user.campusIds[0] : undefined)

  const campusScoped = user?.dataScope === 'CAMPUS'
  const canManage = hasPermission(user, 'DIRECTORY_MANAGE')
  const showCampusPicker = !campusScoped || (user?.campusIds?.length || 0) > 1

  const { data: campuses } = useLoad(
    () => showCampusPicker ? api<Campus[]>({ url: '/campuses', params: { enabled: true } }) : Promise.resolve([]),
    [] as Campus[], [showCampusPicker],
  )
  const visibleCampuses = campusScoped
    ? campuses.filter(campus => user?.campusIds?.includes(campus.id))
    : campuses
  const { data: members, loading, error, reload } = useLoad(
    () => !showCampusPicker
      ? api<CampusMember[]>({ url: '/campus-members' })
      : selectedCampus
        ? api<CampusMember[]>({ url: '/campus-members', params: { campusId: selectedCampus } })
        : Promise.resolve([] as CampusMember[]),
    [] as CampusMember[], [showCampusPicker, selectedCampus],
  )

  const openCreate = () => { setEditing(null); form.resetFields(); form.setFieldsValue({ enabled: true }); setOpen(true) }
  const openEdit = (member: CampusMember) => {
    setEditing(member)
    form.setFieldsValue({ name: member.name, studentId: member.studentId, enabled: member.enabled })
    setOpen(true)
  }

  const save = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      if (editing) {
        await api({ method: 'PUT', url: `/campus-members/${editing.id}`, data: {
          studentId: values.studentId, name: values.name, enabled: values.enabled, version: editing.version,
        } })
        message.success('成员信息已更新')
      } else {
        await api({ method: 'POST', url: '/campus-members', data: qs({
          campusId: showCampusPicker ? selectedCampus : undefined,
          studentId: values.studentId,
          name: values.name,
        }) })
        message.success('成员已加入通讯录')
      }
      setOpen(false); form.resetFields(); setEditing(null); await reload()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const toggle = async (member: CampusMember) => {
    try {
      await api({ method: 'PUT', url: `/campus-members/${member.id}`, data: {
        studentId: member.studentId, name: member.name, enabled: !member.enabled, version: member.version,
      } })
      message.success(member.enabled ? '成员已停用' : '成员已启用')
      await reload()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const confirmToggle = (member: CampusMember) => {
    if (!member.enabled) { void toggle(member); return }
    modal.confirm({
      title: `停用“${member.name}”？`,
      content: '停用后该成员将无法被选为拍摄者或工时人员，历史工时与照片中的姓名、学号不受影响。',
      okText: '停用',
      okButtonProps: { danger: true },
      onOk: () => toggle(member),
    })
  }

  const del = async (member: CampusMember) => {
    try {
      await api({ method: 'DELETE', url: `/campus-members/${member.id}` })
      message.success('成员已从通讯录删除')
      await reload()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const confirmDelete = (member: CampusMember) => {
    modal.confirm({
      title: `删除“${member.name}”？`,
      content: '将把该成员从通讯录彻底移除；历史工时与照片中的姓名、学号快照不受影响。如只是暂时不可选，请改用“停用”。',
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: () => del(member),
    })
  }

  const description = canManage
    ? '按授权校区维护摄影人员通讯录，供上传拍摄者与工时人员选择。'
    : '查看摄影人员通讯录。'

  const manageExtra = canManage
    ? <Button type="primary" size="large" icon={<PlusOutlined />}
        disabled={showCampusPicker && !selectedCampus} onClick={openCreate}>添加成员</Button>
    : undefined

  return <>
    <PageTitle eyebrow="DIRECTORY" title="通讯录" description={description} extra={manageExtra} />

    <>
      {showCampusPicker && (
        <Card className="filter-card">
          <Space wrap>
            <span>校区</span>
            <Select
              showSearch
              optionFilterProp="label"
              style={{ width: 240 }}
              placeholder="请选择要维护的校区"
              value={selectedCampus}
              options={visibleCampuses.map(campus => ({ value: campus.id, label: campus.name }))}
              onChange={(value) => setSelectedCampus(value)}
            />
          </Space>
        </Card>
      )}
      <Card>
        {showCampusPicker && !selectedCampus
          ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请先选择要查看的校区" />
          : <DataState loading={loading} error={error} empty={!members.length} onRetry={reload}>
              <Table
                rowKey="id"
                dataSource={members}
                pagination={{ pageSize: 12, hideOnSinglePage: true }}
                scroll={{ x: 'max-content' }}
                columns={[
                  { title: '姓名', dataIndex: 'name' },
                  { title: '学号', dataIndex: 'studentId' },
                  { title: '状态', dataIndex: 'enabled', width: 100,
                    render: (enabled: boolean) => enabled
                      ? <Tag color="green">启用</Tag>
                      : <Tag>停用</Tag> },
                  ...(canManage ? [{ title: '操作', width: 280, render: (_: unknown, member: CampusMember) => <Space>
                    <Button type="text" icon={<EditOutlined />} onClick={() => openEdit(member)}>编辑</Button>
                    {member.enabled
                      ? <Button type="text" danger icon={<StopOutlined />} onClick={() => confirmToggle(member)}>停用</Button>
                      : <Button type="text" icon={<CheckCircleOutlined />} onClick={() => confirmToggle(member)}>启用</Button>}
                    <Button type="text" danger icon={<DeleteOutlined />} onClick={() => confirmDelete(member)}>删除</Button>
                  </Space> }] : []),
                ]}
              />
            </DataState>}
      </Card>
    </>

    <Modal
      title={editing ? '编辑成员' : '添加成员'}
      open={open}
      onCancel={() => { setOpen(false); setEditing(null) }}
      onOk={save}
      okText="保存"
      confirmLoading={saving}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" requiredMark={false} initialValues={{ enabled: true }}>
        <Form.Item label="姓名" name="name" rules={[{ required: true, message: '请输入姓名' }, { max: 100 }]}>
          <Input placeholder="成员姓名" />
        </Form.Item>
        <Form.Item label="学号" name="studentId" rules={[{ required: true, message: '请输入学号' }, { max: 64 }]}>
          <Input placeholder="成员学号" />
        </Form.Item>
        {editing && (
          <Form.Item label="状态" name="enabled">
            <Select options={[{ value: true, label: '启用' }, { value: false, label: '停用' }]} />
          </Form.Item>
        )}
      </Form>
    </Modal>
  </>
}
