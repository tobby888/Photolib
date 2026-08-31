import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons'
import { Alert, Button, Skeleton, Space, Typography } from 'antd'
import { useEffect, useState } from 'react'
import { http } from './api'

/**
 * 内嵌的 PDF 阅读器，读者页和编辑器共用。
 *
 * <p><b>必须走 axios 取 Blob，不能把接口地址直接塞给 iframe。</b>和
 * {@link MarkdownRenderer} 里的插图是同一个理由：`<iframe src>` 不会带上
 * localStorage 里的令牌，仅限成员的 PDF 会变成一个 403 的白框；编辑器里预览
 * 草稿更是必须带令牌（那个接口要 DOC_MANAGE）。</p>
 *
 * <p>Blob URL 在切换文档和卸载时都要回收，否则一份几十 MiB 的 PDF 会一直留在
 * 内存里——读者在目录里点几篇就能把标签页撑爆。</p>
 */
export default function DocPdfViewer({ path, title, height = '70vh' }: {
  /** 相对 `/api/v1` 的接口路径，例如 `/public/docs/{publicId}/file`。 */
  path: string
  title: string
  height?: string
}) {
  const [objectUrl, setObjectUrl] = useState<string>()
  const [error, setError] = useState<string>()
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let active = true
    let currentUrl: string | undefined
    setObjectUrl(undefined)
    setError(undefined)
    void http.get<Blob>(path, { responseType: 'blob' })
      .then(response => {
        if (!active) return
        currentUrl = URL.createObjectURL(response.data)
        setObjectUrl(currentUrl)
      })
      .catch(() => { if (active) setError('PDF 没能加载出来，请稍后重试') })
    return () => {
      active = false
      if (currentUrl) URL.revokeObjectURL(currentUrl)
    }
  }, [path, attempt])

  if (error) return <Alert type="warning" showIcon message={error}
    action={<Button size="small" icon={<ReloadOutlined />}
      onClick={() => setAttempt(value => value + 1)}>重试</Button>} />
  if (!objectUrl) return <Skeleton active paragraph={{ rows: 10 }} />

  return <div className="docs-pdf">
    <Space className="docs-pdf-actions">
      <Button icon={<DownloadOutlined />} href={objectUrl} download={`${title}.pdf`}>下载 PDF</Button>
      <Typography.Text type="secondary">浏览器里看不了的话，下载后用本地阅读器打开。</Typography.Text>
    </Space>
    <iframe className="docs-pdf-frame" style={{ height }} src={objectUrl} title={title} />
  </div>
}
