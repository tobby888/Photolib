// 在浏览器里按需读上传者自己选的那个 ZIP，只为「确认标签」对话框里的缩略图。
//
// 压缩包最大 1.5 GB，不能整个读进内存（fflate / JSZip 的 unzip 都要整份 ArrayBuffer），
// 所以这里只读末尾的中央目录建索引，要哪张图再用 Blob.slice 取那一段：存储（不压缩）的
// 条目直接是原文件的一个切片，deflate 的经 DecompressionStream 解压。缩略图失败不影响上传，
// 对话框里显示占位图即可。条目名的解码与后端 SafeImageZipExtractor.decodeEntryName 一致：
// 带 UTF-8 标志位的按 UTF-8，其余先试 UTF-8、再试 GBK（中文 Windows 自带压缩不写标志位）。

import { entryKey } from './zipFolderTags.ts'

export interface LocalZipEntry {
  /** 与后端条目对得上的路径键，见 zipFolderTags.entryKey。 */
  key: string
  method: number
  encrypted: boolean
  compressedSize: number
  uncompressedSize: number
  localHeaderOffset: number
}

const EOCD = 0x06054b50
const ZIP64_LOCATOR = 0x07064b50
const ZIP64_EOCD = 0x06064b50
const CENTRAL = 0x02014b50
const LOCAL = 0x04034b50
const U32_MAX = 0xffffffff

const readBytes = async (blob: Blob, start: number, end: number) =>
  new DataView(await blob.slice(start, end).arrayBuffer())

const u64 = (view: DataView, offset: number) => Number(view.getBigUint64(offset, true))

const utf8 = new TextDecoder('utf-8')

function decodeName(bytes: Uint8Array, utf8Flag: boolean): string {
  if (utf8Flag || bytes.every(value => value < 0x80)) return utf8.decode(bytes)
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    try {
      return new TextDecoder('gbk', { fatal: true }).decode(bytes)
    } catch {
      return new TextDecoder('latin1').decode(bytes)
    }
  }
}

/** 读中央目录，按路径键建索引（同一个键出现两次时留第一个，与后端解包顺序一致）。 */
export async function readZipIndex(file: Blob): Promise<Map<string, LocalZipEntry>> {
  const tailStart = Math.max(0, file.size - (0xffff + 22))
  const tail = await readBytes(file, tailStart, file.size)
  let eocd = -1
  for (let offset = tail.byteLength - 22; offset >= 0; offset--) {
    if (tail.getUint32(offset, true) === EOCD) {
      eocd = offset
      break
    }
  }
  if (eocd < 0) throw new Error('不是有效的 ZIP 文件')
  let entryCount = tail.getUint16(eocd + 10, true)
  let directorySize = tail.getUint32(eocd + 12, true)
  let directoryOffset = tail.getUint32(eocd + 16, true)
  if (entryCount === 0xffff || directorySize === U32_MAX || directoryOffset === U32_MAX) {
    const locator = eocd - 20
    if (locator >= 0 && tail.getUint32(locator, true) === ZIP64_LOCATOR) {
      const zip64Offset = u64(tail, locator + 8)
      const zip64 = await readBytes(file, zip64Offset, zip64Offset + 56)
      if (zip64.getUint32(0, true) !== ZIP64_EOCD) throw new Error('ZIP64 目录损坏')
      entryCount = u64(zip64, 32)
      directorySize = u64(zip64, 40)
      directoryOffset = u64(zip64, 48)
    }
  }
  const directory = await readBytes(file, directoryOffset, directoryOffset + directorySize)
  const entries = new Map<string, LocalZipEntry>()
  let offset = 0
  for (let index = 0; index < entryCount && offset + 46 <= directory.byteLength; index++) {
    if (directory.getUint32(offset, true) !== CENTRAL) break
    const flags = directory.getUint16(offset + 8, true)
    const method = directory.getUint16(offset + 10, true)
    let compressedSize = directory.getUint32(offset + 20, true)
    let uncompressedSize = directory.getUint32(offset + 24, true)
    const nameLength = directory.getUint16(offset + 28, true)
    const extraLength = directory.getUint16(offset + 30, true)
    const commentLength = directory.getUint16(offset + 32, true)
    let localHeaderOffset = directory.getUint32(offset + 42, true)
    const nameBytes = new Uint8Array(directory.buffer, directory.byteOffset + offset + 46, nameLength)
    // ZIP64 扩展字段：只有在常规字段是 0xFFFFFFFF 时才按顺序出现。
    let extra = offset + 46 + nameLength
    const extraEnd = extra + extraLength
    while (extra + 4 <= extraEnd) {
      const id = directory.getUint16(extra, true)
      const size = directory.getUint16(extra + 2, true)
      if (id === 0x0001) {
        let field = extra + 4
        if (uncompressedSize === U32_MAX) { uncompressedSize = u64(directory, field); field += 8 }
        if (compressedSize === U32_MAX) { compressedSize = u64(directory, field); field += 8 }
        if (localHeaderOffset === U32_MAX) localHeaderOffset = u64(directory, field)
      }
      extra += 4 + size
    }
    const name = decodeName(nameBytes, (flags & 0x0800) !== 0)
    const key = name.endsWith('/') ? null : entryKey(name)
    if (key && !entries.has(key)) {
      entries.set(key, {
        key, method, encrypted: (flags & 0x0001) !== 0, compressedSize, uncompressedSize, localHeaderOffset,
      })
    }
    offset = extraEnd + commentLength
  }
  return entries
}

async function inflateRaw(data: Blob): Promise<Blob> {
  if (typeof DecompressionStream === 'function') {
    return new Response(data.stream().pipeThrough(new DecompressionStream('deflate-raw'))).blob()
  }
  // 老浏览器：退回 fflate（已经是依赖），按需加载，不进首屏。
  const { inflateSync } = await import('fflate')
  return new Blob([inflateSync(new Uint8Array(await data.arrayBuffer()))])
}

/** 取出一个条目的原始字节。只支持存储（0）和 deflate（8），加密条目不读。 */
export async function readZipEntry(file: Blob, entry: LocalZipEntry): Promise<Blob> {
  if (entry.encrypted) throw new Error('加密的条目')
  const header = await readBytes(file, entry.localHeaderOffset, entry.localHeaderOffset + 30)
  if (header.getUint32(0, true) !== LOCAL) throw new Error('条目头损坏')
  const start = entry.localHeaderOffset + 30 + header.getUint16(26, true) + header.getUint16(28, true)
  const data = file.slice(start, start + entry.compressedSize)
  if (entry.method === 0) return data
  if (entry.method === 8) return inflateRaw(data)
  throw new Error(`不支持的压缩方式 ${entry.method}`)
}
