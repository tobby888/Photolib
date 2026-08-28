import {
  App, Button, Card, Col, DatePicker, Form, Input, InputNumber, Modal, Pagination, Radio, Row,
  Select, Space, Tag, Tree, Typography,
} from 'antd'
import { ArrowRightOutlined, CalendarOutlined, FileWordOutlined, PictureOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, emptyPage, qs } from '../api'
import { useAuth } from '../auth'
import { DataState, PageTitle } from '../components'
import {
  buildAssignmentTree, checkedKeysToSelection, coveredUserIds, describeSelection,
  pruneCoveredUsers, selectionToCheckedKeys, type AssignmentNode,
} from '../featuredAssignmentTree'
import { FEATURED_DOCUMENT_LABELS, featuredStatusDisplay } from '../featuredCollections'
import { useLoad } from '../hooks'
import { hasPermission } from '../permissions'
import RichTextEditor from '../RichTextEditor'
import type {
  Campus, CampusAssignmentUser, EntityId, FeaturedCollection, PageData,
} from '../types'

interface CollectionValues {
  title: string
  window: [Dayjs, Dayjs]
  entryLimit: number
  scope: 'ALL' | 'PICKED'
  campusIds: EntityId[]
  userIds: EntityId[]
}

// 共享的空数组：useWatch 未初始化时每次渲染都新建一个 []，会让下面的 useMemo 永远失效。
const NO_IDS: EntityId[] = []

const statusOptions = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'CLOSED', label: '已截止' },
]

