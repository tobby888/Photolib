import type { FeedbackCategory, FeedbackStatus, Notification } from './types'

/**
 * 反馈分类 / 状态的中文名与标签配色。列表（FeedbackPanel）与线程页
 * （FeedbackDetailPage）共用这一份，避免两处各写一遍慢慢走样。
 */
export const FEEDBACK_CATEGORY_LABEL: Record<FeedbackCategory, string> = {
  ISSUE: '问题',
  SUGGESTION: '建议',
}

export const FEEDBACK_STATUS_LABEL: Record<FeedbackStatus, string> = {
  PENDING: '待处理',
  IN_PROGRESS: '处理中',
  RESOLVED: '已解决',
}

export const FEEDBACK_STATUS_COLOR: Record<FeedbackStatus, string> = {
  PENDING: 'gold',
  IN_PROGRESS: 'processing',
  RESOLVED: 'green',
}

/**
 * 每个状态能改去哪儿，与后端 {@code FeedbackService.requireValidTransition} 一致：
 * 没有回到「待处理」的路，「已解决」只能重开回「处理中」。按钮只列这些，免得点了才报错。
 */
export const FEEDBACK_NEXT_STATUSES: Record<FeedbackStatus, { status: FeedbackStatus; label: string }[]> = {
  PENDING: [{ status: 'IN_PROGRESS', label: '开始处理' }, { status: 'RESOLVED', label: '标为已解决' }],
  IN_PROGRESS: [{ status: 'RESOLVED', label: '标为已解决' }],
  RESOLVED: [{ status: 'IN_PROGRESS', label: '重新打开' }],
}

/**
 * 反馈通知要跳进它自己的工单线程（actionUrl），而不是通用的通知详情页——那里只有一句摘要。
 * 其余通知返回 null，由调用方沿用各自原来的去向。
 */
export function feedbackThreadUrl(item: Pick<Notification, 'eventType' | 'actionUrl'>) {
  return item.eventType.startsWith('FEEDBACK_') && item.actionUrl ? item.actionUrl : null
}
