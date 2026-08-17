import { measureNaturalWidth, prepareWithSegments } from '@chenglou/pretext'

const TABLE_CONTROL_FONT = '14px "PingFang SC", "Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC", "Source Han Sans SC", sans-serif'
const DEFAULT_CELL_INLINE_PADDING = 32
const DEFAULT_SPACE_GAP = 8
const DEFAULT_BUTTON_CHROME = 32
const DEFAULT_ICON_CHROME = 22
const DEFAULT_SAFETY_MARGIN = 8
const DEFAULT_MIN_WIDTH = 96

export interface ActionWidthItem {
  /** Visible button label. */
  label?: string
  /** Adds the Ant Design icon width and icon/text gap. */
  icon?: boolean
  /** Width for non-button controls such as a Switch. */
  fixedWidth?: number
  /** Any additional component chrome not covered by the defaults. */
  extraWidth?: number
}

export interface ActionColumnWidthOptions {
  measureText?: (text: string) => number
  minWidth?: number
  cellInlinePadding?: number
  itemGap?: number
  buttonChrome?: number
  iconChrome?: number
  safetyMargin?: number
}

const measuredTextWidths = new Map<string, number>()
const wideCharacter = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}\p{Extended_Pictographic}]/u

/** Conservative fallback for non-browser runtimes that have no Canvas. */
export function fallbackTableTextWidth(text: string, fontSize = 14): number {
  return Array.from(text).reduce((width, character) =>
    width + (wideCharacter.test(character) ? fontSize : fontSize * 0.56), 0)
}

/** Measures the natural, unwrapped width of table/control text with Pretext. */
export function measureTableText(text: string): number {
  if (!text) return 0
  const cached = measuredTextWidths.get(text)
  if (cached !== undefined) return cached

  let width: number
  try {
    width = measureNaturalWidth(prepareWithSegments(text, TABLE_CONTROL_FONT, { wordBreak: 'keep-all' }))
  } catch {
    // Node-based unit tests and older runtimes may not expose a Canvas context.
    width = fallbackTableTextWidth(text)
  }
  measuredTextWidths.set(text, width)
  return width
}

/**
 * Calculates a stable minimum width for a fixed action column. Each variant is
 * one possible row of controls; the widest valid row wins. Browser
 * `max-content` layout remains the final guard for arbitrary rendered JSX.
 */
export function calculateActionColumnWidth(
  variants: readonly (readonly ActionWidthItem[])[],
  options: ActionColumnWidthOptions = {},
): number {
  const measureText = options.measureText ?? measureTableText
  const minWidth = options.minWidth ?? DEFAULT_MIN_WIDTH
  const cellInlinePadding = options.cellInlinePadding ?? DEFAULT_CELL_INLINE_PADDING
  const itemGap = options.itemGap ?? DEFAULT_SPACE_GAP
  const buttonChrome = options.buttonChrome ?? DEFAULT_BUTTON_CHROME
  const iconChrome = options.iconChrome ?? DEFAULT_ICON_CHROME
  const safetyMargin = options.safetyMargin ?? DEFAULT_SAFETY_MARGIN

  const widestControls = variants.reduce((widest, items) => {
    const controlsWidth = items.reduce((total, item) => {
      const controlWidth = item.fixedWidth ?? (
        measureText(item.label ?? '') + buttonChrome + (item.icon ? iconChrome : 0)
      )
      return total + controlWidth + (item.extraWidth ?? 0)
    }, 0)
    const gapsWidth = Math.max(0, items.length - 1) * itemGap
    return Math.max(widest, controlsWidth + gapsWidth)
  }, 0)

  const headerWidth = measureText('操作') + cellInlinePadding + safetyMargin
  return Math.ceil(Math.max(minWidth, headerWidth, widestControls + cellInlinePadding + safetyMargin))
}
