import { Table } from 'antd'
import type { TableProps } from 'antd'
import {
  CONTENT_FIT_TABLE_LAYOUT,
  contentFitTableClassName,
  contentFitTableScroll,
} from './contentFitTableLayout'

interface TableEllipsisTextProps {
  value: unknown
  maxWidth: number
  emptyText?: string
}

/** Keeps intentionally bounded free text readable without forcing fixed table layout. */
export function TableEllipsisText({ value, maxWidth, emptyText = '—' }: TableEllipsisTextProps) {
  const text = value === null || value === undefined || value === '' ? emptyText : String(value)
  return <span className="table-ellipsis-text" style={{ maxWidth }} title={text}>{text}</span>
}

type ContentFitTableProps<RecordType extends object> = Omit<TableProps<RecordType>, 'tableLayout'>

/**
 * Ant Design table with content-sized horizontal overflow. This keeps rc-table
 * in automatic layout even when a right/left column is fixed, so rendered cell
 * controls can enlarge their column instead of overlapping adjacent cells.
 */
export function ContentFitTable<RecordType extends object = Record<string, unknown>>({
  className,
  scroll,
  ...props
}: ContentFitTableProps<RecordType>) {
  return <Table<RecordType>
    {...props}
    className={contentFitTableClassName(className)}
    scroll={contentFitTableScroll(scroll)}
    tableLayout={CONTENT_FIT_TABLE_LAYOUT}
  />
}
