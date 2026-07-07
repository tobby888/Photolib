import axios from 'axios'

export interface ObjectUploadTicket {
  uploadUrl: string
  method?: string
  contentType: string
}

export async function uploadToObjectStorage(
  ticket: ObjectUploadTicket,
  file: File,
  onProgress?: (percent: number) => void,
) {
  try {
    await axios.request({
      method: ticket.method || 'PUT',
      url: ticket.uploadUrl,
      data: file,
      headers: { 'Content-Type': ticket.contentType },
      transformRequest: [(value) => value],
      onUploadProgress: onProgress
        ? (event) => {
            const total = event.total ?? file.size
            if (total > 0) onProgress(Math.min(100, Math.round((event.loaded / total) * 100)))
          }
        : undefined,
    })
  } catch (error) {
    throw new Error(explainObjectUploadError(error))
  }
}

function explainObjectUploadError(error: unknown) {
  if (!axios.isAxiosError(error)) {
    return error instanceof Error ? error.message : '图片上传到对象存储失败'
  }
  if (!error.response) {
    return '图片未能上传到 OSS。请检查 OSS Bucket CORS 是否允许当前站点发起 PUT 请求，并确认预签名 URL 域名可从浏览器访问。'
  }
  if (error.response.status === 403) {
    return 'OSS 拒绝了上传请求。请检查预签名 URL、Bucket 权限、Endpoint/Public Endpoint，以及上传时的 Content-Type 是否与签名一致。'
  }
  return `OSS 上传失败（HTTP ${error.response.status}），请稍后重试或检查对象存储配置。`
}
