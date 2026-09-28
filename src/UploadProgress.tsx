import { Progress, Typography } from 'antd'

/**
 * 经后端转存的上传进度。`percent` 为 null 时不渲染。
 *
 * <p>浏览器能量到的只是"传给后端"这一段；到 100% 之后后端还要把文件转存到对象存储，
 * 这段没有进度可报，所以单独说一句，免得人以为卡在了 100%。</p>
 */
export default function UploadProgress({ percent }: { percent: number | null }) {
  if (percent === null) return null
  return <div style={{ marginTop: 8 }}>
    <Progress percent={percent} status="active" />
    <Typography.Text type="secondary">
      {percent < 100 ? `正在上传… ${percent}%` : '上传完成，服务器正在保存文件，请稍候…'}
    </Typography.Text>
  </div>
}
