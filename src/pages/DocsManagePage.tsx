import {
  DeleteOutlined, EyeOutlined, FileAddOutlined, FileTextOutlined, FolderAddOutlined,
  FolderOpenOutlined, FolderOutlined, GlobalOutlined, LockOutlined, ReloadOutlined, SaveOutlined,
} from '@ant-design/icons'
import {
  Alert, App, Button, Card, Empty, Input, Popconfirm, Segmented, Skeleton, Space, Switch,
  Tag, Tree, Typography,
} from 'antd'
import type { DataNode } from 'antd/es/tree'
import dayjs from 'dayjs'
import { useEffect, useMemo, useState, type Key } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import {
  ancestorKeysOf, findManageNode, manageNodesToTree, relativeDropPosition, resolveDrop,
} from '../docsTree'
import { useLoad } from '../hooks'
import MarkdownEditor from '../MarkdownEditor'
import type {
  DocDocumentDetail, DocManageNode, DocNodeType, DocTreeMutation, DocVisibility,
} from '../types'

/**
 * 文档中心的编写页（需要 DOC_MANAGE）。
 *
 * <p>页面上有两个互相独立的开关，UI 上也刻意分开摆，不做成一个三档选择器：</p>
 * <ul>
 *   <li><b>发布</b>：草稿只有编辑看得到；</li>
 *   <li><b>可见范围</b>：公开（未登录也能看）还是仅成员（必须登录）。</li>
 * </ul>
 * <p>合成一个选择器看起来更简洁，但会让"我只想临时下架一篇公开文档"
 * 变成一次会丢掉可见范围设置的操作。</p>
 */
function toTreeData(nodes: DocManageNode[]): DataNode[] {
  return nodes.map(node => ({
    key: String(node.id),
    title: <span className="docs-tree-label">
      <span className="docs-tree-title">{node.title}</span>
      {node.nodeType === 'DOCUMENT' && !node.published && <Tag color="default">草稿</Tag>}
      {node.nodeType === 'DOCUMENT' && node.published && node.visibility === 'PUBLIC' &&
        <Tag color="green" icon={<GlobalOutlined />}>公开</Tag>}
      {node.nodeType === 'DOCUMENT' && node.published && node.visibility === 'MEMBERS' &&
        <Tag color="gold" icon={<LockOutlined />}>需登录</Tag>}
    </span>,
    icon: node.nodeType === 'FOLDER'
      ? (({ expanded }: { expanded?: boolean }) => expanded ? <FolderOpenOutlined /> : <FolderOutlined />)
      : <FileTextOutlined />,
    isLeaf: node.nodeType === 'DOCUMENT',
    children: node.nodeType === 'FOLDER' ? toTreeData(node.children || []) : undefined,
  }))
}

