const MAX_PHOTO_TITLE_CODE_POINTS = 200

export function photoTitleFromFileName(fileName: string): string {
  let title = fileName.trim()
  const extensionSeparator = title.lastIndexOf('.')
  if (extensionSeparator > 0) title = title.slice(0, extensionSeparator).trim()
  if (!title) title = '未命名图片'
  return Array.from(title).slice(0, MAX_PHOTO_TITLE_CODE_POINTS).join('')
}
