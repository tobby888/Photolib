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
    // 看到提示的是拍摄的同学和校区负责人，他们没有对象存储控制台，
    // 所以界面上只说发生了什么、下一步做什么；CORS / 预签名 / Content-Type
    // 这些只有管理员用得上的线索留在控制台，方便对方来问时排查。
    console.error('[storage] 对象存储上传失败', {
      url: ticket.uploadUrl,
      contentType: ticket.contentType,
      status: axios.isAxiosError(error) ? error.response?.status : undefined,
      hint: axios.isAxiosError(error) && !error.response
        ? '无响应：多为 Bucket CORS 未放行当前站点的 PUT，或预签名 URL 域名浏览器不可达'
        : axios.isAxiosError(error) && error.response?.status === 403
          ? '403：检查预签名 URL、Bucket 权限、Endpoint/Public Endpoint，以及上传时的 Content-Type 是否与签名一致'
          : undefined,
      error,
    })
    throw new Error(explainObjectUploadError(error))
  }
}

function explainObjectUploadError(error: unknown) {
  if (!axios.isAxiosError(error)) {
    return error instanceof Error ? error.message : '图片没能存进图库，请重试一次。'
  }
  if (!error.response) {
    return '图片没能存进图库，通常是网络中断了。先重试一次；如果一直不行，把这句提示告诉管理员，需要调整服务器上的存储配置。'
  }
  if (error.response.status === 403) {
    return '存储服务拒绝了这次上传，多半是上传链接过期了。回到上一步重新发起一次；如果还是不行，请联系管理员。'
  }
  return `图片没能存进图库（错误码 ${error.response.status}）。先重试一次；如果一直不行，把这个错误码告诉管理员。`
}
