import { api } from './api'
import type { EntityId, FeaturedEntry, Photo } from './types'

/**
 * 预览图加载失败之后，去后端重新要一条签名地址。
 *
 * 走的都是页面本来就用过的读接口，所以不需要额外的权限判断：能看见这张图的人
 * 才拿得到新地址，看不见的人这里同样会被拒。
 *
 * 同一批地址是一起过期的，一屏几十张图会几乎同时失败。所以按"一次请求能救回
 * 多少张图"合并在途请求：图库详情是一张一条，精选作品则一次把整个合集的条目
 * 重取回来。
 */

const inflight = new Map<string, Promise<unknown>>()

function once<T>(key: string, load: () => Promise<T>): Promise<T> {
  const existing = inflight.get(key)
  if (existing) return existing as Promise<T>
  const pending: Promise<T> = load().finally(() => {
    if (inflight.get(key) === pending) inflight.delete(key)
  })
  inflight.set(key, pending)
  return pending
}

/** 站内图片：重新读一次详情，后端会按当前签名窗口重新签地址。 */
export function refreshPhotoPreviewUrl(photoId: EntityId): Promise<string | undefined> {
  return once(`photo:${photoId}`, async () => {
    const photo = await api<Photo>({ url: `/photos/${photoId}` })
    return photo.thumbnailUrl
  })
}

/** 精选作品：条目接口没有单条版本，一次把整个合集重取回来，正好把整屏都换新。 */
export async function refreshFeaturedEntryPreviewUrl(
  collectionId: EntityId, entryId: EntityId,
): Promise<string | undefined> {
  const entries = await once(`featured-entries:${collectionId}`,
    () => api<FeaturedEntry[]>({ url: `/featured-collections/${collectionId}/entries` }))
  return entries.find(entry => String(entry.id) === String(entryId))?.previewUrl ?? undefined
}
