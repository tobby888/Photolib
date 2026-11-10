import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { zipSync } from 'fflate'
import { readZipEntry, readZipIndex } from '../src/localZip.ts'
import {
  archiveItemKey, entryKey, folderTags, hasFolderTags, initialItemTags, isAllowedTag, itemTagsPayload,
  recognizedTags, rejectedTags, removeTag, renameTag, tagUsage,
} from '../src/zipFolderTags.ts'
import type { ArchiveItem } from '../src/zipFolderTags.ts'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')

const items: ArchiveItem[] = [
  { id: '1', fileName: '1.jpg', folderPath: '运动会/开幕式' },
  { id: '2', fileName: '2.jpg', folderPath: '运动会' },
  { id: '3', fileName: '3.jpg', folderPath: null },
]

test('每一层文件夹名识别成一个标签，由外到内', () => {
  assert.deepEqual(folderTags('运动会/开幕式'), ['运动会', '开幕式'])
  assert.deepEqual(folderTags('运动会'), ['运动会'])
  assert.deepEqual(folderTags(null), [])
  assert.deepEqual(folderTags(''), [])
})

test('空层、"." 层和同名的层不重复成标签，首尾空白去掉', () => {
  assert.deepEqual(folderTags(' 合影 //./班级/合影'), ['合影', '班级'])
})

test('超过 50 字的文件夹名截到 50 字，保留标签不会从文件夹名里冒出来', () => {
  const long = '长'.repeat(60)
  assert.deepEqual(folderTags(`${long}/Deprecated/x`), ['长'.repeat(50), 'x'])
})

test('一个文件夹都没有的压缩包不弹对话框', () => {
  assert.equal(hasFolderTags(items), true)
  assert.equal(hasFolderTags([{ id: '9', fileName: 'a.jpg', folderPath: null }]), false)
  assert.equal(hasFolderTags([]), false)
})

test('对话框里每张图的初始标签 = 整批共用的 + 文件夹识别的，去重', () => {
  assert.deepEqual(initialItemTags(items, ['活动', '运动会']), {
    1: ['活动', '运动会', '开幕式'],
    2: ['活动', '运动会'],
    3: ['活动', '运动会'],
  })
  assert.deepEqual(recognizedTags(items), ['运动会', '开幕式'])
})

test('标签最多 30 个：整批的加文件夹的超出时截掉', () => {
  const shared = Array.from({ length: 29 }, (_, index) => `t${index}`)
  assert.equal(initialItemTags([{ id: '1', fileName: 'a.jpg', folderPath: 'x/y' }], shared)['1'].length, 30)
})

test('整体改名与整体去掉作用在所有图上，改成已有的名字时合并', () => {
  const tags = initialItemTags(items, [])
  assert.deepEqual(tagUsage(items, tags), [{ tag: '运动会', count: 2 }, { tag: '开幕式', count: 1 }])
  const renamed = renameTag(tags, '开幕式', '运动会')
  assert.deepEqual(renamed['1'], ['运动会'])
  assert.deepEqual(renameTag(tags, '运动会', '  校运会 ')['2'], ['校运会'])
  assert.equal(renameTag(tags, '运动会', '  '), tags)
  const removed = removeTag(tags, '运动会')
  assert.deepEqual(removed, { 1: ['开幕式'], 2: [], 3: [] })
})

test('受预设约束时只认预设，访客不能用保留标签，与后端各条路一致', () => {
  const restricted = { restricted: true, presets: ['合影'], forbidReserved: false }
  assert.equal(isAllowedTag('合影', restricted), true)
  assert.equal(isAllowedTag('开幕式', restricted), false)
  // 站内选片人的保留标签永远允许（ProjectService.requireAllowedPhotoTags）。
  assert.equal(isAllowedTag('deprecated', restricted), true)
  const guest = { restricted: false, presets: ['合影'], forbidReserved: true }
  assert.equal(isAllowedTag('开幕式', guest), true)
  assert.equal(isAllowedTag('Deprecated', guest), false)
  assert.equal(isAllowedTag('长'.repeat(51), guest), false)
  assert.deepEqual(rejectedTags({ 1: ['合影', '开幕式'], 2: ['开幕式', '运动会'] }, restricted), ['开幕式', '运动会'])
})

test('提交时每张都发，空数组表示这张不打标签', () => {
  assert.deepEqual(itemTagsPayload(items, { 1: [' 运动会 ', '运动会'], 2: [] }), [
    { itemId: '1', tags: ['运动会'] },
    { itemId: '2', tags: [] },
    { itemId: '3', tags: [] },
  ])
})

