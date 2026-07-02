import {
  App, Button, Card, Form, Input, Modal, Select, Space, Switch, Table, Tabs, Tag, Typography, Upload,
} from 'antd'
import {
  AimOutlined, BgColorsOutlined, BulbOutlined, CameraOutlined, PictureOutlined,
  PlusOutlined, SafetyCertificateOutlined, StarOutlined, TeamOutlined, UploadOutlined,
} from '@ant-design/icons'
import { useState } from 'react'
import { api, emptyPage } from '../api'
import type { BrandingSettings, Campus, PageData, Role, User } from '../types'
import { DataState, PageTitle, roleName } from '../components'
import { useLoad } from '../hooks'

interface BrandingFormValues {
  title: string
  slogan: string
  iconChoice: BrandingSettings['builtinIcon'] | 'custom'
}

export default function AdminPage() {
  const { message, modal } = App.useApp()
  const [userForm] = Form.useForm()
  const [campusForm] = Form.useForm()
  const [brandingForm] = Form.useForm<BrandingFormValues>()
  const [userOpen, setUserOpen] = useState(false)
  const [campusOpen, setCampusOpen] = useState(false)
  const { data: users, loading, error, reload } = useLoad(
    () => api<PageData<User>>({ url: '/users', params: { page: 1, pageSize: 100 } }), emptyPage<User>(), [],
  )
  const { data: campuses, reload: reloadCampuses } = useLoad(
    () => api<Campus[]>({ url: '/campuses' }), [] as Campus[], [],
  )
  const { data: branding, loading: brandingLoading, error: brandingError, reload: reloadBranding } = useLoad(
    () => api<BrandingSettings>({ url: '/branding' }),
    { title: 'PhotoLib', iconType: 'builtin', builtinIcon: 'camera', slogan: '摄影工作站' } as BrandingSettings, [],
  )
  const saveBranding = async () => {
    try {
      const values = await brandingForm.validateFields()
      await api<BrandingSettings>({ method: 'PUT', url: '/branding', data: {
        title: values.title,
        slogan: values.slogan,
        iconType: values.iconChoice === 'custom' ? 'custom' : 'builtin',
        builtinIcon: values.iconChoice === 'custom' ? branding.builtinIcon : values.iconChoice,
      } })
      window.dispatchEvent(new Event('branding-updated'))
      message.success('面板品牌设置已保存')
      await reloadBranding()
    } catch (e) { message.error((e as Error).message) }
  }
  const uploadIcon = async (file: File) => {
    if (!['image/png', 'image/jpeg'].includes(file.type)) {
      message.error('图标仅支持 PNG 或 JPEG')
      return
    }
    if (file.size > 512 * 1024) {
      message.error('图标不能超过 512 KB')
      return
    }
    try {
      const data = new FormData()
      data.append('file', file)
      await api<BrandingSettings>({ method: 'POST', url: '/branding/icon', data })
      window.dispatchEvent(new Event('branding-updated'))
      message.success('自定义图标已上传并启用')
      await reloadBranding()
    } catch (e) { message.error((e as Error).message) }
  }
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
        { key: 'branding', label: <span><BgColorsOutlined /> 面板品牌</span>, children:
          <DataState loading={brandingLoading} error={brandingError} onRetry={reloadBranding}>
            <div className="branding-settings">
              <div className="tab-toolbar"><div>
                <Typography.Title level={4}>面板标识</Typography.Title>
                <Typography.Text type="secondary">修改左侧导航栏显示的品牌名称、Slogan 和图标，保存后立即生效。</Typography.Text>
              </div></div>
              <Form form={brandingForm} layout="vertical" initialValues={{
                title: branding.title,
                slogan: branding.slogan,
                iconChoice: branding.iconType === 'custom' ? 'custom' : branding.builtinIcon,
              }} key={`${branding.title}-${branding.iconType}-${branding.builtinIcon}-${branding.slogan}-${branding.customIconUrl || ''}`}
                className="branding-form">
                <Form.Item label="品牌名称" name="title" rules={[
                  { required: true, whitespace: true, message: '请输入品牌名称' },
                  { max: 40, message: '品牌名称不能超过 40 个字符' },
                ]}>
                  <Input showCount maxLength={40} placeholder="例如：PhotoLib" />
                </Form.Item>
                <Form.Item label="面板图标" name="iconChoice" rules={[{ required: true }]}>
                  <Select size="large" options={[
                    { value: 'camera', label: <Space><CameraOutlined />相机</Space> },
                    { value: 'aperture', label: <Space><AimOutlined />光圈</Space> },
                    { value: 'picture', label: <Space><PictureOutlined />图片</Space> },
                    { value: 'bulb', label: <Space><BulbOutlined />灵感</Space> },
                    { value: 'star', label: <Space><StarOutlined />星标</Space> },
                    ...(branding.customIconUrl ? [{ value: 'custom', label: '已上传的自定义图片' }] : []),
                  ]} />
                </Form.Item>
                <Form.Item label="上传自定义图标" extra="支持 PNG、JPEG；文件不超过 512 KB，尺寸不超过 1024 × 1024 像素。">
                  <Upload accept="image/png,image/jpeg" maxCount={1} showUploadList={false}
                    beforeUpload={file => { void uploadIcon(file); return false }}>
                    <Button icon={<UploadOutlined />}>选择图片并上传</Button>
                  </Upload>
                  {branding.customIconUrl && <div className="custom-icon-preview">
                    <img src={branding.customIconUrl} alt="当前自定义图标" />
                    <Typography.Text type="secondary">当前已上传的图标</Typography.Text>
                  </div>}
                </Form.Item>
                <Form.Item label="Slogan" name="slogan" rules={[
                  { required: true, whitespace: true, message: '请输入 Slogan' },
                  { max: 80, message: 'Slogan 不能超过 80 个字符' },
                ]}>
                  <Input showCount maxLength={80} placeholder="例如：摄影工作站" />
                </Form.Item>
                <Button type="primary" onClick={() => void saveBranding()}>保存品牌设置</Button>
              </Form>
            </div>
          </DataState> },
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
