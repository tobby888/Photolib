import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { blobErrorMessage, fileNameFromContentDisposition } from '../src/exportDownload.ts'

test('export file name comes from the server, with RFC 5987 Chinese names decoded', () => {
  const chinese = '2026秋季摄影部招新-报名-2026-08-25.xlsx'
  assert.equal(
    fileNameFromContentDisposition(
      `attachment; filename="=?UTF-8?Q?report?="; filename*=UTF-8''${encodeURIComponent(chinese)}`),
    chinese,
  )
  assert.equal(
    fileNameFromContentDisposition('attachment; filename="autumn-applications.xlsx"'),
    'autumn-applications.xlsx',
  )
  assert.equal(fileNameFromContentDisposition('attachment; filename=plain.xlsx'), 'plain.xlsx')
})

test('unusable content-disposition values fall back to the caller name instead of saving mojibake', () => {
  // A MIME encoded-word is only there for old browsers; saving it verbatim would
  // hand the user a file called =?UTF-8?Q?...?=.
  assert.equal(fileNameFromContentDisposition('attachment; filename="=?UTF-8?Q?=E6=8A=A5?="'), undefined)
  // Broken percent-encoding must not throw and must not win over the fallback.
  assert.equal(fileNameFromContentDisposition("attachment; filename*=UTF-8''%E4%B8"), undefined)
  assert.equal(fileNameFromContentDisposition('attachment'), undefined)
  assert.equal(fileNameFromContentDisposition(undefined), undefined)
  assert.equal(fileNameFromContentDisposition(''), undefined)
})

test('blob error responses surface the reason the backend wrote, not a generic network message', async () => {
  const failure = {
    response: { data: new Blob([JSON.stringify({ code: 'VALIDATION_ERROR', message: '一次最多导出 10000 条报名' })]) },
  }
  assert.equal(await blobErrorMessage(failure, '兜底'), '一次最多导出 10000 条报名')
  assert.equal(await blobErrorMessage({ response: { data: new Blob(['<html>502</html>']) } }, '兜底'), '兜底')
  assert.equal(await blobErrorMessage(new Error('boom'), '兜底'), '兜底')
})

test('the applications card exports through the same filter the list is showing', async () => {
  const detail = await readFile(new URL('../src/pages/RecruitmentDetailPage.tsx', import.meta.url), 'utf8')
  assert.match(detail, /`\/recruitment-tasks\/\$\{task\.id\}\/applications\/export`/)
  assert.match(detail, /qs\(\{ studentId: studentIdFilter \}\)/)
  assert.match(detail, /responseType: 'blob'/)
  assert.match(detail, /loading=\{exporting\}/)
  assert.match(detail, />导出 XLSX</)
  assert.match(detail, /message\.error\(await blobErrorMessage\(error, '报名导出失败，请稍后重试'\)\)/)
  // The list request and the export request must read the same filter state.
  assert.match(detail, /url: `\/recruitment-tasks\/\$\{taskId\}\/applications`,\s*\n\s*params: qs\(\{ page: applicationPage, pageSize: 20, studentId: studentIdFilter \}\)/)
})
