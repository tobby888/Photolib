// 图片标签与选题预设标签的前端规则。与后端 PhotoTags 保持一致：去首尾空白、丢空标签、
// 按首次出现去重，最多 30 个、每个最多 50 字。后端仍是最终边界，这里只负责让表单和筛选
// 与服务端算出同样的结果。

export const MAX_TAGS = 30
export const MAX_TAG_LENGTH = 50

export function normalizeTags(tags: readonly (string | null | undefined)[] | null | undefined): string[] {
  const result: string[] = []
  for (const tag of tags || []) {
    const value = tag?.trim()
    if (value && !result.includes(value)) result.push(value)
  }
  return result
}

/** 与后端批量改标签同一个顺序：先删后加，新增的追加在末尾。 */
export function applyTagChanges(existing: readonly string[] | null | undefined,
  add: readonly string[], remove: readonly string[]): string[] {
  const removed = new Set(normalizeTags(remove))
  return normalizeTags([...(existing || []).filter(tag => !removed.has(tag)), ...normalizeTags(add)])
}

export interface TaggablePhoto {
  tags?: string[] | null
  takenAt: string
  photographerName: string
}

export interface ProjectPhotoFilters {
  /** 同时包含所选的全部标签。 */
  tags: string[]
  /** 拍摄日期下界（YYYY-MM-DD），含当天。 */
  takenFrom?: string | null
  /** 拍摄日期上界（YYYY-MM-DD），含当天——即截止到次日零点之前。 */
  takenTo?: string | null
  /** 拍摄者任意其一。 */
  photographers: string[]
  /** 本选题里是否被引；null 表示不限。取值与后端 `ProjectShareService.AdoptionFilter` 一致。 */
  adoption?: AdoptionFilter | null
}

export type AdoptionFilter = 'ADOPTED' | 'NOT_ADOPTED'

export const adoptionFilterOptions: { value: AdoptionFilter; label: string }[] = [
  { value: 'ADOPTED', label: '已被引' },
  { value: 'NOT_ADOPTED', label: '未被引' },
]

export const emptyProjectPhotoFilters: ProjectPhotoFilters = {
  tags: [], takenFrom: null, takenTo: null, photographers: [], adoption: null,
}

export function hasActiveFilters(filters: ProjectPhotoFilters): boolean {
  return !!(filters.tags.length || filters.photographers.length || filters.takenFrom || filters.takenTo
    || filters.adoption)
}

/**
 * 选题详情页一次取回了全部项目图片，筛选直接在前端做。拍摄时间按「日」比较：
 * takenAt 是后端的 LocalDateTime（Asia/Shanghai，无时区后缀），取前 10 位即当地日期，
 * 不经过 Date 解析，避免浏览器时区把日期挪一天。
 *
 * 被引是「在这个选题里」的状态，图片自身带的 adoptionCount 是跨选题的总数，不能拿来判断，
 * 所以由调用方按本选题的采用记录传入 isAdopted。分享页走服务端分页，同一套规则在
 * `ProjectShareService.photos` 里实现。
 */
export function filterPhotos<T extends TaggablePhoto>(photos: readonly T[], filters: ProjectPhotoFilters,
  isAdopted: (photo: T) => boolean = () => false): T[] {
  const photographers = new Set(filters.photographers)
  return photos.filter(photo => {
    const tags = photo.tags || []
    if (filters.tags.some(tag => !tags.includes(tag))) return false
    if (photographers.size && !photographers.has(photo.photographerName)) return false
    const day = (photo.takenAt || '').slice(0, 10)
    if (filters.takenFrom && (!day || day < filters.takenFrom)) return false
    if (filters.takenTo && (!day || day > filters.takenTo)) return false
    if (filters.adoption && isAdopted(photo) !== (filters.adoption === 'ADOPTED')) return false
    return true
  })
}

/** 筛选下拉里的标签：先列选题预设（保持定义顺序），再按出现次数列出图片上的其他标签。 */
export function collectTagOptions(presets: readonly string[] | null | undefined,
  photos: readonly TaggablePhoto[]): string[] {
  const counts = new Map<string, number>()
  for (const photo of photos) {
    for (const tag of photo.tags || []) counts.set(tag, (counts.get(tag) || 0) + 1)
  }
  const preset = normalizeTags(presets)
  const others = [...counts.entries()]
    .filter(([tag]) => !preset.includes(tag))
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0], 'zh-CN'))
    .map(([tag]) => tag)
  return [...preset, ...others]
}

export function collectPhotographers(photos: readonly TaggablePhoto[]): string[] {
  return [...new Set(photos.map(photo => photo.photographerName).filter(Boolean))]
    .sort((a, b) => a.localeCompare(b, 'zh-CN'))
}

/** 所选图片上现有的全部标签，用作「批量移除」的候选。 */
export function tagsOnPhotos(photos: readonly TaggablePhoto[]): string[] {
  return collectTagOptions([], photos)
}

/** 新增标签里不在预设中的那些；预设为空表示不限制。 */
export function tagsOutsidePresets(tags: readonly string[], presets: readonly string[] | null | undefined): string[] {
  const allowed = normalizeTags(presets)
  if (!allowed.length) return []
  return normalizeTags(tags).filter(tag => !allowed.includes(tag))
}

/** 表单项里的长度/数量校验，文案与后端一致。可以直接拼进 antd Form.Item 的 rules。 */
export function tagInputError(tags: readonly string[] | null | undefined): string | null {
  const normalized = normalizeTags(tags)
  const tooLong = normalized.find(tag => [...tag].length > MAX_TAG_LENGTH)
  if (tooLong) return `标签不能超过 ${MAX_TAG_LENGTH} 个字：${tooLong}`
  if (normalized.length > MAX_TAGS) return `标签最多 ${MAX_TAGS} 个`
  return null
}

export const tagRules = [{
  validator: (_: unknown, value: string[] | undefined) => {
    const error = tagInputError(value)
    return error ? Promise.reject(new Error(error)) : Promise.resolve()
  },
}]
