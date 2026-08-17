export const CONTENT_FIT_TABLE_LAYOUT = 'auto' as const
export const CONTENT_FIT_TABLE_SCROLL = { x: 'max-content' } as const

export function contentFitTableClassName(className?: string): string {
  return ['content-fit-table', className].filter(Boolean).join(' ')
}

export function contentFitTableScroll<Scroll extends object>(scroll?: Scroll) {
  return { ...scroll, ...CONTENT_FIT_TABLE_SCROLL }
}
