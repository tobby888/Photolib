import { App, Button, Card, Select, Space, Table, Tag, Typography } from 'antd'
import { SaveOutlined, WarningOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { api, emptyPage } from '../api'
import { DataState, PageTitle } from '../components'
import { useLoad } from '../hooks'
import type { Campus, EntityId, PageData, User } from '../types'

export default function ManagerCampusesPage() {
  const { message } = App.useApp()
  const [selectedCampuses, setSelectedCampuses] = useState<Record<EntityId, EntityId>>({})
  const [savingId, setSavingId] = useState<EntityId | null>(null)
  const { data: users, loading, error, reload } = useLoad(
    () => api<PageData<User>>({
      url: '/users',
      params: { page: 1, pageSize: 100, role: 'CAMPUS_MANAGER' },
    }),
    emptyPage<User>(),
    [],
  )
  const { data: campuses, loading: campusesLoading, error: campusesError, reload: reloadCampuses } = useLoad(
    () => api<Campus[]>({ url: '/campuses' }),
    [] as Campus[],
    [],
  )

  const saveCampus = async (user: User) => {
    const campusId = selectedCampuses[user.id]
    if (!campusId || !user.version) return
    setSavingId(user.id)
    try {
      await api<User>({
        method: 'PUT',
        url: `/users/${user.id}/campus`,
        data: { campusId, version: user.version },
      })
      setSelectedCampuses(current => {
        const next = { ...current }
        delete next[user.id]
        return next
      })
      message.success(`已更新 ${user.displayName} 的负责校区`)
      await reload()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSavingId(null)
    }
  }

  const enabledCampuses = campuses.filter(campus => campus.enabled)
  const unassignedCount = users.items.filter(user => !user.campusId).length

  return <>
    <PageTitle
      eyebrow="CAMPUS ASSIGNMENT"
      title="负责人校区"
      description="为校区负责人指定或修改负责校区。修改后，负责人的项目、需求、图库和通讯录权限将按新校区生效。"
    />
    <Card>
      <div className="tab-toolbar">
        <div>
          <Typography.Title level={4}>校区负责人</Typography.Title>
          <Typography.Text type="secondary">
            {unassignedCount > 0
              ? `有 ${unassignedCount} 位历史迁移负责人尚未指定校区，请及时补全。`
              : '所有校区负责人均已指定负责校区。'}
          </Typography.Text>
        </div>
        {unassignedCount > 0 && <Tag color="warning" icon={<WarningOutlined />}>待补全 {unassignedCount}</Tag>}
      </div>
      <DataState loading={loading || campusesLoading} error={error || campusesError}
        empty={!users.items.length} onRetry={() => { void reload(); void reloadCampuses() }}>
        <Table
          rowKey="id"
          dataSource={users.items}
          pagination={{ pageSize: 12 }}
          scroll={{ x: 720 }}
          columns={[
            {
              title: '负责人',
              render: (_value, user) => <div className="table-title">
                <strong>{user.displayName}</strong><span>@{user.username}</span>
              </div>,
            },
            {
              title: '当前校区',
              dataIndex: 'campusId',
              render: campusId => campusId
                ? campuses.find(campus => campus.id === campusId)?.name || `校区 #${campusId}`
                : <Tag color="warning">未指定</Tag>,
            },
            {
              title: '账号状态',
              dataIndex: 'enabled',
              render: enabled => <Tag color={enabled ? 'green' : 'default'}>{enabled ? '正常' : '已停用'}</Tag>,
            },
            {
              title: '指定负责校区',
              width: 360,
              render: (_value, user) => {
                const selected = selectedCampuses[user.id]
                return <Space.Compact block>
                  <Select
                    style={{ minWidth: 220, flex: 1 }}
                    value={selected ?? user.campusId ?? undefined}
                    placeholder="请选择校区"
                    options={enabledCampuses.map(campus => ({ value: campus.id, label: campus.name }))}
                    onChange={campusId => setSelectedCampuses(current => ({ ...current, [user.id]: campusId }))}
                  />
                  <Button
                    type="primary"
                    icon={<SaveOutlined />}
                    loading={savingId === user.id}
                    disabled={!selected || selected === user.campusId || savingId !== null}
                    onClick={() => void saveCampus(user)}
                  >保存</Button>
                </Space.Compact>
              },
            },
          ]}
        />
      </DataState>
    </Card>
  </>
}
