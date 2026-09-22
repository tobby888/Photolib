import { Alert, Button, Card, Space, Spin, Typography } from 'antd'
import { SafetyCertificateOutlined } from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth'
import { twoFactorNext } from '../loginRedirect'
import { loadMfaOverview } from '../mfa'
import TwoFactorSetup from '../TwoFactorSetup'

/**
 * 登录后的两步验证页，两种来法：
 * - 所在权限组强制、却还没绑定：必须绑定才能继续，连首次改密都排在后面，没有"跳过"；
 * - 所在权限组建议、还没绑定：每次登录后看到一次，可以"暂不启用"。
 * 处理完回到 `next`（原本要去的那一页）；还欠着首次改密的，先去改密。
 */
export default function TwoFactorPage() {
  const { user, refreshUser, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [totpAvailable, setTotpAvailable] = useState<boolean | null>(null)
  const [error, setError] = useState<string | null>(null)
  const next = twoFactorNext(location.search)
  const required = !!user?.mfa?.enrollmentRequired

  useEffect(() => {
    void loadMfaOverview().then(overview => setTotpAvailable(overview.totpAvailable))
      .catch(failure => setError((failure as Error).message))
  }, [])

  if (!user) return <Navigate to="/login" replace />
  if (!required && !user.mfa?.suggested) {
    return <Navigate to={user.mustChangePassword ? '/initial-password' : next} replace />
  }

  const proceed = async () => {
    const current = await refreshUser()
    navigate(current?.mustChangePassword ? '/initial-password' : next, { replace: true })
  }

  return <main className="password-page">
    <Card className="password-card two-factor-card">
      <div className="password-icon"><SafetyCertificateOutlined /></div>
      <Typography.Text className="eyebrow">TWO-FACTOR AUTHENTICATION</Typography.Text>
      <Typography.Title level={2}>{required ? '请先绑定两步验证' : '建议启用两步验证'}</Typography.Title>
      <Typography.Paragraph type="secondary">
        {required
          ? '你所在的权限组要求两步验证。绑定一个验证设备后才能继续使用系统。'
          : '启用后，在新的浏览器上登录、删除图片 / 选题 / 需求时，需要再用手机或安全密钥确认一次，账号被盗用的风险会小很多。'}
      </Typography.Paragraph>
      {error && <Alert type="error" showIcon title={error} />}
      {totpAvailable === null && !error
        ? <div className="two-factor-loading"><Spin /></div>
        : totpAvailable !== null && <TwoFactorSetup totpAvailable={totpAvailable} onEnrolled={() => void proceed()} />}
      <Space orientation="vertical" style={{ width: '100%', marginTop: 16 }}>
        {!required && <Button block onClick={() => navigate(user.mustChangePassword ? '/initial-password' : next,
          { replace: true })}>暂不启用</Button>}
        <Button block type="text" onClick={async () => { await logout(); navigate('/login', { replace: true }) }}>
          退出登录
        </Button>
      </Space>
    </Card>
  </main>
}
