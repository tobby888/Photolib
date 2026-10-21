import type { EntityId } from './types'

/** 移出选题时需要的那几个字段；`projectId` 是图片的来源项目（按需求或上传链接传进来的那个）。 */
export interface RemovablePhoto {
  id: string
  projectId?: EntityId | null
}

export interface PhotoRemovalPlan {
  removable: string[]
  adopted: string[]
  uploadedHere: string[]
}

/**
 * 把勾选的图片按「能不能移出本选题」分开，规则与后端 `ProjectService.removePhotos` 一致：
 * 在本选题被采纳的要先取消采纳；来源项目就是本选题的不移（移走后任何选题都不再展示它）。
 * 后端仍会逐张重判——校区范围账号在详情页拿不到采纳记录，`adoptedIds` 对它是空的。
 */
export function planPhotoRemoval(
  photos: RemovablePhoto[], projectId: EntityId, adoptedIds: ReadonlySet<string>,
): PhotoRemovalPlan {
  const plan: PhotoRemovalPlan = { removable: [], adopted: [], uploadedHere: [] }
  for (const photo of photos) {
    if (photo.projectId != null && String(photo.projectId) === String(projectId)) plan.uploadedHere.push(photo.id)
    else if (adoptedIds.has(String(photo.id))) plan.adopted.push(photo.id)
    else plan.removable.push(photo.id)
  }
  return plan
}

export interface RemovePhotosResult {
  removed: number
  skippedAdopted: number
  skippedUploadedHere: number
}

/** 移出之后给人看的一句话：移了几张，没移的为什么没移。 */
export function describeRemovalResult(result: RemovePhotosResult): string {
  const skipped = [
    result.skippedAdopted ? `${result.skippedAdopted} 张已采纳` : '',
    result.skippedUploadedHere ? `${result.skippedUploadedHere} 张是在本选题上传的` : '',
  ].filter(Boolean)
  const head = result.removed ? `已将 ${result.removed} 张图片移出选题` : '没有图片被移出选题'
  return skipped.length ? `${head}；${skipped.join('、')}，未移出` : head
}
