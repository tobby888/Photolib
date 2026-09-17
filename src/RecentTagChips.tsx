import { Button, Space, Tag, Typography } from 'antd'
import { useReducer } from 'react'
import { clearTagHistory, readRecentTags } from './photoTagHistory'

interface RecentTagChipsProps {
  /** localStorage 里历史记录的范围，见 `tagHistoryStorageKey`。 */
  scope: string
  selected: readonly string[]
  /** 点标签只切换筛选，不记录历史——否则刚点的标签会被挪到最前面，位置当场跳走。 */
  onChange: (tags: string[]) => void
  /** 清空之后调用，供父组件刷新同样读历史的地方（例如下拉框的候选）。 */
  onClear?: () => void
}

/** 「最近标签」一排：图库页和选题相册筛选栏共用。没有历史时不渲染。 */
export default function RecentTagChips({ scope, selected, onChange, onClear }: RecentTagChipsProps) {
  // 历史存在 localStorage 里，每次渲染现读；清空之后只需要触发一次重渲染。
  const [, rerender] = useReducer((count: number) => count + 1, 0)
  const recentTags = readRecentTags(scope)
  if (!recentTags.length) return null
  return <Space size={4} wrap className="tag-history-chips">
    <Typography.Text type="secondary">最近标签：</Typography.Text>
    {recentTags.map(tag => <Tag key={tag} className="clickable-tag"
      color={selected.includes(tag) ? 'blue' : undefined}
      onClick={() => onChange(selected.includes(tag)
        ? selected.filter(item => item !== tag)
        : [...selected, tag])}>
      {tag}</Tag>)}
    <Button type="link" size="small" onClick={() => {
      clearTagHistory(scope)
      rerender()
      onClear?.()
    }}>清空</Button>
  </Space>
}
