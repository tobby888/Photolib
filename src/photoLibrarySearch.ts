import { MAX_TAG_LENGTH, MAX_TAGS, normalizeTags } from './photoTags.ts'
import { GRID_PAGE_SIZES, normalizePageSize } from './pagination.ts'

/**
 * 图库默认的每页图片数。用户可以在分页条上改，改完的值进 URL（见下），详情页的
 * 「上一张 / 下一张」按同一套筛选条件重新取列表来定位当前图片（`src/photoNeighbors.ts`），
 * 两边的分页必须严格一致，否则翻页会跳号——所以每页条数也是筛选条件的一部分。
 */
export const PHOTO_LIBRARY_PAGE_SIZE = 24

/** 图库每页条数的候选值。后端 `GET /photos` 的 pageSize 上限是 100。 */
export const PHOTO_LIBRARY_PAGE_SIZES = GRID_PAGE_SIZES

export const PHOTO_LIBRARY_STATUSES = ['AVAILABLE', 'PROCESSING', 'ARCHIVED'] as const

export type PhotoLibraryStatus = typeof PHOTO_LIBRARY_STATUSES[number]

/** 一次最多按多少位拍摄者筛选；后端 `GET /photos` 的 photographers 上限是 200，这里远用不到。 */
export const MAX_PHOTOGRAPHER_FILTERS = 50

/** 拍摄者姓名的长度上限，与 photo.photographer_name 的列宽一致。 */
const MAX_PHOTOGRAPHER_NAME_LENGTH = 100

export interface PhotoLibraryFilters {
  page: number
  /** 每页张数，取值来自 `PHOTO_LIBRARY_PAGE_SIZES`；随 URL 走，详情页要按同一个值定位。 */
  pageSize: number
  keyword: string
  status: PhotoLibraryStatus
  /** 同时包含全部所选标签；与后端的精确标签过滤对应。 */
  tags: string[]
  /**
   * 拍摄者姓名，任意其一，空数组表示不限；候选来自 GET /photos/photographers。
   * 认的是图片上的拍摄者而不是上传账号——上传的人不一定是拍的人。
   */
  photographers: string[]
}

export const DEFAULT_PHOTO_LIBRARY_FILTERS: PhotoLibraryFilters = {
  page: 1,
  pageSize: PHOTO_LIBRARY_PAGE_SIZE,
  keyword: '',
  status: 'AVAILABLE',
  tags: [],
  photographers: [],
}

export function readPhotoLibraryFilters(searchParams: URLSearchParams): PhotoLibraryFilters {
  const requestedPage = Number(searchParams.get('page'))
  const requestedStatus = searchParams.get('status')

  return {
    page: Number.isSafeInteger(requestedPage) && requestedPage > 0 ? requestedPage : 1,
    // URL 可能被手改：后端的 pageSize 上限是 100，超出会 400 让整页变成加载出错。
    pageSize: normalizePageSize(searchParams.get('pageSize'), PHOTO_LIBRARY_PAGE_SIZES, PHOTO_LIBRARY_PAGE_SIZE),
    keyword: searchParams.get('keyword') ?? '',
    status: PHOTO_LIBRARY_STATUSES.includes(requestedStatus as PhotoLibraryStatus)
      ? requestedStatus as PhotoLibraryStatus
      : 'AVAILABLE',
    // URL 可能被手改：超长的标签后端会直接 400，这里先丢掉，页面按剩下的条件正常加载。
    tags: normalizeTags(searchParams.getAll('tags')).filter(tag => !isTagTooLong(tag)).slice(0, MAX_TAGS),
    photographers: normalizePhotographerFilter(searchParams.getAll('photographers')),
  }
}

/**
 * 整理拍摄者筛选：去空、去重、限个数。不 trim——候选是库里的原值，原样传回去才对得上。
 * URL 可能被手改，超长或超量的值在这里丢掉，页面按剩下的条件正常加载。
 */
export function normalizePhotographerFilter(names: readonly string[]): string[] {
  return [...new Set(names.filter(name => name.trim() && [...name].length <= MAX_PHOTOGRAPHER_NAME_LENGTH))]
    .slice(0, MAX_PHOTOGRAPHER_FILTERS)
}

/** 按「字」计长度（与后端 codePointCount 一致），一个 emoji 算一个字。 */
export function isTagTooLong(tag: string): boolean {
  return [...tag].length > MAX_TAG_LENGTH
}

/**
 * 图库列表请求的查询参数。图库页和详情页的「上一张 / 下一张」必须发同一套参数，否则定位会错位。
 * 多个标签 / 拍摄者发成 `tags=a&tags=b`：Spring 的 List 参数只认这种格式，不认 axios 默认的 `tags[]=`。
 */
export function photoLibraryRequestParams(
  filters: PhotoLibraryFilters,
  options: { page?: number; favoritesOnly?: boolean } = {},
): URLSearchParams {
  const params = new URLSearchParams()
  params.set('page', String(options.page ?? filters.page))
  params.set('pageSize', String(filters.pageSize))
  if (filters.keyword) params.set('keyword', filters.keyword)
  params.set('status', filters.status)
  for (const tag of filters.tags) params.append('tags', tag)
  for (const photographer of filters.photographers) params.append('photographers', photographer)
  if (options.favoritesOnly) params.set('favoritesOnly', 'true')
  return params
}

export function writePhotoLibraryFilters(filters: PhotoLibraryFilters): URLSearchParams {
  const searchParams = new URLSearchParams()
  if (filters.keyword) searchParams.set('keyword', filters.keyword)
  if (filters.status !== DEFAULT_PHOTO_LIBRARY_FILTERS.status) searchParams.set('status', filters.status)
  if (filters.page > 1) searchParams.set('page', String(filters.page))
  if (filters.pageSize !== DEFAULT_PHOTO_LIBRARY_FILTERS.pageSize) searchParams.set('pageSize', String(filters.pageSize))
  for (const tag of filters.tags ?? []) searchParams.append('tags', tag)
  for (const photographer of filters.photographers ?? []) searchParams.append('photographers', photographer)
  return searchParams
}

export function withPhotoLibrarySearch(pathname: string, search: string): string {
  const query = search.startsWith('?') ? search.slice(1) : search
  return query ? `${pathname}?${query}` : pathname
}
