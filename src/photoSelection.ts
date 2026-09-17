/**
 * Shift 连选：从锚点到当前点击的图片，把中间可见图片并入已有选择。
 *
 * 锚点或目标不在当前列表里时返回原选择并交出新的锚点，调用方据此回退到普通点选。
 */
export function selectPhotoRange(
  current: readonly string[],
  orderedIds: readonly string[],
  anchorId: string | null,
  targetId: string,
  limit = 200,
): { selected: string[]; anchorId: string; truncated: boolean } {
  const anchorIndex = anchorId ? orderedIds.indexOf(anchorId) : -1
  const targetIndex = orderedIds.indexOf(targetId)
  if (anchorIndex < 0 || targetIndex < 0) {
    return { selected: [...current], anchorId: targetId, truncated: false }
  }

  const start = Math.min(anchorIndex, targetIndex)
  const end = Math.max(anchorIndex, targetIndex)
  const known = new Set(current)
  const added: string[] = []
  let truncated = false

  for (const id of orderedIds.slice(start, end + 1)) {
    if (known.has(id)) continue
    if (current.length + added.length >= limit) {
      truncated = true
      break
    }
    known.add(id)
    added.push(id)
  }

  return {
    selected: added.length ? [...current, ...added] : [...current],
    anchorId: targetId,
    truncated,
  }
}