test('本机条目名整理成与后端条目一样的路径键，缩略图才对得上', () => {
  assert.equal(archiveItemKey(items[0]), '运动会/开幕式/1.jpg')
  assert.equal(archiveItemKey(items[2]), '3.jpg')
  assert.equal(entryKey('运动会\\开幕式\\1.jpg'), '运动会/开幕式/1.jpg')
  assert.equal(entryKey('./合影//班级/ a.jpg '), '合影/班级/a.jpg')
  assert.equal(entryKey('活\u0007动/b\u0001.jpg'), '活_动/b_.jpg')
  assert.equal(entryKey('目录/'), null)
})

test('从本机压缩包按路径键取出原字节：存储和 deflate 两种都认，目录条目不进索引', async () => {
  const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 1, 2, 3, 4, 5, 6, 7, 8, 9])
  const png = new Uint8Array(4096).fill(7)
  const zip = zipSync({
    '运动会/': {},
    '运动会/开幕式/1.jpg': [jpeg, { level: 0 }],
    '运动会/2.png': [png, { level: 6 }],
  })
  const archive = new Blob([zip])
  const index = await readZipIndex(archive)
  assert.deepEqual([...index.keys()].sort(), ['运动会/2.png', '运动会/开幕式/1.jpg'])
  const stored = index.get('运动会/开幕式/1.jpg')!
  assert.equal(stored.method, 0)
  assert.deepEqual(new Uint8Array(await (await readZipEntry(archive, stored)).arrayBuffer()), jpeg)
  const deflated = index.get('运动会/2.png')!
  assert.equal(deflated.method, 8)
  assert.ok(deflated.compressedSize < png.length)
  assert.deepEqual(new Uint8Array(await (await readZipEntry(archive, deflated)).arrayBuffer()), png)
})

test('中文 Windows 压缩的 GBK 条目名（不带 UTF-8 标志位）也能解出来', async () => {
  // 手写一个只含一个存储条目的 ZIP，文件名是 GBK 编码的「合影/图.jpg」。
  const name = new Uint8Array([0xba, 0xcf, 0xd3, 0xb0, 0x2f, 0xcd, 0xbc, 0x2e, 0x6a, 0x70, 0x67])
  const data = new Uint8Array([0xff, 0xd8, 0xff, 9])
  const local = new DataView(new ArrayBuffer(30))
  local.setUint32(0, 0x04034b50, true)
  local.setUint32(18, data.length, true)
  local.setUint32(22, data.length, true)
  local.setUint16(26, name.length, true)
  const central = new DataView(new ArrayBuffer(46))
  central.setUint32(0, 0x02014b50, true)
  central.setUint32(20, data.length, true)
  central.setUint32(24, data.length, true)
  central.setUint16(28, name.length, true)
  const directoryOffset = 30 + name.length + data.length
  const end = new DataView(new ArrayBuffer(22))
  end.setUint32(0, 0x06054b50, true)
  end.setUint16(8, 1, true)
  end.setUint16(10, 1, true)
  end.setUint32(12, 46 + name.length, true)
  end.setUint32(16, directoryOffset, true)
  const archive = new Blob([local.buffer, name, data, central.buffer, name, end.buffer])

  const index = await readZipIndex(archive)
  const entry = index.get('合影/图.jpg')
  assert.ok(entry, `索引里的键：${[...index.keys()].join(',')}`)
  assert.deepEqual(new Uint8Array(await (await readZipEntry(archive, entry).then(blob => blob.arrayBuffer()))), data)
})

test('不是 ZIP 的文件给出明确的错误，而不是卡住', async () => {
  await assert.rejects(readZipIndex(new Blob([new Uint8Array(64)])), /不是有效的 ZIP/)
})

test('两条上传路径都接了确认标签对话框，并把逐张标签交给后端', async () => {
  const batchPage = await read('pages/BatchUploadPage.tsx')
  const sharePage = await read('pages/SharedUploadPage.tsx')
  for (const page of [batchPage, sharePage]) {
    assert.match(page, /<ZipTagReviewModal/)
    assert.match(page, /hasFolderTags\(archiveItems\)/)
    assert.match(page, /itemTagsPayload\(archiveItems, tags\)/)
  }
  assert.match(batchPage, /itemTags: itemTags \?\? null/)
  assert.match(await read('projectShare.ts'), /itemTags: itemTags \?\? null/)
})
