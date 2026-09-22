import { Alert, App, Card, Descriptions, Space, Switch, Typography } from 'antd'
import { useState } from 'react'
import { api } from './api'
import { useAuth } from './auth'
import { DataState } from './components'
import { useLoad } from './hooks'
import type { MfaSettings } from './types'

const EMPTY: MfaSettings = { enabled: false, totpAvailable: false, webauthnRpId: null }

/**
 * 系统管理面板里的全站两步验证开关。各权限组要不要两步验证在「权限管理」里按组设置；
 * 这里关着的时候，那些设置都不生效。
 */
export default function MfaSettingsPanel() {
  const { message, modal } = App.useApp()
  const { user, refreshUser } = useAuth()
  const { data, loading, error, reload } = useLoad(
    () => api<MfaSettings>({ url: '/mfa-settings' }), EMPTY, [],
  )
  const [saving, setSaving] = useState(false)

  const apply = async (enabled: boolean) => {
    setSaving(true)
    try {
      await api<MfaSettings>({ method: 'PUT', url: '/mfa-settings', data: { enabled } })
      message.success(enabled ? '已启用两步验证' : '已关闭两步验证')
      // 先更新自己的身份再刷新面板：刚打开开关后面板的下一次请求就要求再验证。
      await refreshUser()
      await reload()
    } catch (failure) {
      message.error((failure as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const toggle = (enabled: boolean) => {
    if (enabled) {
      void apply(true)
      return
    }
    modal.confirm({
      title: '关闭全站两步验证？',
      content: '关闭后登录只需要密码，删除操作和系统管理面板也不再要求验证。已绑定的设备会保留，重新开启后继续有效。',
      okText: '关闭', okButtonProps: { danger: true },
      onOk: () => apply(false),
    })
  }

  return <DataState loading={loading} error={error} onRetry={reload}>
    <Card title="两步验证">
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <Space>
          <Switch checked={data.enabled} loading={saving} onChange={toggle}
            disabled={!data.enabled && !data.totpAvailable} />
          <Typography.Text strong>{data.enabled ? '已在全站启用' : '未启用'}</Typography.Text>
        </Space>
        <Typography.Paragraph type="secondary">
          启用后：系统管理员组必须绑定两步验证；其余权限组按「权限管理」里的设置强制、建议或不使用。
          已绑定的成员在新浏览器登录、删除图片 / 选题 / 需求、进入系统管理面板时需要验证，
          一次验证 15 分钟内有效；登录时可选择信任浏览器 30 天。管理员重置某人的密码时，会同时清除他的验证设备。
        </Typography.Paragraph>
        {!data.enabled && !user?.mfa?.enrolled && <Alert type="warning" showIcon
          title="启用前，请先在右上角头像菜单的「两步验证」里绑定你自己的验证设备。" />}
        {!data.totpAvailable && <Alert type="error" showIcon
          title="服务器未配置 MFA_ENCRYPTION_KEY"
          description="验证器 App 的密钥需要它来加密保存。配置后重启服务才能启用两步验证，部署说明见 README。" />}
        <Descriptions size="small" column={1} bordered items={[
          { key: 'totp', label: '验证器 App', children: data.totpAvailable ? '可用' : '不可用（缺少加密密钥）' },
          { key: 'rp', label: '安全密钥域名', children: data.webauthnRpId
            ?? '未配置（按访问地址自动判断，生产环境建议设置 MFA_WEBAUTHN_RP_ID）' },
        ]} />
      </Space>
    </Card>
  </DataState>
}
