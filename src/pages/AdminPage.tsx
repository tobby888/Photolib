import {
  App, Button, Card, Form, Input, Modal, Select, Space, Switch, Table, Tabs, Tag, Typography,
} from 'antd'
import { PlusOutlined, SafetyCertificateOutlined, TeamOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { api, emptyPage } from '../api'
import type { Campus, PageData, Role, User } from '../types'
import { DataState, PageTitle, roleName } from '../components'
import { useLoad } from '../hooks'

export default function AdminPage() {
  const { message, modal } = App.useApp()
  const [userForm] = Form.useForm()
  const [campusForm] = Form.useForm()
  const [userOpen, setUserOpen] = useState(false)
  const [campusOpen, setCampusOpen] = useState(false)
  const { data: users, loading, error, reload } = useLoad(
    () => api<PageData<User>>({ url: '/users', params: { page: 1, pageSize: 100 } }), emptyPage<User>(), [],
  )
  const { data: campuses, reload: reloadCampuses } = useLoad(
    () => api<Campus[]>({ url: '/campuses' }), [] as Campus[], [],
  )
  const createUser = async () => {
    try {
      const result = await api<{ user: User; initialPassword: string }>({ method: 'POST', url: '/users', data: await userForm.validateFields() })
      setUserOpen(false); userForm.resetFields(); await reload()
      modal.success({ title: '账号创建成功', content: <div><p>请通过安全渠道把初始密码交给用户，此密码只显示一次。</p><Typography.Text copyable code>{result.initialPassword}</Typography.Text></div> })
    } catch (e) { message.error((e as Error).message) }
  }
  const createCampus = async () => {
    try {
      await api({ method: 'POST', url: '/campuses', data: await campusForm.validateFields() })
      message.success('校区已创建'); setCampusOpen(false); campusForm.resetFields(); await reloadCampuses()
    } catch (e) { message.error((e as Error).message) }
  }
  const toggleUser = async (user: User) => {
    try {
      await api({ method: 'POST', url: `/users/${user.id}/${user.enabled ? 'disable' : 'enable'}` })
      message.success(user.enabled ? '账号已停用' : '账号已启用'); await reload()
    } catch (e) { message.error((e as Error).message) }
  }
  return <>
    <PageTitle eyebrow="ADMINISTRATION" title="系统管理" description="维护账号、校区和系统运行秩序。" />
    <Card>
      <Tabs items={[
        { key: 'users', label: <span><TeamOutlined /> 账号管理</span>, children: <>
          <div className="tab-toolbar"><div><Typography.Title level={4}>成员账号</Typography.Title><Typography.Text type="secondary">系统不开放注册，账号均由管理员创建。</Typography.Text></div>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setUserOpen(true)}>创建账号</Button></div>
          <DataState loading={loading} error={error} empty={!users.items.length} onRetry={reload}>
            <Table rowKey="id" dataSource={users.items} pagination={{ pageSize: 12 }} scroll={{ x: 850 }} columns={[
              { title: '成员', render: (_, item) => <div className="table-title"><strong>{item.displayName}</strong><span>@{item.username}</span></div> },
              { title: '角色', dataIndex: 'role', render: value => <Tag variant="filled">{roleName[value]}</Tag> },
              { title: '校区', dataIndex: 'campusId', render: value => campuses.find(c => c.id === value)?.name || '-' },
              { title: '联系方式', render: (_, item) => item.email || item.phone || '-' },
              { title: '状态', dataIndex: 'enabled', render: value => <Tag color={value ? 'green' : 'default'} variant="filled">{value ? '正常' : '已停用'}</Tag> },
              { title: '操作', fixed: 'right', render: (_, item) => <Space><Switch size="small" checked={item.enabled} onChange={() => void toggleUser(item)} /><Button type="link" onClick={async () => {
                try { const result = await api<{ initialPassword: string }>({ method: 'PUT', url: `/users/${item.id}/password` }); modal.warning({ title: '密码已重置', content: <Typography.Text copyable code>{result.initialPassword}</Typography.Text> }) } catch (e) { message.error((e as Error).message) }
              }}>重置密码</Button></Space> },
            ]} />
          </DataState>
        </> },
        { key: 'campuses', label: <span><SafetyCertificateOutlined /> 校区管理</span>, children: <>
          <div className="tab-toolbar"><div><Typography.Title level={4}>校区资源</Typography.Title><Typography.Text type="secondary">校区代码创建后不可修改。</Typography.Text></div>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCampusOpen(true)}>新建校区</Button></div>
          <Table rowKey="id" dataSource={campuses} pagination={false} columns={[
            { title: '代码', dataIndex: 'code', render: value => <Typography.Text code>{value}</Typography.Text> },
            { title: '校区名称', dataIndex: 'name' }, { title: '状态', dataIndex: 'enabled', render: value => <Tag color={value ? 'green' : 'default'}>{value ? '已启用' : '已停用'}</Tag> },
            { title: '版本', dataIndex: 'version', render: value => `v${value}` },
          ]} />
        </> },
      ]} />
    </Card>
    <Modal title="创建成员账号" open={userOpen} onCancel={() => setUserOpen(false)} onOk={createUser} okText="创建账号">
      <Form form={userForm} layout="vertical" requiredMark={false}>
        <Form.Item label="登录账号" name="username" rules={[{ required: true }, { pattern: /^[A-Za-z0-9_.-]{3,64}$/, message: '3-64 位字母、数字或 ._-' }]}><Input /></Form.Item>
        <Form.Item label="显示姓名" name="displayName" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item label="角色" name="role" rules={[{ required: true }]}><Select options={Object.entries(roleName).map(([value, label]) => ({ value, label }))} /></Form.Item>
        <Form.Item noStyle shouldUpdate={(prev, next) => prev.role !== next.role}>{({ getFieldValue }) => getFieldValue('role') === 'CAMPUS_MANAGER' && <Form.Item label="所属校区" name="campusId" rules={[{ required: true }]}><Select options={campuses.filter(c => c.enabled).map(c => ({ value: c.id, label: c.name }))} /></Form.Item>}</Form.Item>
        <Form.Item label="邮箱" name="email" rules={[{ type: 'email' }]}><Input /></Form.Item>
        <Form.Item label="手机号" name="phone"><Input /></Form.Item>
      </Form>
    </Modal>
    <Modal title="新建校区" open={campusOpen} onCancel={() => setCampusOpen(false)} onOk={createCampus}>
      <Form form={campusForm} layout="vertical" requiredMark={false}>
        <Form.Item label="校区代码" name="code" rules={[{ required: true }, { pattern: /^[A-Za-z0-9_-]{2,32}$/ }]}><Input placeholder="例如 SOUTH" /></Form.Item>
        <Form.Item label="校区名称" name="name" rules={[{ required: true }]}><Input placeholder="例如 南校区" /></Form.Item>
      </Form>
    </Modal>
  </>
}
