import { Select } from 'antd'
import type { SelectProps } from 'antd'
import { MAX_TAGS } from './photoTags'

type TagSelectProps = Omit<SelectProps<string[]>, 'mode' | 'options'> & {
  /** 候选标签，通常是选题预设标签。 */
  presets?: string[]
  /** 为 true 时只能从 presets 里选；否则可以输入任意新标签，presets 只作为候选。 */
  restricted?: boolean
}

/**
 * 统一的标签输入框。选题设置了预设标签时切到只选模式——不能靠 `tags` 模式加一个
 * 提示文案，那样用户照样能敲回车造出新标签，提交时才被后端拒绝。
 */
export default function TagSelect({ presets = [], restricted = false, placeholder, ...rest }: TagSelectProps) {
  return <Select<string[]>
    mode={restricted ? 'multiple' : 'tags'}
    maxCount={MAX_TAGS}
    allowClear
    showSearch
    tokenSeparators={restricted ? undefined : [',', '，']}
    options={presets.map(tag => ({ value: tag, label: tag }))}
    placeholder={placeholder ?? (restricted ? '从选题预设标签中选择，可不选' : '输入后回车添加标签')}
    notFoundContent={restricted ? '没有可选的标签' : null}
    {...rest} />
}
