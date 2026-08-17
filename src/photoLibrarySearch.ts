export const PHOTO_LIBRARY_STATUSES = ['AVAILABLE', 'PROCESSING', 'ARCHIVED'] as const

export type PhotoLibraryStatus = typeof PHOTO_LIBRARY_STATUSES[number]

export interface PhotoLibraryFilters {
  page: number
  keyword: string
  status: PhotoLibraryStatus
}

export const DEFAULT_PHOTO_LIBRARY_FILTERS: PhotoLibraryFilters = {
  page: 1,
  keyword: '',
  status: 'AVAILABLE',
}

export function readPhotoLibraryFilters(searchParams: URLSearchParams): PhotoLibraryFilters {
  const requestedPage = Number(searchParams.get('page'))
  const requestedStatus = searchParams.get('status')

  return {
    page: Number.isSafeInteger(requestedPage) && requestedPage > 0 ? requestedPage : 1,
    keyword: searchParams.get('keyword') ?? '',
    status: PHOTO_LIBRARY_STATUSES.includes(requestedStatus as PhotoLibraryStatus)
      ? requestedStatus as PhotoLibraryStatus
      : 'AVAILABLE',
  }
}

export function writePhotoLibraryFilters(filters: PhotoLibraryFilters): URLSearchParams {
  const searchParams = new URLSearchParams()
  if (filters.keyword) searchParams.set('keyword', filters.keyword)
  if (filters.status !== DEFAULT_PHOTO_LIBRARY_FILTERS.status) searchParams.set('status', filters.status)
  if (filters.page > 1) searchParams.set('page', String(filters.page))
  return searchParams
}

export function withPhotoLibrarySearch(pathname: string, search: string): string {
  const query = search.startsWith('?') ? search.slice(1) : search
  return query ? `${pathname}?${query}` : pathname
}
