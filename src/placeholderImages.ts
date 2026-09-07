/**
 * 缺图占位图的取图规则。管理员可以传多张，前端在图片取不到时任取一张顶上。
 *
 * "任取一张"不等于每次渲染都随机：同一张图片必须每次都落到同一张占位图上，
 * 否则列表滚动、重渲染时占位图会不停地换，看起来像是页面在闪。所以按
 * seed（通常是图片 ID）做一次哈希再取模——不同图片散落到不同占位图上，
 * 同一张图片则始终稳定。没有 seed 可用时才真随机取一张。
 */
export function pickPlaceholderImage(images: string[], seed?: string | number) {
  if (!images.length) return undefined
  if (seed === undefined || seed === null || seed === '') {
    return images[Math.floor(Math.random() * images.length)]
  }
  const text = String(seed)
  let hash = 5381
  for (let index = 0; index < text.length; index += 1) {
    hash = (hash * 33 + text.charCodeAt(index)) >>> 0
  }
  return images[hash % images.length]
}
