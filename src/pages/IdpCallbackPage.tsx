import { Button, Result } from 'antd'
import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api, type LoginResult } from '../api'
import { useAuth } from '../auth'
import { afterLoginRoute } from '../loginRedirect'

export default function IdpCallbackPage() {
  const { updateSession } = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const code = params.get('code') ?? ''
  const state = params.get('state') ?? ''
  const [error, setError] = useState('')
  const exchanged = useRef(false)

  useEffect(() => {
    if (exchanged.current) return
    exchanged.current = true
    if (!code || !state) {
      setError('单点登录回调参数不完整，请重新发起登录。')
      return
    }
    void api<LoginResult>({
      method: 'POST',
      url: '/auth/idp/exchange',
      data: {
        code,
        state,
        redirectUri: `${window.location.origin}/auth/callback`,
      },
    }).then((result) => {
      updateSession(result)
      navigate(afterLoginRoute(result.user, null), { replace: true })
    }).catch((err: unknown) => {
      setError(err instanceof Error ? err.message : '统一认证登录失败，请重试')
    })
  }, [code, navigate, state, updateSession])

  if (error) {
    return <Result
      status="error"
      title="统一认证登录未完成"
      subTitle={error}
      extra={<Button type="primary" onClick={() => navigate('/login', { replace: true })}>返回登录页</Button>}
    />
  }

  return <Result status="info" title="正在验证统一认证中心登录…" subTitle="请稍候，正在同步账号信息。" />
}
