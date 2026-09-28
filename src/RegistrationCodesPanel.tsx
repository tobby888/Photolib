import {
  App, Button, DatePicker, Form, Input, InputNumber, Modal, Select, Space, Switch, Tag, Typography,
} from 'antd'
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import { useState } from 'react'
import { api, emptyPage } from './api'
import { DataState } from './components'
import { ContentFitTable } from './ContentFitTable'
import { useLoad } from './hooks'
import { serverTablePagination, turnPage } from './pagination'
import type { Campus, PageData, PermissionGroup, RegistrationCode, RegistrationCodeStatus } from './types'

/** 与后端 RegistrationCodeController.MAX_USES 一致。 */
const MAX_USES = 1000
const DATE_TIME = 'YYYY-MM-DD HH:mm'

const statusMeta: Record<RegistrationCodeStatus, { label: string; color?: string }> = {
  ACTIVE: { label: '可使用', color: 'green' },
  NOT_STARTED: { label: '未开始', color: 'blue' },
  EXPIRED: { label: '已过期' },
  EXHAUSTED: { label: '名额已满', color: 'orange' },
  DISABLED: { label: '已停用' },
  INVALID: { label: '权限组已删除', color: 'red' },
}

/** 注册码按 4 位一组显示，同学抄写、口头转述都不容易错；后端提交时会去掉分隔符。 */
export function formatRegistrationCode(code: string) {
  return code.replace(/(.{4})(?=.)/g, '$1-')
}

interface CodeFormValues {
  name: string
  permissionGroupId?: string
  campusIds?: string[]
  maxUses: number
  validity: [Dayjs, Dayjs]
  enabled?: boolean
}

const toLocalDateTime = (value: Dayjs) => value.format('YYYY-MM-DDTHH:mm:ss')

