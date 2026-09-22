import { Alert, Button, Divider, Input, Space, Typography } from 'antd'
import { KeyOutlined } from '@ant-design/icons'
import { useState, type ReactNode } from 'react'
import { webAuthnSupported } from './webauthn'

type Method = 'TOTP' | 'WEBAUTHN'

/**
 * 输入两步验证的地方：登录第二步、敏感操作前的再验证、进入系统管理面板都用它。
 * 6 位码填满就自动提交；绑过安全密钥的账号多一个按钮，点了弹出浏览器自带的验证窗口。
 */
export default function TwoFactorVerify({ methods, onCode, onSecurityKey, extra, autoFocus = true }: {
  /** 账号绑过的验证方式；不给就两种都显示。 */
  methods?: Method[]
  onCode: (code: string) => Promise<void>
  onSecurityKey?: () => Promise<void>
  /** 放在验证码下面的附加内容，例如"信任此浏览器"。 */
  extra?: ReactNode
  autoFocus?: boolean
}) {
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState<Method | null>(null)
  const [error, setError] = useState<string | null>(null)
  const showTotp = !methods || methods.includes('TOTP')
  const showKey = !!onSecurityKey && (!methods || methods.includes('WEBAUTHN'))

  const run = async (method: Method, action: () => Promise<void>) => {
    setBusy(method)
    setError(null)
    try {
      await action()
    } catch (failure) {
      setError((failure as Error).message)
      setCode('')
    } finally {
      setBusy(null)
    }
  }

  const submit = (value: string) => {
    setCode(value)
    if (/^\d{6}$/.test(value)) void run('TOTP', () => onCode(value))
  }

  return <div className="two-factor-verify">
    {showTotp && <>
      <Typography.Paragraph type="secondary">打开手机上的验证器 App（如 Microsoft Authenticator），输入 PhotoLib 显示的 6 位动态码。</Typography.Paragraph>
      <Input.OTP length={6} size="large" value={code} autoFocus={autoFocus} disabled={busy !== null}
        formatter={value => value.replace(/\D/g, '')} onChange={submit}
        aria-label="6 位动态码" />
    </>}
    {showTotp && showKey && <Divider plain>或</Divider>}
    {showKey && <Space orientation="vertical" style={{ width: '100%' }}>
      {!webAuthnSupported() && <Alert type="warning" showIcon
        title="当前浏览器或网址不支持安全密钥，请改用验证器 App" />}
      <Button block size="large" icon={<KeyOutlined />} loading={busy === 'WEBAUTHN'}
        disabled={busy !== null || !webAuthnSupported()}
        onClick={() => onSecurityKey && void run('WEBAUTHN', onSecurityKey)}>
        使用安全密钥 / 通行密钥
      </Button>
    </Space>}
    {extra}
    {error && <Alert className="two-factor-error" type="error" showIcon title={error} />}
  </div>
}
