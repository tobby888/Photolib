import {
  ArrowLeftOutlined, EditOutlined, FileTextOutlined, FolderOpenOutlined, FolderOutlined,
  LockOutlined, LoginOutlined,
} from '@ant-design/icons'
import { Alert, Button, Empty, Result, Skeleton, Space, Tag, Tree, Typography } from 'antd'
import type { DataNode } from 'antd/es/tree'
import dayjs from 'dayjs'
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError, api } from '../api'
import { useAuth } from '../auth'
import { BrandGlyph, useBranding } from '../branding'
import { ancestorKeysOf, documentKeysInOrder, readerNodesToTree } from '../docsTree'
import { useLoad } from '../hooks'
import MarkdownRenderer from '../MarkdownRenderer'
import { hasPermission } from '../permissions'
import type { DocReaderDocument, DocReaderNode } from '../types'

/**
 * 文档中心的阅读页，不需要登录。
 *
 * <p>登录与否只影响能看到什么，不影响能不能进这个页面：未登录只看得到公开文档，
 * 登录后仅限成员的文档也会出现在目录里（带一把锁）。判定完全在服务端做，
 * 这里不做任何"因为没登录所以先藏起来"的前端过滤——那样的过滤是装饰，不是权限。</p>
 */
function toTreeData(nodes: DocReaderNode[]): DataNode[] {
  return nodes.map(node => ({
    key: node.publicId,
    title: <span className="docs-tree-label">
      {node.title}
      {node.requiresLogin && <LockOutlined className="docs-tree-lock" title="需要登录后查看" />}
    </span>,
    icon: node.nodeType === 'FOLDER'
      ? (({ expanded }: { expanded?: boolean }) => expanded ? <FolderOpenOutlined /> : <FolderOutlined />)
      : <FileTextOutlined />,
    isLeaf: node.nodeType === 'DOCUMENT',
    children: node.nodeType === 'FOLDER' ? toTreeData(node.children || []) : undefined,
  }))
}

export default function DocsPage() {
  const { publicId } = useParams<{ publicId?: string }>()
  const navigate = useNavigate()
  const branding = useBranding()
  const { user } = useAuth()
  const [expandedKeys, setExpandedKeys] = useState<string[]>([])
  const [document, setDocument] = useState<DocReaderDocument | null>(null)
  const [documentError, setDocumentError] = useState<{ message: string; needsLogin: boolean } | null>(null)
  const [documentLoading, setDocumentLoading] = useState(false)

  // 登录状态变化要重新拉目录：登录之后仅限成员的文档才会出现。
  const tree = useLoad(
    () => api<DocReaderNode[]>({ url: '/public/docs' }),
    [] as DocReaderNode[],
    [user?.id],
  )

  const shape = useMemo(() => readerNodesToTree(tree.data), [tree.data])
  const treeData = useMemo(() => toTreeData(tree.data), [tree.data])
  const firstDocument = useMemo(() => documentKeysInOrder(shape)[0], [shape])

  // 没有指定文档时打开目录里的第一篇，让页面不至于开局一片空白。
  useEffect(() => {
    if (!publicId && firstDocument) navigate(`/docs/${firstDocument}`, { replace: true })
  }, [publicId, firstDocument, navigate])

  // 跳到某篇文档时把它的祖先文件夹全部展开，否则侧边栏看不出当前在哪。
  useEffect(() => {
    if (!publicId) return
    const ancestors = ancestorKeysOf(shape, publicId)
    if (ancestors.length) {
      setExpandedKeys(current => Array.from(new Set([...current, ...ancestors])))
    }
  }, [publicId, shape])

  useEffect(() => {
    if (!publicId) {
      setDocument(null)
      setDocumentError(null)
      return
    }
    let active = true
    setDocumentLoading(true)
    setDocumentError(null)
    void api<DocReaderDocument>({ url: `/public/docs/${publicId}` })
      .then(value => { if (active) setDocument(value) })
      .catch((reason: unknown) => {
        if (!active) return
        setDocument(null)
        // 服务端对"已发布但需要登录"回 403 并说明原因，正是为了在这里
        // 给出"去登录"而不是"文档不存在"。
        const forbidden = reason instanceof ApiError && reason.code === 'FORBIDDEN'
        setDocumentError({ message: (reason as Error).message, needsLogin: forbidden && !user })
      })
      .finally(() => { if (active) setDocumentLoading(false) })
    return () => { active = false }
  }, [publicId, user])

  const empty = !tree.loading && !tree.error && !tree.data.length

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

    <div className="docs-body">
      <aside className="docs-sidebar">
        {tree.loading && <Skeleton active paragraph={{ rows: 8 }} />}
        {!tree.loading && tree.error && <Alert type="warning" showIcon message="目录没能加载出来"
          description={tree.error} action={<Button size="small" onClick={() => void tree.reload()}>重试</Button>} />}
        {empty && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有公开的文档" />}
        {!tree.loading && !tree.error && !!tree.data.length && <Tree
          showIcon
          blockNode
          treeData={treeData}
          selectedKeys={publicId ? [publicId] : []}
          expandedKeys={expandedKeys}
          onExpand={keys => setExpandedKeys(keys.map(String))}
          onSelect={(_keys, info) => {
            const key = String(info.node.key)
            // 文件夹没有正文，点它就当作展开/收起，不改变右侧内容。
            if (!info.node.isLeaf) {
              setExpandedKeys(current => current.includes(key)
                ? current.filter(item => item !== key)
                : [...current, key])
              return
            }
            navigate(`/docs/${key}`)
          }} />}
      </aside>

      <section className="docs-content">
        {documentLoading && <Skeleton active paragraph={{ rows: 12 }} />}
        {!documentLoading && documentError && (documentError.needsLogin
          ? <Result status="403" icon={<LockOutlined />} title="这篇文档需要登录后查看"
            subTitle="它只对摄影部成员开放。用你的账号登录后就能继续阅读。"
            extra={<Button type="primary" icon={<LoginOutlined />}
              onClick={() => navigate('/login')}>去登录</Button>} />
          : <Result status="404" title="没找到这篇文档" subTitle={documentError.message}
            extra={<Link to="/docs"><Button>回到目录</Button></Link>} />)}
        {!documentLoading && !documentError && document && <article>
          {document.breadcrumb.length > 1 && <Typography.Text type="secondary" className="docs-breadcrumb">
            {document.breadcrumb.slice(0, -1).join(' / ')}
          </Typography.Text>}
          <Typography.Title level={2} className="docs-title">
            {document.title}
            {document.requiresLogin && <Tag icon={<LockOutlined />} color="gold">仅成员可见</Tag>}
          </Typography.Title>
          <Typography.Text type="secondary" className="docs-meta">
            {document.updaterDisplayName && <>{document.updaterDisplayName} · </>}
            {document.updatedAt && <>更新于 {dayjs(document.updatedAt).format('YYYY-MM-DD HH:mm')}</>}
          </Typography.Text>
          {document.content.trim()
            ? <MarkdownRenderer value={document.content} />
            : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="这篇文档还没有内容" />}
        </article>}
        {!documentLoading && !documentError && !document && !empty &&
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="从左边选一篇文档开始阅读" />}
      </section>
    </div>
  </main>
}
