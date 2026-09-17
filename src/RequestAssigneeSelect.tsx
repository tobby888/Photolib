import { Select } from 'antd'
import { useEffect, useState } from 'react'
import { api } from './api'
import type { AssignableUser, EntityId } from './types'

interface Props {
  campusIds?: EntityId[]
  value?: EntityId
  onChange?: (value: EntityId | undefined) => void
}

/**
 * 新建需求时的「指派给」。候选人由后端按「需求访问、接受和提交」权限和校区授权筛出来，
 * 多个校区时只列出每个校区都能接收的人；前端不自己推断资格，后端创建时还会再校验一次。
 */
export default function RequestAssigneeSelect({ campusIds, value, onChange }: Props) {
  const campusKey = (campusIds || []).join(',')
  const [candidates, setCandidates] = useState<AssignableUser[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!campusKey) {
      setCandidates([])
      setError('')
      return
    }
    let cancelled = false
    setLoading(true)
    setError('')
    api<AssignableUser[]>({ url: '/requests/assignable-users', params: { campusIds: campusKey } })
      .then(items => { if (!cancelled) setCandidates(items) })
      .catch(reason => {
        if (cancelled) return
        setCandidates([])
        setError((reason as Error).message)
      })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [campusKey])

  // 换了校区之后原来选的人可能已经不在候选里，留着只会在提交时被后端拒掉。
  useEffect(() => {
    if (!loading && value && !candidates.some(item => item.id === value)) onChange?.(undefined)
  }, [candidates, loading, value, onChange])

  return <Select allowClear showSearch optionFilterProp="label" loading={loading}
    disabled={!campusKey} status={error ? 'error' : undefined}
    value={value} onChange={next => onChange?.(next)}
    placeholder={!campusKey ? '请先选择校区' : error ? `加载可指派用户失败：${error}` : '不指派，由校区负责人自行接单'}
    notFoundContent={loading ? '加载中…' : '没有能同时接收所选校区需求的用户'}
    options={candidates.map(item => ({
      value: item.id,
      label: item.displayName && item.displayName !== item.username
        ? `${item.displayName}（${item.username}）` : item.username,
    }))} />
}
