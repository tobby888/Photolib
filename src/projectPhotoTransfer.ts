/**
 * 把选题相册里勾选的图片复制或移动到另一个选题（后端 `ProjectService.transferPhotos`）。
 *
 * 复制只往目标相册加归属，图片本身、采用记录都不动；移动在复制之后再按「移出选题」的规则
 * 从本选题移出（见 `projectPhotoRemoval.ts`），移不了的留在本选题，目标里也有一份。
 */
export type TransferMode = 'COPY' | 'MOVE'

export interface TransferPhotosResult {
  /** 新加进目标相册的张数。 */
  copied: number
  /** 本来就在目标相册里的张数（不算失败）。 */
  alreadyInTarget: number
  /** 已不在本选题相册、已删除或还没处理完，没有动。 */
  skipped: number
  /** 移动时真正离开本选题的张数。 */
  removed: number
  keptAdopted: number
  keptUploadedHere: number
}

/**
 * 做完之后给人看的一句话。复制说进了目标几张；移动说离开本选题几张——
 * 移不走的那几张目标里也有了，但还留在本选题，要单独说清楚。
 */
export function describeTransferResult(result: TransferPhotosResult, mode: TransferMode, targetTitle: string): string {
  const parts: string[] = []
  if (mode === 'MOVE') {
    parts.push(result.removed
      ? `已将 ${result.removed} 张图片移动到「${targetTitle}」`
      : '没有图片从本选题移走')
    const kept = [
      result.keptAdopted ? `${result.keptAdopted} 张在本选题已采纳` : '',
      result.keptUploadedHere ? `${result.keptUploadedHere} 张是在本选题上传的` : '',
    ].filter(Boolean)
    if (kept.length) parts.push(`${kept.join('、')}，已复制过去但仍保留在本选题`)
  } else {
    parts.push(result.copied
      ? `已复制 ${result.copied} 张图片到「${targetTitle}」`
      : `没有新图片进入「${targetTitle}」`)
    if (result.alreadyInTarget) parts.push(`${result.alreadyInTarget} 张本来就在目标选题里`)
  }
  if (result.skipped) parts.push(`${result.skipped} 张已不在本选题或尚未处理完，未处理`)
  return parts.join('；')
}
