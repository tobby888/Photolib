import { GlobalOutlined, LockOutlined, TeamOutlined } from '@ant-design/icons'
import { Alert, Button, Segmented, Select, Space, Typography } from 'antd'
import { useMemo } from 'react'
import { api } from './api'
import { AUDIENCE_LABELS, audienceError, normalizeAudienceOptions, type AudienceValue } from './docAudience'
import { useLoad } from './hooks'
import type { DocAudienceOptions, DocVisibility } from './types'

/**
 * "谁能看 / 谁能下载"的选择器，文档编辑器和文件库共用：三档范围，选「指定成员」时再按
 * 权限组和成员挑名单。候选项来自 `GET /doc-audience`（要 DOC_PUBLISH / FILE_UPLOAD / FILE_MANAGE），
 * 只在用得到时才取——大多数文档停在「登录后」，没必要每次都拉一遍全站名单。
 *
 * 前端只做提示，名单在保存时由后端 `DocAudience.normalize` 再校验一遍。
 */
export default function DocAudiencePicker({ value, onChange, disabled = false, subject = '查看' }: {
  value: AudienceValue
  onChange: (value: AudienceValue) => void
  disabled?: boolean
  /** 文案里的动词：文档是"查看"，文件是"下载"。 */
  subject?: string
}) {
  const restricted = value.visibility === 'RESTRICTED'
  const options = useLoad(
    async () => (restricted ? normalizeAudienceOptions(await api<unknown>({ url: '/doc-audience' })) : null),
    null as DocAudienceOptions | null, [restricted])

  const groupOptions = useMemo(() => (options.data?.groups ?? []).map(group => ({
    value: group.id, label: `${group.name}（${group.memberCount} 人）`,
  })), [options.data])
  const userOptions = useMemo(() => (options.data?.users ?? []).map(user => ({
    value: user.id,
    label: `${user.displayName} · ${user.username}${user.permissionGroupName ? `（${user.permissionGroupName}）` : ''}`,
  })), [options.data])
  const error = audienceError(value)

  return <Space orientation="vertical" size={8} style={{ width: '100%' }}>
    <Segmented value={value.visibility} disabled={disabled}
      onChange={next => onChange({ ...value, visibility: next as DocVisibility })}
      options={[
        { value: 'PUBLIC', label: <Space size={4}><GlobalOutlined />{AUDIENCE_LABELS.PUBLIC}</Space> },
        { value: 'MEMBERS', label: <Space size={4}><LockOutlined />{AUDIENCE_LABELS.MEMBERS}</Space> },
        { value: 'RESTRICTED', label: <Space size={4}><TeamOutlined />{AUDIENCE_LABELS.RESTRICTED}</Space> },
      ]} />
    <Typography.Text type="secondary">
      {value.visibility === 'PUBLIC' && `未登录的访客也能${subject}。`}
      {value.visibility === 'MEMBERS' && `登录后才能${subject}，包括还没分配权限组的新同学。`}
      {restricted && `只有下面选中的权限组成员和单独指定的成员能${subject}（还要登录）。`}
    </Typography.Text>
    {restricted && options.error && <Alert type="warning" showIcon title="名单没能加载出来"
      description={options.error}
      action={<Button size="small" onClick={() => void options.reload()}>重试</Button>} />}
    {restricted && <>
      <Select mode="multiple" allowClear showSearch optionFilterProp="label" disabled={disabled}
        loading={options.loading} placeholder="按权限组选择" aria-label="按权限组选择"
        style={{ width: '100%' }} maxTagCount="responsive"
        value={value.groupIds} options={groupOptions}
        onChange={groupIds => onChange({ ...value, groupIds })} />
      <Select mode="multiple" allowClear showSearch optionFilterProp="label" disabled={disabled}
        loading={options.loading} placeholder="单独指定成员（按姓名或账号搜索）" aria-label="单独指定成员"
        style={{ width: '100%' }} maxTagCount="responsive"
        value={value.userIds} options={userOptions}
        onChange={userIds => onChange({ ...value, userIds })} />
      {error && <Typography.Text type="danger">{error}</Typography.Text>}
    </>}
  </Space>
}
