import { App, Button, Card, Space, Typography } from 'antd'
import { ArrowLeftOutlined, FilePdfOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { api } from '../api'
import { DataState } from '../components'
import { useLoad } from '../hooks'
import MailPreviewFrame from '../MailPreviewFrame'
import { statementFileName } from '../settlementMail'
import type { SettlementStatement } from '../settlementMail'

/**
 * 站内信里的清算单：发送那一刻的快照。图片和选题可以点开（新标签页），可以下载成 PDF。
 * 后端只让收件人本人和能发清算单的人看（见 SettlementInboxController）。
 */
export default function SettlementStatementPage() {
  const { statementId } = useParams()
  const navigate = useNavigate()
  const { message } = App.useApp()
  const { data, loading, error, reload } = useLoad(
    () => api<SettlementStatement>({ url: `/settlement-statements/${statementId}` }),
    null as SettlementStatement | null, [statementId],
  )
  const [progress, setProgress] = useState<string | null>(null)

  const download = async () => {
    if (!data) return
    setProgress('正在准备…')
    try {
      const { statementPdf, saveBlob } = await import('../settlementPdf')
      const blob = await statementPdf(data.html, {
        title: data.subject,
        onProgress: (done, total) => setProgress(`正在生成 ${done} / ${total} 页`),
      })
      saveBlob(blob, statementFileName(data.recipientName, data.from, data.to))
    } catch (e) {
      message.error(`PDF 没能生成：${(e as Error).message}`)
    } finally {
      setProgress(null)
    }
  }

  return <div className="settlement-statement-page">
    <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/notifications')}>返回消息中心</Button>
    <Card>
      <DataState loading={loading} error={error} onRetry={reload} empty={!data} emptyText="这份清算单不在了">
        {data && <>
          <div className="settlement-statement-head">
            <div>
              <Typography.Title level={4} style={{ margin: 0 }}>{data.subject}</Typography.Title>
              <Typography.Text type="secondary">
                清算周期 {data.from} 至 {data.to} · {data.createdByName} 发送
                {data.createdAt ? `于 ${dayjs(data.createdAt).format('YYYY-MM-DD HH:mm')}` : ''}
              </Typography.Text>
            </div>
            <Space>
              <Button type="primary" icon={<FilePdfOutlined />} loading={progress !== null} onClick={() => void download()}>
                {progress ?? '下载 PDF'}
              </Button>
            </Space>
          </div>
          <MailPreviewFrame html={data.html} interactive />
        </>}
      </DataState>
    </Card>
  </div>
}
