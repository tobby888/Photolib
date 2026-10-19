import type { AnalysisRow } from './worklogAnalysis'

const STATUS: Record<string, string> = { CONFIRMED: '已确认', SUBMITTED: '待确认', REJECTED: '已退回' }

/**
 * 一个 CSV 单元格。两件事：
 * - 含逗号、引号、换行的值要整体加引号，引号本身写两遍；
 * - **公式注入**：Excel / WPS 会把以 `=` `+` `-` `@`（以及制表符、回车）开头的单元格当公式执行，
 *   而工作说明、成员姓名都是用户填的，`=HYPERLINK(...)` 这类内容打开表格就会生效。
 *   这类值前面补一个单引号，让表格软件按文本显示。
 */
export function csvCell(value: unknown): string {
  let text = value == null ? '' : String(value)
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

/** 开头的 BOM 让 Excel 认出这是 UTF-8。写成转义而不是字面字符，免得编辑器把它吞掉。 */
const BOM = String.fromCharCode(0xfeff)

const hours = (minutes: number) => (minutes / 60).toFixed(2)

/** 一张表导出成 CSV：表头一行、每条一行，单元格都过 {@link csvCell}，开头带 BOM。 */
export function tableToCsv(header: readonly string[], rows: readonly (readonly unknown[])[]): string {
  return `${BOM}${[header, ...rows].map(cells => cells.map(csvCell).join(',')).join('\r\n')}\r\n`
}

/** 工时分析明细导出成 CSV。开头带 BOM，Excel 才认得出这是 UTF-8，中文不乱码。时长与 XLSX 导出一致，写小时。 */
export function rowsToCsv(rows: readonly AnalysisRow[]): string {
  const header = ['工作日期', '姓名', '学号', '选题', '需求', '校区', '拍摄（小时）', '修图（小时）', '合计（小时）',
    '状态', '填报账号', '工作说明']
  return tableToCsv(header, rows.map(row => [
    row.workDate, row.memberName, row.memberStudentId, row.projectTitle ?? '',
    row.requestId == null ? '上传链接申报' : row.requestTitle ?? '', row.campusName ?? '',
    hours(row.shootingMinutes), hours(row.retouchingMinutes), hours(row.shootingMinutes + row.retouchingMinutes),
    STATUS[row.status] ?? row.status, row.userDisplayName ?? '', row.remark ?? '',
  ]))
}
