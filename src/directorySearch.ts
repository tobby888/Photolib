import type { Campus, CampusMember, EntityId } from './types'

/**
 * 通讯录页的搜索。
 *
 * 选了校区就只在那个校区里按姓名/学号筛（{@link filterMembers}）；没选校区就是全局搜索
 * （{@link searchAcrossCampuses}）。通讯录按校区各存一行，同一个学生在几个校区就有几行
 * 同学号的记录，全局结果按学号归并成一个人，并列出他所在的全部校区。
 */

/** 全角数字/字母转半角、去首尾空白、忽略大小写：学号常被从别处粘进来。 */
function normalize(text: string): string {
  return text.normalize('NFKC').trim().toLowerCase()
}

export function normalizeKeyword(keyword: string): string {
  return normalize(keyword)
}

export function matchesMember(member: Pick<CampusMember, 'name' | 'studentId'>, keyword: string): boolean {
  const needle = normalizeKeyword(keyword)
  if (!needle) return true
  return normalize(member.name).includes(needle) || normalize(member.studentId).includes(needle)
}

export function filterMembers<T extends Pick<CampusMember, 'name' | 'studentId'>>(members: T[], keyword: string): T[] {
  if (!normalizeKeyword(keyword)) return members
  return members.filter(member => matchesMember(member, keyword))
}

export interface DirectoryHitCampus {
  campusId: EntityId
  campusName: string
  memberId: EntityId
  enabled: boolean
}

export interface DirectorySearchHit {
  /** 归并用的学号键，也作表格 rowKey。 */
  key: string
  studentId: string
  /** 各校区录入的姓名可能不一致（多打一个空格、错别字），去重后全部列出。 */
  names: string[]
  /** 按校区下拉框的顺序排列。 */
  campuses: DirectoryHitCampus[]
}

/**
 * 全局搜索：在可见校区的全部成员里按姓名/学号匹配，再按学号归并成人。
 *
 * - 只看 `campuses` 里的校区：页面上的校区下拉只列启用校区，停用校区的通讯录在这里本来就看不到，
 *   后端不带 campusId 的列表却会把它们一起返回。
 * - 一个人只要有一个校区的记录匹配就算命中，并列出他在所有可见校区的记录——按姓名搜到
 *   某校区写错名字的那一行时，也能看到他在别的校区的正确记录。
 * - 结果顺序沿用 `members` 的顺序（后端按姓名、学号排好）。
 */
export function searchAcrossCampuses(members: CampusMember[], campuses: Campus[], keyword: string): DirectorySearchHit[] {
  if (!normalizeKeyword(keyword)) return []
  const campusOrder = new Map(campuses.map((campus, index) => [String(campus.id), { campus, index }]))
  const groups = new Map<string, CampusMember[]>()
  const matched = new Set<string>()
  for (const member of members) {
    if (!campusOrder.has(String(member.campusId))) continue
    const key = normalize(member.studentId).toUpperCase()
    const group = groups.get(key)
    if (group) group.push(member)
    else groups.set(key, [member])
    if (matchesMember(member, keyword)) matched.add(key)
  }
  const hits: DirectorySearchHit[] = []
  for (const [key, group] of groups) {
    if (!matched.has(key)) continue
    const names = [...new Set(group.map(member => member.name.trim()))]
    const hitCampuses = group
      .map(member => {
        const { campus, index } = campusOrder.get(String(member.campusId))!
        return { index, entry: { campusId: member.campusId, campusName: campus.name, memberId: member.id, enabled: member.enabled } }
      })
      .sort((a, b) => a.index - b.index)
      .map(item => item.entry)
    hits.push({ key, studentId: group[0].studentId, names, campuses: hitCampuses })
  }
  return hits
}
