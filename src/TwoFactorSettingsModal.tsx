import { Alert, App, Button, Collapse, Empty, List, Modal, Popconfirm, Tag, Typography } from 'antd'
import { DeleteOutlined, GlobalOutlined, KeyOutlined, MobileOutlined, PlusOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useCallback, useEffect, useState } from 'react'
import { useAuth } from './auth'
import { deleteMfaDevice, deviceTypeLabel, loadMfaOverview, revokeTrustedBrowser } from './mfa'
import type { MfaOverview } from './types'
import TwoFactorSetup from './TwoFactorSetup'

const time = (value?: string | null) => value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'

/** 从 User-Agent 里挑出给人看的"什么浏览器、什么系统"，不求精确。 */
function browserName(label?: string | null) {
  if (!label) return '未知浏览器'
  const browser = /Edg\//.test(label) ? 'Edge' : /Firefox\//.test(label) ? 'Firefox'
    : /Chrome\//.test(label) ? 'Chrome' : /Safari\//.test(label) ? 'Safari' : '浏览器'
  const system = /Windows/.test(label) ? 'Windows' : /Android/.test(label) ? 'Android'
    : /iPhone|iPad/.test(label) ? 'iOS' : /Mac OS X/.test(label) ? 'macOS' : /Linux/.test(label) ? 'Linux' : ''
  return system ? `${browser} · ${system}` : browser
}

/**
 * 头像菜单里的"两步验证"：查看、添加、删除自己的验证设备，管理信任的浏览器。
 * 添加和删除设备属于敏感操作，已启用两步验证的账号会先被要求再验证一次（请求层自动弹框）。
 */
export default function TwoFactorSettingsModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { message } = App.useApp()
  const { updateUser } = useAuth()
  const [overview, setOverview] = useState<MfaOverview | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)

  const reload = useCallback(async () => {
    try {
      const next = await loadMfaOverview()
      setOverview(next)
      setError(null)
      updateUser({ mfa: next.summary })
    } catch (failure) {
      setError((failure as Error).message)
    }
  }, [updateUser])

  useEffect(() => { if (open) void reload() }, [open, reload])

  const remove = async (id: string) => {
    try {
      await deleteMfaDevice(id)
      message.success('已删除')
      await reload()
    } catch (failure) {
      message.error((failure as Error).message)
    }
  }

  const revoke = async (id: string) => {
    try {
      await revokeTrustedBrowser(id)
      message.success('已取消信任')
      await reload()
    } catch (failure) {
      message.error((failure as Error).message)
    }
  }

  const summary = overview?.summary
  return <Modal open={open} onCancel={onClose} footer={null} width={560} title="两步验证" destroyOnHidden>
    {error && <Alert type="error" showIcon title={error} />}
    {summary && <>
      <div className="two-factor-status">
        {summary.active ? <Tag color="success">已启用</Tag>
          : summary.enrolled ? <Tag>已绑定，尚未生效</Tag> : <Tag color="warning">未绑定</Tag>}
        <Typography.Text type="secondary">
          {summary.policy === 'REQUIRED' ? '你所在的权限组要求两步验证' : '你所在的权限组建议启用两步验证'}
        </Typography.Text>
      </div>
      {!summary.systemEnabled && <Alert type="info" showIcon className="two-factor-note"
        title="管理员还没有在全站启用两步验证，现在绑定的设备会在启用后生效。" />}

      <Typography.Title level={5}>验证设备</Typography.Title>
      {overview.devices.length === 0
        ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有绑定任何设备" />
        : <List dataSource={overview.devices} renderItem={device => <List.Item actions={[
          <Popconfirm key="delete" title="删除这个验证设备？" okText="删除" okButtonProps={{ danger: true }}
            onConfirm={() => remove(device.id)}>
            <Button type="text" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>,
        ]}>
          <List.Item.Meta avatar={device.type === 'TOTP' ? <MobileOutlined /> : <KeyOutlined />}
            title={device.name}
            description={`${deviceTypeLabel(device.type)} · 添加于 ${time(device.createdAt)} · 上次使用 ${time(device.lastUsedAt)}`} />
        </List.Item>} />}
      <Collapse ghost activeKey={adding ? ['add'] : []} onChange={keys => setAdding(keys.includes('add'))} items={[{
        key: 'add', label: <span><PlusOutlined /> 添加验证设备</span>,
        children: <TwoFactorSetup totpAvailable={overview.totpAvailable}
          onEnrolled={() => { setAdding(false); void reload() }} />,
      }]} />

      <Typography.Title level={5}>信任的浏览器</Typography.Title>
      <Typography.Paragraph type="secondary">
        登录时勾选"信任此浏览器"后，这台设备 30 天内再登录不需要两步验证；30 天没有登录会自动失效。
      </Typography.Paragraph>
      {overview.trustedBrowsers.length === 0
        ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有信任的浏览器" />
        : <List dataSource={overview.trustedBrowsers} renderItem={browser => <List.Item actions={[
          <Button key="revoke" type="text" onClick={() => void revoke(browser.id)}>取消信任</Button>,
        ]}>
          <List.Item.Meta avatar={<GlobalOutlined />} title={browserName(browser.label)}
            description={`上次登录 ${time(browser.lastUsedAt)} · 到期 ${time(browser.expiresAt)}`} />
        </List.Item>} />}
    </>}
  </Modal>
}
