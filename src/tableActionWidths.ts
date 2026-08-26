import { calculateActionColumnWidth } from './tableLayout'

export const PERMISSION_GROUP_ACTION_MIN_WIDTH = calculateActionColumnWidth([[
  { label: '查看详情', icon: true },
  { label: '编辑权限', icon: true },
  { label: '删除', icon: true },
]])

export const AUTHORIZATION_ACTION_MIN_WIDTH = calculateActionColumnWidth([[
  { label: '保存授权', icon: true },
]])

export const USER_ACTION_MIN_WIDTH = calculateActionColumnWidth([[
  { fixedWidth: 28 },
  { label: '修改邮箱', icon: true },
  { label: '重置密码' },
  { label: '删除' },
]])

export const DIRECTORY_ACTION_MIN_WIDTH = calculateActionColumnWidth([[
  { label: '编辑', icon: true },
  { label: '停用', icon: true },
  { label: '删除', icon: true },
]])

export const REQUEST_ACTION_MIN_WIDTH = calculateActionColumnWidth([
  [
    { label: '交付图片', icon: true },
    { label: '退回', icon: true },
    { label: '确认完成' },
    { label: '关闭' },
    { label: '删除', icon: true },
  ],
  [
    { label: '交付图片', icon: true },
    { label: '提交' },
  ],
])

export const WORKLOG_OWNER_ACTION_MIN_WIDTH = calculateActionColumnWidth([[
  { label: '提交' },
  { label: '删除', icon: true },
]])

export const WORKLOG_REVIEW_ACTION_MIN_WIDTH = calculateActionColumnWidth([[
  { label: '批准', icon: true },
  { label: '退回', icon: true },
  { label: '删除', icon: true },
]])
