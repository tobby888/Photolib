import {
  DeleteOutlined, EyeOutlined, FileAddOutlined, FilePdfOutlined, FileTextOutlined,
  FolderAddOutlined, FolderOpenOutlined, FolderOutlined, GlobalOutlined, LockOutlined,
  ReloadOutlined, SaveOutlined, SwapOutlined, TeamOutlined, UploadOutlined,
} from '@ant-design/icons'
import {
  Alert, App, Button, Card, Empty, Input, Popconfirm, Skeleton, Space, Switch,
  Tag, Tree, Typography, Upload,
} from 'antd'
import type { DataNode } from 'antd/es/tree'
import dayjs from 'dayjs'
import { useEffect, useMemo, useState, type Key } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, largeUploadConfig } from '../api'
import { useAuth } from '../auth'
import DocAudiencePicker from '../DocAudiencePicker'
import {
  audienceError, audienceOf, audiencePayload, sameAudience, type AudienceValue,
} from '../docAudience'
import DocPdfViewer from '../DocPdfViewer'
import {
  ancestorKeysOf, findManageNode, manageNodesToTree, relativeDropPosition, resolveDrop,
} from '../docsTree'
import { useLoad } from '../hooks'
import MarkdownEditor from '../MarkdownEditor'
import { hasPermission } from '../permissions'
import type {
  DocDocumentDetail, DocManageNode, DocNodeType, DocTreeMutation,
} from '../types'
import { describeBytes } from '../uploadLimits'
import UploadProgress from '../UploadProgress'
import { useUploadLimits } from '../useUploadLimits'

/**
 * 文档中心的编写页（需要 DOC_MANAGE 或 DOC_PUBLISH）。
 *
 * <p>两条权限管两件事（V62 起）：`DOC_MANAGE` 写内容——新建、改名、正文、PDF、拖拽、删除；
 * `DOC_PUBLISH` 决定给谁看——发布开关和读者范围。只有其中一条的人看得到整棵树，
 * 另一半的控件是灰的；真正的拦截在后端。</p>
 *
 * <p>页面上有两个互相独立的开关，UI 上也刻意分开摆，不做成一个选择器：</p>
 * <ul>
 *   <li><b>发布</b>：草稿只有编辑看得到；</li>
 *   <li><b>读者范围</b>：所有人 / 登录后 / 指定权限组与成员。</li>
 * </ul>
 * <p>合成一个选择器看起来更简洁，但会让"我只想临时下架一篇公开文档"
 * 变成一次会丢掉读者范围设置的操作。</p>
 */
function toTreeData(nodes: DocManageNode[]): DataNode[] {
  return nodes.map(node => {
    // 状态标签按"是不是文件夹"判，不按 DOCUMENT：PDF 文档一样有发布和可见范围
    // 两个开关，漏掉它的标签会让人以为 PDF 传上去就已经发布了。
    const leaf = node.nodeType !== 'FOLDER'
    return {
      key: String(node.id),
      title: <span className="docs-tree-label">
        <span className="docs-tree-title">{node.title}</span>
        {node.nodeType === 'PDF' && <Tag color="blue">PDF</Tag>}
        {leaf && !node.published && <Tag color="default">草稿</Tag>}
        {leaf && node.published && node.visibility === 'PUBLIC' &&
          <Tag color="green" icon={<GlobalOutlined />}>公开</Tag>}
        {leaf && node.published && node.visibility === 'MEMBERS' &&
          <Tag color="gold" icon={<LockOutlined />}>需登录</Tag>}
        {leaf && node.published && node.visibility === 'RESTRICTED' &&
          <Tag color="purple" icon={<TeamOutlined />}>指定成员</Tag>}
      </span>,
      icon: node.nodeType === 'FOLDER'
        ? (({ expanded }: { expanded?: boolean }) => expanded ? <FolderOpenOutlined /> : <FolderOutlined />)
        : node.nodeType === 'PDF' ? <FilePdfOutlined /> : <FileTextOutlined />,
      isLeaf: leaf,
      children: node.nodeType === 'FOLDER' ? toTreeData(node.children || []) : undefined,
    }
  })
}

