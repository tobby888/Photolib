import {
  Alert, App, Button, Card, Descriptions, Empty, Form, Input, InputNumber, Modal, Popconfirm, Select, Space,
  Switch, Tag, Tooltip, Typography,
} from 'antd'
import {
  ArrowDownOutlined, ArrowUpOutlined, DeleteOutlined, EditOutlined, PlusOutlined, SendOutlined,
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import dayjs from 'dayjs'
import { useRef, useState } from 'react'
import { api } from '../../api'
import { DataState } from '../../components'
import { ContentFitTable } from '../../ContentFitTable'
import { useLoad } from '../../hooks'
import {
  accountHealth, defaultPortForSecurity, moveAccountIds, needsSeparateFromAddress,
  portAfterSecurityChange, SECURITY_LABELS, SECURITY_SHORT_LABELS,
} from '../../smtpAccounts'
import type { SmtpAccount, SmtpAccountPayload, SmtpOverview, SmtpSecurity } from '../../smtpAccounts'

interface FormValues {
  name: string
  host: string
  port: number
  username: string
  password?: string
  fromAddress?: string
  fromName?: string
  security: SmtpSecurity
  enabled: boolean
}

export default function SmtpAccountsPanel({ onChanged }: { onChanged?: () => void }) {
  const { message } = App.useApp()
  const overview = useLoad(() => api<SmtpOverview>({ url: '/smtp-accounts' }), null as SmtpOverview | null, [])
  const [editing, setEditing] = useState<SmtpAccount | 'new' | null>(null)
  const [testing, setTesting] = useState<SmtpAccount | null>(null)
  const [testTo, setTestTo] = useState('')
  const [testSending, setTestSending] = useState(false)
  const [saving, setSaving] = useState(false)
  const [busyId, setBusyId] = useState<number | null>(null)
  const [reordering, setReordering] = useState(false)
  const prevSecurity = useRef<SmtpSecurity>('SSL')
  const [form] = Form.useForm<FormValues>()
  const watchedUsername = Form.useWatch('username', form)
  const watchedFromAddress = Form.useWatch('fromAddress', form)
  const requireFromAddress = needsSeparateFromAddress(watchedUsername, watchedFromAddress)

  const refresh = () => {
    overview.reload()
    onChanged?.()
  }

  const openCreate = () => {
    form.resetFields()
    prevSecurity.current = 'SSL'
    form.setFieldsValue({
      name: '',
      host: '',
      port: defaultPortForSecurity('SSL'),
      username: '',
      password: '',
      fromAddress: '',
      fromName: '',
      security: 'SSL',
      enabled: true,
    })
    setEditing('new')
  }

  const openEdit = (account: SmtpAccount) => {
    form.resetFields()
    prevSecurity.current = account.security
    form.setFieldsValue({
      name: account.name,
      host: account.host,
      port: account.port,
      username: account.username,
      password: '',
      fromAddress: account.fromAddress ?? '',
      fromName: account.fromName ?? '',
      security: account.security,
      enabled: account.enabled,
    })
    setEditing(account)
  }

  const submitForm = async () => {
    let values: FormValues
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    setSaving(true)
    try {
      const payload: SmtpAccountPayload = {
        name: values.name.trim(),
        host: values.host.trim(),
        port: Number(values.port),
        username: values.username.trim(),
        password: values.password?.trim() ? values.password : undefined,
        fromAddress: values.fromAddress?.trim() || undefined,
        fromName: values.fromName?.trim() || undefined,
        security: values.security,
        enabled: values.enabled,
      }
      if (editing === 'new') {
        await api<SmtpAccount>({ method: 'POST', url: '/smtp-accounts', data: payload })
        message.success('已添加发信邮箱，下一封邮件即会生效')
      } else if (editing) {
        await api<SmtpAccount>({ method: 'PUT', url: `/smtp-accounts/${editing.id}`, data: payload })
        message.success('已保存发信邮箱配置')
      }
      setEditing(null)
      refresh()
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const toggleEnabled = async (account: SmtpAccount, enabled: boolean) => {
    setBusyId(account.id)
    try {
      const payload: SmtpAccountPayload = {
        name: account.name,
        host: account.host,
        port: account.port,
        username: account.username,
        fromAddress: account.fromAddress ?? undefined,
        fromName: account.fromName ?? undefined,
        security: account.security,
        enabled,
      }
      await api<SmtpAccount>({ method: 'PUT', url: `/smtp-accounts/${account.id}`, data: payload })
      message.success(enabled ? `已启用「${account.name}」` : `已停用「${account.name}」`)
      refresh()
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setBusyId(null)
    }
  }

  const removeAccount = async (account: SmtpAccount) => {
    setBusyId(account.id)
    try {
      await api<void>({ method: 'DELETE', url: `/smtp-accounts/${account.id}` })
      message.success(`已删除「${account.name}」`)
      refresh()
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setBusyId(null)
    }
  }

  const move = async (index: number, delta: -1 | 1) => {
    const accounts = overview.data?.accounts ?? []
    const ids = moveAccountIds(accounts, index, delta)
    if (!ids) return
    setReordering(true)
    try {
      const next = await api<SmtpAccount[]>({ method: 'PUT', url: '/smtp-accounts/order', data: { ids } })
      overview.setData(prev => (prev ? { ...prev, accounts: next } : prev))
      onChanged?.()
    } catch (error) {
      message.error((error as Error).message)
      overview.reload()
    } finally {
      setReordering(false)
    }
  }

  const sendTest = async () => {
    if (!testing) return
    setTestSending(true)
    try {
      const result = await api<{ email: string; fromAddress: string }>({
        method: 'POST',
        url: `/smtp-accounts/${testing.id}/test`,
        data: testTo.trim() ? { to: testTo.trim() } : {},
      })
      message.success(`测试邮件已通过 ${result.fromAddress} 发往 ${result.email}`)
      setTesting(null)
      setTestTo('')
      overview.reload()
    } catch (error) {
      message.error((error as Error).message)
      overview.reload()
    } finally {
      setTestSending(false)
    }
  }

  const accounts = overview.data?.accounts ?? []

  const columns: ColumnsType<SmtpAccount> = [
    {
      title: '顺序',
      key: 'order',
      render: (_, record, index) => <Space size={4}>
        <Tag>{index + 1}</Tag>
        <Tooltip title="上移">
          <Button size="small" type="text" icon={<ArrowUpOutlined />}
            disabled={index === 0 || reordering} onClick={() => void move(index, -1)} />
        </Tooltip>
        <Tooltip title="下移">
          <Button size="small" type="text" icon={<ArrowDownOutlined />}
            disabled={index === accounts.length - 1 || reordering} onClick={() => void move(index, 1)} />
        </Tooltip>
      </Space>,
    },
    {
      title: '邮箱名称',
      key: 'name',
      render: (_, record) => <Space orientation="vertical" size={2}>
        <Space size={6} wrap>
          <Typography.Text strong>{record.name}</Typography.Text>
          {record.fromName && <Tag>{record.fromName}</Tag>}
          {!record.passwordEncrypted && <Tooltip title="该密码当前未加密保存；在服务器配置 SMTP_ENCRYPTION_KEY / MFA_ENCRYPTION_KEY 后重新保存密码即可加密">
            <Tag color="gold">未加密</Tag>
          </Tooltip>}
        </Space>
      </Space>,
    },
    {
      title: 'SMTP 服务器',
      key: 'server',
      render: (_, record) => <Space size={6}>
        <Typography.Text code>{`${record.host}:${record.port}`}</Typography.Text>
        <Tag color={record.security === 'NONE' ? 'warning' : 'blue'}>
          {SECURITY_SHORT_LABELS[record.security] ?? record.security}
        </Tag>
      </Space>,
    },
    {
      title: '发件地址 / 登录名',
      key: 'identity',
      render: (_, record) => {
        const separate = record.sender !== record.username
        return <Space orientation="vertical" size={0}>
          <Typography.Text>{separate ? `发件：${record.sender}` : record.sender}</Typography.Text>
          {separate && <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {`登录：${record.username}`}
          </Typography.Text>}
        </Space>
      },
    },
    {
      title: '最近状态',
      key: 'health',
      render: (_, record) => {
        const health = accountHealth(record)
        const tag = health === 'disabled' ? <Tag>已停用</Tag>
          : health === 'healthy' ? <Tag color="success">最近成功</Tag>
            : health === 'failed' ? <Tag color="error">最近失败</Tag>
              : <Tag color="default">尚未发信</Tag>
        return <Space orientation="vertical" size={2}>
          <Space size={6} wrap>
            {tag}
            {record.lastSuccessAt && <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              成功 {dayjs(record.lastSuccessAt).format('MM-DD HH:mm')}
            </Typography.Text>}
          </Space>
          {record.lastFailureAt && <Tooltip title={record.lastError || '发送失败'}>
            <Typography.Text type="danger" style={{ fontSize: 12, maxWidth: 260 }} ellipsis>
              {dayjs(record.lastFailureAt).format('MM-DD HH:mm')}：{record.lastError || '发送失败'}
            </Typography.Text>
          </Tooltip>}
        </Space>
      },
    },
    {
      title: '启用',
      key: 'enabled',
      render: (_, record) => <Switch size="small" checked={record.enabled} loading={busyId === record.id}
        onChange={checked => void toggleEnabled(record, checked)} />,
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'end',
      render: (_, record) => <Space size={4}>
        <Button size="small" icon={<SendOutlined />} onClick={() => { setTesting(record); setTestTo('') }}>
          测试
        </Button>
        <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>
          编辑
        </Button>
        <Popconfirm title={`删除发信邮箱「${record.name}」？`} description="删除后无法恢复，将不再参与发信。"
          okText="删除" okButtonProps={{ danger: true }} onConfirm={() => void removeAccount(record)}>
          <Button size="small" danger icon={<DeleteOutlined />} loading={busyId === record.id}>
            删除
          </Button>
        </Popconfirm>
      </Space>,
    },
  ]

  return <DataState loading={overview.loading} error={overview.error} onRetry={overview.reload}>
    {overview.data && <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      <Alert type="info" showIcon
        title="多发信邮箱按投递结果顺序顺延"
        description="发信时会按列表从上到下依次尝试已启用的邮箱：只要前序邮箱投递失败（含登录失败、连接超时、限频或服务器拒收），就会自动切换下一个邮箱继续投递，直到投递成功或全部邮箱尝试完毕。保存后下一封邮件立即生效，无需重启。" />
      {!overview.data.encrypting && <Alert type="warning" showIcon
        title="当前未配置加密密钥"
        description="服务器未设置 SMTP_ENCRYPTION_KEY（且未设置 MFA_ENCRYPTION_KEY），后台保存的邮箱密码目前以未加密形式存储。建议在 .env 中配置 Base64 密钥并重新保存密码。" />}
      <Card title="已配置的 SMTP 发信邮箱" extra={<Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
        添加发信邮箱
      </Button>}>
        <ContentFitTable rowKey="id" pagination={false} dataSource={accounts} columns={columns}
          locale={{ emptyText: <Empty description="尚未在后台配置发信邮箱" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }} />
      </Card>
      {overview.data.envFallback && <Card size="small" title="服务器 .env 兜底邮箱（只读）"
        extra={<Tag color="default">排在以上全部邮箱之后</Tag>}>
        <Descriptions size="small" column={{ xs: 1, sm: 2, md: 4 }}>
          <Descriptions.Item label="SMTP 服务器">
            {`${overview.data.envFallback.host}:${overview.data.envFallback.port}`}
          </Descriptions.Item>
          <Descriptions.Item label="加密方式">
            {SECURITY_SHORT_LABELS[overview.data.envFallback.security] ?? overview.data.envFallback.security}
          </Descriptions.Item>
          <Descriptions.Item label="发件地址">{overview.data.envFallback.sender}</Descriptions.Item>
          <Descriptions.Item label="SMTP 登录名">{overview.data.envFallback.username}</Descriptions.Item>
        </Descriptions>
      </Card>}
    </Space>}

    <Modal open={editing !== null} title={editing === 'new' ? '添加发信邮箱' : `编辑发信邮箱 · ${editing?.name ?? ''}`}
      okText="保存" confirmLoading={saving} destroyOnHidden width={560}
      onOk={() => void submitForm()} onCancel={() => setEditing(null)}>
      <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
        <Form.Item name="name" label="邮箱名称" rules={[{ required: true, message: '请填写邮箱名称' }]}>
          <Input maxLength={100} placeholder="例如：腾讯企业邮主账号、SendGrid 备用" />
        </Form.Item>
        <Space.Compact style={{ width: '100%' }}>
          <Form.Item name="host" label="SMTP 服务器" style={{ flex: 1 }}
            rules={[{ required: true, message: '请填写 SMTP 服务器地址' }]}>
            <Input maxLength={255} placeholder="例如：smtp.qq.com / smtp.sendgrid.net" />
          </Form.Item>
          <Form.Item name="port" label="端口" style={{ width: 120 }}
            rules={[{ required: true, message: '请填写端口' }]}>
            <InputNumber min={1} max={65535} style={{ width: '100%' }} />
          </Form.Item>
        </Space.Compact>
        <Form.Item name="security" label="加密方式" rules={[{ required: true }]}>
          <Select options={(['SSL', 'STARTTLS', 'NONE'] as SmtpSecurity[]).map(value => ({
            value, label: SECURITY_LABELS[value],
          }))} onChange={(next: SmtpSecurity) => {
            const currentPort = form.getFieldValue('port') as number | undefined
            form.setFieldValue('port', portAfterSecurityChange(currentPort, prevSecurity.current, next))
            prevSecurity.current = next
          }} />
        </Form.Item>
        <Form.Item name="username" label="SMTP 登录名"
          rules={[{ required: true, message: '请填写 SMTP 登录名' }]}
          extra="普通邮箱通常填写邮箱地址；SendGrid、Amazon SES 等服务商填写专用的 SMTP 用户名（如 apikey）。">
          <Input maxLength={255} placeholder="例如：bot@example.com 或 apikey" />
        </Form.Item>
        <Form.Item name="password" label={editing === 'new' ? '密码 / 授权码' : '密码 / 授权码（留空保持不变）'}
          rules={editing === 'new' ? [{ required: true, message: '请填写密码或授权码' }] : undefined}
          extra="QQ、163、企业邮箱请填写邮箱后台生成的授权码，而非登录密码。">
          <Input.Password maxLength={512} autoComplete="new-password"
            placeholder={editing === 'new' ? '输入密码或授权码' : '不修改密码请留空'} />
        </Form.Item>
        <Form.Item name="fromAddress" label="发件地址（From / MAIL FROM）"
          required={requireFromAddress}
          rules={requireFromAddress ? [{ required: true, message: '登录名不是邮箱地址，请单独填写发件地址' }] : undefined}
          extra="留空则直接使用 SMTP 登录名。若邮件提供商的发件地址与 SMTP 登录名分开（如 SendGrid / Amazon SES / 部分企业中继），请在此填写已验证的发件邮箱地址。">
          <Input maxLength={255} placeholder="例如：noreply@example.com（与登录名相同时可留空）" />
        </Form.Item>
        <Form.Item name="fromName" label="发件人显示名（可选）"
          extra="留空则使用站点外观里设置的品牌名称。">
          <Input maxLength={100} placeholder="例如：校园摄影部" />
        </Form.Item>
        <Form.Item name="enabled" label="启用该邮箱" valuePropName="checked">
          <Switch />
        </Form.Item>
      </Form>
    </Modal>

    <Modal open={testing !== null} title={`测试发信邮箱 · ${testing?.name ?? ''}`}
      okText="发送测试邮件" confirmLoading={testSending} destroyOnHidden width={460}
      onOk={() => void sendTest()} onCancel={() => { setTesting(null); setTestTo('') }}>
      <Space orientation="vertical" size={12} style={{ width: '100%', marginTop: 8 }}>
        <Typography.Text type="secondary">
          将直接使用「{testing?.name}」（发件地址：{testing?.sender}）发送一封测试邮件，不会顺延到其他邮箱；即使当前停用也可测试。
        </Typography.Text>
        <Input value={testTo} onChange={event => setTestTo(event.target.value)}
          placeholder="收件邮箱（留空则发送到你当前账号绑定的邮箱）" />
      </Space>
    </Modal>
  </DataState>
}
