import { Button, Result, Typography } from 'antd'
import { AuditOutlined } from '@ant-design/icons'
import { useLoad } from './hooks'
import { api } from './api'

/** 管理员面板 · 注册审核：已迁移至 PhotolibIdp 统一认证中心。 */
export default function RegistrationReviewPanel() {
  const { data: idp } = useLoad(
    () => api<{ enabled: boolean; reviewsUrl: string; publicUrl: string }>({ url: '/auth/idp/config' }),
    { enabled: false, reviewsUrl: '', publicUrl: '' },
    [],
  )

  return <Result
    icon={<AuditOutlined />}
    title="注册申请审核已迁移至统一身份认证中心 (PhotolibIdp)"
    subTitle={<Typography.Paragraph type="secondary">
      持注册码提交的账号申请统一在 PhotolibIdp 认证中心内审核，通过后可在「账号管理」同步或由成员直接单点登录进入本系统。
    </Typography.Paragraph>}
    extra={idp.reviewsUrl ? <Button type="primary" href={idp.reviewsUrl} target="_blank" rel="noreferrer">
      打开 PhotolibIdp 注册审核
    </Button> : null}
  />
}