export default function DocsManagePage({ onPreview }: {
  /** 由外层决定"查看读者视角"去哪；缺省时跳到登录页前面的独立阅读页。 */
  onPreview?: (publicId: string) => void
} = {}) {
  const { message, modal } = App.useApp()
  const { user } = useAuth()
  const canWrite = hasPermission(user, 'DOC_MANAGE')
  const canPublish = hasPermission(user, 'DOC_PUBLISH')
  /** PDF 上限由管理员在「上传限额」里设（DOC_PDF_MAX_BYTES），后端按同一个数再判一次。 */
  const pdfMaxBytes = useUploadLimits().DOC_PDF_MAX_BYTES
  const pdfTooLarge = `PDF 不能超过 ${describeBytes(pdfMaxBytes)}`
  const navigate = useNavigate()
  const [tree, setTree] = useState<DocManageNode[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [expandedKeys, setExpandedKeys] = useState<string[]>([])
  const [detail, setDetail] = useState<DocDocumentDetail | null>(null)
  const [draft, setDraft] = useState('')
  const [detailLoading, setDetailLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  // PDF 上传进度：kind 决定进度条摆在目录旁（新建）还是替换按钮旁。
  const [pdfUpload, setPdfUpload] = useState<{ kind: 'new' | 'replace'; percent: number } | null>(null)
  const [renaming, setRenaming] = useState('')
  // 替换文件后强制重新取一遍预览：地址没变，不加这个的话看到的还是旧文件。
  const [pdfToken, setPdfToken] = useState(0)

  const loaded = useLoad(() => api<DocManageNode[]>({ url: '/docs/tree' }), [] as DocManageNode[], [])
  useEffect(() => { setTree(loaded.data) }, [loaded.data])

  const shape = useMemo(() => manageNodesToTree(tree), [tree])
  const treeData = useMemo(() => toTreeData(tree), [tree])
  const selected = useMemo(
    () => (selectedId ? findManageNode(tree, selectedId) : undefined), [tree, selectedId])

  const selectedTitle = selected?.title || ''
  useEffect(() => { setRenaming(selectedTitle) }, [selectedId, selectedTitle])

  // 读者范围的草稿：所有人 / 登录后点一下就生效（和以前一样），指定成员要挑完名单再点保存。
  // 按节点和版本重置——别人改过、或者自己刚保存过，草稿都跟着服务端的值走。
  const savedAudience = useMemo<AudienceValue | null>(() => (selected && selected.nodeType !== 'FOLDER'
    ? audienceOf(selected) : null), [selected])
  const [audienceDraft, setAudienceDraft] = useState<AudienceValue | null>(null)
  useEffect(() => { setAudienceDraft(savedAudience) }, [savedAudience])
  const audienceDirty = !!audienceDraft && !!savedAudience && !sameAudience(audienceDraft, savedAudience)

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

  /** 新建落点：选中文件夹就放进去，选中文档就放到它旁边（同一个父节点）。 */
  const parentForNew = () => (selected
    ? (selected.nodeType === 'FOLDER' ? String(selected.id)
      : selected.parentId != null ? String(selected.parentId) : null)
    : null)

  const create = (nodeType: DocNodeType) => {
    const parentId = parentForNew()
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

  /**
   * 上传一份 PDF 作为新文档。标题默认取文件名（去掉扩展名），传完可以直接改名——
   * 先弹一个填标题的框会让"把手头这份 PDF 放上去"多一步，而这一步本来可有可无。
   */
  const uploadPdf = async (file: File) => {
    if (file.size > pdfMaxBytes) {
      message.error(pdfTooLarge)
      return
    }
    const parentId = parentForNew()
    const title = (file.name.replace(/\.pdf$/i, '').trim() || 'PDF 文档').slice(0, 200)
    const form = new FormData()
    form.append('file', file)
    form.append('title', title)
    if (parentId) form.append('parentId', parentId)
    setPdfUpload({ kind: 'new', percent: 0 })
    const created = await mutate(
      () => api<DocTreeMutation>({
        method: 'POST', url: '/docs/pdf', data: form,
        ...largeUploadConfig(percent => setPdfUpload({ kind: 'new', percent })),
      }).finally(() => setPdfUpload(null)),
      'PDF 已上传，可以设置发布与可见范围了')
    if (created && parentId) {
      setExpandedKeys(current => Array.from(new Set([...current, parentId])))
    }
  }

  /** 换掉已有 PDF 的文件。对象键跟着 publicId 走，读者手上的链接继续有效。 */
  const replacePdf = async (file: File) => {
    if (!selected) return
    if (file.size > pdfMaxBytes) {
      message.error(pdfTooLarge)
      return
    }
    const form = new FormData()
    form.append('file', file)
    setPdfUpload({ kind: 'replace', percent: 0 })
    await mutate(() => api<DocTreeMutation>({
      method: 'PUT', url: `/docs/${selected.id}/pdf`,
      params: { version: selected.version }, data: form,
      ...largeUploadConfig(percent => setPdfUpload({ kind: 'replace', percent })),
    }).finally(() => setPdfUpload(null)), 'PDF 已替换')
    // 版本变了，预览要重新取一遍文件。
    setPdfToken(token => token + 1)
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

  const saveAudience = (value: AudienceValue) => {
    if (!selected) return
    const problem = audienceError(value)
    if (problem) {
      message.error(problem)
      return
    }
    void mutate(() => api<DocTreeMutation>({
      method: 'POST', url: `/docs/${selected.id}/visibility`,
      data: { ...audiencePayload(value), version: selected.version },
    }), value.visibility === 'PUBLIC' ? '已设为所有人可见'
      : value.visibility === 'MEMBERS' ? '已设为登录后可见' : '已设为只给指定成员看')
  }

  const changeAudience = (value: AudienceValue) => {
    setAudienceDraft(value)
    // 两个"不用挑名单"的档位点了就存；指定成员等挑完再点保存。
    if (value.visibility !== 'RESTRICTED') saveAudience(value)
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
        <Button size="small" icon={<FolderAddOutlined />} disabled={!canWrite}
          onClick={() => create('FOLDER')}>文件夹</Button>
        <Button size="small" type="primary" icon={<FileAddOutlined />} disabled={!canWrite}
          onClick={() => create('DOCUMENT')}>文档</Button>
        <Upload accept="application/pdf,.pdf" showUploadList={false} disabled={busy || !canWrite}
          beforeUpload={file => { void uploadPdf(file); return false }}>
          <Button size="small" icon={<UploadOutlined />} disabled={busy || !canWrite}>PDF</Button>
        </Upload>
        <Button size="small" icon={<ReloadOutlined />} onClick={() => void loaded.reload()} />
      </Space>}>
      <Alert type="info" showIcon className="docs-manage-hint"
        message={canWrite ? '拖动条目可以调整顺序，或把它拖进文件夹。'
          : '你可以发布文档、设置读者范围；新建、编辑和整理目录需要「编写文档」权限。'} />
      {pdfUpload?.kind === 'new' && <UploadProgress percent={pdfUpload.percent} />}
      {loaded.loading && <Skeleton active paragraph={{ rows: 8 }} />}
      {!loaded.loading && loaded.error && <Alert type="warning" showIcon message="目录没能加载出来"
        description={loaded.error} />}
      {!loaded.loading && !loaded.error && !tree.length &&
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有任何文档，先新建一个吧" />}
      {!loaded.loading && !!tree.length && <Tree
        showIcon
        blockNode
        draggable={canWrite}
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
          <Input value={renaming} maxLength={200} style={{ width: 320 }} disabled={!canWrite}
            onChange={event => setRenaming(event.target.value)} onPressEnter={rename} />
          <Button onClick={rename}
            disabled={busy || !canWrite || !renaming.trim() || renaming.trim() === selected.title}>
            重命名
          </Button>
          <Popconfirm title={selected.nodeType === 'FOLDER' ? '连同里面的内容一起删除？' : '删除这篇文档？'}
            description="删除后读者立刻看不到，正文仍保留在对象存储里以备恢复。"
            okText="删除" cancelText="取消" okButtonProps={{ danger: true }} onConfirm={remove}>
            <Button danger icon={<DeleteOutlined />} disabled={busy || !canWrite}>删除</Button>
          </Popconfirm>
        </Space>

        {selected.nodeType === 'FOLDER' && <Alert type="info" showIcon
          message="文件夹本身没有发布开关"
          description="它会在下面有读者能看到的文档时，自动出现在读者目录里。" />}

        {selected.nodeType !== 'FOLDER' && <>
          <Space wrap size={24}>
            <Space>
              <Typography.Text strong>发布</Typography.Text>
              <Switch checked={selected.published} disabled={busy || !canPublish || !selected.hasContent}
                onChange={setPublished} checkedChildren="已发布" unCheckedChildren="草稿" />
              {!selected.hasContent &&
                <Typography.Text type="secondary">写完正文才能发布</Typography.Text>}
            </Space>
            {selected.published && <Button icon={<EyeOutlined />}
              onClick={() => onPreview
                ? onPreview(selected.publicId)
                : navigate(`/docs/${selected.publicId}`)}>查看读者视角</Button>}
          </Space>

          {audienceDraft && <div>
            <Typography.Text strong>读者范围</Typography.Text>
            {!canPublish && <Typography.Text type="secondary">（需要「发布文档」权限才能修改）</Typography.Text>}
            <div style={{ marginTop: 8, maxWidth: 640 }}>
              <DocAudiencePicker value={audienceDraft} onChange={changeAudience} disabled={busy || !canPublish} />
            </div>
            {audienceDraft.visibility === 'RESTRICTED' && audienceDirty && <Space style={{ marginTop: 8 }}>
              <Button type="primary" icon={<TeamOutlined />} disabled={busy || !!audienceError(audienceDraft)}
                onClick={() => saveAudience(audienceDraft)}>保存读者名单</Button>
              <Button disabled={busy} onClick={() => setAudienceDraft(savedAudience)}>撤销</Button>
            </Space>}
          </div>}

          {selected.visibility === 'RESTRICTED' && selected.published && <Alert type="warning" showIcon
            message="这篇文档只对指定的成员开放"
            description="名单外的人（包括未登录访客）在目录里看不到它，直接打开链接会被拒绝，插图和 PDF 直链同样拒绝。" />}

          {selected.visibility === 'MEMBERS' && selected.published && <Alert type="warning" showIcon
            message="这篇文档需要登录才能查看"
            description={selected.nodeType === 'PDF'
              ? '未登录的访客在目录里看不到它，直接打开链接也会被要求先登录，PDF 文件的直链同样拒绝匿名访问。'
              : '未登录的访客在目录里看不到它，直接打开链接也会被要求先登录，文档里的插图同样拒绝匿名访问。'} />}

          {selected.nodeType === 'PDF' && <>
            <Space wrap>
              <Upload accept="application/pdf,.pdf" showUploadList={false} disabled={busy || !canWrite}
                beforeUpload={file => { void replacePdf(file); return false }}>
                <Button icon={<SwapOutlined />} disabled={busy || !canWrite}>替换 PDF 文件</Button>
              </Upload>
              <Typography.Text type="secondary">
                {selected.contentSize ? `${(selected.contentSize / 1024 / 1024).toFixed(1)} MiB · ` : ''}
                最多 {describeBytes(pdfMaxBytes)}；替换后读者手上的链接继续有效。
              </Typography.Text>
            </Space>
            {pdfUpload?.kind === 'replace' && <UploadProgress percent={pdfUpload.percent} />}
            <DocPdfViewer key={pdfToken} path={`/docs/${selected.id}/file`}
              title={selected.title} height="60vh" />
          </>}

          {selected.nodeType === 'DOCUMENT' && detailLoading &&
            <Skeleton active paragraph={{ rows: 8 }} />}
          {selected.nodeType === 'DOCUMENT' && !detailLoading && detail && <>
            <MarkdownEditor value={draft} onChange={setDraft} maxLength={100000}
              uploadUrl={`/docs/${detail.node.id}/assets`}
              placeholder="使用 Markdown 编写文档；可以直接上传插图" />
            <Space>
              <Button type="primary" icon={<SaveOutlined />} loading={busy}
                disabled={!dirty || !canWrite} onClick={() => void saveContent()}>保存正文</Button>
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
