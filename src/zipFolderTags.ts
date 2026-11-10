// ZIP 批量上传：按压缩包里的文件夹识别标签。
//
// 上传者整理压缩包时习惯把同一类图放进同一个文件夹，所以每一层文件夹名就是一个标签：
// 「运动会/开幕式/1.jpg」识别成「运动会」「开幕式」两个标签。识别只是建议——解包完成后
// 弹出「确认标签」对话框（ZipTagReviewModal），上传者看着缩略图逐张确认、修改，确认之后
// 才把这一批落成照片。站内批量上传页和上传链接页共用这里的规则。
//
// 文件夹路径由后端解包时记下（`photo_upload_item.folder_path`，V77），这里只负责把它拆成
// 标签；后端照常按 PhotoTags 的规则校验最终提交的标签。

import type { EntityId } from './types'
import { DEPRECATED_TAG, MAX_TAGS, MAX_TAG_LENGTH, normalizeTags } from './photoTags.ts'

/** 对话框里的一张图：后端批次条目里与识别有关的那几项。 */
export interface ArchiveItem {
  id: EntityId
  fileName: string
  /** 所在文件夹，`/` 分隔；在压缩包根目录时为空。 */
  folderPath?: string | null
}

/** 每张图当前的完整标签，按条目 id。 */
export type ItemTags = Record<string, string[]>

const truncate = (value: string, max: number) => {
  const points = Array.from(value)
  return points.length > max ? points.slice(0, max).join('').trim() : value
}

/**
 * 一张图的文件夹识别出的标签：每一层一个，由外到内。
 *
 * - 空段、`.` 段不算一层（与后端 `SafeImageZipExtractor.folderPath` 一致）；
 * - 超过 50 字的文件夹名截到 50 字（标签的长度上限），对话框里看得见、改得了；
 * - 保留标签 `deprecated`（选片人的「用不上」结论）不会从文件夹名里冒出来；
 * - 同名的层只留一个。
 */
export function folderTags(folderPath: string | null | undefined): string[] {
  if (!folderPath) return []
  const tags = folderPath.split(/[/\\]/)
    .map(segment => segment.trim())
    .filter(segment => segment && segment !== '.' && segment.toLowerCase() !== DEPRECATED_TAG)
    .map(segment => truncate(segment, MAX_TAG_LENGTH))
  return normalizeTags(tags)
}

/** 这一批里有没有任何一张能从文件夹识别出标签；一张都没有就不弹对话框，照原来的流程走。 */
export function hasFolderTags(items: readonly ArchiveItem[]): boolean {
  return items.some(item => folderTags(item.folderPath).length > 0)
}

/**
 * 对话框打开时每张图的标签：整批共用的在前（站内的「统一标签」、上传链接的「这批照片的标签」），
 * 文件夹识别出的在后，去重后最多 30 个。对话框里看到的就是最终落到这张图上的全部标签。
 */
export function initialItemTags(items: readonly ArchiveItem[], sharedTags: readonly string[]): ItemTags {
  const result: ItemTags = {}
  for (const item of items) {
    result[item.id] = normalizeTags([...sharedTags, ...folderTags(item.folderPath)]).slice(0, MAX_TAGS)
  }
  return result
}

/** 文件夹识别出的全部标签（不含整批共用的），按首次出现的顺序。 */
export function recognizedTags(items: readonly ArchiveItem[]): string[] {
  return normalizeTags(items.flatMap(item => folderTags(item.folderPath)))
}

export interface TagUsage {
  tag: string
  count: number
}

/** 当前用到的每个标签和用了它的张数，按 items 的顺序首次出现排列。 */
export function tagUsage(items: readonly ArchiveItem[], tags: ItemTags): TagUsage[] {
  const counts = new Map<string, number>()
  for (const item of items) {
    for (const tag of tags[item.id] ?? []) counts.set(tag, (counts.get(tag) ?? 0) + 1)
  }
  return [...counts].map(([tag, count]) => ({ tag, count }))
}

/**
 * 把所有图上的一个标签整体改名。改成的名字某张图上已经有了就合并成一个，位置留在原处。
 * 用得最多的场景是：整个压缩包外面套了一层文件夹（比如「照片导出」），或者文件夹名和
 * 选题预设差几个字。
 */
export function renameTag(tags: ItemTags, from: string, to: string): ItemTags {
  const target = to.trim()
  if (!target || target === from) return tags
  const result: ItemTags = {}
  for (const [id, list] of Object.entries(tags)) {
    result[id] = list.includes(from) ? normalizeTags(list.map(tag => tag === from ? target : tag)) : list
  }
  return result
}

/** 从所有图上去掉一个标签。 */
export function removeTag(tags: ItemTags, tag: string): ItemTags {
  const result: ItemTags = {}
  for (const [id, list] of Object.entries(tags)) {
    result[id] = list.includes(tag) ? list.filter(value => value !== tag) : list
  }
  return result
}

export interface TagRules {
  /** 选题设了预设标签、且这条路受预设约束（站内需求 / 选题批量）：只能用 presets 里的。 */
  restricted: boolean
  presets: readonly string[]
  /** 上传链接的访客不能用保留标签 deprecated。 */
  forbidReserved: boolean
}

/** 当前不能提交的标签（去重），为空才允许确认。与后端各条路的校验一致，后端仍是最终边界。 */
export function rejectedTags(tags: ItemTags, rules: TagRules): string[] {
  const all = normalizeTags(Object.values(tags).flat())
  return all.filter(tag => !isAllowedTag(tag, rules))
}

export function isAllowedTag(tag: string, rules: TagRules): boolean {
  if (Array.from(tag).length > MAX_TAG_LENGTH) return false
  const reserved = tag.toLowerCase() === DEPRECATED_TAG
  if (reserved) return !rules.forbidReserved
  return !rules.restricted || rules.presets.includes(tag)
}

/** 提交给后端的逐张标签（`itemTags`）：每张都发，空数组表示这张不打标签。 */
export function itemTagsPayload(items: readonly ArchiveItem[], tags: ItemTags) {
  return items.map(item => ({ itemId: item.id, tags: normalizeTags(tags[item.id]) }))
}

/**
 * 一张图在压缩包里的路径键，用来把后端的条目和本机压缩包里的条目对上（缩略图在浏览器里
 * 从上传者自己选的那个 ZIP 现解，不经过服务器）。两边都按后端 `SafeImageZipExtractor`
 * 的规则把条目名整理一遍：反斜杠当分隔符、丢空段和 `.` 段、控制字符换成 `_`、去首尾空白。
 */
export function archiveItemKey(item: Pick<ArchiveItem, 'fileName' | 'folderPath'>): string {
  return item.folderPath ? `${item.folderPath}/${item.fileName}` : item.fileName
}

// 与后端 Character.isISOControl 一致：C0（含 DEL）和 C1 控制字符。
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/g

/** 本机压缩包里一个条目名对应的路径键（规则见 {@link archiveItemKey}）。不是图片也照样算，调用方自己筛。 */
export function entryKey(rawName: string): string | null {
  const segments = rawName.replace(/\\/g, '/').split('/')
  // 文件名按 Java String.trim 的口径只去空格（控制字符已经换掉了），全角空格等原样保留。
  const base = segments.pop()?.replace(CONTROL, '_').replace(/^ +| +$/g, '')
  if (!base) return null
  const folders = segments.map(segment => segment.replace(CONTROL, '_').trim())
    .filter(segment => segment && segment !== '.')
  return folders.length ? `${folders.join('/')}/${base}` : base
}
