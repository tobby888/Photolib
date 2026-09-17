import { Button, DatePicker, Select } from 'antd'
import { FilterOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { adoptionFilterOptions, emptyProjectPhotoFilters, hasActiveFilters } from './photoTags'
import type { AdoptionFilter, ProjectPhotoFilters } from './photoTags'
import { recordTagSearch } from './photoTagHistory'
import RecentTagChips from './RecentTagChips'

interface ProjectPhotoFilterBarProps {
  value: ProjectPhotoFilters
  onChange: (next: ProjectPhotoFilters) => void
  tagOptions: readonly string[]
  photographerOptions: readonly string[]
  /** 记录最近标签历史的范围；不传则不显示历史，也不记录。 */
  historyScope?: string
}

/**
 * 选题相册的一排筛选：标签、拍摄日期、拍摄者、被引状态。选题详情页（前端筛）和
 * 分享访客页（服务端筛）共用这一个组件，两边的筛选项因此不会各自漂移。
 */
export default function ProjectPhotoFilterBar(
  { value, onChange, tagOptions, photographerOptions, historyScope }: ProjectPhotoFilterBarProps,
) {
  // 只有在下拉框里新选的标签才记进历史；点「最近标签」只切换筛选，见 RecentTagChips。
  const selectTags = (tags: string[]) => {
    if (historyScope) {
      const added = tags.filter(tag => !value.tags.includes(tag))
      if (added.length) recordTagSearch(historyScope, added)
    }
    onChange({ ...value, tags })
  }
  return <div className="project-photo-filters">
    <FilterOutlined className="project-photo-filters-icon" />
    {historyScope && <RecentTagChips scope={historyScope} selected={value.tags}
      onChange={tags => onChange({ ...value, tags })} />}
    <Select mode="multiple" allowClear showSearch maxTagCount="responsive" style={{ minWidth: 220, flex: '1 1 220px' }}
      placeholder="按标签筛选（同时包含）" value={value.tags}
      options={tagOptions.map(tag => ({ value: tag, label: tag }))}
      notFoundContent="这些图片还没有标签"
      onChange={selectTags} />
    <DatePicker.RangePicker allowEmpty={[true, true]} style={{ flex: '0 1 280px' }}
      placeholder={['拍摄开始日期', '拍摄结束日期']}
      value={[
        value.takenFrom ? dayjs(value.takenFrom) : null,
        value.takenTo ? dayjs(value.takenTo) : null,
      ]}
      onChange={range => onChange({
        ...value,
        takenFrom: range?.[0]?.format('YYYY-MM-DD') || null,
        takenTo: range?.[1]?.format('YYYY-MM-DD') || null,
      })} />
    <Select mode="multiple" allowClear showSearch maxTagCount="responsive" style={{ minWidth: 180, flex: '1 1 180px' }}
      placeholder="按拍摄者筛选" value={value.photographers}
      options={photographerOptions.map(name => ({ value: name, label: name }))}
      onChange={photographers => onChange({ ...value, photographers })} />
    <Select<AdoptionFilter> allowClear style={{ minWidth: 120, flex: '0 1 140px' }}
      placeholder="被引状态" value={value.adoption ?? undefined} options={adoptionFilterOptions}
      onChange={adoption => onChange({ ...value, adoption: adoption ?? null })} />
    {hasActiveFilters(value) && <Button type="link" onClick={() => onChange(emptyProjectPhotoFilters)}>
      清空筛选</Button>}
  </div>
}
