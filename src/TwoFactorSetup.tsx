import { Alert, App, Button, Input, QRCode, Segmented, Space, Steps, Typography } from 'antd'
import { KeyOutlined, MobileOutlined, QrcodeOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { beginTotp, confirmTotp, registerSecurityKey, type TotpSetup } from './mfa'
import type { MfaDevice } from './types'
import { webAuthnSupported } from './webauthn'

type Kind = 'TOTP' | 'WEBAUTHN'

/** 把 Base32 密钥四个一组断开，手动输入时不容易看串行。 */
const groupSecret = (secret: string) => secret.replace(/(.{4})/g, '$1 ').trim()

/**
 * 绑定一个新的验证设备：验证器 App（扫码，再输一次 6 位码确认）或安全密钥 / 通行密钥
 * （浏览器自带的验证窗口）。强制绑定页、建议页和头像菜单里的管理弹窗共用。
 */
export default function TwoFactorSetup({ totpAvailable, onEnrolled }: {
  /** 服务器是否配置了加密密钥；没配时只能绑定安全密钥。 */
  totpAvailable: boolean
  onEnrolled: (device: MfaDevice) => void
}) {
  const { message } = App.useApp()
  const [kind, setKind] = useState<Kind>(totpAvailable ? 'TOTP' : 'WEBAUTHN')
  const [setup, setSetup] = useState<TotpSetup | null>(null)
  const [totpName, setTotpName] = useState('我的手机')
  const [keyName, setKeyName] = useState('安全密钥')
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const run = async (action: () => Promise<void>) => {
    setBusy(true)
    setError(null)
    try { await action() } catch (failure) { setError((failure as Error).message) } finally { setBusy(false) }
  }

  const start = () => run(async () => {
    setCode('')
    setSetup(await beginTotp())
  })

  const confirm = (value: string) => {
    setCode(value)
    if (!setup || !/^\d{6}$/.test(value)) return
    void run(async () => {
      try {
        const device = await confirmTotp(setup.deviceId, value, totpName)
        message.success('验证器 App 已绑定')
        setSetup(null)
        onEnrolled(device)
      } catch (failure) {
        setCode('')
        throw failure
      }
    })
  }

  const addKey = () => run(async () => {
    const device = await registerSecurityKey(keyName)
    message.success('安全密钥已绑定')
    onEnrolled(device)
  })

  return <div className="two-factor-setup">
    <Segmented block value={kind} onChange={value => { setKind(value as Kind); setError(null) }} options={[
      { value: 'TOTP', label: <span><MobileOutlined /> 验证器 App</span>, disabled: !totpAvailable },
      { value: 'WEBAUTHN', label: <span><KeyOutlined /> 安全密钥 / 通行密钥</span> },
    ]} />
    {kind === 'TOTP' && (!setup
      ? <Space orientation="vertical" style={{ width: '100%' }}>
        <Typography.Paragraph type="secondary">
          支持 Microsoft Authenticator、Google Authenticator、1Password 等任意验证器 App。
          先在手机上装好其中一个，再点下面的按钮。
        </Typography.Paragraph>
        <Button type="primary" block icon={<QrcodeOutlined />} loading={busy} onClick={() => void start()}>
          生成二维码
        </Button>
      </Space>
      : <div>
        <Steps orientation="vertical" size="small" current={-1} items={[
          { title: '用验证器 App 扫描二维码', content: <div className="two-factor-qr">
            <QRCode value={setup.otpauthUri} size={180} bordered={false} color="#000000" bgColor="#ffffff" />
            <Typography.Text type="secondary">无法扫码？在 App 里选"手动输入"，填写密钥：</Typography.Text>
            <Typography.Text code copyable={{ text: setup.secret }}>{groupSecret(setup.secret)}</Typography.Text>
          </div> },
          { title: '给它起个名字', content: <Input value={totpName} maxLength={64}
            onChange={event => setTotpName(event.target.value)} placeholder="例如：我的手机" /> },
          { title: '输入 App 里显示的 6 位动态码完成绑定', content: <Input.OTP length={6} value={code}
            disabled={busy} formatter={value => value.replace(/\D/g, '')} onChange={confirm} /> },
        ]} />
        <Button type="link" onClick={() => void start()} disabled={busy}>重新生成二维码</Button>
      </div>)}
    {kind === 'WEBAUTHN' && <Space orientation="vertical" style={{ width: '100%' }}>
      <Typography.Paragraph type="secondary">
        点下面的按钮后，浏览器会弹出系统的验证窗口：可以插入 USB 安全密钥，用手机扫码，
        或使用本机的 Windows Hello / 触控 ID。
      </Typography.Paragraph>
      {!webAuthnSupported() && <Alert type="warning" showIcon
        title="当前浏览器或网址不支持安全密钥（需要 HTTPS 域名），请改用验证器 App" />}
      <Input value={keyName} maxLength={64} onChange={event => setKeyName(event.target.value)}
        placeholder="例如：蓝色 USB 密钥" addonBefore="名称" />
      <Button type="primary" block icon={<KeyOutlined />} loading={busy} disabled={!webAuthnSupported()}
        onClick={() => void addKey()}>添加安全密钥 / 通行密钥</Button>
    </Space>}
    {!totpAvailable && <Alert type="info" showIcon className="two-factor-note"
      title="服务器还没有配置验证器 App 所需的加密密钥，目前只能绑定安全密钥。" />}
    {error && <Alert className="two-factor-error" type="error" showIcon title={error} />}
  </div>
}
