import {
  ArrowLeftOutlined,
  DownloadOutlined,
  FileImageOutlined,
  FileZipOutlined,
  IdcardOutlined,
  PaperClipOutlined,
} from '@ant-design/icons'
import { Alert, App, Button, Card, Col, Image, Result, Row, Skeleton, Space, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, http } from '../api'
import { formatBytes } from '../components'
import { useLoad } from '../hooks'
import MarkdownRenderer from '../MarkdownRenderer'
import { buildApplicationDetailsMarkdown, normalizeRecruitmentFormSchema } from '../recruitmentForm'
import {
  normalizeApplicationDetail,
  type RecruitmentApplicationDetail,
  type RecruitmentAttachment,
} from '../recruitmentTypes'

function protectedApiPath(url: string) {
  if (url.startsWith('/api/v1/')) return url.slice('/api/v1'.length)
  if (url.startsWith('/')) return url
  return undefined
}

function AttachmentPreview({ attachment }: { attachment: RecruitmentAttachment }) {
  const remoteUrl = attachment.previewUrl || attachment.downloadUrl || ''
  const [state, setState] = useState<{ loading: boolean; url?: string; error?: string }>({ loading: true })

  useEffect(() => {
    if (!attachment.contentType.startsWith('image/') || !remoteUrl) {
      setState({ loading: false })
      return
    }
    const apiPath = protectedApiPath(remoteUrl)
    if (!apiPath) {
      setState({ loading: false, url: remoteUrl })
      return
    }
    let active = true
    let objectUrl = ''
    setState({ loading: true })
    void http.get<Blob>(apiPath, { responseType: 'blob' }).then(response => {
      if (!active) return
      objectUrl = URL.createObjectURL(response.data)
      setState({ loading: false, url: objectUrl })
    }).catch(error => {
      if (active) setState({ loading: false, error: (error as Error).message || '这张图没能加载出来' })
    })
    return () => {
      active = false
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [attachment.contentType, remoteUrl])

  if (state.loading) return <div style={{ height: 220, padding: 18 }}><Skeleton.Image active style={{ width: '100%', height: 184 }} /></div>
  if (state.error) return <div style={{ height: 220, display: 'grid', placeItems: 'center', background: '#f5f3ee' }}>
    <Typography.Text type="secondary">{state.error}</Typography.Text></div>
  if (state.url) return <Image src={state.url} alt={attachment.fileName} width="100%" height={220}
    style={{ objectFit: 'contain', background: '#f3f1eb' }} />
  return <div style={{ height: 220, display: 'grid', placeItems: 'center', background: '#edf3f0',
    color: '#28594f', fontSize: 48 }}>{attachment.contentType === 'application/zip' ? <FileZipOutlined /> : <FileImageOutlined />}</div>
}

export default function RecruitmentApplicationDetailPage() {
  const params = useParams()
  const applicationId = params.applicationId || params.id || ''
  const navigate = useNavigate()
  const { message } = App.useApp()
  const [downloadingId, setDownloadingId] = useState<string>()
  const detailState = useLoad(
    async () => normalizeApplicationDetail(await api<unknown>({ url: `/recruitment-applications/${applicationId}` })),
    null as RecruitmentApplicationDetail | null,
    [applicationId],
  )
  const detail = detailState.data

  const download = async (attachment: RecruitmentAttachment) => {
    setDownloadingId(String(attachment.id))
    try {
      const url = attachment.downloadUrl || ''
      if (!url) throw new Error('没拿到这个文件的下载地址，刷新一下再试')
      const apiPath = protectedApiPath(url)
      if (!apiPath) {
        window.open(url, '_blank', 'noopener')
        return
      }
      const response = await http.get<Blob>(apiPath, { responseType: 'blob' })
      const objectUrl = URL.createObjectURL(response.data)
      const anchor = document.createElement('a')
      anchor.href = objectUrl
      anchor.download = attachment.fileName
      anchor.click()
      window.setTimeout(() => URL.revokeObjectURL(objectUrl), 0)
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setDownloadingId(undefined)
    }
  }

  if (detailState.error) return <Result status="403" title="这份报名看不了" subTitle={detailState.error}
    extra={<Button onClick={() => navigate('/recruitments')}>返回招募列表</Button>} />

  const markdown = detail?.detailsMarkdown || (detail
    ? buildApplicationDetailsMarkdown(
        normalizeRecruitmentFormSchema(detail.formSchema), detail.studentId, detail.answers || {},
      )
    : '')

  return <>
    <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => detail?.taskId
      ? navigate(`/recruitments/${detail.taskId}`)
      : navigate('/recruitments')}>返回这次招募</Button>
    {detailState.loading && <Card style={{ marginTop: 12 }}><Skeleton active paragraph={{ rows: 12 }} /></Card>}
    {!detailState.loading && detail && <>
      <section className="project-detail-hero" style={{ marginTop: 10 }}>
        <Space wrap><Typography.Text className="eyebrow">RECRUITMENT APPLICATION · {detail.id}</Typography.Text>
          <Tag color="green">已收到</Tag></Space>
        <Typography.Title>{detail.taskTitle || '新成员报名'}</Typography.Title>
        <Space wrap size="large">
          <Typography.Text><IdcardOutlined /> 学号：<strong>{detail.studentId}</strong></Typography.Text>
          <Typography.Text type="secondary">提交时间：{dayjs(detail.submittedAt).format('YYYY-MM-DD HH:mm:ss')}</Typography.Text>
          <Typography.Text type="secondary"><PaperClipOutlined /> 带了 {detail.attachments.length} 个文件</Typography.Text>
        </Space>
      </section>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={detail.attachments.length ? 14 : 24}>
          <Card title="他/她是这么填的">
            {markdown ? <MarkdownRenderer value={markdown} allowLinks={false} /> : <Alert type="info" showIcon title="这份报名没有文字回答" />}
          </Card>
        </Col>
        {!!detail.attachments.length && <Col xs={24} xl={10}>
          <Card title={<Space><PaperClipOutlined />交上来的作品</Space>}>
            <Row gutter={[12, 12]}>
              {detail.attachments.map(attachment => <Col xs={24} sm={12} xl={24} key={String(attachment.id)}>
                <Card size="small" cover={<AttachmentPreview attachment={attachment} />}>
                  <Typography.Text strong ellipsis={{ tooltip: attachment.fileName }} style={{ display: 'block' }}>
                    {attachment.fileName}
                  </Typography.Text>
                  <Space style={{ width: '100%', justifyContent: 'space-between', marginTop: 8 }}>
                    <Typography.Text type="secondary">{formatBytes(attachment.size)}</Typography.Text>
                    <Button type="link" size="small" icon={<DownloadOutlined />}
                      loading={downloadingId === String(attachment.id)} onClick={() => void download(attachment)}>下载原图</Button>
                  </Space>
                </Card>
              </Col>)}
            </Row>
          </Card>
        </Col>}
      </Row>
    </>}
  </>
}