export default function FeaturedCollectionsPage() {
  const { user } = useAuth()
  const { message, modal } = App.useApp()
  const navigate = useNavigate()
  const [form] = Form.useForm<CollectionValues>()
  const [editing, setEditing] = useState<FeaturedCollection | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [requirementHtml, setRequirementHtml] = useState('')
  const [saving, setSaving] = useState(false)
  const [filters, setFilters] = useState({ page: 1, keyword: '', status: '' })
  const canManage = hasPermission(user, 'FEATURED_MANAGE')

  const collections = useLoad(
    () => api<PageData<FeaturedCollection>>({
      url: '/featured-collections', params: qs({ ...filters, pageSize: 12 }),
    }),
    emptyPage<FeaturedCollection>(), [filters.page, filters.keyword, filters.status],
  )

  // 指派候选只有管理精选的人才需要，普通读者不必为此多发两个请求。
  const { data: campuses } = useLoad(
    () => canManage ? api<Campus[]>({ url: '/campuses' }) : Promise.resolve([]),
    [] as Campus[], [canManage],
  )
  const { data: managers } = useLoad(
    () => canManage ? api<CampusAssignmentUser[]>({ url: '/featured-collections/assignable-managers' }) : Promise.resolve([]),
    [] as CampusAssignmentUser[], [canManage],
  )

  const openCreate = () => {
    setEditing(null)
    setRequirementHtml('')
    form.setFieldsValue({
      title: '',
      window: [dayjs().add(1, 'hour').startOf('hour'), dayjs().add(14, 'day').endOf('day')],
      entryLimit: 5,
      scope: 'ALL',
      campusIds: [],
      userIds: [],
    })
    setModalOpen(true)
  }

  const openEdit = (collection: FeaturedCollection) => {
    setEditing(collection)
    setRequirementHtml(collection.requirementHtml || '')
    form.setFieldsValue({
      title: collection.title,
      window: [dayjs(collection.startsAt), dayjs(collection.endsAt)],
      entryLimit: collection.entryLimit,
      scope: collection.assignAll ? 'ALL' : 'PICKED',
      campusIds: collection.campusIds,
      userIds: collection.userIds,
    })
    setModalOpen(true)
  }

  const save = async () => {
    let values: CollectionValues
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    if (!values.window?.[1]?.isAfter(values.window[0])) {
      message.error('截止时间要晚于开始时间')
      return
    }
    const picked = values.scope === 'PICKED'
    if (picked && !values.campusIds?.length && !values.userIds?.length) {
      message.error('请至少选择一个校区或一位负责人')
      return
    }
    const payload = {
      title: values.title,
      requirementHtml,
      startsAt: values.window[0].format('YYYY-MM-DDTHH:mm:ss'),
      endsAt: values.window[1].format('YYYY-MM-DDTHH:mm:ss'),
      assignAll: !picked,
      entryLimit: values.entryLimit,
      campusIds: picked ? values.campusIds || [] : [],
      userIds: picked ? values.userIds || [] : [],
    }
    setSaving(true)
    try {
      if (editing) {
        await api<FeaturedCollection>({
          method: 'PUT', url: `/featured-collections/${editing.id}`,
          data: { ...payload, version: editing.version },
        })
        message.success('已保存')
      } else {
        await api<FeaturedCollection>({ method: 'POST', url: '/featured-collections', data: payload })
        message.success('精选已创建，发布后负责人才会收到通知')
      }
      setModalOpen(false)
      await collections.reload()
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const publish = async (collection: FeaturedCollection) => {
    try {
      await api<FeaturedCollection>({
        method: 'POST', url: `/featured-collections/${collection.id}/publish`,
        data: { version: collection.version },
      })
      message.success('已发布，被指派的负责人会收到站内通知')
      await collections.reload()
    } catch (error) {
      message.error((error as Error).message)
    }
  }

  const close = (collection: FeaturedCollection) => modal.confirm({
    title: '现在就截止这次精选？',
    content: '截止后负责人不能再增删条目，服务器会立即开始生成 Word 文档。',
    okText: '截止并生成文档',
    cancelText: '再等等',
    onOk: async () => {
      try {
        await api<FeaturedCollection>({
          method: 'POST', url: `/featured-collections/${collection.id}/close`,
        })
        message.success('已截止，文档正在后台生成')
        await collections.reload()
      } catch (error) {
        message.error((error as Error).message)
      }
    },
  })

  const remove = (collection: FeaturedCollection) => modal.confirm({
    title: `删除「${collection.title}」？`,
    content: '删除后这份精选和它的条目都不会再出现在列表里。',
    okText: '删除',
    okButtonProps: { danger: true },
    cancelText: '取消',
    onOk: async () => {
      try {
        await api<void>({
          method: 'DELETE', url: `/featured-collections/${collection.id}`,
          params: { version: collection.version },
        })
        message.success('已删除')
        await collections.reload()
      } catch (error) {
        message.error((error as Error).message)
      }
    },
  })

  const scope = Form.useWatch('scope', form)
  const campusIds = Form.useWatch('campusIds', form) ?? NO_IDS
  const userIds = Form.useWatch('userIds', form) ?? NO_IDS
  const tree = useMemo(() => buildAssignmentTree(campuses, managers), [campuses, managers])
  const covered = useMemo(() => coveredUserIds(campusIds, managers), [campusIds, managers])
  const checkedKeys = useMemo(
    () => selectionToCheckedKeys({ campusIds, userIds }, tree),
    [campusIds, userIds, tree])

  const onCheck = (keys: React.Key[] | { checked: React.Key[] }) => {
    // checkStrictly 下 antd 回传的是 { checked, halfChecked }。
    const next = pruneCoveredUsers(
      checkedKeysToSelection(Array.isArray(keys) ? keys : keys.checked), managers)
    form.setFieldsValue({ campusIds: next.campusIds, userIds: next.userIds })
  }

  const treeData = useMemo(() => tree.map(node => toTreeNode(node, covered)), [tree, covered])

  return <div className="page">
    <PageTitle eyebrow="好图精选" title="好图精选"
      description="部长发布征集要求，各校区负责人从图库选图并补充拍摄思路；截止后自动生成按校区分章的 Word 文档。"
      extra={canManage && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>发布精选</Button>} />

    <Card className="filter-card">
      <Space wrap>
        <Input allowClear prefix={<SearchOutlined />} placeholder="搜索标题或要求"
          defaultValue={filters.keyword} style={{ width: 240 }}
          onPressEnter={(event) => setFilters({
            ...filters, page: 1, keyword: (event.target as HTMLInputElement).value.trim(),
          })}
          onChange={(event) => { if (!event.target.value) setFilters({ ...filters, page: 1, keyword: '' }) }} />
        <Select allowClear placeholder="全部状态" style={{ width: 160 }} options={statusOptions}
          value={filters.status || undefined}
          onChange={(value) => setFilters({ ...filters, page: 1, status: value || '' })} />
      </Space>
    </Card>

    <DataState loading={collections.loading} error={collections.error}
      empty={!collections.data.items.length} onRetry={collections.reload}
      emptyText="还没有好图精选"
      emptyHint={canManage ? '点右上角发布一次征集，负责人就能开始报送作品了。' : '等部长发布征集后，这里会出现需要你填写的精选。'}>
      <Row gutter={[16, 16]}>
        {collections.data.items.map(collection => {
          const status = featuredStatusDisplay(collection)
          return <Col key={collection.id} xs={24} md={12} xl={8}>
            <Card className="entity-card" title={collection.title}
              extra={<Tag color={status.color} variant="filled">{status.label}</Tag>}
              actions={[
                <Button key="open" type="link" icon={<ArrowRightOutlined />}
                  onClick={() => navigate(`/featured/${collection.id}`)}>
                  {collection.submissionOpen ? '去填写' : '查看'}
                </Button>,
              ]}>
              <Space direction="vertical" size={6} style={{ width: '100%' }}>
                <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }} style={{ marginBottom: 0 }}>
                  {collection.requirementText || '（未填写要求）'}
                </Typography.Paragraph>
                <Typography.Text type="secondary">
                  <CalendarOutlined /> {dayjs(collection.startsAt).format('MM-DD HH:mm')}
                  {' 至 '}{dayjs(collection.endsAt).format('MM-DD HH:mm')}
                </Typography.Text>
                <Space wrap size={4}>
                  <Tag icon={<PictureOutlined />}>已收 {collection.entryCount} 张</Tag>
                  <Tag>每人上限 {collection.entryLimit}</Tag>
                  {collection.assignAll ? <Tag>全部校区负责人</Tag>
                    : <Tag>指定 {collection.campusIds.length} 校区 / {collection.userIds.length} 人</Tag>}
                  {collection.assignedToMe && <Tag color="green">需要我提交</Tag>}
                  {collection.status === 'CLOSED' && <Tag icon={<FileWordOutlined />}
                    color={collection.documentStatus === 'READY' ? 'blue'
                      : collection.documentStatus === 'FAILED' ? 'red' : 'default'}>
                    {FEATURED_DOCUMENT_LABELS[collection.documentStatus]}
                  </Tag>}
                </Space>
                {canManage && <Space wrap size={4}>
                  {collection.status === 'DRAFT' && <>
                    <Button size="small" onClick={() => openEdit(collection)}>编辑</Button>
                    <Button size="small" type="primary" onClick={() => void publish(collection)}>发布</Button>
                  </>}
                  {collection.status === 'PUBLISHED' && <>
                    <Button size="small" onClick={() => openEdit(collection)}>编辑</Button>
                    <Button size="small" onClick={() => close(collection)}>手动截止</Button>
                  </>}
                  <Button size="small" danger onClick={() => remove(collection)}>删除</Button>
                </Space>}
              </Space>
            </Card>
          </Col>
        })}
      </Row>
      {collections.data.total > collections.data.pageSize && <Pagination className="pager"
        current={collections.data.page} pageSize={collections.data.pageSize}
        total={collections.data.total} showSizeChanger={false}
        onChange={(page) => setFilters({ ...filters, page })} />}
    </DataState>

    <Modal open={modalOpen} width={720} title={editing ? '编辑好图精选' : '发布好图精选'}
      okText={editing ? '保存' : '创建草稿'} cancelText="取消" confirmLoading={saving}
      onOk={() => void save()} onCancel={() => setModalOpen(false)} destroyOnHidden>
      <Form form={form} layout="vertical">
        <Form.Item name="title" label="标题" rules={[{ required: true, message: '请填写标题' }]}>
          <Input maxLength={200} placeholder="例如：2026 年春季好图精选" />
        </Form.Item>
        <Form.Item label="征集要求" extra="支持加粗、列表和插图；负责人会在填写页看到这段要求。">
          {/* 说明图片对任何登录用户可读，正是这里需要的可见范围；
              消息图片只对收件人可读，用在这里会让负责人看到裂图。 */}
          <RichTextEditor value={requirementHtml} onChange={setRequirementHtml}
            uploadUrl="/description-images" placeholder="写清楚要什么题材、什么风格、交几张……" />
        </Form.Item>
        <Row gutter={16}>
          <Col span={16}>
            <Form.Item name="window" label="填报时间"
              rules={[{ required: true, message: '请选择开始和截止时间' }]}
              extra={editing?.status === 'PUBLISHED' ? '已发布后只能延长或提前截止时间，开始时间不可改。' : undefined}>
              <DatePicker.RangePicker showTime style={{ width: '100%' }}
                disabled={editing?.status === 'PUBLISHED' ? [true, false] : false} />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="entryLimit" label="每人最多提交"
              rules={[{ required: true, message: '请填写上限' }]}>
              <InputNumber min={1} max={50} style={{ width: '100%' }} addonAfter="张" />
            </Form.Item>
          </Col>
        </Row>
        <Form.Item name="scope" label="谁需要提交">
          <Radio.Group options={[
            { value: 'ALL', label: '全部校区负责人' },
            { value: 'PICKED', label: '指定校区或个人' },
          ]} optionType="button" />
        </Form.Item>
        {scope === 'PICKED' && <>
          {/* campusIds / userIds 由下面的树统一维护，表单里只留隐藏项承载取值。 */}
          <Form.Item name="campusIds" hidden><Input /></Form.Item>
          <Form.Item name="userIds" hidden><Input /></Form.Item>
          <Form.Item label="选择提交对象"
            extra="勾选校区＝该校区全部负责人（含之后新增的成员）；只勾某个人＝单独点名他。两者互不影响。">
            <div className="featured-assign-tree">
              {treeData.length
                ? <Tree checkable selectable={false}
                  // 不级联：勾校区和勾个人是两种不同的指派，父子联动会把这个区别抹平。
                  checkStrictly
                  defaultExpandAll
                  treeData={treeData}
                  checkedKeys={checkedKeys}
                  onCheck={onCheck} />
                : <Typography.Text type="secondary">还没有校区，请先在系统管理里添加。</Typography.Text>}
            </div>
            <Typography.Text type="secondary" className="featured-assign-summary">
              {describeSelection({ campusIds, userIds }, managers)}
            </Typography.Text>
          </Form.Item>
        </>}
      </Form>
    </Modal>
  </div>
}

