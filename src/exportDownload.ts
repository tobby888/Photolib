/**
 * 下载后端直接返回的二进制导出文件。
 *
 * 文件名以服务端的 Content-Disposition 为准：导出表的名字里有招募标题和导出日期，
 * 只有后端知道自己按哪一天、哪个标题生成的。中文名会被编成 RFC 5987 的
 * `filename*=UTF-8''…`，所以这里必须解码，否则用户存下来的是一串百分号。
 */
export function fileNameFromContentDisposition(header: unknown): string | undefined {
  if (typeof header !== 'string' || !header) return undefined
  const encoded = /filename\*=\s*(?:UTF-8|utf-8)''([^;]+)/.exec(header)
  if (encoded) {
    try {
      const decoded = decodeURIComponent(encoded[1].trim())
      if (decoded) return decoded
    } catch {
      // 编码坏了就退回下面的 ASCII filename，别让整个下载失败。
    }
  }
  const plain = /filename=\s*("([^"]*)"|[^;]+)/.exec(header)
  const candidate = (plain?.[2] ?? plain?.[1] ?? '').trim()
  // `filename="=?UTF-8?Q?…?="` 是给老浏览器看的 MIME encoded-word，这里不认，
  // 交给调用方的兜底名字，好过存成一串 =?UTF-8?Q? 乱码。
  if (!candidate || candidate.startsWith('=?')) return undefined
  return candidate
}

/** 把内存里的 Blob 存成本地文件，用完立刻释放 object URL。 */
export function saveBlobAs(blob: Blob, fileName: string) {
  const objectUrl = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = objectUrl
  anchor.download = fileName
  anchor.click()
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 0)
}

/**
 * 二进制接口失败时，错误响应体也是 Blob，读不出标准信封里的 message，
 * 界面上就只剩一句“网络错误”。这里把 Blob 读回来取出后端写的原因。
 */
export async function blobErrorMessage(error: unknown, fallback: string): Promise<string> {
  const body = (error as { response?: { data?: unknown } })?.response?.data
  if (body instanceof Blob) {
    try {
      const text = await body.text()
      const envelope = JSON.parse(text) as { message?: unknown }
      if (typeof envelope.message === 'string' && envelope.message) return envelope.message
    } catch {
      // 不是 JSON 信封就用兜底文案。
    }
  }
  return fallback
}
