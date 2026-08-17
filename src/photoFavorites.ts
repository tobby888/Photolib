import type { EntityId, PageData } from './types'

export function updateFavoritePage<T extends { id: EntityId; favorited: boolean }>(
  page: PageData<T>,
  photoId: EntityId,
  favorited: boolean,
  favoritesOnly: boolean,
): PageData<T> {
  const containsPhoto = page.items.some(item => item.id === photoId)
  if (!containsPhoto) return page

  if (favoritesOnly && !favorited) {
    const total = Math.max(0, page.total - 1)
    return {
      ...page,
      items: page.items.filter(item => item.id !== photoId),
      total,
      totalPages: page.pageSize > 0 ? Math.ceil(total / page.pageSize) : 0,
    }
  }

  return {
    ...page,
    items: page.items.map(item => item.id === photoId ? { ...item, favorited } : item),
  }
}
