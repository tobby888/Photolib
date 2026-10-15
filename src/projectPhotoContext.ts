import type { EntityId } from './types'
import { emptyProjectPhotoFilters, normalizeTags, type ProjectPhotoFilters } from './photoTags.ts'
import { GRID_PAGE_SIZES } from './pagination.ts'
import { PHOTO_LIBRARY_PAGE_SIZE } from './photoLibrarySearch.ts'

/**
 * 从选题相册点进图片详情页时带上的「来处」。
 *
 * 详情页原本只认图片库：返回按钮回 `/photos`，上一张 / 下一张按图库那一屏的顺序翻。
 * 从选题里点进去的人想要的是「看完回到选题、接着在这个相册里往下翻」，所以：
 * - 选题 id 放在 URL 上（`/photos/<id>?project=<projectId>`），刷新、复制链接都还认得回哪；
 * - 相册当时的顺序（已经套过筛选）放在路由 state 里——几百个 id 塞不进 URL，
 *   而且只在「从选题点进来」这一路上才有意义。直链进来没有 state，详情页退回按整个相册的顺序翻；
 * - 相册的筛选、页码和最后看的那张记在 sessionStorage，回到选题时原样恢复并滚到那张。
 */
export const PROJECT_CONTEXT_PARAM = 'project'

/** 选题相册每页张数的候选与默认值；与图库一致。 */
export const PROJECT_ALBUM_PAGE_SIZES = GRID_PAGE_SIZES
export const PROJECT_ALBUM_PAGE_SIZE = PHOTO_LIBRARY_PAGE_SIZE

export interface ProjectPhotoNavState {
  /** 相册当时（套过筛选之后）的图片顺序。 */
  projectPhotoIds: string[]
}

export function projectPhotoDetailPath(photoId: EntityId, projectId: EntityId): string {
  return `/photos/${photoId}?${PROJECT_CONTEXT_PARAM}=${encodeURIComponent(String(projectId))}`
}

/** 选题 id 全是数字；URL 可能被手改，不像 id 的一律当成没有来处。 */
export function readProjectContext(search: string): string | null {
  const value = new URLSearchParams(search).get(PROJECT_CONTEXT_PARAM)
  return value && /^\d{1,20}$/.test(value) ? value : null
}

export function readProjectNavState(state: unknown): string[] | null {
  const ids = (state as Partial<ProjectPhotoNavState> | null)?.projectPhotoIds
  return Array.isArray(ids) && ids.every(id => typeof id === 'string') ? ids : null
}

export interface ListNeighbors {
  /** 当前图片在不在这份顺序里。不在（被移出筛选、刚被删除）时两个方向都不可用。 */
  positioned: boolean
  previous: string | null
  next: string | null
}

export function neighborsInList(ids: readonly string[] | null, photoId: EntityId | undefined): ListNeighbors {
  const index = ids && photoId !== undefined ? ids.indexOf(String(photoId)) : -1
  if (!ids || index < 0) return { positioned: false, previous: null, next: null }
  return {
    positioned: true,
    previous: index > 0 ? ids[index - 1] : null,
    next: index < ids.length - 1 ? ids[index + 1] : null,
  }
}

/** 相册视图里回来时要恢复的那几样。 */
export interface ProjectAlbumView {
  filters: ProjectPhotoFilters
  page: number
  pageSize: number
  /** 最后在详情页看的那张；回到选题时把它所在的那一页翻出来并滚过去。 */
  focusPhotoId: string | null
}

const storageKey = (projectId: EntityId) => `photolib_project_album_${projectId}`

const isDate = (value: unknown): value is string => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)

/**
 * 读回存过的相册视图。sessionStorage 可能被禁用、被清空，存的东西也可能是旧版本留下的，
 * 所以逐项校验，坏掉的那一项退回默认值，而不是让整页挂掉。
 */
export function loadProjectAlbumView(projectId: EntityId): ProjectAlbumView | null {
  let raw: string | null = null
  try {
    raw = window.sessionStorage.getItem(storageKey(projectId))
  } catch {
    return null
  }
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as Partial<Omit<ProjectAlbumView, 'filters'>> & {
      filters?: Partial<Record<keyof ProjectPhotoFilters, unknown>>
    }
    const filters = parsed.filters ?? {}
    return {
      filters: {
        tags: Array.isArray(filters.tags)
          ? normalizeTags(filters.tags.filter((tag): tag is string => typeof tag === 'string')) : [],
        takenFrom: isDate(filters.takenFrom) ? filters.takenFrom : null,
        takenTo: isDate(filters.takenTo) ? filters.takenTo : null,
        photographers: Array.isArray(filters.photographers)
          ? filters.photographers.filter((name): name is string => typeof name === 'string')
          : [],
        adoption: filters.adoption === 'ADOPTED' || filters.adoption === 'NOT_ADOPTED'
          ? filters.adoption : emptyProjectPhotoFilters.adoption,
      },
      page: Number.isSafeInteger(parsed.page) && (parsed.page as number) > 0 ? parsed.page as number : 1,
      pageSize: PROJECT_ALBUM_PAGE_SIZES.includes(parsed.pageSize as number)
        ? parsed.pageSize as number : PROJECT_ALBUM_PAGE_SIZE,
      focusPhotoId: typeof parsed.focusPhotoId === 'string' ? parsed.focusPhotoId : null,
    }
  } catch {
    return null
  }
}

export function saveProjectAlbumView(projectId: EntityId, view: ProjectAlbumView): void {
  try {
    window.sessionStorage.setItem(storageKey(projectId), JSON.stringify(view))
  } catch {
    // 存不下（隐私模式、配额满）只是回来时不恢复，不影响任何功能。
  }
}

/** 详情页里翻到别的图时，只改「最后看的那张」，其余照旧。 */
export function rememberProjectAlbumFocus(projectId: EntityId, photoId: EntityId): void {
  const current = loadProjectAlbumView(projectId)
  saveProjectAlbumView(projectId, {
    filters: current?.filters ?? emptyProjectPhotoFilters,
    page: current?.page ?? 1,
    pageSize: current?.pageSize ?? PROJECT_ALBUM_PAGE_SIZE,
    focusPhotoId: String(photoId),
  })
}

/** 让最后看的那张落在当前页上：它在筛选结果里的话，返回它所在的页码，否则沿用原页码。 */
export function pageContaining(ids: readonly string[], photoId: string | null, pageSize: number,
  fallback: number): number {
  const index = photoId ? ids.indexOf(photoId) : -1
  return index < 0 ? fallback : Math.floor(index / pageSize) + 1
}
