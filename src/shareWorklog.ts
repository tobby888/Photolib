import type { ShareGuestWorklog } from './types'

/** 单项时长的上限：一天。与后端 `WorklogDeclarationRequest` 的 `@Max(1440)` 一致。 */
export const MAX_WORKLOG_MINUTES = 1440

export interface WorklogDeclarationDraft {
  /** `YYYY-MM-DD` */
  workDate: string
  shootingMinutes: number
  retouchingMinutes: number
}

/**
 * 申报前的校验，与后端 `WorklogService.validate` 加上请求体上的字段约束是同一套规则。
 *
 * <p>这里拦下来只是为了让人当场看到哪里填错了——真正作数的仍然是服务端那一遍。
 * 日期按 `YYYY-MM-DD` 的字典序比：两边都是补零的定长串，字典序就是时间序，不必为了
 * 比一个日期把时区再搅进来。</p>
 *
 * @param today 访客本地的今天（`YYYY-MM-DD`）
 * @returns 第一条不满足的规则，全部满足时为 `null`
 */
export function rejectWorklogReason(draft: WorklogDeclarationDraft, today: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(draft.workDate)) return '请选择工作日期'
  if (draft.workDate > today) return '工作日期不能晚于今天'
  for (const [label, minutes] of [
    ['拍摄时长', draft.shootingMinutes], ['修图时长', draft.retouchingMinutes],
  ] as const) {
    if (!Number.isInteger(minutes) || minutes < 0) return `${label}请填写不小于 0 的整数分钟`
    if (minutes > MAX_WORKLOG_MINUTES) return `${label}不能超过 ${MAX_WORKLOG_MINUTES} 分钟（一天）`
  }
  if (draft.shootingMinutes + draft.retouchingMinutes <= 0) return '拍摄和修图时长不能同时为 0'
  return null
}

/**
 * 这一天已经有几条在审或已确认的申报（被退回的不算）。
 *
 * <p>同一天可以报多条——一天跑两场活动、上午拍下午修都是正当情况，后端不再拦。
 * 这个数只用来给一句不拦人的提醒：访客页一刷新表单就回到初始状态，手一抖交两次
 * 一样的，审核的人还得一条条退回。</p>
 */
export function declaredCountOn(worklogs: readonly ShareGuestWorklog[], workDate: string): number {
  return worklogs.filter(worklog => worklog.workDate === workDate
    && (worklog.status === 'SUBMITTED' || worklog.status === 'CONFIRMED')).length
}

export interface WorklogStatusCopy {
  text: string
  color: string
  /** 给申报人看的一句下一步：他没有账号，页面上这句话是唯一的说明。 */
  hint: string
}

export function describeWorklogStatus(worklog: Pick<ShareGuestWorklog, 'status' | 'rejectReason'>): WorklogStatusCopy {
  switch (worklog.status) {
    case 'CONFIRMED':
      return { text: '已确认', color: 'green', hint: '部长或管理员已经确认了这条工时。' }
    case 'REJECTED':
      return {
        text: '已退回',
        color: 'volcano',
        // 审核的人写原因时带不带句号都有，去掉末尾的再统一补一个，免得拼出「。。」。
        hint: worklog.rejectReason?.trim()
          ? `退回原因：${worklog.rejectReason.trim().replace(/[。.！!；;]+$/, '')}。改好后在上面重新申报一条即可。`
          : '这条工时被退回了。改好后在上面重新申报一条即可。',
      }
    case 'SUBMITTED':
      return { text: '待确认', color: 'purple', hint: '已提交，等待部长或管理员确认。' }
    default:
      // 链接申报没有草稿这一步，走到这里说明后端多了新状态：如实显示，不猜。
      return { text: worklog.status, color: 'default', hint: '' }
  }
}

/** 分钟数写成人话：`165` → `2 小时 45 分钟`。申报记录里用，省得人自己除 60。 */
export function describeMinutes(minutes: number): string {
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  if (!hours) return `${rest} 分钟`
  return rest ? `${hours} 小时 ${rest} 分钟` : `${hours} 小时`
}
