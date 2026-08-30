import {
  FileTextOutlined, FolderOpenOutlined, FolderOutlined, LockOutlined, LoginOutlined,
} from '@ant-design/icons'
import { Alert, Button, Empty, Result, Skeleton, Tag, Tree, Typography } from 'antd'
import type { DataNode } from 'antd/es/tree'
import dayjs from 'dayjs'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError, api } from './api'
import { useAuth } from './auth'
import { ancestorKeysOf, documentKeysInOrder, readerNodesToTree } from './docsTree'
import { useLoad } from './hooks'
import MarkdownRenderer from './MarkdownRenderer'
import type { DocReaderDocument, DocReaderNode } from './types'

/**
 * 文档阅读器，两个入口共用：登录页前面的独立页 `/docs`，以及工作台里的
 * `/documents`。刻意做成一个组件而不是两份实现——目录展开、默认打开第一篇、
 * 403 提示登录这些行为一旦分叉，两个入口就会给出不一样的答案。
 *
 * <p>两处的差别只有外壳（独立页有自己的品牌头）和 `basePath`（链接落在
 * `/docs/…` 还是 `/documents/…`），所以差别通过 props 表达。</p>
 *
 * <p>能看到什么完全由服务端按"这次请求带没带令牌"决定：登录后仅限成员的
 * 文档会自动出现在同一个接口的返回里。这里不做任何前端过滤——那是装饰，
 * 不是权限。</p>
 */
function toTreeData(nodes: DocReaderNode[]): DataNode[] {
  return nodes.map(node => ({
    key: node.publicId,
    title: <span className="docs-tree-label">
      <span className="docs-tree-title">{node.title}</span>
      {node.requiresLogin && <LockOutlined className="docs-tree-lock" title="需要登录后查看" />}
    </span>,
    icon: node.nodeType === 'FOLDER'
      ? (({ expanded }: { expanded?: boolean }) => expanded ? <FolderOpenOutlined /> : <FolderOutlined />)
      : <FileTextOutlined />,
    isLeaf: node.nodeType === 'DOCUMENT',
    children: node.nodeType === 'FOLDER' ? toTreeData(node.children || []) : undefined,
  }))
}

export default function DocsReader({ basePath, publicId, reloadToken = 0 }: {
  /** 目录里的链接落到哪个路由前缀下，例如 `/docs` 或 `/documents`。 */
  basePath: string
  publicId?: string
  /** 变化时强制重新拉目录与正文，供"从编辑模式切回阅读"刷新用。 */
  reloadToken?: number
}) {
  const navigate = useNavigate()
  const { user } = useAuth()
  const [expandedKeys, setExpandedKeys] = useState<string[]>([])
  const [document, setDocument] = useState<DocReaderDocument | null>(null)
  const [documentError, setDocumentError] = useState<{ message: string; needsLogin: boolean } | null>(null)
  const [documentLoading, setDocumentLoading] = useState(false)

  // 登录状态变化要重新拉目录：登录之后仅限成员的文档才会出现。
  const tree = useLoad(
    () => api<DocReaderNode[]>({ url: '/public/docs' }),
    [] as DocReaderNode[],
    [user?.id, reloadToken],
  )

  const shape = useMemo(() => readerNodesToTree(tree.data), [tree.data])
  const treeData = useMemo(() => toTreeData(tree.data), [tree.data])
  const firstDocument = useMemo(() => documentKeysInOrder(shape)[0], [shape])

  // 没有指定文档时打开目录里的第一篇，让页面不至于开局一片空白。
  useEffect(() => {
    if (!publicId && firstDocument) navigate(`${basePath}/${firstDocument}`, { replace: true })
  }, [publicId, firstDocument, navigate, basePath])

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
  }, [publicId, user, reloadToken])

  const empty = !tree.loading && !tree.error && !tree.data.length

  return <div className="docs-body">
    <aside className="docs-sidebar">
      {tree.loading && <Skeleton active paragraph={{ rows: 8 }} />}
      {!tree.loading && tree.error && <Alert type="warning" showIcon message="目录没能加载出来"
        description={tree.error} action={<Button size="small" onClick={() => void tree.reload()}>重试</Button>} />}
      {empty && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={user
        ? '还没有已发布的文档' : '还没有公开的文档'} />}
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
          navigate(`${basePath}/${key}`)
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
          extra={<Button onClick={() => navigate(basePath)}>回到目录</Button>} />)}
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
}
