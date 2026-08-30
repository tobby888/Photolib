import { EditOutlined, ReadOutlined } from '@ant-design/icons'
import { Button, Space, Typography } from 'antd'
import { lazy, Suspense, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '../auth'
import DocsReader from '../DocsReader'
import { hasPermission } from '../permissions'

// 编辑器只有部长和管理员用得到，懒加载让其余人不必下载这段代码。
const DocsManagePage = lazy(() => import('./DocsManagePage'))

/**
 * 工作台里的文档中心。
 *
 * <p><b>对所有能进系统的账号开放</b>，不挂 `DOC_MANAGE`：需要登录才能看的文档
 * 正是给普通成员准备的，把入口按编辑权限收起来，等于让唯一能看到它们的人
 * 找不到入口。能看到哪些文档由服务端按令牌判定，不在这里过滤。</p>
 *
 * <p>有 `DOC_MANAGE` 的人<b>也先进阅读模式</b>，点"编辑文档"才切到编辑器。
 * 部长打开文档中心多数时候是来查东西的，直接落进一棵带草稿标签的可拖拽树
 * 既容易误拖，也让"读一篇文档"这件事凭空多了一步。</p>
 */
export default function DocumentsPage() {
  const { publicId } = useParams<{ publicId?: string }>()
  const navigate = useNavigate()
  const { user } = useAuth()
  const canManage = hasPermission(user, 'DOC_MANAGE')
  const [editing, setEditing] = useState(false)
  // 从编辑模式切回阅读时强制重拉：刚改过的正文、标题和发布状态都要立刻反映出来。
  const [readerToken, setReaderToken] = useState(0)

  const leaveEditing = () => {
    setEditing(false)
    setReaderToken(token => token + 1)
  }

  return <div className="documents-page">
    <div className="documents-toolbar">
      <div>
        <Typography.Title level={4} style={{ margin: 0 }}>
          {editing ? '编写文档' : '文档中心'}
        </Typography.Title>
        <Typography.Text type="secondary">
          {editing
            ? '拖动条目整理目录；发布与可见范围是两个独立开关。'
            : '使用说明、流程规范与常见问题。带锁的文档需要登录后查看，你已经登录。'}
        </Typography.Text>
      </div>
      {canManage && <Space>
        {editing
          ? <Button icon={<ReadOutlined />} onClick={leaveEditing}>返回阅读</Button>
          : <Button type="primary" icon={<EditOutlined />} onClick={() => setEditing(true)}>编辑文档</Button>}
      </Space>}
    </div>

    {editing
      ? <Suspense fallback={<div className="route-loading">正在打开编辑器…</div>}>
          <DocsManagePage onPreview={publicId => {
            leaveEditing()
            navigate(`/documents/${publicId}`)
          }} />
        </Suspense>
      : <DocsReader basePath="/documents" publicId={publicId} reloadToken={readerToken} />}
  </div>
}
