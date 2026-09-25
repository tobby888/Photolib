import type { FeedbackCategory, FeedbackStatus } from './types'

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
