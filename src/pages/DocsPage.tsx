import { ArrowLeftOutlined, EditOutlined, LoginOutlined } from '@ant-design/icons'
import { Button, Space, Typography } from 'antd'
import { useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '../auth'
import { BrandGlyph, useBranding } from '../branding'
import DocsReader from '../DocsReader'
import { hasPermission } from '../permissions'

/**
 * 登录页前面的文档入口，不需要登录。
 *
 * <p>这里只负责外壳：品牌头和回到系统的按钮。目录与正文由 {@link DocsReader}
 * 渲染，和工作台里的 `/documents` 共用同一份实现。</p>
 *
 * <p>登录用户不会被弹回工作台（招募页是那样做的）：文档对所有人都是要读的
 * 东西，而且登录之后还能多看到"仅成员可见"的那部分。</p>
 */
export default function DocsPage() {
  const { publicId } = useParams<{ publicId?: string }>()
  const navigate = useNavigate()
  const branding = useBranding()
  const { user } = useAuth()

  return <main className="docs-page">
    <header className="docs-header">
      <Space size={12}>
        <span className="brand-glyph"><BrandGlyph branding={branding} /></span>
        <div>
          <Typography.Title level={4} style={{ color: 'white', margin: 0 }}>{branding.title} 文档中心</Typography.Title>
          <Typography.Text style={{ color: 'rgba(255,255,255,.62)' }}>使用说明、流程规范与常见问题</Typography.Text>
        </div>
      </Space>
      <Space>
        {hasPermission(user, 'DOC_MANAGE') && <Button ghost icon={<EditOutlined />}
          onClick={() => navigate('/documents')}>编写文档</Button>}
        {user
          ? <Button ghost icon={<ArrowLeftOutlined />} onClick={() => navigate('/')}>返回工作台</Button>
          : <Button ghost icon={<LoginOutlined />} onClick={() => navigate('/login')}>登录</Button>}
      </Space>
    </header>
    <DocsReader basePath="/docs" publicId={publicId} />
  </main>
}
