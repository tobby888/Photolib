import { Button, Result, Typography } from 'antd'
import { IdcardOutlined } from '@ant-design/icons'
import { useLoad } from '../../hooks'
import { api } from '../../api'

/** 管理员面板 · 注册码：已迁移至 PhotolibIdp 统一认证中心。 */
export default function RegistrationCodesPage() {
  const { data: idp } = useLoad(
    () => api<{ enabled: boolean; codesUrl: string; publicUrl: string }>({ url: '/auth/idp/config' }),
    { enabled: false, codesUrl: '', publicUrl: '' },
    [],
  )

  return <Result
    icon={<IdcardOutlined />}
    title="注册码管理已迁移至统一身份认证中心 (PhotolibIdp)"
    subTitle={<Typography.Paragraph type="secondary">
      注册码生成、有效期与名额管理以及应用准入绑定均已在 PhotolibIdp 统一认证中心内完成。
    </Typography.Paragraph>}
    extra={idp.codesUrl ? <Button type="primary" href={idp.codesUrl} target="_blank" rel="noreferrer">
      打开 PhotolibIdp 注册码管理
    </Button> : null}
  />
}
