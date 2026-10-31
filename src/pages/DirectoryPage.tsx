import { App, Button, Card, Empty, Form, Input, Modal, Select, Space, Tag } from 'antd'
import { EditOutlined, PlusOutlined, StopOutlined, CheckCircleOutlined, DeleteOutlined, SearchOutlined } from '@ant-design/icons'
import { useMemo, useState } from 'react'
import { api, qs } from '../api'
import { useAuth } from '../auth'
import type { Campus, CampusMember, EntityId } from '../types'
import { DataState, PageTitle } from '../components'
import { ContentFitTable } from '../ContentFitTable'
import { useLoad } from '../hooks'
import { hasPermission } from '../permissions'
import { DIRECTORY_ACTION_MIN_WIDTH } from '../tableActionWidths'
import { GRID_PAGE_SIZES, clientTablePagination } from '../pagination'
import { type DirectorySearchHit, filterMembers, normalizeKeyword, searchAcrossCampuses } from '../directorySearch'

export default function DirectoryPage() {
  const { user } = useAuth()
  const { message, modal } = App.useApp()
  const [form] = Form.useForm()
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [editing, setEditing] = useState<CampusMember | null>(null)
  const [selectedCampus, setSelectedCampus] = useState<EntityId | undefined>(
    user?.campusIds?.length === 1 ? user.campusIds[0] : undefined)
  const [keyword, setKeyword] = useState('')
  const searching = normalizeKeyword(keyword) !== ''

  const campusScoped = user?.dataScope === 'CAMPUS'
  const canManage = hasPermission(user, 'DIRECTORY_MANAGE')
  const showCampusPicker = !campusScoped || (user?.campusIds?.length || 0) > 1

  const { data: campuses } = useLoad(
    () => showCampusPicker ? api<Campus[]>({ url: '/campuses', params: { enabled: true } }) : Promise.resolve([]),
    [] as Campus[], [showCampusPicker],
  )
  const visibleCampuses = useMemo(() => campusScoped
    ? campuses.filter(campus => user?.campusIds?.includes(campus.id))
    : campuses, [campusScoped, campuses, user?.campusIds])
  // 能选校区却没选校区时输入关键词，就是在全部可见校区里搜：一次拉回后端允许的全部成员，
  // 之后每次按键只在本地筛，不再请求。
  const globalSearch = showCampusPicker && !selectedCampus && searching
  const { data: members, loading, error, reload } = useLoad(
    () => !showCampusPicker || globalSearch
      ? api<CampusMember[]>({ url: '/campus-members' })
      : selectedCampus
        ? api<CampusMember[]>({ url: '/campus-members', params: { campusId: selectedCampus } })
        : Promise.resolve([] as CampusMember[]),
    [] as CampusMember[], [showCampusPicker, selectedCampus, globalSearch],
  )
  const filteredMembers = useMemo(() => filterMembers(members, keyword), [members, keyword])
  const globalHits = useMemo(
    () => globalSearch ? searchAcrossCampuses(members, visibleCampuses, keyword) : [],
    [globalSearch, members, visibleCampuses, keyword],
  )

  // 从全局结果跳到某个校区：关键词换成这个人的学号。各校区录的姓名可能写法不同，
  // 留着原来的姓名关键词，跳过去可能一个人都筛不出来。
  const openInCampus = (campusId: EntityId, studentId: string) => {
    setSelectedCampus(campusId)
    setKeyword(studentId)
  }

  const openCreate = () => { setEditing(null); form.resetFields(); form.setFieldsValue({ enabled: true }); setOpen(true) }
  const openEdit = (member: CampusMember) => {
    setEditing(member)
    form.setFieldsValue({ name: member.name, studentId: member.studentId, enabled: member.enabled })
    setOpen(true)
  }

  const save = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      if (editing) {
        await api({ method: 'PUT', url: `/campus-members/${editing.id}`, data: {
          studentId: values.studentId, name: values.name, enabled: values.enabled, version: editing.version,
        } })
        message.success('成员信息已更新')
      } else {
        await api({ method: 'POST', url: '/campus-members', data: qs({
          campusId: showCampusPicker ? selectedCampus : undefined,
          studentId: values.studentId,
          name: values.name,
        }) })
        message.success('成员已加入通讯录')
      }
      setOpen(false); form.resetFields(); setEditing(null); await reload()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const toggle = async (member: CampusMember) => {
    try {
      await api({ method: 'PUT', url: `/campus-members/${member.id}`, data: {
        studentId: member.studentId, name: member.name, enabled: !member.enabled, version: member.version,
      } })
      message.success(member.enabled ? '成员已停用' : '成员已启用')
      await reload()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const confirmToggle = (member: CampusMember) => {
    if (!member.enabled) { void toggle(member); return }
    modal.confirm({
      title: `停用“${member.name}”？`,
      content: '停用后该成员将无法被选为拍摄者或工时人员，历史工时与照片中的姓名、学号不受影响。',
      okText: '停用',
      okButtonProps: { danger: true },
      onOk: () => toggle(member),
    })
  }

  const del = async (member: CampusMember) => {
    try {
      await api({ method: 'DELETE', url: `/campus-members/${member.id}` })
      message.success('成员已从通讯录删除')
      await reload()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const confirmDelete = (member: CampusMember) => {
    modal.confirm({
      title: `删除“${member.name}”？`,
      content: '将把该成员从通讯录彻底移除；历史工时与照片中的姓名、学号快照不受影响。如只是暂时不可选，请改用“停用”。',
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: () => del(member),
    })
  }

  const description = canManage
    ? '按授权校区维护摄影人员通讯录，供上传拍摄者与工时人员选择。'
    : '查看摄影人员通讯录。'

  const manageExtra = canManage
    ? <Button type="primary" size="large" icon={<PlusOutlined />}
        disabled={showCampusPicker && !selectedCampus} onClick={openCreate}>添加成员</Button>
    : undefined

  return <>
    <PageTitle eyebrow="DIRECTORY" title="通讯录" description={description} extra={manageExtra} />

    <>
      <Card className="filter-card">
        <Space wrap>
          {showCampusPicker && <>
            <span>校区</span>
            <Select
              showSearch
              allowClear
              optionFilterProp="label"
              style={{ width: 240 }}
              placeholder="全部校区"
              value={selectedCampus}
              options={visibleCampuses.map(campus => ({ value: campus.id, label: campus.name }))}
              onChange={(value) => setSelectedCampus(value)}
            />
          </>}
          <Input
            allowClear
            prefix={<SearchOutlined />}
            style={{ width: 280 }}
            placeholder={showCampusPicker && !selectedCampus ? '在全部校区中搜索姓名或学号' : '搜索姓名或学号'}
            value={keyword}
            onChange={event => setKeyword(event.target.value)}
          />
        </Space>
      </Card>
      <Card>
        {showCampusPicker && !selectedCampus
          ? !searching
            ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择校区查看通讯录，或直接输入姓名、学号在全部校区中搜索" />
            : <DataState loading={loading} error={error} empty={!globalHits.length} onRetry={reload}
                emptyText="全部校区里都没有找到匹配的成员"
                emptyHint="换个关键词试试，姓名和学号都支持只输入一部分。">
                <ContentFitTable<DirectorySearchHit>
                  key={normalizeKeyword(keyword)}
                  rowKey="key"
                  dataSource={globalHits}
                  pagination={clientTablePagination(globalHits.length,
                    { defaultPageSize: 12, sizes: GRID_PAGE_SIZES, showTotal: total => `共找到 ${total} 人` })}
                  columns={[
                    { title: '姓名', key: 'name', render: (_, hit) => hit.names.join(' / ') },
                    { title: '学号', dataIndex: 'studentId' },
                    { title: '所在校区', key: 'campuses', render: (_, hit) => <Space size={[4, 4]} wrap>
                      {hit.campuses.map(entry => <Tag
                        key={entry.memberId}
                        color={entry.enabled ? 'blue' : undefined}
                        role="button"
                        tabIndex={0}
                        title={`在${entry.campusName}通讯录中查看`}
                        style={{ cursor: 'pointer', marginInlineEnd: 0 }}
                        onClick={() => openInCampus(entry.campusId, hit.studentId)}
                        onKeyDown={event => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault()
                            openInCampus(entry.campusId, hit.studentId)
                          }
                        }}
                      >{entry.campusName}{entry.enabled ? '' : '（停用）'}</Tag>)}
                    </Space> },
                  ]}
                />
              </DataState>
          : <DataState loading={loading} error={error} empty={!filteredMembers.length} onRetry={reload}
              emptyText={searching && members.length ? '这个校区里没有找到匹配的成员' : '这个校区的通讯录还是空的'}
              emptyHint={searching && members.length
                ? (showCampusPicker ? '换个关键词试试，或清空校区选择、在全部校区中搜索。' : '换个关键词试试，姓名和学号都支持只输入一部分。')
                : '先把摄影人员加进来，上传图片选拍摄者、填报工时选成员时才有人可选。'}>
              <ContentFitTable
                key={normalizeKeyword(keyword)}
                rowKey="id"
                dataSource={filteredMembers}
                pagination={clientTablePagination(filteredMembers.length,
                  { defaultPageSize: 12, sizes: GRID_PAGE_SIZES, showTotal: total => `共 ${total} 人` })}
                columns={[
                  { title: '姓名', dataIndex: 'name' },
                  { title: '学号', dataIndex: 'studentId' },
                  { title: '状态', dataIndex: 'enabled', width: 100,
                    render: (enabled: boolean) => enabled
                      ? <Tag color="green">启用</Tag>
                      : <Tag>停用</Tag> },
                  ...(canManage ? [{ title: '操作', minWidth: DIRECTORY_ACTION_MIN_WIDTH, className: 'table-action-cell', render: (_: unknown, member: CampusMember) => <Space>
                    <Button type="text" icon={<EditOutlined />} onClick={() => openEdit(member)}>编辑</Button>
                    {member.enabled
                      ? <Button type="text" danger icon={<StopOutlined />} onClick={() => confirmToggle(member)}>停用</Button>
                      : <Button type="text" icon={<CheckCircleOutlined />} onClick={() => confirmToggle(member)}>启用</Button>}
                    <Button type="text" danger icon={<DeleteOutlined />} onClick={() => confirmDelete(member)}>删除</Button>
                  </Space> }] : []),
                ]}
              />
            </DataState>}
      </Card>
    </>

    <Modal
      title={editing ? '编辑成员' : '添加成员'}
      open={open}
      onCancel={() => { setOpen(false); setEditing(null) }}
      onOk={save}
      okText="保存"
      confirmLoading={saving}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" requiredMark={false} initialValues={{ enabled: true }}>
        <Form.Item label="姓名" name="name" rules={[{ required: true, message: '请输入姓名' }, { max: 100 }]}>
          <Input placeholder="成员姓名" />
        </Form.Item>
        <Form.Item label="学号" name="studentId" rules={[{ required: true, message: '请输入学号' }, { max: 64 }]}>
          <Input placeholder="成员学号" />
        </Form.Item>
        {editing && (
          <Form.Item label="状态" name="enabled">
            <Select options={[{ value: true, label: '启用' }, { value: false, label: '停用' }]} />
          </Form.Item>
        )}
      </Form>
    </Modal>
  </>
}