/** 把纯数据节点转成 antd 需要的形状；被校区覆盖的人显示为已包含且不可再单独勾选。 */
function toTreeNode(node: AssignmentNode, covered: Set<string>): {
  key: string; title: React.ReactNode; disabled?: boolean; checkable?: boolean;
  children?: ReturnType<typeof toTreeNode>[]
} {
  if (node.kind === 'USER') {
    const included = node.userId != null && covered.has(String(node.userId))
    return {
      key: node.key,
      // 已由校区带进来的人禁用勾选：让"为什么勾了校区还要再勾人"这个疑问不出现。
      disabled: included,
      title: <Space size={6}>
        <span>{node.label}</span>
        {node.caption && <Typography.Text type="secondary">{node.caption}</Typography.Text>}
        {included && <Tag color="green">已由校区包含</Tag>}
      </Space>,
    }
  }
  const children = (node.children ?? []).map(child => toTreeNode(child, covered))
  if (node.kind === 'GROUP') {
    return {
      key: node.key,
      // 「未分配校区」只是一个容器，勾它没有对应的后端语义。
      checkable: false,
      title: <Space size={6}>
        <Typography.Text type="secondary">{node.label}</Typography.Text>
        <Typography.Text type="secondary">（{children.length} 人，只能单独点名）</Typography.Text>
      </Space>,
      children,
    }
  }
  return {
    key: node.key,
    title: <Space size={6}>
      <strong>{node.label}</strong>
      <Typography.Text type="secondary">
        {children.length ? `全部负责人 · 当前 ${children.length} 人` : '全部负责人 · 当前暂无成员'}
      </Typography.Text>
    </Space>,
    children,
  }
}