export default function RegistrationCodesPanel({ permissionGroups, campuses }: {
  permissionGroups: PermissionGroup[]
  campuses: Campus[]
}) {
  const { message, modal } = App.useApp()
  const [createForm] = Form.useForm<CodeFormValues>()
  const [editForm] = Form.useForm<CodeFormValues>()
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<RegistrationCode | null>(null)
  const [saving, setSaving] = useState(false)
  const [searchText, setSearchText] = useState('')
  const [filters, setFilters] = useState({ keyword: '', page: 1, pageSize: 10 })
  const { data, loading, error, reload } = useLoad(
    () => api<PageData<RegistrationCode>>({
      url: '/registration-codes',
      params: { page: filters.page, pageSize: filters.pageSize, keyword: filters.keyword || undefined },
    }), emptyPage<RegistrationCode>(), [filters],
  )
  // 系统管理员组不能经注册码授予（后端同样拒绝），下拉框里直接不给。
  const grantableGroups = permissionGroups.filter(group => group.code !== 'ADMIN')
  const groupScope = (groupId?: string) => permissionGroups.find(group => group.id === groupId)?.dataScope
  const campusName = (id: string) => campuses.find(campus => campus.id === id)?.name || `#${id}`
  const registerUrl = `${window.location.origin}/register`

  const openCreate = () => {
    createForm.resetFields()
    createForm.setFieldsValue({ maxUses: 30, validity: [dayjs().startOf('minute'), dayjs().add(7, 'day').endOf('day')] })
    setCreating(true)
  }

  const create = async () => {
    const values = await createForm.validateFields()
    setSaving(true)
    try {
      const created = await api<RegistrationCode>({
        method: 'POST', url: '/registration-codes', data: {
          name: values.name,
          permissionGroupId: values.permissionGroupId,
          campusIds: groupScope(values.permissionGroupId) === 'CAMPUS' ? values.campusIds : [],
          maxUses: values.maxUses,
          validFrom: toLocalDateTime(values.validity[0]),
          validUntil: toLocalDateTime(values.validity[1]),
        },
      })
      setCreating(false)
      setFilters(current => ({ ...current, page: 1 }))
      modal.success({
        title: '注册码已生成',
        width: 480,
        content: <Space orientation="vertical" size={12}>
          <Typography.Text type="secondary">把注册码和注册地址一起发给同学。提交的注册申请要在「注册审核」里通过后才生效。</Typography.Text>
          <Typography.Text copyable={{ text: created.code }} code strong style={{ fontSize: 18 }}>
            {formatRegistrationCode(created.code)}
          </Typography.Text>
          <Typography.Text copyable>{registerUrl}</Typography.Text>
        </Space>,
      })
    } catch (failure) {
      message.error((failure as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const openEdit = (item: RegistrationCode) => {
    editForm.setFieldsValue({
      name: item.name, maxUses: item.maxUses, enabled: item.enabled,
      validity: [dayjs(item.validFrom), dayjs(item.validUntil)],
    })
    setEditing(item)
  }

  const saveEdit = async () => {
    if (!editing) return
    const values = await editForm.validateFields()
    setSaving(true)
    try {
      await api<RegistrationCode>({
        method: 'PUT', url: `/registration-codes/${editing.id}`, data: {
          name: values.name,
          maxUses: values.maxUses,
          validFrom: toLocalDateTime(values.validity[0]),
          validUntil: toLocalDateTime(values.validity[1]),
          enabled: values.enabled,
          version: editing.version,
        },
      })
      setEditing(null)
      message.success('注册码已更新')
      void reload()
    } catch (failure) {
      message.error((failure as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const toggle = async (item: RegistrationCode) => {
    try {
      await api<RegistrationCode>({
        method: 'PUT', url: `/registration-codes/${item.id}`, data: {
          name: item.name, maxUses: item.maxUses, validFrom: item.validFrom, validUntil: item.validUntil,
          enabled: !item.enabled, version: item.version,
        },
      })
      message.success(item.enabled ? '注册码已停用' : '注册码已启用')
      void reload()
    } catch (failure) {
      message.error((failure as Error).message)
    }
  }

  const remove = (item: RegistrationCode) => modal.confirm({
    title: `删除注册码「${item.name}」？`,
    content: '只有还没有人用过的注册码可以删除；已经有人提交过申请的，请改为停用。',
    okText: '删除', okButtonProps: { danger: true },
    onOk: async () => {
      try {
        await api<void>({ method: 'DELETE', url: `/registration-codes/${item.id}` })
        message.success('注册码已删除')
        void reload()
      } catch (failure) {
        message.error((failure as Error).message)
        throw failure
      }
    },
  })

  const validityField = <Form.Item label="有效期" name="validity" extra="在这段时间内可以用这个码提交注册申请。"
    rules={[{ required: true, message: '请选择有效期' }]}>
    <DatePicker.RangePicker showTime={{ format: 'HH:mm' }} format={DATE_TIME} style={{ width: '100%' }} />
  </Form.Item>
  const maxUsesField = <Form.Item label="可注册人数" name="maxUses" extra="待审核和已通过的申请都占名额，驳回会归还名额。"
    rules={[{ required: true, message: '请输入可注册人数' }]}>
    <InputNumber min={1} max={MAX_USES} precision={0} style={{ width: '100%' }} />
  </Form.Item>

  return <>
    <div className="tab-toolbar">
      <div>
        <Typography.Title level={4}>注册码</Typography.Title>
        <Typography.Text type="secondary">持码的同学可以自助提交注册申请，经「注册审核」通过后账号才生效。</Typography.Text>
      </div>
      <Space wrap>
        <Input.Search allowClear value={searchText} placeholder="搜索名称或注册码" style={{ width: 240 }}
          onChange={event => {
            setSearchText(event.target.value)
            if (!event.target.value) setFilters(current => ({ ...current, keyword: '', page: 1 }))
          }}
          onSearch={value => setFilters(current => ({ ...current, keyword: value.trim(), page: 1 }))} />
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>生成注册码</Button>
      </Space>
    </div>
    <DataState loading={loading} error={error} empty={!data.items.length} onRetry={reload}
      emptyText={filters.keyword ? `没有匹配“${filters.keyword}”的注册码` : '还没有生成注册码'}
      emptyHint={filters.keyword ? '名称和注册码都会被搜索，换个词再试试。' : '生成一个注册码发给同学，他们就能自助提交注册申请。'}>
      <ContentFitTable rowKey="id" dataSource={data.items}
        pagination={serverTablePagination(filters, data.total,
          (page, pageSize) => setFilters(current => turnPage(current, page, pageSize)),
          { showTotal: total => `共 ${total} 个注册码` })}
        columns={[
          { title: '名称', dataIndex: 'name' },
          { title: '注册码', dataIndex: 'code', render: (value: string) =>
            <Typography.Text code copyable={{ text: value }}>{formatRegistrationCode(value)}</Typography.Text> },
          { title: '权限组', render: (_, item: RegistrationCode) => <Space size={4} wrap>
            <Tag variant="filled">{item.permissionGroupName || '已删除'}</Tag>
            {!!item.campusIds.length && <Typography.Text type="secondary">
              {item.campusIds.map(campusName).join('、')}
            </Typography.Text>}
          </Space> },
          { title: '名额', render: (_, item: RegistrationCode) => <span>
            {item.usedCount} / {item.maxUses}
            {item.pendingCount > 0 && <Typography.Text type="warning">（{item.pendingCount} 待审核）</Typography.Text>}
          </span> },
          { title: '有效期', render: (_, item: RegistrationCode) =>
            `${dayjs(item.validFrom).format(DATE_TIME)} ~ ${dayjs(item.validUntil).format(DATE_TIME)}` },
          { title: '状态', dataIndex: 'status', render: (value: RegistrationCodeStatus) =>
            <Tag color={statusMeta[value].color} variant="filled">{statusMeta[value].label}</Tag> },
          { title: '操作', fixed: 'right' as const, className: 'table-action-cell', render: (_, item: RegistrationCode) => <Space>
            <Switch size="small" checked={item.enabled} onChange={() => void toggle(item)} />
            <Button type="link" icon={<EditOutlined />} onClick={() => openEdit(item)}>修改</Button>
            <Button type="link" danger icon={<DeleteOutlined />} onClick={() => remove(item)}>删除</Button>
          </Space> },
        ]} />
    </DataState>
    <Modal title="生成注册码" open={creating} onCancel={() => setCreating(false)} onOk={() => void create()}
      okText="生成" confirmLoading={saving} destroyOnHidden>
      <Form form={createForm} layout="vertical" requiredMark={false}>
        <Form.Item label="名称" name="name" extra="方便自己区分，例如「2026 秋季招新」。"
          rules={[{ required: true, whitespace: true, message: '请输入名称' }, { max: 100 }]}>
          <Input />
        </Form.Item>
        <Form.Item label="注册后的权限组" name="permissionGroupId" rules={[{ required: true, message: '请选择权限组' }]}>
          <Select options={grantableGroups.map(group => ({ value: group.id, label: group.name }))} />
        </Form.Item>
        <Form.Item noStyle shouldUpdate={(prev, next) => prev.permissionGroupId !== next.permissionGroupId}>
          {({ getFieldValue }) => groupScope(getFieldValue('permissionGroupId')) === 'CAMPUS'
            && <Form.Item label="授权校区" name="campusIds" preserve={false}
              rules={[{ required: true, message: '请至少选择一个校区' }]}>
              <Select mode="multiple" options={campuses.filter(c => c.enabled).map(c => ({ value: c.id, label: c.name }))} />
            </Form.Item>}
        </Form.Item>
        {maxUsesField}
        {validityField}
      </Form>
    </Modal>
    <Modal title={`修改注册码「${editing?.name || ''}」`} open={editing !== null} onCancel={() => setEditing(null)}
      onOk={() => void saveEdit()} okText="保存" confirmLoading={saving} destroyOnHidden>
      <Typography.Paragraph type="secondary">
        权限组在生成时确定，不能修改；需要换权限组的话，请停用这个码并重新生成一个。
      </Typography.Paragraph>
      <Form form={editForm} layout="vertical" requiredMark={false}>
        <Form.Item label="名称" name="name" rules={[{ required: true, whitespace: true, message: '请输入名称' }, { max: 100 }]}>
          <Input />
        </Form.Item>
        {maxUsesField}
        {validityField}
        <Form.Item label="启用" name="enabled" valuePropName="checked"><Switch /></Form.Item>
      </Form>
    </Modal>
  </>
}
