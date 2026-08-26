import { App, Button, Card, Input, Select, Space, Tag, Typography } from 'antd'
import { SaveOutlined, WarningOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { api } from '../api'
import { DataState, PageTitle } from '../components'
import { ContentFitTable } from '../ContentFitTable'
import { useLoad } from '../hooks'
import type { Campus, CampusAssignmentUser, EntityId } from '../types'

export default function ManagerCampusesPage() {
  const { message } = App.useApp()
  const [selectedCampuses, setSelectedCampuses] = useState<Record<EntityId, EntityId>>({})
  const [savingId, setSavingId] = useState<EntityId | null>(null)
  const [searchText, setSearchText] = useState('')
  const [keyword, setKeyword] = useState('')
  const { data: users, loading, error, reload } = useLoad(
    () => api<CampusAssignmentUser[]>({ url: '/users/campus-assignable' }),
    [] as CampusAssignmentUser[],
    [],
  )
  const { data: campuses, loading: campusesLoading, error: campusesError, reload: reloadCampuses } = useLoad(
    () => api<Campus[]>({ url: '/campuses' }),
    [] as Campus[],
    [],
  )

  const saveCampus = async (user: CampusAssignmentUser) => {
    const campusId = selectedCampuses[user.id]
    if (!campusId || !user.version) return
    setSavingId(user.id)
    try {
      await api({
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
  const filteredUsers = users.filter(user => !keyword
    || user.displayName.toLowerCase().includes(keyword.toLowerCase())
    || user.permissionGroupName.toLowerCase().includes(keyword.toLowerCase()))
  const unassignedCount = users.filter(user => !user.campusIds.length).length

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
            {keyword
              ? `找到 ${filteredUsers.length} 位匹配的校区范围账号。`
              : unassignedCount > 0
              ? `有 ${unassignedCount} 位历史迁移负责人尚未指定校区，请及时补全。`
              : '所有校区负责人均已指定负责校区。'}
          </Typography.Text>
        </div>
        <Space wrap>
          <Input.Search
            allowClear
            value={searchText}
            placeholder="搜索负责人姓名或账号"
            style={{ width: 280 }}
            onChange={event => {
              setSearchText(event.target.value)
              if (!event.target.value) setKeyword('')
            }}
            onSearch={value => setKeyword(value.trim())}
          />
          {unassignedCount > 0 && <Tag color="warning" icon={<WarningOutlined />}>待补全 {unassignedCount}</Tag>}
        </Space>
      </div>
      <DataState loading={loading || campusesLoading} error={error || campusesError}
        empty={!filteredUsers.length} onRetry={() => { void reload(); void reloadCampuses() }}
        emptyText={keyword ? `没有匹配“${keyword}”的负责人` : '还没有校区范围的账号'}
        emptyHint={keyword
          ? '姓名和账号都会被搜索，换个词再试试。'
          : '在“系统管理 - 权限管理”里把账号设为校区范围，才能在这里指定负责校区。'}>
        <ContentFitTable
          rowKey="id"
          dataSource={filteredUsers}
          pagination={{ pageSize: 12 }}
          columns={[
            {
              title: '负责人',
              render: (_value, user) => <div className="table-title">
                <strong>{user.displayName}</strong><span>{user.permissionGroupName}</span>
              </div>,
            },
            {
              title: '当前校区',
              dataIndex: 'campusIds',
              render: (campusIds: EntityId[]) => campusIds.length
                ? campusIds.map(campusId => campuses.find(campus => campus.id === campusId)?.name || `校区 #${campusId}`).join('、')
                : <Tag color="warning">未指定</Tag>,
            },
            {
              title: '指定负责校区',
              width: 360,
              render: (_value, user) => {
                const selected = selectedCampuses[user.id]
                return <Space.Compact block>
                  <Select
                    style={{ minWidth: 220, flex: 1 }}
                    value={selected ?? user.campusIds[0] ?? undefined}
                    placeholder="请选择校区"
                    options={enabledCampuses.map(campus => ({ value: campus.id, label: campus.name }))}
                    onChange={campusId => setSelectedCampuses(current => ({ ...current, [user.id]: campusId }))}
                  />
                  <Button
                    type="primary"
                    icon={<SaveOutlined />}
                    loading={savingId === user.id}
                    disabled={!selected || selected === user.campusIds[0] || savingId !== null}
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
