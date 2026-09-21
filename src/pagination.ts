import type { TablePaginationConfig } from 'antd'

/**
 * 列表分页的公共约定。
 *
 * ## 为什么需要这么一个模块
 *
 * 「每页条数」的下拉框一度是个摆设：antd 的分页条在总数超过 50 条时会**自己**把它显示出来
 * （rc-pagination 的 `totalBoundaryShowSizeChanger` 默认就是 50），而各个页面都把每页条数写成
 * 常量、`onChange` 也只接第一个参数（页码），于是用户选的新条数在下一次渲染就被常量盖了回去
 * ——下拉框看得见、点得动、选完什么也没发生。
 *
 * antd 的 Table 自己分页时是同一个坑：`pagination={{ pageSize: 12 }}` 传进去的对象在
 * `usePagination` 里**优先于**它自己记着的状态（`mergeProps(innerPagination, paginationObj)`），
 * 所以固定的 `pageSize` 会把用户的选择覆盖掉。客户端分页一律改用 {@link clientTablePagination}
 * （里面给的是 `defaultPageSize`），服务端分页则要把条数提升成页面状态，翻页回调两个参数都收。
 *
 * ## 候选值为什么最大只到 100
 *
 * 后端每个分页接口的 `pageSize` 都带 `@Max(100)`（审计日志那条是静默截断），传大于 100 的值会
 * 400、整页变成加载出错。所以下拉框里的候选值一律 ≤ 100，URL 里手改进来的值也要先收回候选集。
 */

/** 卡片网格（图库、项目、招募、精选……）的每页条数候选。 */
export const GRID_PAGE_SIZES = [12, 24, 48, 96]

/** 表格类列表的每页条数候选。 */
export const TABLE_PAGE_SIZES = [10, 20, 50, 100]

export interface PagedFilters {
  page: number
  pageSize: number
}

/**
 * 翻页 / 改每页条数之后的筛选状态。
 *
 * 条数变了必须回到第 1 页：原先停在第 9 页的人把条数从 12 改成 96，第 9 页多半已经不存在，
 * 留在那里只会得到一屏空列表。
 */
export function turnPage<T extends PagedFilters>(filters: T, page: number, pageSize: number): T {
  return { ...filters, pageSize, page: pageSize === filters.pageSize ? page : 1 }
}

/** 把外部来的每页条数（URL、旧的本地状态）收回到候选集里，超出上限的值会被后端 400。 */
export function normalizePageSize(value: unknown, sizes: number[] = GRID_PAGE_SIZES, fallback = sizes[0]): number {
  const size = Number(value)
  return sizes.includes(size) ? size : fallback
}

export interface ClientTablePaginationOptions {
  /** 首屏的每页条数，默认取候选集里最小的一个。 */
  defaultPageSize?: number
  sizes?: number[]
  showTotal?: (total: number) => string
  size?: TablePaginationConfig['size']
}

/**
 * antd Table 自己分页（`dataSource` 就是全部数据）时的分页配置。
 *
 * 两件事只能这么写：
 * - 给 `defaultPageSize` 而不是 `pageSize`，否则下拉框选完会被传进来的常量盖回去（见上）。
 * - `hideOnSinglePage` 只在**最小档位下也只有一页**时才为真。写死成 `true` 的话，用户选了
 *   100/页、数据正好凑不满一页，整条分页条连同下拉框一起消失，再也没有地方调回去。
 */
export function clientTablePagination(
  rowCount: number, options: ClientTablePaginationOptions = {},
): TablePaginationConfig {
  const sizes = options.sizes ?? TABLE_PAGE_SIZES
  const smallest = Math.min(...sizes)
  return {
    defaultPageSize: options.defaultPageSize ?? smallest,
    pageSizeOptions: sizes,
    showSizeChanger: true,
    hideOnSinglePage: rowCount <= smallest,
    showTotal: options.showTotal,
    size: options.size,
  }
}

/**
 * 服务端分页的 antd Table 用的分页配置：页码和条数都由调用方持有（列表请求要带着它们）。
 *
 * 这里不做 `hideOnSinglePage`：服务端分页的列表通常还要靠 `showTotal` 告诉用户一共多少条。
 */
export function serverTablePagination(
  filters: PagedFilters,
  total: number,
  onChange: (page: number, pageSize: number) => void,
  options: { sizes?: number[]; showTotal?: (total: number) => string; size?: TablePaginationConfig['size'] } = {},
): TablePaginationConfig {
  return {
    current: filters.page,
    pageSize: filters.pageSize,
    total,
    pageSizeOptions: options.sizes ?? TABLE_PAGE_SIZES,
    showSizeChanger: true,
    showTotal: options.showTotal,
    size: options.size,
    onChange,
  }
}