export default function DocsManagePage() {
  const { message, modal } = App.useApp()
  const navigate = useNavigate()
  const [tree, setTree] = useState<DocManageNode[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [expandedKeys, setExpandedKeys] = useState<string[]>([])
  const [detail, setDetail] = useState<DocDocumentDetail | null>(null)
  const [draft, setDraft] = useState('')
  const [detailLoading, setDetailLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [renaming, setRenaming] = useState('')

  const loaded = useLoad(() => api<DocManageNode[]>({ url: '/docs/tree' }), [] as DocManageNode[], [])
  useEffect(() => { setTree(loaded.data) }, [loaded.data])

  const shape = useMemo(() => manageNodesToTree(tree), [tree])
  const treeData = useMemo(() => toTreeData(tree), [tree])
  const selected = useMemo(
    () => (selectedId ? findManageNode(tree, selectedId) : undefined), [tree, selectedId])

  const selectedTitle = selected?.title || ''
  useEffect(() => { setRenaming(selectedTitle) }, [selectedId, selectedTitle])

  /**
   * 只有"选中的文档换了一篇"才重新拉正文。
   *
   * <p>这里刻意依赖这个字符串而不是 selected 对象：每次发布、改可见范围、拖拽
   * 都会换回一整棵新树，selected 的对象身份随之改变。若按对象重跑这个 effect，
   * 每改一次开关都会把编辑器里没保存的草稿冲掉。</p>
   */
  const openDocumentId = selected?.nodeType === 'DOCUMENT' ? String(selected.id) : null

  useEffect(() => {
    if (!openDocumentId) {
      setDetail(null)
      setDraft('')
      return
    }
    let active = true
    setDetailLoading(true)
    void api<DocDocumentDetail>({ url: `/docs/${openDocumentId}` })
      .then(value => {
        if (!active) return
        setDetail(value)
        setDraft(value.content)
      })
      .catch((reason: unknown) => { if (active) message.error((reason as Error).message) })
      .finally(() => { if (active) setDetailLoading(false) })
    return () => { active = false }
  }, [openDocumentId, message])

  const dirty = !!detail && draft !== detail.content

  /** 所有写操作都走这里：服务端返回整棵新树，前端整体替换，不做局部打补丁。 */
  const mutate = async (run: () => Promise<DocTreeMutation>, success: string) => {
    setBusy(true)
    try {
      const result = await run()
      setTree(result.tree)
      setSelectedId(result.focusId != null ? String(result.focusId) : null)
      message.success(success)
      return true
    } catch (reason) {
      message.error((reason as Error).message)
      return false
    } finally {
      setBusy(false)
    }
  }

  const create = (nodeType: DocNodeType) => {
    // 新建的落点：选中文件夹就放进去，选中文档就放到它旁边（同一个父节点）。
    const parentId = selected
      ? (selected.nodeType === 'FOLDER' ? String(selected.id)
        : selected.parentId != null ? String(selected.parentId) : null)
      : null
    let title = ''
    modal.confirm({
      title: nodeType === 'FOLDER' ? '新建文件夹' : '新建文档',
      content: <Input autoFocus placeholder={nodeType === 'FOLDER' ? '文件夹名称' : '文档标题'}
        maxLength={200} onChange={event => { title = event.target.value }} />,
      okText: '创建',
      cancelText: '取消',
      onOk: async () => {
        if (!title.trim()) {
          message.error('名称不能为空')
          throw new Error('名称不能为空')
        }
        const created = await mutate(
          () => api<DocTreeMutation>({ method: 'POST', url: '/docs', data: { parentId, nodeType, title } }),
          nodeType === 'FOLDER' ? '文件夹已创建' : '文档已创建，可以开始写正文了')
        if (!created) throw new Error('创建失败')
        if (parentId) setExpandedKeys(current => Array.from(new Set([...current, parentId])))
      },
    })
  }

  const saveContent = async () => {
    if (!detail || !selected) return
    setBusy(true)
    try {
      // 版本取自树上的节点而不是 detail：发布、改可见范围都会让版本 +1，
      // 而它们不会重新拉正文，detail.node.version 从那一刻起就是旧的了。
      const saved = await api<DocDocumentDetail>({
        method: 'PUT', url: `/docs/${detail.node.id}/content`,
        data: { content: draft, version: selected.version },
      })
      setDetail(saved)
      setDraft(saved.content)
      // 保存会让 version 和"是否有正文"变化，树上的状态要跟着更新。
      setTree(await api<DocManageNode[]>({ url: '/docs/tree' }))
      message.success('正文已保存到对象存储')
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const handleDrop = (info: {
    dragNode: { key: Key }
    node: { key: Key; pos: string }
    dropPosition: number
    dropToGap: boolean
  }) => {
    const dragKey = String(info.dragNode.key)
    const node = findManageNode(tree, dragKey)
    if (!node) return
    const relative = relativeDropPosition(info.node.pos, info.dropPosition)
    const target = resolveDrop(shape, dragKey, String(info.node.key), info.dropToGap, relative)
    if (!target) {
      message.warning('只能放进文件夹，或放在同级条目之间')
      return
    }
    void mutate(() => api<DocTreeMutation>({
      method: 'POST', url: `/docs/${dragKey}/move`,
      data: { parentId: target.parentKey, index: target.index, version: node.version },
    }), '目录已更新')
  }

  const setPublished = (published: boolean) => {
    if (!selected) return
    void mutate(() => api<DocTreeMutation>({
      method: 'POST', url: `/docs/${selected.id}/publication`,
      data: { published, version: selected.version },
    }), published ? '文档已发布' : '文档已退回草稿')
  }

  const setVisibility = (visibility: DocVisibility) => {
    if (!selected) return
    void mutate(() => api<DocTreeMutation>({
      method: 'POST', url: `/docs/${selected.id}/visibility`,
      data: { visibility, version: selected.version },
    }), visibility === 'PUBLIC' ? '已设为所有人可见' : '已设为登录后可见')
  }

  const rename = () => {
    if (!selected || renaming.trim() === selected.title) return
    void mutate(() => api<DocTreeMutation>({
      method: 'PUT', url: `/docs/${selected.id}/title`,
      data: { title: renaming, version: selected.version },
    }), '名称已更新')
  }

  const remove = () => {
    if (!selected) return
    void mutate(() => api<DocTreeMutation>({
      method: 'DELETE', url: `/docs/${selected.id}`, params: { version: selected.version },
    }), '已删除')
  }

  return <div className="docs-manage-page">
    <Card className="docs-manage-tree" styles={{ body: { padding: 12 } }}
      title="文档目录"
      extra={<Space size={4}>
        <Button size="small" icon={<FolderAddOutlined />} onClick={() => create('FOLDER')}>文件夹</Button>
        <Button size="small" type="primary" icon={<FileAddOutlined />} onClick={() => create('DOCUMENT')}>文档</Button>
        <Button size="small" icon={<ReloadOutlined />} onClick={() => void loaded.reload()} />
      </Space>}>
      <Alert type="info" showIcon className="docs-manage-hint"
        message="拖动条目可以调整顺序，或把它拖进文件夹。" />
      {loaded.loading && <Skeleton active paragraph={{ rows: 8 }} />}
      {!loaded.loading && loaded.error && <Alert type="warning" showIcon message="目录没能加载出来"
        description={loaded.error} />}
      {!loaded.loading && !loaded.error && !tree.length &&
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有任何文档，先新建一个吧" />}
      {!loaded.loading && !!tree.length && <Tree
        showIcon
        blockNode
        draggable
        disabled={busy}
        treeData={treeData}
        selectedKeys={selectedId ? [selectedId] : []}
        expandedKeys={expandedKeys}
        onExpand={keys => setExpandedKeys(keys.map(String))}
        onSelect={(_keys, info) => {
          const key = String(info.node.key)
          setSelectedId(key)
          setExpandedKeys(current => Array.from(new Set([...current, ...ancestorKeysOf(shape, key)])))
        }}
        onDrop={handleDrop} />}
    </Card>

    <Card className="docs-manage-detail">
      {!selected && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="从左边选一个文件夹或文档" />}
      {selected && <Space orientation="vertical" size={16} style={{ width: '100%' }}>
        <Space wrap>
          <Input value={renaming} maxLength={200} style={{ width: 320 }}
            onChange={event => setRenaming(event.target.value)} onPressEnter={rename} />
          <Button onClick={rename} disabled={busy || !renaming.trim() || renaming.trim() === selected.title}>
            重命名
          </Button>
          <Popconfirm title={selected.nodeType === 'FOLDER' ? '连同里面的内容一起删除？' : '删除这篇文档？'}
            description="删除后读者立刻看不到，正文仍保留在对象存储里以备恢复。"
            okText="删除" cancelText="取消" okButtonProps={{ danger: true }} onConfirm={remove}>
            <Button danger icon={<DeleteOutlined />} disabled={busy}>删除</Button>
          </Popconfirm>
        </Space>

        {selected.nodeType === 'FOLDER' && <Alert type="info" showIcon
          message="文件夹本身没有发布开关"
          description="它会在下面有读者能看到的文档时，自动出现在读者目录里。" />}

        {selected.nodeType === 'DOCUMENT' && <>
          <Space wrap size={24}>
            <Space>
              <Typography.Text strong>发布</Typography.Text>
              <Switch checked={selected.published} disabled={busy || !selected.hasContent}
                onChange={setPublished} checkedChildren="已发布" unCheckedChildren="草稿" />
              {!selected.hasContent &&
                <Typography.Text type="secondary">写完正文才能发布</Typography.Text>}
            </Space>
            <Space>
              <Typography.Text strong>可见范围</Typography.Text>
              <Segmented value={selected.visibility} disabled={busy}
                onChange={value => setVisibility(value as DocVisibility)}
                options={[
                  { value: 'PUBLIC', label: <Space size={4}><GlobalOutlined />所有人</Space> },
                  { value: 'MEMBERS', label: <Space size={4}><LockOutlined />登录后</Space> },
                ]} />
            </Space>
            {selected.published && <Button icon={<EyeOutlined />}
              onClick={() => navigate(`/docs/${selected.publicId}`)}>查看读者视角</Button>}
          </Space>

          {selected.visibility === 'MEMBERS' && selected.published && <Alert type="warning" showIcon
            message="这篇文档需要登录才能查看"
            description="未登录的访客在目录里看不到它，直接打开链接也会被要求先登录，文档里的插图同样拒绝匿名访问。" />}

          {detailLoading && <Skeleton active paragraph={{ rows: 8 }} />}
          {!detailLoading && detail && <>
            <MarkdownEditor value={draft} onChange={setDraft} maxLength={100000}
              uploadUrl={`/docs/${detail.node.id}/assets`}
              placeholder="使用 Markdown 编写文档；可以直接上传插图" />
            <Space>
              <Button type="primary" icon={<SaveOutlined />} loading={busy}
                disabled={!dirty} onClick={() => void saveContent()}>保存正文</Button>
              {dirty && <Typography.Text type="warning">有未保存的修改</Typography.Text>}
              {!dirty && detail.node.updatedAt && <Typography.Text type="secondary">
                最后更新 {dayjs(detail.node.updatedAt).format('YYYY-MM-DD HH:mm')}
                {detail.node.updaterDisplayName ? ` · ${detail.node.updaterDisplayName}` : ''}
              </Typography.Text>}
            </Space>
          </>}
        </>}
      </Space>}
    </Card>
  </div>
}
