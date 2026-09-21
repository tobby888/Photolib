import { Pagination } from 'antd'
import { GRID_PAGE_SIZES } from './pagination'

interface ListPaginationProps {
  page: number
  pageSize: number
  total: number
  /** 每页条数的候选值，默认是卡片网格那一套（见 `src/pagination.ts`）。 */
  sizes?: number[]
  showTotal?: (total: number) => string
  className?: string
  onChange: (page: number, pageSize: number) => void
}

/**
 * 卡片网格和自己渲染分页条的列表共用的分页控件。
 *
 * 两点和直接写 `<Pagination>` 不同，都是为了让「每页条数」的下拉框真的可用：
 *
 * - **总是显式打开下拉框并给出候选值。** 交给 antd 自己判断的话，它只在总数超过 50 条时才显示，
 *   而调用方多半没为它准备好状态（见 `src/pagination.ts` 开头），于是下拉框成了摆设。
 * - **不用 `hideOnSinglePage`。** 它会连下拉框一起藏掉：选了 96/页 之后只剩一页，就再也没有
 *   地方把它调回 12 了。只有在**最小档位下也只有一页**时才真的不摆——那时怎么选都是这一屏。
 */
export default function ListPagination({
  page, pageSize, total, sizes = GRID_PAGE_SIZES, showTotal, className, onChange,
}: ListPaginationProps) {
  if (total <= Math.min(...sizes)) return null
  return <Pagination className={['list-pagination', className].filter(Boolean).join(' ')}
    current={page} pageSize={pageSize} total={total}
    showSizeChanger pageSizeOptions={sizes} showTotal={showTotal} onChange={onChange} />
}
