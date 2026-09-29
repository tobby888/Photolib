import { App, Button, Form, Input, Modal, Tag, Typography } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { api } from '../../api'
import type { Campus } from '../../types'
import { ContentFitTable } from '../../ContentFitTable'
import { useLoad } from '../../hooks'

/** 管理员面板 · 校区管理。校区代码创建后不可修改。 */
export default function CampusesPage() {
  const { message } = App.useApp()
  const [campusForm] = Form.useForm()
  const [campusOpen, setCampusOpen] = useState(false)
  const { data: campuses, reload: reloadCampuses } = useLoad(
    () => api<Campus[]>({ url: '/campuses' }), [] as Campus[], [],
  )
  const createCampus = async () => {
    try {
      await api({ method: 'POST', url: '/campuses', data: await campusForm.validateFields() })
      message.success('校区已创建'); setCampusOpen(false); campusForm.resetFields(); await reloadCampuses()
    } catch (e) { message.error((e as Error).message) }
  }
  return <>
    <div className="tab-toolbar"><div><Typography.Title level={4}>校区资源</Typography.Title><Typography.Text type="secondary">校区代码创建后不可修改。</Typography.Text></div>
      <Button type="primary" icon={<PlusOutlined />} onClick={() => setCampusOpen(true)}>新建校区</Button></div>
    <ContentFitTable rowKey="id" dataSource={campuses} pagination={false} columns={[
      { title: '代码', dataIndex: 'code', render: value => <Typography.Text code>{value}</Typography.Text> },
      { title: '校区名称', dataIndex: 'name' }, { title: '状态', dataIndex: 'enabled', render: value => <Tag color={value ? 'green' : 'default'}>{value ? '已启用' : '已停用'}</Tag> },
      { title: '版本', dataIndex: 'version', render: value => `v${value}` },
    ]} />
    <Modal title="新建校区" open={campusOpen} onCancel={() => setCampusOpen(false)} onOk={createCampus}>
      <Form form={campusForm} layout="vertical" requiredMark={false}>
        <Form.Item label="校区代码" name="code" rules={[{ required: true }, { pattern: /^[A-Za-z0-9_-]{2,32}$/ }]}><Input placeholder="例如 SOUTH" /></Form.Item>
        <Form.Item label="校区名称" name="name" rules={[{ required: true }]}><Input placeholder="例如 南校区" /></Form.Item>
      </Form>
    </Modal>
  </>
}
