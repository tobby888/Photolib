import { useSyncExternalStore } from 'react'

/**
 * 图片卡片的键盘快捷键（图库、选题详情、分享页、需求交付页共用），由用户自己设置。
 *
 * 存在浏览器 localStorage 里而不是账号上：分享页的访客没有账号，也要能改；
 * 同一台电脑上工作台和分享页用的是同一份设置。
 *
 * 值是 `KeyboardEvent.key`。字母统一存小写，这样开着大写锁定、或按住 Shift 连选时也能命中。
 * Shift 固定留给「连选」：按住 Shift 再按「选择」键，从上次选中的图片连选到当前图片。
 */
export interface PhotoCardShortcuts {
  /** 打开详情页 / 大图预览 */
  open: string
  /** 勾选 / 取消勾选 */
  select: string
}

export type PhotoCardShortcutAction = keyof PhotoCardShortcuts

export const DEFAULT_PHOTO_CARD_SHORTCUTS: PhotoCardShortcuts = { open: 'Enter', select: ' ' }

export const PHOTO_CARD_SHORTCUT_LABELS: Record<PhotoCardShortcutAction, string> = {
  open: '打开详情 / 大图',
  select: '选择 / 取消选择',
}

const STORAGE_KEY = 'photolib_photo_card_shortcuts'
const CHANGE_EVENT = 'photolib:photo-card-shortcuts'

/**
 * 不能设成快捷键的键：Tab 要留给焦点移动，Esc 要留给关闭预览 / 弹窗，
 * 单独的修饰键和输入法、死键产生的 key 也不是一个稳定的按键。
 */
const RESERVED_KEYS = new Set([
  'Tab', 'Escape', 'Shift', 'Control', 'Alt', 'AltGraph', 'Meta', 'CapsLock', 'NumLock', 'ScrollLock',
  'Fn', 'FnLock', 'Hyper', 'Super', 'Symbol', 'SymbolLock', 'OS', 'ContextMenu',
  'Dead', 'Process', 'Unidentified', 'Compose',
])

export function normalizeShortcutKey(key: string): string {
  return key.length === 1 ? key.toLowerCase() : key
}

/** 返回不能用的原因；能用返回 null。 */
export function validateShortcutKey(key: string): string | null {
  if (!key) return '没有识别到按键，请换一个键'
  if (RESERVED_KEYS.has(key)) {
    if (key === 'Tab') return 'Tab 用来在图片之间移动焦点，不能设为快捷键'
    if (key === 'Escape') return 'Esc 用来关闭预览和弹窗，不能设为快捷键'
    return '修饰键或输入法按键不能单独设为快捷键'
  }
  return null
}

/** 两个动作用了同一个键时返回提示；没有冲突返回 null。 */
export function validatePhotoCardShortcuts(shortcuts: PhotoCardShortcuts): string | null {
  for (const action of ['open', 'select'] as const) {
    const error = validateShortcutKey(shortcuts[action])
    if (error) return `${PHOTO_CARD_SHORTCUT_LABELS[action]}：${error}`
  }
  if (normalizeShortcutKey(shortcuts.open) === normalizeShortcutKey(shortcuts.select)) {
    return '「打开」和「选择」不能用同一个键'
  }
  return null
}

const KEY_NAMES: Record<string, string> = {
  ' ': '空格',
  Enter: 'Enter',
  Backspace: 'Backspace',
  Delete: 'Delete',
  Insert: 'Insert',
  Home: 'Home',
  End: 'End',
  PageUp: 'PageUp',
  PageDown: 'PageDown',
  ArrowUp: '↑',
  ArrowDown: '↓',
  ArrowLeft: '←',
  ArrowRight: '→',
}

/** 给界面和提示文字用的按键名。 */
export function formatShortcutKey(key: string): string {
  if (KEY_NAMES[key]) return KEY_NAMES[key]
  return key.length === 1 ? key.toUpperCase() : key
}

/**
 * 判断一次按键对应哪个动作。按住 Ctrl / Alt / ⌘ 时一律不接管，免得吃掉浏览器和系统的快捷键；
 * Shift 不参与匹配（Shift+选择键 = 连选，由调用方看 `event.shiftKey`）。
 */
export function matchPhotoCardShortcut(
  event: Pick<KeyboardEvent, 'key' | 'ctrlKey' | 'altKey' | 'metaKey'>,
  shortcuts: PhotoCardShortcuts,
): PhotoCardShortcutAction | null {
  if (event.ctrlKey || event.altKey || event.metaKey) return null
  const key = normalizeShortcutKey(event.key)
  if (key === normalizeShortcutKey(shortcuts.open)) return 'open'
  if (key === normalizeShortcutKey(shortcuts.select)) return 'select'
  return null
}

