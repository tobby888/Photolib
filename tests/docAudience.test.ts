import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  audienceError, audienceOf, audiencePayload, describeAudience, formatFileSize,
  normalizeAudienceOptions, sameAudience,
} from '../src/docAudience.ts'
import { describeLimit, inputUnit } from '../src/uploadLimits.ts'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')
const readBackend = (path: string) =>
  readFile(new URL(`../backend/src/main/java/cn/photolib/${path}`, import.meta.url), 'utf8')

test('只有「指定成员」带名单，其余两档的名单一律清空', () => {
  assert.deepEqual(audienceOf({ visibility: 'MEMBERS', readerGroupIds: ['1'], readerUserIds: ['2'] }),
    { visibility: 'MEMBERS', groupIds: [], userIds: [] })
  // 后端的 id 是数字，统一成字符串，重复的去掉。
  assert.deepEqual(audienceOf({ visibility: 'RESTRICTED', readerGroupIds: [3, 3], readerUserIds: [7] }),
    { visibility: 'RESTRICTED', groupIds: ['3'], userIds: ['7'] })
  assert.deepEqual(audiencePayload({ visibility: 'PUBLIC', groupIds: ['1'], userIds: ['2'] }),
    { visibility: 'PUBLIC', groupIds: [], userIds: [] })
})

test('指定成员至少要选一个组或一个人，和后端同一条规则', async () => {
  assert.match(audienceError({ visibility: 'RESTRICTED', groupIds: [], userIds: [] }) || '', /至少/)
  assert.equal(audienceError({ visibility: 'RESTRICTED', groupIds: ['1'], userIds: [] }), null)
  assert.equal(audienceError({ visibility: 'MEMBERS', groupIds: [], userIds: [] }), null)
  const backend = await readBackend('doc/DocAudience.java')
  assert.match(backend, /MAX_GROUPS = 50/)
  assert.match(backend, /MAX_USERS = 500/)
})

test('名单比较不看顺序；描述里列出组名和人名', () => {
  assert.ok(sameAudience({ visibility: 'RESTRICTED', groupIds: ['1', '2'], userIds: [] },
    { visibility: 'RESTRICTED', groupIds: ['2', '1'], userIds: [] }))
  const options = normalizeAudienceOptions({
    groups: [{ id: 1, name: '部长', memberCount: 3 }],
    users: [{ id: 9, displayName: '张三', username: 'zs', permissionGroupId: 1 }],
  })
  assert.equal(options.groups[0].id, '1')
  assert.equal(describeAudience({ visibility: 'RESTRICTED', groupIds: ['1'], userIds: ['9'] }, options),
    '仅 部长 及 张三')
  assert.equal(describeAudience({ visibility: 'PUBLIC', groupIds: [], userIds: [] }), '所有人')
  assert.deepEqual(normalizeAudienceOptions(null), { groups: [], users: [] })
})

test('非字节类限额按各自的单位显示，不再一律写成「张」', () => {
  assert.equal(describeLimit({ unit: 'PER_SECOND' }, 5), '5 次/秒')
  assert.equal(describeLimit({ unit: 'FILES' }, 50), '50 个')
  assert.equal(describeLimit({ unit: 'SECONDS', unitLabel: '秒' }, 300), '300 秒')
  assert.deepEqual(inputUnit({ unit: 'TIMES', max: 10_000 }), { label: '次', factor: 1 })
  assert.equal(formatFileSize(1536), '1.5 KiB')
  assert.equal(formatFileSize(200 * 1024 * 1024), '200 MiB')
})

test('文件库的下载走签名直链、上传不受 20 秒超时约束', async () => {
  const [panel, viewer, api] = await Promise.all([read('DocFilesPanel.tsx'), read('DocPdfViewer.tsx'), read('api.ts')])
  // 上传：不设超时、带进度。
  assert.match(panel, /largeUploadConfig\(setProgress\)/)
  // 下载：拿到直链交给浏览器，不经过 axios 取 Blob。
  assert.match(panel, /url: `\/public\/doc-files\/\$\{file\.publicId\}\/download`/)
  assert.match(panel, /startBrowserDownload\(signed\.downloadUrl\)/)
  assert.doesNotMatch(panel, /responseType: 'blob'/)
  // PDF 阅读器取的是 Blob，也不能卡在全局 20 秒上。
  assert.match(viewer, /largeDownloadConfig\(\)/)
  assert.match(api, /export function largeDownloadConfig[\s\S]*?timeout: 0/)
})

test('文档中心的「文件」两个入口都在，前端不按权限过滤列表', async () => {
  const [app, panel] = await Promise.all([read('App.tsx'), read('DocFilesPanel.tsx')])
  assert.match(app, /path="\/docs\/files" element=\{<DocsPage section="files" \/>\}/)
  assert.match(app, /path="\/documents\/files" element=\{<DocumentsPage section="files" \/>\}/)
  assert.doesNotMatch(panel, /\.filter\([^)]*visibility/)
  // 上传按钮按 FILE_UPLOAD 显示，编辑 / 删除按服务端给的 canManage 显示。
  assert.match(panel, /const canUpload = hasPermission\(user, 'FILE_UPLOAD'\)/)
  assert.match(panel, /\{file\.canManage && </)
})
