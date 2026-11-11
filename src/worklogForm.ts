import type { Campus, CampusMember, EntityId, PhotoRequest } from './types'

/**
 * 「填报工时」表单里跟校区有关的推导。
 *
 * 后端 `CampusMemberService.getForWorklog` 只接受需求所属校区通讯录里的启用成员。
 * 通讯录按校区各存一行，同一个学生同时在几个校区时就有几行同名同学号的记录；
 * 管几个校区的负责人拉到的是这几个校区的合集，不按需求校区筛的话下拉里会出现
 * 两个一模一样的「张三 · 学号」，选中另一个校区那一行就会被后端拒掉。
 */

export const campusNameOf = (campuses: Campus[], campusId: EntityId) =>
  campuses.find(campus => campus.id === campusId)?.name || `校区 #${campusId}`

// 没选需求时不给候选人：这时候选谁都可能和随后选的需求校区对不上。
export const membersForRequest = (directory: CampusMember[], request?: PhotoRequest | null) =>
  request ? directory.filter(member => member.campusId === request.campusId) : []

// 多校区发布会给每个校区各建一条同名需求，候选需求跨校区时只写标题分不清是哪一条，
// 所以跨校区才在标题后面带上校区名；只有一个校区时带上去只是噪音。
export const requestSelectOptions = (requests: PhotoRequest[], campuses: Campus[]) => {
  const showCampus = new Set(requests.map(request => request.campusId)).size > 1
  return requests.map(request => ({
    value: request.id,
    label: showCampus ? `${request.title} · ${campusNameOf(campuses, request.campusId)}` : request.title,
  }))
}

/**
 * 能不能「不关联需求、只写自定义选题」地填报：要能向任意需求申报工时（默认只有管理员），而且不限校区。
 * 这类工时不属于任何校区，校区范围账号建了也看不到；后端 `createWithoutRequest` 是同一条判断。
 */
export const canFileWithoutRequest = (user: { dataScope?: string; permissions?: readonly string[] } | null | undefined) =>
  Boolean(user && user.dataScope !== 'CAMPUS' && user.permissions?.includes('WORKLOG_SUBMIT_ANY'))

/**
 * 不关联需求时的工作人员候选：全部校区的通讯录。同一个学生在几个校区各有一行，标签里一律带校区名，
 * 选哪一行都行（后端 `getAnyForWorklog` 只要求是启用成员），按姓名、校区排。
 */
export const memberOptionsAnyCampus = (directory: CampusMember[], campuses: Campus[]) =>
  directory
    .map(member => ({ member, campus: campusNameOf(campuses, member.campusId) }))
    .sort((a, b) => a.member.name.localeCompare(b.member.name, 'zh-CN') || a.campus.localeCompare(b.campus, 'zh-CN'))
    .map(({ member, campus }) => ({ value: member.id, label: `${member.name} · ${member.studentId} · ${campus}` }))
