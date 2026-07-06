import exifr from 'exifr'
import dayjs, { type Dayjs } from 'dayjs'

/**
 * 读取图片 EXIF 中的原始拍摄时间（DateTimeOriginal，次选 CreateDate）。
 * 读不到、解析失败或时间无效时返回 null，由调用方保留手动输入。
 * 未来时间会被判定为无效（后端 takenAt 为 @PastOrPresent，会拒绝未来值）。
 */
export async function readTakenAt(file: File): Promise<Dayjs | null> {
  try {
    const data = await exifr.parse(file, ['DateTimeOriginal', 'CreateDate'])
    const raw = data?.DateTimeOriginal ?? data?.CreateDate
    if (!raw) return null
    const parsed = dayjs(raw)
    if (!parsed.isValid()) return null
    if (parsed.isAfter(dayjs())) return null
    return parsed
  } catch {
    return null
  }
}
