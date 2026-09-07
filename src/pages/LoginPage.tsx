import { App, Button, Card, Checkbox, Divider, Form, Input, Space, Typography } from 'antd'
import {
  ArrowRightOutlined, LockOutlined, ReadOutlined, TeamOutlined, UserOutlined,
} from '@ant-design/icons'
import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth'
import { BrandGlyph, useBranding } from '../branding'
import SiteFooter from '../SiteFooter'

export default function LoginPage() {
  const { login } = useAuth()
  const branding = useBranding()
  const { message } = App.useApp()
  const navigate = useNavigate()
  const location = useLocation()
  const [loading, setLoading] = useState(false)
  const headline = branding.loginHeadline?.trim()
  const subheadline = branding.loginSubheadline?.trim()
  const highlights = branding.loginHighlights ?? []
  const notice = branding.loginNotice?.trim()
  const submit = async (values: { identifier: string; password: string }) => {
    setLoading(true)
    try {
      const result = await login(values.identifier, values.password)
      message.success(`欢迎回来，${result.user.displayName}`)
      navigate(result.mustChangePassword ? '/initial-password' :
        ((location.state as { from?: { pathname: string } })?.from?.pathname || '/'))
    } catch (error) {
      message.error((error as Error).message)
    } finally { setLoading(false) }
  }
  return <main className="login-page">
    <section className="login-story">
      <div className="login-brand">
        <span className="brand-glyph"><BrandGlyph branding={branding} /></span>
        <span>{branding.title}</span>
      </div>
      <div className="story-copy">
        <Typography.Text>{branding.slogan}</Typography.Text>
        {/* 主标题、副标题和下方关键词都由管理员配置；留空就整段不渲染，
            而不是退回一份写死的文案——写死的那份迟早和后台配置对不上。 */}
        {headline && <Typography.Title className="story-headline">{headline}</Typography.Title>}
        {subheadline && <Typography.Paragraph>{subheadline}</Typography.Paragraph>}
      </div>
      <div className="frame-marks"><i /><i /><i /><i /></div>
      {!!highlights.length && <div className="story-meta">
        {highlights.map(item => <span key={item}>{item}</span>)}
      </div>}
    </section>
    <section className="login-form-side">
      <Card className="login-card" variant="borderless">
        <Typography.Text className="eyebrow">欢迎回来</Typography.Text>
        <Typography.Title level={2}>登录{branding.title}</Typography.Title>
        <Typography.Paragraph type="secondary">使用管理员分配给你的账号或邮箱继续工作。</Typography.Paragraph>
        <Form layout="vertical" size="large" onFinish={submit} requiredMark={false}>
          <Form.Item label="账号或邮箱" name="identifier" rules={[{ required: true, message: '请输入账号或邮箱' }]}>
            <Input prefix={<UserOutlined />} placeholder="请输入账号或邮箱" autoComplete="username" />
          </Form.Item>
          <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="请输入密码" autoComplete="current-password" />
          </Form.Item>
          <div className="form-between"><Checkbox>记住账号</Checkbox><Typography.Text type="secondary">忘记密码请联系管理员</Typography.Text></div>
          <Button block type="primary" htmlType="submit" loading={loading}>登录 <ArrowRightOutlined /></Button>
        </Form>
        <Divider plain>不用登录也能看</Divider>
        <Space.Compact block>
          <Button size="large" style={{ width: '50%' }} icon={<TeamOutlined />}
            onClick={() => navigate('/recruitment')}>我要报名</Button>
          <Button size="large" style={{ width: '50%' }} icon={<ReadOutlined />}
            onClick={() => navigate('/docs')}>查看文档</Button>
        </Space.Compact>
        {notice && <Typography.Text className="login-help">{notice}</Typography.Text>}
      </Card>
      <SiteFooter className="login-footer" />
    </section>
  </main>
}
