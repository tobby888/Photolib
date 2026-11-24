import { AutoComplete, Input } from 'antd'
import { SearchOutlined } from '@ant-design/icons'
import type { CSSProperties } from 'react'
import { useMemo } from 'react'
import type { WorklogMemberPair } from './types'
import {
  filterMemberPairs, formatMemberPair, normalizeMemberPairs, resolveMemberSelection,
} from './worklogMemberSuggest'
import type { MemberSuggestionPick } from './worklogMemberSuggest'

export interface WorklogMemberAutoCompleteProps {
  pairs: readonly WorklogMemberPair[]
  value: string
  onChange: (value: string) => void
  /** 点选某条「姓名 + 学号」提示时触发（例如工时列表页选中即立刻查询）。 */
  onPick?: (pickedValue: string, pair: WorklogMemberPair) => void
  /** search 模式下点击搜索按钮或回车触发。 */
  onSearch?: (value: string) => void
  /** 选中后回填学号（默认，唯一区分同名）还是姓名（用于仅按姓名匹配的字段）。 */
  pick?: MemberSuggestionPick
  variant?: 'input' | 'search'
  placeholder?: string
  size?: 'small' | 'middle' | 'large'
  style?: CSSProperties
  popupWidth?: number
  maxLength?: number
  ariaLabel?: string
}

/**
 * 带「姓名 + 学号」配对提示的搜索输入框：
 * - 聚焦即可浏览全部有工时记录的成员配对；
 * - 输入姓名或学号实时过滤提示列表；
 * - 点选配对自动回填学号（或指定字段）减少输入，也完全兼容直接输入自由关键词。
 */
export default function WorklogMemberAutoComplete({
  pairs,
  value,
  onChange,
  onPick,
  onSearch,
  pick = 'studentId',
  variant = 'input',
  placeholder = '姓名或学号',
  size,
  style,
  popupWidth = 280,
  maxLength,
  ariaLabel,
}: WorklogMemberAutoCompleteProps) {
  const normalizedPairs = useMemo(() => normalizeMemberPairs(pairs), [pairs])
  const filtered = useMemo(() => filterMemberPairs(normalizedPairs, value), [normalizedPairs, value])
  const options = useMemo(() => filtered.map(pair => ({
    value: formatMemberPair(pair),
    label: (
      <div className="member-suggest-option">
        <span className="member-suggest-name">{pair.displayName}</span>
        <span className="member-suggest-id">{pair.studentId}</span>
      </div>
    ),
  })), [filtered])

  const handleChange = (next: string) => {
    const resolved = resolveMemberSelection(next, normalizedPairs, pick)
    onChange(resolved.value)
  }

  const handleSelect = (selected: string) => {
    const resolved = resolveMemberSelection(selected, normalizedPairs, pick)
    onChange(resolved.value)
    if (resolved.pair && onPick) onPick(resolved.value, resolved.pair)
  }

  return (
    <AutoComplete
      value={value}
      options={options}
      filterOption={false}
      popupMatchSelectWidth={popupWidth}
      listHeight={280}
      style={style}
      onChange={handleChange}
      onSelect={handleSelect}
    >
      {variant === 'search' ? (
        <Input.Search
          allowClear
          size={size}
          maxLength={maxLength}
          placeholder={placeholder}
          aria-label={ariaLabel}
          onSearch={raw => {
            const resolved = resolveMemberSelection(raw, normalizedPairs, pick)
            onSearch?.(resolved.value.trim())
          }}
        />
      ) : (
        <Input
          allowClear
          size={size}
          maxLength={maxLength}
          prefix={<SearchOutlined />}
          placeholder={placeholder}
          aria-label={ariaLabel}
        />
      )}
    </AutoComplete>
  )
}