function storage(): Storage | null {
  try {
    const candidate = globalThis.localStorage
    if (!candidate) return null
    // 隐私模式里访问 localStorage 可能直接抛异常，这里用一次读探测可用性。
    candidate.getItem(STORAGE_KEY)
    return candidate
  } catch {
    return null
  }
}

/** 读回用户设置；没设过、数据损坏或设置本身不合法时退回默认值。 */
export function readPhotoCardShortcuts(): PhotoCardShortcuts {
  const store = storage()
  if (!store) return DEFAULT_PHOTO_CARD_SHORTCUTS
  try {
    const raw = store.getItem(STORAGE_KEY)
    if (!raw) return DEFAULT_PHOTO_CARD_SHORTCUTS
    const parsed: unknown = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return DEFAULT_PHOTO_CARD_SHORTCUTS
    const { open, select } = parsed as Record<string, unknown>
    if (typeof open !== 'string' || typeof select !== 'string') return DEFAULT_PHOTO_CARD_SHORTCUTS
    const shortcuts = { open: normalizeShortcutKey(open), select: normalizeShortcutKey(select) }
    return validatePhotoCardShortcuts(shortcuts) ? DEFAULT_PHOTO_CARD_SHORTCUTS : shortcuts
  } catch {
    return DEFAULT_PHOTO_CARD_SHORTCUTS
  }
}

/** 保存设置并通知当前页面上的卡片。存不进去（隐私模式、配额满）时抛出，让设置弹窗提示用户。 */
export function savePhotoCardShortcuts(shortcuts: PhotoCardShortcuts): void {
  const error = validatePhotoCardShortcuts(shortcuts)
  if (error) throw new Error(error)
  const store = storage()
  if (!store) throw new Error('当前浏览器不允许保存设置（可能处于隐私模式）')
  const normalized = { open: normalizeShortcutKey(shortcuts.open), select: normalizeShortcutKey(shortcuts.select) }
  const isDefault = normalized.open === DEFAULT_PHOTO_CARD_SHORTCUTS.open
    && normalized.select === DEFAULT_PHOTO_CARD_SHORTCUTS.select
  try {
    if (isDefault) store.removeItem(STORAGE_KEY)
    else store.setItem(STORAGE_KEY, JSON.stringify(normalized))
  } catch {
    throw new Error('设置没能保存到浏览器，请稍后重试')
  }
  cachedRaw = undefined
  globalThis.dispatchEvent?.(new Event(CHANGE_EVENT))
}

// useSyncExternalStore 要求快照在数据没变时引用不变，按原始字符串缓存一份。
let cachedRaw: string | null | undefined
let cachedShortcuts = DEFAULT_PHOTO_CARD_SHORTCUTS

function snapshot(): PhotoCardShortcuts {
  let raw: string | null = null
  try {
    raw = storage()?.getItem(STORAGE_KEY) ?? null
  } catch {
    raw = null
  }
  if (raw !== cachedRaw) {
    cachedRaw = raw
    cachedShortcuts = readPhotoCardShortcuts()
  }
  return cachedShortcuts
}

function subscribe(onChange: () => void) {
  const onStorage = (event: StorageEvent) => {
    // 其他标签页改了设置，这里跟着变。
    if (event.key === null || event.key === STORAGE_KEY) onChange()
  }
  window.addEventListener(CHANGE_EVENT, onChange)
  window.addEventListener('storage', onStorage)
  return () => {
    window.removeEventListener(CHANGE_EVENT, onChange)
    window.removeEventListener('storage', onStorage)
  }
}

/** 当前生效的图片卡片快捷键；设置弹窗保存后、或其他标签页改了之后自动更新。 */
export function usePhotoCardShortcuts(): PhotoCardShortcuts {
  return useSyncExternalStore(subscribe, snapshot, () => DEFAULT_PHOTO_CARD_SHORTCUTS)
}

/** 卡片 title 上的操作提示，跟着用户设置的键变。 */
export function photoCardHint(shortcuts: PhotoCardShortcuts, openLabel: string): string {
  return `单击或 ${formatShortcutKey(shortcuts.select)} 选择（Shift 连选），`
    + `双击或 ${formatShortcutKey(shortcuts.open)} ${openLabel}`
}
