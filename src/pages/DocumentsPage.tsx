import { EditOutlined, FileTextOutlined, FolderOpenOutlined, ReadOutlined } from '@ant-design/icons'
import { Button, Segmented, Space, Typography } from 'antd'
import { lazy, Suspense, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '../auth'
import DocFilesPanel from '../DocFilesPanel'
import DocsReader from '../DocsReader'
import { hasAnyPermission } from '../permissions'

// 编辑器只有部长和管理员用得到，懒加载让其余人不必下载这段代码。
const DocsManagePage = lazy(() => import('./DocsManagePage'))

/**
 * 工作台里的文档中心，分「文档」和「文件」两块（`/documents`、`/documents/files`）。
 *
 * <p><b>对所有能进系统的账号开放</b>，不挂 `DOC_MANAGE`：需要登录才能看的文档
 * 正是给普通成员准备的，把入口按编辑权限收起来，等于让唯一能看到它们的人
 * 找不到入口。能看到哪些文档、哪些文件由服务端按令牌判定，不在这里过滤。</p>
 *
 * <p>能编辑的人（写内容的 `DOC_MANAGE`、管发布与读者范围的 `DOC_PUBLISH`）<b>也先进阅读模式</b>，
 * 点"编辑文档"才切到编辑器。部长打开文档中心多数时候是来查东西的，直接落进一棵带草稿标签的
 * 可拖拽树既容易误拖，也让"读一篇文档"这件事凭空多了一步。</p>
 */
export default function DocumentsPage({ section = 'docs' }: { section?: 'docs' | 'files' }) {
  const { publicId } = useParams<{ publicId?: string }>()
  const navigate = useNavigate()
  const { user } = useAuth()
  const canManage = hasAnyPermission(user, 'DOC_MANAGE', 'DOC_PUBLISH')
  const [editing, setEditing] = useState(false)
  // 从编辑模式切回阅读时强制重拉：刚改过的正文、标题和发布状态都要立刻反映出来。
  const [readerToken, setReaderToken] = useState(0)
  const files = section === 'files'

  const leaveEditing = () => {
    setEditing(false)
    setReaderToken(token => token + 1)
  }

  return <div className="documents-page">
    <div className="documents-toolbar">
      <div>
        <Typography.Title level={4} style={{ margin: 0 }}>
          {editing && !files ? '编写文档' : '文档中心'}
        </Typography.Title>
        <Typography.Text type="secondary">
          {files
            ? '可下载的资料与文件。每个文件由上传者指定谁能下载。'
            : editing
              ? '拖动条目整理目录；发布与读者范围是两个独立开关。'
              : '使用说明、流程规范与常见问题。带锁的文档需要登录后查看，你已经登录。'}
        </Typography.Text>
      </div>
      <Space wrap>
        {!editing && <Segmented value={section}
          onChange={value => navigate(value === 'files' ? '/documents/files' : '/documents')}
          options={[
            { value: 'docs', label: <Space size={4}><FileTextOutlined />文档</Space> },
            { value: 'files', label: <Space size={4}><FolderOpenOutlined />文件</Space> },
          ]} />}
        {canManage && !files && (editing
          ? <Button icon={<ReadOutlined />} onClick={leaveEditing}>返回阅读</Button>
          : <Button type="primary" icon={<EditOutlined />} onClick={() => setEditing(true)}>编辑文档</Button>)}
      </Space>
    </div>

    {files
      ? <DocFilesPanel />
      : editing
        ? <Suspense fallback={<div className="route-loading">正在打开编辑器…</div>}>
            <DocsManagePage onPreview={publicId => {
              leaveEditing()
              navigate(`/documents/${publicId}`)
            }} />
          </Suspense>
        : <DocsReader basePath="/documents" publicId={publicId} reloadToken={readerToken} />}
  </div>
}
