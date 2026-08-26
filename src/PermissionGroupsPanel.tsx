import {
  Alert, App, Button, Card, Checkbox, Col, Descriptions, Form, Input, Modal, Row, Select, Space,
  Tag, Typography,
} from 'antd'
import {
  DeleteOutlined, EditOutlined, EyeOutlined, PlusOutlined, SaveOutlined, SafetyCertificateOutlined,
} from '@ant-design/icons'
import { useState } from 'react'
import { api, emptyPage } from './api'
import { DataState } from './components'
import { ContentFitTable } from './ContentFitTable'
import { useLoad } from './hooks'
import {
  AUTHORIZATION_ACTION_MIN_WIDTH,
  PERMISSION_GROUP_ACTION_MIN_WIDTH,
} from './tableActionWidths'
import type {
  Campus, DataScope, EntityId, PageData, PermissionCategoryDefinition, PermissionCode,
  PermissionGroup, User,
} from './types'

type GroupFormValues = {
  code: string
  name: string
  description?: string
  dataScope: Exclude<DataScope, 'NONE'>
}

type AuthorizationDraft = { permissionGroupId: EntityId; campusIds: EntityId[] }

export default function PermissionGroupsPanel() {
  const { message, modal } = App.useApp()
  const [form] = Form.useForm<GroupFormValues>()
  const [editing, setEditing] = useState<PermissionGroup | null>(null)
  const [groupOpen, setGroupOpen] = useState(false)
  const [permissions, setPermissions] = useState<PermissionCode[]>([])
  const [savingGroup, setSavingGroup] = useState(false)
  const [savingUserId, setSavingUserId] = useState<EntityId | null>(null)
  const [drafts, setDrafts] = useState<Record<EntityId, AuthorizationDraft>>({})
  const [search, setSearch] = useState('')
  const [permissionGroupFilter, setPermissionGroupFilter] = useState<EntityId | undefined>()
  const [userPage, setUserPage] = useState(1)
  const [detailGroupId, setDetailGroupId] = useState<EntityId | null>(null)
  const [memberPage, setMemberPage] = useState(1)

  const groupsState = useLoad(
    () => api<PermissionGroup[]>({ url: '/permission-groups' }), [] as PermissionGroup[], [],
  )
  const definitionsState = useLoad(
    () => api<PermissionCategoryDefinition[]>({ url: '/permission-groups/definitions' }),
    [] as PermissionCategoryDefinition[], [],
  )
  const usersState = useLoad(
    () => api<PageData<User>>({ url: '/users', params: {
      page: userPage, pageSize: 20, keyword: search || undefined,
      permissionGroupId: permissionGroupFilter,
    } }),
    emptyPage<User>(), [userPage, search, permissionGroupFilter],
  )
  const campusesState = useLoad(
    () => api<Campus[]>({ url: '/campuses', params: { enabled: true } }), [] as Campus[], [],
  )
  const detailGroupState = useLoad(
    () => detailGroupId
      ? api<PermissionGroup>({ url: `/permission-groups/${detailGroupId}` })
      : Promise.resolve(null),
    null as PermissionGroup | null, [detailGroupId],
  )
  const membersState = useLoad(
    () => detailGroupId
      ? api<PageData<User>>({ url: '/users', params: {
        page: memberPage, pageSize: 10, permissionGroupId: detailGroupId,
      } })
      : Promise.resolve(emptyPage<User>()),
    emptyPage<User>(), [detailGroupId, memberPage],
  )

  const openCreate = () => {
    setEditing(null)
    setPermissions([])
    form.resetFields()
    form.setFieldsValue({ dataScope: 'CAMPUS' })
    setGroupOpen(true)
  }

  const openEdit = (group: PermissionGroup) => {
    setEditing(group)
    setPermissions(group.permissions)
    form.setFieldsValue({
      code: group.code,
      name: group.name,
      description: group.description || undefined,
      dataScope: group.dataScope === 'GLOBAL' ? 'GLOBAL' : 'CAMPUS',
    })
    setGroupOpen(true)
  }

  const openDetails = (group: PermissionGroup) => {
    setMemberPage(1)
    setDetailGroupId(group.id)
  }

  const toggleCategory = (category: PermissionCategoryDefinition, checked: boolean) => {
    const categoryCodes = category.permissions.map(item => item.code)
    setPermissions(current => checked
      ? [...new Set([...current, ...categoryCodes])]
      : current.filter(code => !categoryCodes.includes(code)))
  }

  const saveGroup = async () => {
    const values = await form.validateFields()
    setSavingGroup(true)
    try {
      if (editing) {
        await api({ method: 'PUT', url: `/permission-groups/${editing.id}`, data: {
          name: values.name,
          description: values.description?.trim() || null,
          dataScope: values.dataScope,
          permissions,
          version: editing.version,
        } })
      } else {
        await api({ method: 'POST', url: '/permission-groups', data: {
          ...values,
          code: values.code.trim().toUpperCase(),
          description: values.description?.trim() || null,
          permissions,
        } })
      }
      message.success(editing ? '权限组已更新' : '权限组已创建')
      setGroupOpen(false)
      await groupsState.reload()
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setSavingGroup(false)
    }
  }

  const deleteGroup = (group: PermissionGroup) => modal.confirm({
    title: `删除权限组“${group.name}”？`,
    content: `当前有 ${group.memberCount} 个账号使用此组。删除后这些账号会自动转入“待分配权限”，仍可登录但无法进入系统。`,
    okText: '确认删除', okButtonProps: { danger: true }, cancelText: '取消',
    onOk: async () => {
      await api({ method: 'DELETE', url: `/permission-groups/${group.id}` })
      if (permissionGroupFilter === group.id) setPermissionGroupFilter(undefined)
      if (detailGroupId === group.id) setDetailGroupId(null)
      setDrafts({})
      await Promise.all([groupsState.reload(), usersState.reload()])
      message.success('权限组已删除，相关账号已降为最低权限组')
    },
  })

  const authorizationFor = (user: User): AuthorizationDraft => drafts[user.id] || {
    permissionGroupId: user.permissionGroupId || '',
    campusIds: user.campusIds || [],
  }

  const changeGroup = (user: User, permissionGroupId: EntityId) => {
    const group = groupsState.data.find(item => item.id === permissionGroupId)
    setDrafts(current => ({
      ...current,
      [user.id]: { permissionGroupId, campusIds: group?.dataScope === 'CAMPUS'
        ? authorizationFor(user).campusIds : [] },
    }))
  }

  const saveAuthorization = async (user: User) => {
    const draft = authorizationFor(user)
    const group = groupsState.data.find(item => item.id === draft.permissionGroupId)
    if (!group) return
    if (group.dataScope === 'CAMPUS' && !draft.campusIds.length) {
      message.warning('校区范围权限组必须至少选择一个校区')
      return
    }
    setSavingUserId(user.id)
    try {
      await api({ method: 'PUT', url: `/users/${user.id}/authorization`, data: {
        permissionGroupId: draft.permissionGroupId,
        campusIds: group.dataScope === 'CAMPUS' ? draft.campusIds : [],
        version: user.version,
      } })
      setDrafts(current => {
        const next = { ...current }
        delete next[user.id]
        return next
      })
      await Promise.all([usersState.reload(), groupsState.reload()])
      message.success(`已更新 ${user.displayName} 的权限组和校区权限`)
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setSavingUserId(null)
    }
  }

  return <Space direction="vertical" size="large" style={{ width: '100%' }}>
    <Card title={<Space><SafetyCertificateOutlined />权限组</Space>}
      extra={<Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增权限组</Button>}>
      <DataState loading={groupsState.loading} error={groupsState.error} onRetry={groupsState.reload}>
        <ContentFitTable rowKey="id" dataSource={groupsState.data} pagination={false} columns={[
          { title: '权限组', render: (_: unknown, group: PermissionGroup) => <div className="table-title">
            <Button type="link" style={{ height: 'auto', padding: 0 }} onClick={() => openDetails(group)}>
              <strong>{group.name}</strong>
            </Button><span>{group.code}</span>
          </div> },
          { title: '数据范围', dataIndex: 'dataScope', render: (scope: DataScope) =>
            <Tag color={scope === 'GLOBAL' ? 'blue' : scope === 'CAMPUS' ? 'cyan' : 'default'}>
              {scope === 'GLOBAL' ? '全局' : scope === 'CAMPUS' ? '授权校区' : '不可访问'}
            </Tag> },
          { title: '权限数', render: (_: unknown, group: PermissionGroup) => group.permissions.length },
          { title: '账号数', dataIndex: 'memberCount' },
          { title: '属性', render: (_: unknown, group: PermissionGroup) => <Space>
            {group.builtIn && <Tag>系统内置</Tag>}{group.lowest && <Tag color="warning">最低权限</Tag>}
          </Space> },
          { title: '操作', fixed: 'right' as const, minWidth: PERMISSION_GROUP_ACTION_MIN_WIDTH, className: 'table-action-cell', render: (_: unknown, group: PermissionGroup) => <Space>
            <Button icon={<EyeOutlined />} onClick={() => openDetails(group)}>查看详情</Button>
            <Button icon={<EditOutlined />} disabled={group.lowest} onClick={() => openEdit(group)}>编辑权限</Button>
            <Button danger icon={<DeleteOutlined />} disabled={group.builtIn} onClick={() => deleteGroup(group)}>删除</Button>
          </Space> },
        ]} />
      </DataState>
    </Card>

    <Card title="账户权限组与校区授权" extra={<Space wrap>
      <Select allowClear style={{ width: 220 }} placeholder="按权限组筛选账户"
        value={permissionGroupFilter}
        options={groupsState.data.map(group => ({ value: group.id, label: group.name }))}
        onChange={value => { setUserPage(1); setPermissionGroupFilter(value) }} />
      <Input.Search allowClear style={{ width: 280 }}
        placeholder="搜索姓名或账号" onSearch={value => { setUserPage(1); setSearch(value.trim()) }} onChange={event => {
          if (!event.target.value) { setUserPage(1); setSearch('') }
        }} />
    </Space>}>
      <Typography.Paragraph type="secondary">
        校区范围账号可同时授权多个校区；全局权限组不使用校区授权。此处变更会在账号下一次请求时立即生效。
      </Typography.Paragraph>
      <DataState loading={usersState.loading || campusesState.loading}
        error={usersState.error || campusesState.error} empty={!usersState.data.items.length}
        onRetry={() => { void usersState.reload(); void campusesState.reload() }}
        emptyText={search || permissionGroupFilter ? '没有符合筛选条件的账号' : '还没有可授权的账号'}
        emptyHint={search || permissionGroupFilter
          ? '换个关键词，或清掉权限组筛选再看看。'
          : '先在“账号管理”里创建成员账号，再回到这里分配权限组和校区。'}>
        <ContentFitTable rowKey="id" dataSource={usersState.data.items} pagination={{
          current: userPage, pageSize: 20, total: usersState.data.total,
          showTotal: total => `共 ${total} 个账号`, onChange: setUserPage,
        }} columns={[
          { title: '账号', render: (_: unknown, user: User) => <div className="table-title">
            <strong>{user.displayName}</strong><span>@{user.username}</span>
          </div> },
          { title: '权限组', width: 220, render: (_: unknown, user: User) => {
            const draft = authorizationFor(user)
            return <Select value={draft.permissionGroupId} style={{ width: '100%' }}
              options={groupsState.data.map(group => ({ value: group.id, label: group.name }))}
              onChange={value => changeGroup(user, value)} />
          } },
          { title: '授权校区', width: 360, render: (_: unknown, user: User) => {
            const draft = authorizationFor(user)
            const group = groupsState.data.find(item => item.id === draft.permissionGroupId)
            return <Select mode="multiple" maxTagCount="responsive" style={{ width: '100%' }}
              value={draft.campusIds} disabled={group?.dataScope !== 'CAMPUS'}
              placeholder={group?.dataScope === 'CAMPUS' ? '至少选择一个校区' : '全局权限组无需校区'}
              options={campusesState.data.map(campus => ({ value: campus.id, label: campus.name }))}
              onChange={campusIds => setDrafts(current => ({
                ...current, [user.id]: { ...draft, campusIds },
              }))} />
          } },
          { title: '操作', fixed: 'right' as const, minWidth: AUTHORIZATION_ACTION_MIN_WIDTH, className: 'table-action-cell', render: (_: unknown, user: User) => {
            const changed = Boolean(drafts[user.id])
            return <Button type="primary" icon={<SaveOutlined />} disabled={!changed || savingUserId !== null}
              loading={savingUserId === user.id} onClick={() => void saveAuthorization(user)}>保存授权</Button>
          } },
        ]} />
      </DataState>
    </Card>

    <Modal title={editing ? `编辑权限组：${editing.name}` : '新增权限组'} width={900}
      open={groupOpen} onCancel={() => setGroupOpen(false)} onOk={() => void saveGroup()}
      okText="保存" confirmLoading={savingGroup} destroyOnHidden>
      <Form form={form} layout="vertical" requiredMark={false}>
        {editing && !editing.builtIn && editing.memberCount > 0 && <Alert showIcon type="warning"
          style={{ marginBottom: 16 }}
          message={`当前权限组仍有 ${editing.memberCount} 个成员，数据范围不可修改`}
          description="请先在权限组详情中确认成员，并在下方“账户权限组与校区授权”中将全部成员迁移到其他权限组。" />}
        <Row gutter={16}>
          <Col xs={24} md={8}><Form.Item label="权限组代码" name="code" rules={[
            { required: true }, { pattern: /^[A-Za-z][A-Za-z0-9_]{2,63}$/, message: '3-64 位字母、数字或下划线' },
          ]}><Input disabled={Boolean(editing)} placeholder="例如 CAMPUS_EDITOR" /></Form.Item></Col>
          <Col xs={24} md={8}><Form.Item label="权限组名称" name="name" rules={[{ required: true }, { max: 100 }]}>
            <Input disabled={editing?.builtIn} /></Form.Item></Col>
          <Col xs={24} md={8}><Form.Item label="数据范围" name="dataScope" rules={[{ required: true }]}>
            <Select disabled={editing?.builtIn || Boolean(editing?.memberCount)} options={[
              { value: 'CAMPUS', label: '按账户授权校区' }, { value: 'GLOBAL', label: '全局数据' },
            ]} /></Form.Item></Col>
        </Row>
        <Form.Item label="说明" name="description" rules={[{ max: 500 }]}><Input.TextArea rows={2} disabled={editing?.builtIn} /></Form.Item>
        <Typography.Title level={5}>权限明细</Typography.Title>
        <Row gutter={[12, 12]}>
          {definitionsState.data.map(category => {
            const codes = category.permissions.map(item => item.code)
            const selectedCount = codes.filter(code => permissions.includes(code)).length
            return <Col xs={24} lg={12} key={category.code}>
              <Card size="small" title={<Checkbox checked={selectedCount === codes.length}
                indeterminate={selectedCount > 0 && selectedCount < codes.length}
                onChange={event => toggleCategory(category, event.target.checked)}>{category.label} · 全选</Checkbox>}>
                <Space direction="vertical">
                    {category.permissions.map(permission => <Checkbox key={permission.code}
                      checked={permissions.includes(permission.code)}
                      onChange={event => setPermissions(current => event.target.checked
                        ? [...new Set([...current, permission.code])]
                        : current.filter(code => code !== permission.code))}>
                      {permission.label}
                    </Checkbox>)}
                </Space>
              </Card>
            </Col>
          })}
        </Row>
      </Form>
    </Modal>

    <Modal title="权限组详情" width={820} open={detailGroupId !== null} footer={null}
      onCancel={() => setDetailGroupId(null)} destroyOnHidden>
      <DataState loading={detailGroupState.loading || membersState.loading}
        error={detailGroupState.error || membersState.error}
        onRetry={() => { void detailGroupState.reload(); void membersState.reload() }}>
        {detailGroupState.data && <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }}>
            <Descriptions.Item label="权限组">{detailGroupState.data.name}</Descriptions.Item>
            <Descriptions.Item label="代码"><Typography.Text code>{detailGroupState.data.code}</Typography.Text></Descriptions.Item>
            <Descriptions.Item label="数据范围">
              {detailGroupState.data.dataScope === 'GLOBAL' ? '全局数据'
                : detailGroupState.data.dataScope === 'CAMPUS' ? '按账户授权校区' : '不可访问'}
            </Descriptions.Item>
            <Descriptions.Item label="成员数">{detailGroupState.data.memberCount}</Descriptions.Item>
            <Descriptions.Item label="说明" span={2}>{detailGroupState.data.description || '-'}</Descriptions.Item>
          </Descriptions>
          <div>
            <Typography.Title level={5}>权限明细</Typography.Title>
            <Space wrap>
              {detailGroupState.data.permissions.length
                ? detailGroupState.data.permissions.map(code => <Tag key={code} color="blue">
                  {definitionsState.data.flatMap(category => category.permissions)
                    .find(permission => permission.code === code)?.label || code}
                </Tag>)
                : <Typography.Text type="secondary">未授予业务权限</Typography.Text>}
            </Space>
          </div>
          <div>
            <Typography.Title level={5}>权限组成员</Typography.Title>
            <ContentFitTable rowKey="id" size="small" dataSource={membersState.data.items} pagination={{
              current: memberPage, pageSize: 10, total: membersState.data.total,
              showTotal: total => `共 ${total} 个成员`, onChange: setMemberPage,
            }} columns={[
              { title: '成员', render: (_: unknown, user: User) => <div className="table-title">
                <strong>{user.displayName}</strong><span>@{user.username}</span>
              </div> },
              { title: '授权校区', dataIndex: 'campusIds', render: (ids: EntityId[] = []) => ids.length
                ? ids.map(id => campusesState.data.find(campus => campus.id === id)?.name || `#${id}`).join('、') : '-' },
              { title: '状态', dataIndex: 'enabled', render: (enabled: boolean) =>
                <Tag color={enabled ? 'green' : 'default'}>{enabled ? '正常' : '已停用'}</Tag> },
            ]} />
          </div>
        </Space>}
      </DataState>
    </Modal>
  </Space>
}
