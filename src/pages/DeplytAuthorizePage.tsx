import { Alert, Button, Result } from 'antd'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '../api'

const tokenPattern = /^[A-Za-z0-9_-]{43}$/

export default function DeplytAuthorizePage() {
  const [params] = useSearchParams()
  const state = params.get('state') ?? ''
  const challenge = params.get('challenge') ?? ''
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  if (!tokenPattern.test(state) || !tokenPattern.test(challenge)) {
    return <Result status="error" title="Deplyt 登录请求无效" subTitle="请回到 Deplyt 重新发起登录。" />
  }

  async function approve() {
    setSubmitting(true)
    setError('')
    try {
      const result = await api<{ callbackUrl: string }>({
        method: 'POST',
        url: '/auth/deplyt/authorize',
        data: { state, challenge },
      })
      window.location.replace(result.callbackUrl)
    } catch (err) {
      setError(err instanceof Error ? err.message : '无法授权 Deplyt')
      setSubmitting(false)
    }
  }

  return <Result
    status="info"
    title="使用 PhotoLib 登录 Deplyt"
    subTitle="PhotoLib 管理员可以查看仓库与部署记录，以及回滚生产版本。"
    extra={<Button type="primary" loading={submitting} onClick={() => void approve()}>授权并登录</Button>}
  >
    {error && <Alert type="error" showIcon message={error} />}
  </Result>
}
