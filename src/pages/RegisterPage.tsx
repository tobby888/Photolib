import { Button, Card, Result, Space, Typography } from 'antd'
import { ArrowLeftOutlined, IdcardOutlined } from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import SiteFooter from '../SiteFooter'

/**
 * 注册码申请服务已迁移至 PhotolibIdp 统一身份认证中心。
 */
export default function RegisterPage() {
  const navigate = useNavigate()
  const [registerUrl, setRegisterUrl] = useState('')

  useEffect(() => {
    void api<{ enabled: boolean; registerUrl: string }>({ url: '/auth/idp/config' })
      .then((res) => {
        if (res.registerUrl) setRegisterUrl(res.registerUrl)
      })
      .catch(() => {})
  }, [])

  return <main className="password-page">
    <Card className="password-card register-card">
      <Result
        icon={<IdcardOutlined />}
        title="请前往统一认证中心 (PhotolibIdp) 注册账号"
        subTitle={<Typography.Paragraph type="secondary">
          注册码验证、新成员账号注册申请与管理员审核已统一迁移至 PhotolibIdp 身份认证中心进行。审核通过后即可直接登录本系统。
        </Typography.Paragraph>}
        extra={<Space wrap>
          {registerUrl && <Button type="primary" onClick={() => window.location.assign(registerUrl)}>
            前往统一认证中心注册
          </Button>}
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/login')}>返回登录</Button>
        </Space>}
      />
    </Card>
    <SiteFooter className="login-footer" />
  </main>
}
