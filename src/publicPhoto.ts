// 公共照片：一个固定的拍摄者，与后端 `cn.photolib.photo.PublicPhoto` 一致。
// 它不计被引、上传链接里选了它就不能申报工时；选题可以设成「仅接收公共照片」。
// 判定按拍摄者姓名，所以图库「按拍摄者筛选」里它就是一个普通的作者。

import type { EntityId } from './types'

export const PUBLIC_PHOTOGRAPHER = '公共照片'

/** 拍摄者下拉框里「公共照片」那一项的值。通讯录成员的 id 是数字，不会撞上。 */
export const PUBLIC_PHOTOGRAPHER_VALUE = '__public__'

export function isPublicPhoto(photographerName: string | null | undefined): boolean {
  return photographerName?.trim() === PUBLIC_PHOTOGRAPHER
}

export interface PhotographerOption {
  value: EntityId
  label: string
}

/** 拍摄者下拉框的选项：公共照片排第一，后面是通讯录成员。 */
export function withPublicPhotographer<T extends PhotographerOption>(
  options: readonly T[],
): (T | PhotographerOption)[] {
  return [{ value: PUBLIC_PHOTOGRAPHER_VALUE, label: PUBLIC_PHOTOGRAPHER }, ...options]
}

/**
 * 表单里选中的拍摄者换成接口要的字段：公共照片发 `publicPhoto: true`，
 * 通讯录成员发 `photographerContactId`。两个字段只出现一个。
 */
export function photographerPayload(value: EntityId | null | undefined):
  { publicPhoto: true } | { photographerContactId: EntityId | null | undefined } {
  return value === PUBLIC_PHOTOGRAPHER_VALUE
    ? { publicPhoto: true }
    : { photographerContactId: value }
}

/** 拍摄者下拉框为空时的提示：通讯录空了也还能选公共照片，所以不算「没得选」。 */
export const PUBLIC_PHOTO_HINT = '选「公共照片」时图片不属于任何成员，不计入被引。'

/** 拍摄者列表（图库筛选候选）里把公共照片挪到最前面，方便在总库里找。 */
export function publicPhotographerFirst(names: readonly string[]): string[] {
  const rest = names.filter(name => !isPublicPhoto(name))
  return rest.length === names.length ? [...names] : [PUBLIC_PHOTOGRAPHER, ...rest]
}

/**
 * 上传表单的拍摄者选项：选题仅接收公共照片时只剩「公共照片」一项（表单同时把它选上并锁住），
 * 否则公共照片排第一、后面是通讯录成员。
 */
export function photographerOptionsFor<T extends PhotographerOption>(
  members: readonly T[], publicPhotosOnly: boolean | null | undefined,
): (T | PhotographerOption)[] {
  return publicPhotosOnly
    ? [{ value: PUBLIC_PHOTOGRAPHER_VALUE, label: PUBLIC_PHOTOGRAPHER }]
    : withPublicPhotographer(members)
}
