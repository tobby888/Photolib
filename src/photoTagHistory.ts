export const TAG_HISTORY_LIMIT = 10

/** 与 `src/photoTags.ts` 的 normalizeTags 保持同一套轻量规则，避免为历史记录引入运行时依赖。 */
function normalize(tags: readonly (string | null | undefined)[] | null | undefined): string[] {
  const result: string[] = []
  for (const tag of tags || []) {
    const value = tag?.trim()
    if (value && !result.includes(value)) result.push(value)
  }
  return result
}

/** localStorage 的键名按使用场景分开，避免图库、选题、分享链接之间的历史互相串。 */
export function tagHistoryStorageKey(scope: string): string {
  return `photolib_tag_history_${scope}`
}

function storage(): Storage | null {
  try {
    const candidate = globalThis.localStorage
    if (!candidate) return null
    // 隐私模式里访问 localStorage 可能直接抛异常，这里用一次读探测可用性。
    candidate.getItem('photolib_tag_history_probe')
    return candidate
  } catch {
    return null
  }
}

/** 读回某个场景的最近标签，最新在前；损坏或不可用一律当作空历史。 */
export function readRecentTags(scope: string, limit = TAG_HISTORY_LIMIT): string[] {
  const store = storage()
  if (!store) return []
  try {
    const raw = store.getItem(tagHistoryStorageKey(scope))
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    const tags: string[] = []
    for (const item of parsed) {
      if (typeof item === 'string') {
        const value = item.trim()
        if (value && !tags.includes(value)) tags.push(value)
      }
    }
    return tags.slice(0, limit)
  } catch {
    return []
  }
}

/** 把这次搜的标签记录到最近使用里，最新在前，按 limit 截断。 */
export function recordTagSearch(
  scope: string,
  tags: readonly (string | null | undefined)[],
  limit = TAG_HISTORY_LIMIT,
): string[] {
  const normalized = normalize(tags)
  if (!normalized.length) return readRecentTags(scope, limit)
  const existing = readRecentTags(scope, Number.MAX_SAFE_INTEGER)
  const merged = [...normalized, ...existing.filter(tag => !normalized.includes(tag))].slice(0, limit)
  const store = storage()
  if (store) {
    try {
      store.setItem(tagHistoryStorageKey(scope), JSON.stringify(merged))
    } catch {
      // 存不下只影响下次建议，不影响本次检索。
    }
  }
  return merged
}

export function clearTagHistory(scope: string): void {
  const store = storage()
  if (!store) return
  try {
    store.removeItem(tagHistoryStorageKey(scope))
  } catch {
    // 清理失败不影响检索。
  }
}
