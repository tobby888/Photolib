import type { Worklog } from './types'

/**
 * 选题详情页「本选题工时」的汇总。
 *
 * 统计口径与统计面板一致：**按学号归并成员**（同一个人在几个校区的通讯录里各有一行，姓名快照
 * 可能差一个空格，按姓名归并会把他劈成两行），时长只把已确认的算进「已确认」，待确认的单列——
 * 核工资看前者，催审核看后者。草稿不算：它还没交上来。
 */
export interface ProjectWorklogMember {
  studentId: string
  name: string
  confirmedShooting: number
  confirmedRetouching: number
  confirmedTotal: number
  pendingTotal: number
  worklogCount: number
}

export interface ProjectWorklogSummary {
  confirmedMinutes: number
  pendingMinutes: number
  pendingCount: number
  rejectedCount: number
  memberCount: number
  members: ProjectWorklogMember[]
}

const total = (worklog: Worklog) => (worklog.shootingMinutes || 0) + (worklog.retouchingMinutes || 0)

export function summarizeProjectWorklogs(worklogs: readonly Worklog[]): ProjectWorklogSummary {
  const members = new Map<string, ProjectWorklogMember>()
  let confirmedMinutes = 0
  let pendingMinutes = 0
  let pendingCount = 0
  let rejectedCount = 0
  for (const worklog of worklogs) {
    if (worklog.status === 'DRAFT') continue
    const key = worklog.memberStudentId || worklog.memberName
    const member = members.get(key) ?? {
      studentId: worklog.memberStudentId, name: worklog.memberName,
      confirmedShooting: 0, confirmedRetouching: 0, confirmedTotal: 0, pendingTotal: 0, worklogCount: 0,
    }
    member.worklogCount += 1
    if (worklog.status === 'CONFIRMED') {
      member.confirmedShooting += worklog.shootingMinutes || 0
      member.confirmedRetouching += worklog.retouchingMinutes || 0
      member.confirmedTotal += total(worklog)
      confirmedMinutes += total(worklog)
    } else if (worklog.status === 'SUBMITTED') {
      member.pendingTotal += total(worklog)
      pendingMinutes += total(worklog)
      pendingCount += 1
    } else if (worklog.status === 'REJECTED') {
      rejectedCount += 1
    }
    members.set(key, member)
  }
  const sorted = [...members.values()].sort((a, b) =>
    b.confirmedTotal - a.confirmedTotal || b.pendingTotal - a.pendingTotal
    || a.studentId.localeCompare(b.studentId))
  return { confirmedMinutes, pendingMinutes, pendingCount, rejectedCount, memberCount: sorted.length, members: sorted }
}

/** 一次最多取多少条：工时按页取（每页 100），取满就提示用户去工时页看完整列表。 */
export const PROJECT_WORKLOG_LIMIT = 2000
