import {
  App, Button, Form, Input, Modal, Select, Space, Switch, Tag, Typography,
} from 'antd'
import { EditOutlined, PlusOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { api, emptyPage } from '../../api'
import type { Campus, PageData, PermissionGroup, User } from '../../types'
import { DataState } from '../../components'
import { ContentFitTable } from '../../ContentFitTable'
import { useLoad } from '../../hooks'
import UserAvatar from '../../UserAvatar'
import { USER_ACTION_MIN_WIDTH } from '../../tableActionWidths'
import { GRID_PAGE_SIZES, clientTablePagination } from '../../pagination'

interface AccountFormValues {
  email?: string
  wecomUserid?: string
}

/** 企业微信通讯录对 userid 的约束：数字、字母和 _-@. 四种半角符号，1~64 字节。 */
const WECOM_USERID_PATTERN = /^[A-Za-z0-9_@.-]{1,64}$/

/** 管理员面板 · 账号管理：创建、启停、改邮箱 / 企业微信、重置密码和删除成员账号。 */
export default function UsersPage() {
  const { message, modal } = App.useApp()
  const [userForm] = Form.useForm()
  const [accountForm] = Form.useForm<AccountFormValues>()
  const [userOpen, setUserOpen] = useState(false)
  const [editingUser, setEditingUser] = useState<User | null>(null)
  const [userSearchText, setUserSearchText] = useState('')
  const [userKeyword, setUserKeyword] = useState('')
  const { data: users, loading, error, reload } = useLoad(
    () => api<PageData<User>>({
      url: '/users',
      params: { page: 1, pageSize: 100, keyword: userKeyword || undefined },
    }), emptyPage<User>(), [userKeyword],
  )
  const { data: campuses } = useLoad(
    () => api<Campus[]>({ url: '/campuses' }), [] as Campus[], [],
  )
  const { data: permissionGroups } = useLoad(
    () => api<PermissionGroup[]>({ url: '/permission-groups' }), [] as PermissionGroup[], [],
  )
  const createUser = async () => {
    try {
      const result = await api<{ user: User; initialPassword: string }>({ method: 'POST', url: '/users', data: await userForm.validateFields() })
      setUserOpen(false); userForm.resetFields(); await reload()
      modal.success({ title: '账号创建成功', content: <div><p>请通过安全渠道把初始密码交给用户，此密码只显示一次。</p><Typography.Text copyable code>{result.initialPassword}</Typography.Text></div> })
    } catch (e) { message.error((e as Error).message) }
  }
  const toggleUser = async (user: User) => {
    try {
      await api({ method: 'POST', url: `/users/${user.id}/${user.enabled ? 'disable' : 'enable'}` })
      message.success(user.enabled ? '账号已停用' : '账号已启用'); await reload()
    } catch (e) { message.error((e as Error).message) }
  }
  const openAccountEditor = (user: User) => {
    setEditingUser(user)
    accountForm.setFieldsValue({ email: user.email, wecomUserid: user.wecomUserid ?? undefined })
  }
  const updateUserAccount = async () => {
    if (!editingUser || editingUser.version == null) return
    try {
      const values = await accountForm.validateFields()
      await api<User>({
        method: 'PUT',
        url: `/users/${editingUser.id}`,
        data: {
          displayName: editingUser.displayName,
          permissionGroupId: editingUser.permissionGroupId,
          campusIds: editingUser.campusIds || [],
          role: null,
          campusId: editingUser.campusId,
          phone: editingUser.phone,
          email: values.email?.trim() || null,
          wecomUserid: values.wecomUserid?.trim() || null,
          enabled: editingUser.enabled ?? true,
          version: editingUser.version,
        },
      })
      message.success('账号信息已更新')
      setEditingUser(null)
      accountForm.resetFields()
      await reload()
    } catch (e) { message.error((e as Error).message) }
  }
  const deleteUser = (user: User) => {
    modal.confirm({
      title: '删除账号', okText: '删除', okButtonProps: { danger: true }, cancelText: '取消',
      content: <span>确定删除账号 <strong>{user.displayName}</strong>（@{user.username}）吗？账号将被停用并从列表中移除，其历史记录仍会保留。</span>,
      onOk: async () => {
        try {
          await api({ method: 'DELETE', url: `/users/${user.id}` })
          message.success('账号已删除'); await reload()
        } catch (e) { message.error((e as Error).message); throw e }
      },
    })
  }
  return <>
    <div className="tab-toolbar"><div><Typography.Title level={4}>成员账号</Typography.Title><Typography.Text type="secondary">账号由管理员创建，或由同学持注册码申请、经审核通过后生成。</Typography.Text></div>
      <Space wrap>
        <Input.Search
          allowClear
          value={userSearchText}
          placeholder="搜索成员姓名、账号或邮箱"
          style={{ width: 280 }}
          onChange={event => {
            setUserSearchText(event.target.value)
            if (!event.target.value) setUserKeyword('')
          }}
          onSearch={value => setUserKeyword(value.trim())}
        />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setUserOpen(true)}>创建账号</Button>
      </Space></div>
    <DataState loading={loading} error={error} empty={!users.items.length} onRetry={reload}
      emptyText={userKeyword ? `没有匹配“${userKeyword}”的账号` : '还没有创建任何成员账号'}
      emptyHint={userKeyword
        ? '姓名、账号和邮箱都会被搜索，换个词再试试。'
        : '可以在这里创建账号，也可以生成注册码让同学自助申请。'}>
      <ContentFitTable rowKey="id" dataSource={users.items}
        pagination={clientTablePagination(users.items.length,
          { defaultPageSize: 12, sizes: GRID_PAGE_SIZES, showTotal: total => `共 ${total} 个账号` })} columns={[
        { title: '成员', render: (_, item) => <Space>
          <UserAvatar size={38} avatarUrl={item.avatarUrl} label={item.displayName}
            style={{ background: '#edf3f0', color: '#28594f' }} />
          <div className="table-title"><strong>{item.displayName}</strong><span>@{item.username}</span></div>
        </Space> },
        { title: '权限组', dataIndex: 'permissionGroupName', render: value => <Tag variant="filled">{value || '待分配'}</Tag> },
        { title: '授权校区', dataIndex: 'campusIds', render: (values: string[] = []) => values.length
          ? values.map(value => campuses.find(c => c.id === value)?.name || `#${value}`).join('、') : '-' },
        { title: '联系方式', render: (_, item) => item.email || item.phone || '-' },
        // 没绑企微 = 收不到任何外发通知，这一列让管理员一眼看出还差谁。
        { title: '通知', render: (_, item) => item.wecomUserid
          ? <Tag color="green" variant="filled">企微 {item.wecomUserid}</Tag>
          : <Tag variant="filled">仅站内信</Tag> },
        { title: '状态', dataIndex: 'enabled', render: value => <Tag color={value ? 'green' : 'default'} variant="filled">{value ? '正常' : '已停用'}</Tag> },
        { title: '操作', fixed: 'right', minWidth: USER_ACTION_MIN_WIDTH, className: 'table-action-cell', render: (_, item) => <Space><Switch size="small" checked={item.enabled} onChange={() => void toggleUser(item)} /><Button type="link" icon={<EditOutlined />} onClick={() => openAccountEditor(item)}>修改账号</Button><Button type="link" onClick={async () => {
          try { const result = await api<{ initialPassword: string }>({ method: 'PUT', url: `/users/${item.id}/password` }); modal.warning({ title: '密码已重置', content: <Typography.Text copyable code>{result.initialPassword}</Typography.Text> }) } catch (e) { message.error((e as Error).message) }
        }}>重置密码</Button><Button type="link" danger onClick={() => deleteUser(item)}>删除</Button></Space> },
      ]} />
    </DataState>
    <Modal title="创建成员账号" open={userOpen} onCancel={() => setUserOpen(false)} onOk={createUser} okText="创建账号">
      <Form form={userForm} layout="vertical" requiredMark={false}>
        <Form.Item label="登录账号" name="username" rules={[{ required: true }, { pattern: /^[A-Za-z0-9_.-]{3,64}$/, message: '3-64 位字母、数字或 ._-' }]}><Input /></Form.Item>
        <Form.Item label="显示姓名" name="displayName" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item label="权限组" name="permissionGroupId" rules={[{ required: true }]}>
          <Select options={permissionGroups.map(group => ({ value: group.id, label: group.name }))} />
        </Form.Item>
        <Form.Item noStyle shouldUpdate={(prev, next) => prev.permissionGroupId !== next.permissionGroupId}>
          {({ getFieldValue }) => permissionGroups.find(group => group.id === getFieldValue('permissionGroupId'))?.dataScope === 'CAMPUS'
            && <Form.Item label="授权校区" name="campusIds" preserve={false}
              rules={[{ required: true, message: '请至少选择一个校区' }]}>
              <Select mode="multiple" options={campuses.filter(c => c.enabled).map(c => ({ value: c.id, label: c.name }))} />
            </Form.Item>}
        </Form.Item>
        <Form.Item label="邮箱" name="email" extra="填写后，用户可使用该邮箱登录。" rules={[{ type: 'email' }, { max: 255 }]}><Input /></Form.Item>
        <Form.Item label="手机号" name="phone"><Input /></Form.Item>
        <Form.Item label="企业微信 UserID" name="wecomUserid"
          extra="企业微信管理后台「通讯录」中该成员的账号；留空则该成员只收站内信。"
          rules={[{ pattern: WECOM_USERID_PATTERN, message: '只能包含字母、数字和 _-@. ，且不超过 64 个字符' }]}>
          <Input placeholder="例如 ZhangSan" />
        </Form.Item>
      </Form>
    </Modal>
    <Modal
      title={`修改 ${editingUser?.displayName || ''} 的账号信息`}
      open={editingUser !== null}
      onCancel={() => { setEditingUser(null); accountForm.resetFields() }}
      onOk={() => void updateUserAccount()}
      okText="保存"
    >
      <Typography.Paragraph type="secondary">邮箱用于登录，不区分大小写，留空则取消邮箱登录；企业微信 UserID 用于接收通知，留空则该成员只收站内信。</Typography.Paragraph>
      <Form form={accountForm} layout="vertical" requiredMark={false}>
        <Form.Item label="邮箱" name="email" rules={[{ type: 'email', message: '请输入有效的邮箱地址' }, { max: 255, message: '邮箱不能超过 255 个字符' }]}>
          <Input placeholder="例如 name@example.com" autoComplete="email" />
        </Form.Item>
        <Form.Item label="企业微信 UserID" name="wecomUserid"
          extra="企业微信管理后台「通讯录」中该成员的账号，注意不是姓名或手机号。"
          rules={[{ pattern: WECOM_USERID_PATTERN, message: '只能包含字母、数字和 _-@. ，且不超过 64 个字符' }]}>
          <Input placeholder="例如 ZhangSan" autoComplete="off" />
        </Form.Item>
      </Form>
    </Modal>
  </>
}
