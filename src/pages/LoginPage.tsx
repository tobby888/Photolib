import { App, Button, Card, Checkbox, Divider, Form, Input, Typography } from 'antd'
import { ArrowRightOutlined, CameraOutlined, LockOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth'

export default function LoginPage() {
  const { login } = useAuth()
  const { message } = App.useApp()
  const navigate = useNavigate()
  const location = useLocation()
  const [loading, setLoading] = useState(false)
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
      <div className="login-brand"><CameraOutlined /><span>PhotoLib</span></div>
      <div className="story-copy">
        <Typography.Text>PHOTOGRAPHY WORKSPACE</Typography.Text>
        <Typography.Title>让每一次快门，<br />都抵达它该去的地方。</Typography.Title>
        <Typography.Paragraph>从拍摄需求到图片采纳，把散落的协作收进一条清晰的工作流。</Typography.Paragraph>
      </div>
      <div className="frame-marks"><i /><i /><i /><i /></div>
      <div className="story-meta"><span>项目协作</span><span>素材管理</span><span>贡献统计</span></div>
    </section>
    <section className="login-form-side">
      <Card className="login-card" variant="borderless">
        <Typography.Text className="eyebrow">欢迎回来</Typography.Text>
        <Typography.Title level={2}>登录摄影工作站</Typography.Title>
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
        <Divider plain>想加入摄影部？</Divider>
        <Button block size="large" icon={<TeamOutlined />} onClick={() => navigate('/recruitment')}>
          招募新成员
        </Button>
        <Typography.Text className="login-help">首次登录后，系统会引导你修改初始密码</Typography.Text>
      </Card>
    </section>
  </main>
}
