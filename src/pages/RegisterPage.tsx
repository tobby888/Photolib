import { Alert, App, Button, Card, Form, Input, Result, Typography } from 'antd'
import {
  ArrowLeftOutlined, CheckCircleOutlined, IdcardOutlined, KeyOutlined, LockOutlined, MailOutlined, UserOutlined,
} from '@ant-design/icons'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { useBranding } from '../branding'
import SiteFooter from '../SiteFooter'

interface RegisterValues {
  code: string
  username: string
  displayName: string
  email: string
  password: string
  confirmPassword: string
}

interface SubmittedApplication {
  id: string
  username: string
  displayName: string
}

/**
 * 持注册码的同学自助注册。提交的只是一份申请：管理员审核通过后账号才真正建出来，
 * 在那之前用这里设的账号密码是登录不了的——页面上要把这一点说清楚，免得同学以为注册失败。
 */
export default function RegisterPage() {
  const branding = useBranding()
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)
  const [submitted, setSubmitted] = useState<SubmittedApplication | null>(null)

  const submit = async (values: RegisterValues) => {
    setLoading(true)
    try {
      setSubmitted(await api<SubmittedApplication>({
        method: 'POST',
        url: '/public/registrations',
        data: {
          ...values,
          username: values.username.trim(),
          displayName: values.displayName.trim(),
          email: values.email.trim(),
        },
      }))
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setLoading(false)
    }
  }

  return <main className="password-page">
    <Card className="password-card register-card">
      {submitted ? <Result status="success" title="注册申请已提交"
        subTitle={<>
          {submitted.displayName}，你的账号 <Typography.Text code>{submitted.username}</Typography.Text> 正在等待管理员审核。
          审核通过后，就可以用刚才设置的账号（或邮箱）和密码登录{branding.title}。
        </>}
        extra={<Button type="primary" onClick={() => navigate('/login', { replace: true })}>返回登录</Button>} />
        : <>
          <div className="password-icon"><IdcardOutlined /></div>
          <Typography.Text className="eyebrow">REGISTER</Typography.Text>
          <Typography.Title level={2}>使用注册码注册</Typography.Title>
          <Typography.Paragraph type="secondary">
            注册码由管理员发放。提交后需要管理员审核，通过后账号才能登录。
          </Typography.Paragraph>
          <Form layout="vertical" size="large" requiredMark={false} onFinish={submit}>
            <Form.Item label="注册码" name="code" rules={[{ required: true, message: '请输入注册码' }]}>
              <Input prefix={<KeyOutlined />} placeholder="例如 ABCD-EFGH-JKLM-NPQR" autoComplete="off" />
            </Form.Item>
            <Form.Item label="登录账号" name="username" extra="用于登录，3-64 位字母、数字或 ._-，注册后不能修改。" rules={[
              { required: true, message: '请输入登录账号' },
              { pattern: /^\s*[A-Za-z0-9_.-]{3,64}\s*$/, message: '登录账号为 3-64 位字母、数字或 ._-' },
            ]}>
              <Input prefix={<UserOutlined />} placeholder="例如 zhangsan" autoComplete="username" />
            </Form.Item>
            <Alert className="register-name-notice" type="warning" showIcon
              title="请填写真实姓名"
              description="管理员会根据姓名核对你的身份，姓名与实际不符的申请会被驳回。" />
            <Form.Item label="真实姓名" name="displayName" rules={[
              { required: true, whitespace: true, message: '请输入真实姓名' },
              { max: 50, message: '姓名不能超过 50 个字符' },
            ]}>
              <Input prefix={<IdcardOutlined />} placeholder="请如实填写，用于审核" autoComplete="name" />
            </Form.Item>
            <Form.Item label="邮箱" name="email" extra="可以用邮箱代替账号登录。" rules={[
              { required: true, message: '请输入邮箱' },
              { type: 'email', message: '请输入有效的邮箱地址' },
              { max: 255, message: '邮箱不能超过 255 个字符' },
            ]}>
              <Input prefix={<MailOutlined />} placeholder="name@example.com" autoComplete="email" />
            </Form.Item>
            <Form.Item label="密码" name="password" rules={[
              { required: true, message: '请输入密码' },
              { pattern: /^(?=.*[A-Za-z])(?=.*\d).{10,72}$/, message: '至少 10 位，并同时包含字母和数字' },
            ]}>
              <Input.Password prefix={<LockOutlined />} placeholder="至少 10 位，包含字母和数字" autoComplete="new-password" />
            </Form.Item>
            <Form.Item label="确认密码" name="confirmPassword" dependencies={['password']} rules={[
              { required: true, message: '请再次输入密码' },
              ({ getFieldValue }) => ({ validator: (_, value) =>
                !value || getFieldValue('password') === value
                  ? Promise.resolve() : Promise.reject(new Error('两次输入的密码不一致')) }),
            ]}>
              <Input.Password prefix={<CheckCircleOutlined />} placeholder="再次输入密码" autoComplete="new-password" />
            </Form.Item>
            <Button block type="primary" htmlType="submit" loading={loading}>提交注册申请</Button>
            <Button block type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/login')}>已有账号，返回登录</Button>
          </Form>
        </>}
    </Card>
    <SiteFooter className="login-footer" />
  </main>
}
