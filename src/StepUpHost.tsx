import { Modal, Typography } from 'antd'
import { SafetyCertificateOutlined } from '@ant-design/icons'
import { useEffect, useRef, useState } from 'react'
import { setStepUpHandler } from './api'
import { loadMfaOverview, stepUpWithCode, stepUpWithSecurityKey } from './mfa'
import type { MfaDevice } from './types'
import TwoFactorVerify from './TwoFactorVerify'

/**
 * 敏感操作的再验证框。挂在外壳里，向请求层注册处理器：任何接口回了
 * `STEP_UP_REQUIRED`，这里弹框；验证通过后请求层自动重发原请求。
 * 验证一次在 15 分钟内有效，期间的删除和管理操作不会再弹。
 */
export default function StepUpHost() {
  const [open, setOpen] = useState(false)
  const [methods, setMethods] = useState<MfaDevice['type'][]>()
  const resolver = useRef<((verified: boolean) => void) | null>(null)

  useEffect(() => setStepUpHandler(() => new Promise<boolean>(resolve => {
    resolver.current = resolve
    setMethods(undefined)
    setOpen(true)
    void loadMfaOverview()
      .then(overview => setMethods([...new Set(overview.devices.map(device => device.type))]))
      .catch(() => setMethods(['TOTP']))
  })), [])

  const finish = (verified: boolean) => {
    resolver.current?.(verified)
    resolver.current = null
    setOpen(false)
  }

  return <Modal open={open} title={<span><SafetyCertificateOutlined /> 需要验证身份</span>} footer={null}
    width={420} destroyOnHidden mask={{ closable: false }} onCancel={() => finish(false)}>
    <Typography.Paragraph>这是敏感操作，请先完成两步验证。验证后 15 分钟内不再询问。</Typography.Paragraph>
    {open && methods && <TwoFactorVerify methods={methods}
      onCode={async code => { await stepUpWithCode(code); finish(true) }}
      onSecurityKey={async () => { await stepUpWithSecurityKey(); finish(true) }} />}
  </Modal>
}
