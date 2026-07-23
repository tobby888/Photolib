import { App, Button, Card, Form, Input, Progress, Typography } from 'antd'
import { CheckCircleOutlined, KeyOutlined, LockOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, type LoginResult } from '../api'
import { useAuth } from '../auth'

interface PasswordValues {
  initialPassword: string
  newPassword: string
  confirmPassword: string
}

export default function InitialPasswordPage() {
  const { updateSession, logout } = useAuth()
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)
  const [strength, setStrength] = useState(0)

  const changePassword = async (values: PasswordValues) => {
    setLoading(true)
    try {
      const result = await api<LoginResult>({
        method: 'PUT',
        url: '/auth/initial-password',
        data: { initialPassword: values.initialPassword, newPassword: values.newPassword },
      })
      updateSession(result)
      message.success('密码修改成功，欢迎使用 PhotoLib')
      navigate('/', { replace: true })
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setLoading(false)
    }
  }

  const updateStrength = (value: string) => {
    let score = 0
    if (value.length >= 10) score += 35
    if (/[A-Za-z]/.test(value)) score += 25
    if (/\d/.test(value)) score += 25
    if (/[^A-Za-z0-9]/.test(value)) score += 15
    setStrength(score)
  }

  return <main className="password-page">
    <Card className="password-card">
      <div className="password-icon"><SafetyCertificateOutlined /></div>
      <Typography.Text className="eyebrow">SECURE YOUR ACCOUNT</Typography.Text>
      <Typography.Title level={2}>首次登录，请设置新密码</Typography.Title>
      <Typography.Paragraph type="secondary">
        初始密码仅用于第一次登录。完成修改后，旧会话将自动失效。
      </Typography.Paragraph>
      <Form layout="vertical" size="large" requiredMark={false} onFinish={changePassword}>
        <Form.Item label="当前初始密码" name="initialPassword" rules={[{ required: true, message: '请输入管理员提供的初始密码' }]}>
          <Input.Password prefix={<KeyOutlined />} placeholder="输入初始密码" autoComplete="current-password" />
        </Form.Item>
        <Form.Item label="设置新密码" name="newPassword" rules={[
          { required: true, message: '请输入新密码' },
          { pattern: /^(?=.*[A-Za-z])(?=.*\d).{10,72}$/, message: '至少 10 位，并同时包含字母和数字' },
        ]}>
          <Input.Password prefix={<LockOutlined />} placeholder="至少 10 位，包含字母和数字"
            autoComplete="new-password" onChange={event => updateStrength(event.target.value)} />
        </Form.Item>
        <div className="password-strength">
          <Progress percent={strength} showInfo={false} size="small"
            strokeColor={strength < 60 ? '#87CEFA' : '#4682B4'} />
          <Typography.Text type="secondary">{strength < 60 ? '请继续增强密码' : strength < 100 ? '密码强度良好' : '密码强度很高'}</Typography.Text>
        </div>
        <Form.Item label="确认新密码" name="confirmPassword" dependencies={['newPassword']} rules={[
          { required: true, message: '请再次输入新密码' },
          ({ getFieldValue }) => ({ validator: (_, value) =>
            !value || getFieldValue('newPassword') === value ? Promise.resolve() : Promise.reject(new Error('两次输入的密码不一致')) }),
        ]}>
          <Input.Password prefix={<CheckCircleOutlined />} placeholder="再次输入新密码" autoComplete="new-password" />
        </Form.Item>
        <Button block type="primary" htmlType="submit" loading={loading}>保存新密码并进入工作台</Button>
        <Button block type="text" onClick={async () => { await logout(); navigate('/login', { replace: true }) }}>返回登录</Button>
      </Form>
    </Card>
  </main>
}
