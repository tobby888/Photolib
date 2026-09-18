import {
  Alert, App, Button, Card, Empty, Form, Input, List, Progress, Result, Segmented, Skeleton, Space,
  Tag, Typography, Upload,
} from 'antd'
import {
  CloudUploadOutlined, FileZipOutlined, InboxOutlined, LockOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { useCallback, useEffect, useState } from 'react'
import { Navigate, useParams } from 'react-router-dom'
import { ApiError } from '../api'
import { BrandGlyph, useBranding } from '../branding'
import SiteFooter from '../SiteFooter'
import {
  clearShareSession, readStoredShareSession, shareApi, shareUploadApi, storeShareSession,
} from '../projectShare'
import { sha256Hex } from '../recruitmentUpload'
import {
  MAX_IMAGES_PER_ARCHIVE, MAX_QUEUE_SIZE, addToQueue, describeBatchOutcome,
  isTerminalBatchStatus, rejectArchiveReason, runQueue, summarize,
} from '../shareUpload'
import type { ShareUploadItem } from '../shareUpload'
import { uploadToObjectStorage } from '../storageUpload'
import type { ShareGuestAccess, ShareUploadBatch } from '../types'

/** 处理结果的轮询上限。压缩一张相机原图通常几秒，超过这个就交给访客自己刷新。 */
const PROCESSING_TIMEOUT_MS = 90_000
/** 一包最多 100 张，每张要解码、压缩、生成预览，所以给的时间比单张宽得多。 */
const BATCH_TIMEOUT_MS = 20 * 60_000

type UploadMode = 'files' | 'archive'
/** ZIP 的四个阶段，用来把"卡住了吗"这个问题回答清楚。 */
type ArchivePhase = 'uploading' | 'extracting' | 'organizing' | 'processing'

const ARCHIVE_PHASE_TEXT: Record<ArchivePhase, string> = {
  uploading: '正在上传压缩包',
  extracting: '上传完成，后台正在安全解压并筛选出图片',
  organizing: '正在按包内文件名生成标题',
  processing: '正在生成成品图和缩略图',
}

/**
 * 上传链接的访客页，不需要登录。
 *
 * <p>与相册访客页（{@link ../pages/SharedProjectPage}）是同一套凭据和同一条会话机制，
 * 两页的区别只在门后：那边给的是相册，这边给的是一个上传台。所以这里刻意不显示
 * 项目里已有的任何图片——上传链接的持有者被授权的是"往里放"，不是"看里面有什么"，
 * 服务端也按同一条线拒绝（`ProjectShareService.requireBrowseLink`）。</p>
 *
 * <p>身份（姓名 + 学号）和密码一起构成这次会话，之后这场会话传的每一张都用同一份
 * 拍摄者快照。站内的拍摄者强制来自通讯录，而拿着链接的人按定义在通讯录之外，把
 * 通讯录摆出来让站外的人挑更是直接泄露姓名和学号。</p>
 */
export default function SharedUploadPage() {
  const { token = '' } = useParams()
  const { message } = App.useApp()
  const branding = useBranding()
  const [gateForm] = Form.useForm<{ password: string; uploaderName: string; uploaderStudentId: string }>()

  const [session, setSession] = useState<string | null>(() => readStoredShareSession(token))
  const [access, setAccess] = useState<ShareGuestAccess | null>(null)
  const [linkState, setLinkState] = useState<'checking' | 'usable' | 'broken' | 'browse'>('checking')
  const [unlocking, setUnlocking] = useState(false)
  const [items, setItems] = useState<ShareUploadItem[]>([])
  const [running, setRunning] = useState(false)
  const [mode, setMode] = useState<UploadMode>('files')
  const [archivePhase, setArchivePhase] = useState<ArchivePhase | null>(null)
  const [archivePercent, setArchivePercent] = useState(0)
  const [archiveBatch, setArchiveBatch] = useState<ShareUploadBatch | null>(null)

  const dropSession = useCallback((notice?: string) => {
    clearShareSession(token)
    setSession(null)
    setAccess(null)
    if (notice) message.warning(notice)
  }, [message, token])

  // 与相册访客页同一条出口：403/404 只有一个意思——这把会话钥匙不作数了。
  const handleGuestError = useCallback((reason: unknown) => {
    if (reason instanceof ApiError && (reason.status === 403 || reason.status === 404)) {
      dropSession('上传链接已失效，请重新输入密码')
      return
    }
    message.error((reason as Error).message)
  }, [dropSession, message])

  useEffect(() => {
    let cancelled = false
    const check = async () => {
      try {
        const greeting = await shareApi.greet(token)
        if (cancelled) return
        // 拿着相册链接走到了上传页（或者反过来）：把人送到对的那一页，
        // 而不是让他对着一个永远传不上去的上传台。
        setLinkState(greeting.purpose === 'UPLOAD' ? 'usable' : 'browse')
      } catch {
        if (!cancelled) setLinkState('broken')
      }
    }
    void check()
    return () => { cancelled = true }
  }, [token])

  useEffect(() => {
    if (!session) return
    let cancelled = false
    const load = async () => {
      try {
        const current = await shareApi.access(token, session)
        if (!cancelled) setAccess(current)
      } catch (reason) {
        if (!cancelled) handleGuestError(reason)
      }
    }
    void load()
    return () => { cancelled = true }
  }, [handleGuestError, session, token])

  const unlock = async () => {
    const values = await gateForm.validateFields()
    setUnlocking(true)
    try {
      const opened = await shareApi.openSession(token, values.password, {
        uploaderName: values.uploaderName.trim(),
        uploaderStudentId: values.uploaderStudentId.trim(),
      })
      storeShareSession(token, opened)
      setSession(opened.sessionToken)
      setAccess(opened.access)
      gateForm.resetFields()
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setUnlocking(false)
    }
  }

  const patch = (key: string, changes: Partial<ShareUploadItem>) =>
    setItems(current => current.map(item => item.key === key ? { ...item, ...changes } : item))

  /** 等后台把这一张压完。失败时照片会被打回 UPLOADING 并带上原因，要如实说出来。 */
  const waitForProcessing = async (guestSession: string, item: ShareUploadItem, photoId: string) => {
    const deadline = Date.now() + PROCESSING_TIMEOUT_MS
    while (Date.now() < deadline) {
      await new Promise(resolve => window.setTimeout(resolve, 1500))
      const status = await shareUploadApi.status(token, guestSession, photoId)
      if (status.status === 'AVAILABLE' || status.status === 'ARCHIVED') {
        patch(item.key, { stage: 'done', percent: 100 })
        return
      }
      if (status.status === 'UPLOADING') {
        patch(item.key, {
          stage: 'failed',
          error: status.failureReason || '这张图片没能处理完成，换一张 JPG / PNG 再试。',
        })
        return
      }
    }
    // 还在排队不是失败：告诉访客它已经交上去了，由后台继续处理。
    patch(item.key, { stage: 'done', percent: 100, error: undefined })
  }

  const uploadOne = async (guestSession: string, item: ShareUploadItem) => {
    patch(item.key, { stage: 'uploading', percent: 0, error: undefined })
    try {
      const ticket = await shareUploadApi.ticket(token, guestSession, {
        fileName: item.file.name,
        contentType: item.file.type,
        size: item.file.size,
        sha256: await sha256Hex(item.file),
        // 拍摄时间取文件自身的修改时间——访客手上只有一堆相机导出来的文件，
        // 逐张问一次拍摄时间没人会填，而这个值恰恰就是相机写下的那个。
        takenAt: dayjs(item.file.lastModified).format('YYYY-MM-DDTHH:mm:ss'),
      })
      patch(item.key, { photoId: ticket.photoId })
      await uploadToObjectStorage(ticket, item.file,
        percent => patch(item.key, { percent }))
      patch(item.key, { stage: 'processing', percent: 100 })
      await shareUploadApi.complete(token, guestSession, ticket.photoId, {
        // 原始文件名是访客和选片人之间唯一的共同语言，留作标题好让人对得上。
        title: item.file.name.slice(0, 200),
      })
      await waitForProcessing(guestSession, item, String(ticket.photoId))
    } catch (reason) {
      if (reason instanceof ApiError && (reason.status === 403 || reason.status === 404)) throw reason
      patch(item.key, { stage: 'failed', error: (reason as Error).message })
    }
  }

  const startUpload = async () => {
    if (!session) return
    const pending = items.filter(item => item.stage === 'waiting' || item.stage === 'failed')
    if (!pending.length) return
    setRunning(true)
    // 会话失效要停下整条队列，但不能靠让 worker 抛出来做这件事：几个并发的
    // worker 会同时抛，Promise.all 只认第一个，剩下的就成了没人接的 rejection。
    // 记一个旗子，后面排队的直接标成失败，跑完再统一退回密码页。
    let expired: unknown = null
    await runQueue(pending, async item => {
      if (expired) {
        patch(item.key, { stage: 'failed', error: '上传链接已失效，请重新输入密码后再传这几张' })
        return
      }
      try {
        await uploadOne(session, item)
      } catch (reason) {
        expired = reason
        patch(item.key, { stage: 'failed', error: '上传链接已失效，请重新输入密码后再传这几张' })
      }
    })
    setRunning(false)
    if (expired) handleGuestError(expired)
  }

  /** 轮询批次，直到满足条件或超时。超时不当失败：后台仍在跑。 */
  const pollBatch = async (guestSession: string, batchId: string,
                           done: (batch: ShareUploadBatch) => boolean) => {
    const deadline = Date.now() + BATCH_TIMEOUT_MS
    let latest = await shareUploadApi.batchStatus(token, guestSession, batchId)
    while (!done(latest) && Date.now() < deadline) {
      await new Promise(resolve => window.setTimeout(resolve, 2000))
      latest = await shareUploadApi.batchStatus(token, guestSession, batchId)
      setArchiveBatch(latest)
    }
    setArchiveBatch(latest)
    return latest
  }

  const uploadArchive = async (file: File) => {
    if (!session) return
    const reason = rejectArchiveReason(file)
    if (reason) {
      message.error(`${file.name}：${reason}`)
      return
    }
    setRunning(true)
    setArchiveBatch(null)
    setArchivePercent(0)
    setArchivePhase('uploading')
    try {
      const ticket = await shareUploadApi.createBatch(token, session, {
        archiveFileName: file.name,
        archiveSize: file.size,
      })
      const target = ticket.tickets[0]
      if (!target) throw new Error('没能创建压缩包的上传地址，请重试')
      await uploadToObjectStorage(target, file, setArchivePercent)

      setArchivePhase('extracting')
      setArchiveBatch(await shareUploadApi.completeBatch(token, session, ticket.batchId))
      const extracted = await pollBatch(session, ticket.batchId,
        batch => batch.status === 'WAITING_METADATA' || isTerminalBatchStatus(batch.status))
      if (extracted.status !== 'WAITING_METADATA') {
        throw new Error(describeBatchOutcome(extracted).message)
      }

      setArchivePhase('organizing')
      // 拍摄时间取压缩包自己的修改时间，与单张上传同一个道理：访客手上只有
      // 一堆相机导出来的文件，逐张问拍摄时间没人会填。
      setArchiveBatch(await shareUploadApi.finishBatch(token, session, ticket.batchId,
        dayjs(file.lastModified).format('YYYY-MM-DDTHH:mm:ss')))

      setArchivePhase('processing')
      const finished = await pollBatch(session, ticket.batchId,
        batch => isTerminalBatchStatus(batch.status))
      const outcome = describeBatchOutcome(finished)
      if (outcome.tone === 'success') message.success(outcome.message)
      else if (outcome.tone === 'warning') message.warning(outcome.message)
      else message.error(outcome.message)
    } catch (reasonThrown) {
      handleGuestError(reasonThrown)
    } finally {
      setRunning(false)
      setArchivePhase(null)
    }
  }

  const enqueue = (files: File[]) => {
    setItems(current => {
      const added = addToQueue(current, files)
      added.errors.forEach(error => message.error(error))
      if (added.dropped) message.warning(`一次最多 ${MAX_QUEUE_SIZE} 张，多出的 ${added.dropped} 张请分批上传`)
      return [...current, ...added.items]
    })
  }

  const summary = summarize(items)
  const header = <header className="share-header">
    <Space size={12}>
      <span className="brand-glyph"><BrandGlyph branding={branding} /></span>
      <div>
        <Typography.Title level={4} style={{ color: 'white', margin: 0 }}>
          {access?.projectTitle || `${branding.title} 图片上传`}
        </Typography.Title>
        <Typography.Text style={{ color: 'rgba(255,255,255,.62)' }}>
          {access ? (access.linkName || '通过上传链接把照片交给这个选题') : '这是一个受密码保护的上传入口'}
        </Typography.Text>
      </div>
    </Space>
    {access && <Space wrap>
      {access.uploaderName && <Tag color="blue">上传者 {access.uploaderName}</Tag>}
      {access.expiresAt && <Tag>有效期至 {dayjs(access.expiresAt).format('YYYY-MM-DD HH:mm')}</Tag>}
    </Space>}
  </header>

  if (linkState === 'browse') return <Navigate to={`/share/${token}`} replace />

  if (linkState === 'checking') return <main className="share-page">{header}
    <div className="share-body"><Skeleton active paragraph={{ rows: 4 }} /></div>
    <SiteFooter />
  </main>

  if (linkState === 'broken') return <main className="share-page">{header}
    <div className="share-body">
      <Result status="404" title="这个上传链接打不开了"
        subTitle="它可能已经被撤销或超过了有效期。请向发给你链接的同学要一个新的。" />
    </div>
    <SiteFooter />
  </main>

  if (!session) return <main className="share-page">{header}
    <div className="share-body">
      <Card className="share-gate">
        <Typography.Title level={5}><LockOutlined /> 输入上传密码</Typography.Title>
        <Typography.Paragraph type="secondary">
          密码由发给你链接的同学提供。姓名和学号会作为你这次上传的每一张照片的拍摄者，请填写本人的。
        </Typography.Paragraph>
        <Form form={gateForm} layout="vertical" requiredMark={false} onFinish={() => void unlock()}>
          <Form.Item label="上传密码" name="password" rules={[{ required: true, message: '请输入上传密码' }]}>
            <Input.Password autoFocus placeholder="请输入上传密码" maxLength={64} />
          </Form.Item>
          <Form.Item label="你的姓名" name="uploaderName"
            rules={[{ required: true, message: '请填写姓名' }, { max: 50 }]}>
            <Input placeholder="例如：张三" maxLength={50} />
          </Form.Item>
          <Form.Item label="你的学号" name="uploaderStudentId"
            extra="用于统计照片归属，请填写本人学号"
            rules={[{ required: true, message: '请填写学号' },
              { pattern: /^[A-Za-z0-9_-]{2,64}$/, message: '学号只能是字母、数字、下划线或连字符' }]}>
            <Input placeholder="例如：20230001" maxLength={64} />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={unlocking}>进入上传</Button>
        </Form>
      </Card>
    </div>
    <SiteFooter />
  </main>

  return <main className="share-page">{header}
    <div className="share-body">
      <Card title={mode === 'files'
        ? `上传照片${summary.total ? `（${summary.done} / ${summary.total}）` : ''}`
        : '打包上传'}
        extra={mode === 'files' ? <Space wrap>
          {!!summary.total && !running &&
            <Button onClick={() => setItems(current => current.filter(item => item.stage !== 'done'))}>
              清掉已完成
            </Button>}
          <Button type="primary" icon={<CloudUploadOutlined />} loading={running}
            disabled={!items.some(item => item.stage === 'waiting' || item.stage === 'failed')
              || !access?.allowUpload}
            onClick={() => void startUpload()}>
            开始上传{summary.failed ? `（含重试 ${summary.failed} 张）` : ''}
          </Button>
        </Space> : null}>
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {access && !access.allowUpload && <Alert type="warning" showIcon
            message="这个选题已经不再接收上传"
            description="活动已经收工了。如果还需要补交照片，请联系发给你链接的同学。" />}

          {/* 几十上百张时打包传一次比逐张省事得多，所以两种方式都给，默认逐张。 */}
          <Segmented value={mode} disabled={running}
            onChange={value => setMode(value as UploadMode)}
            options={[
              { label: '逐张上传', value: 'files', icon: <CloudUploadOutlined /> },
              { label: '打包上传（ZIP）', value: 'archive', icon: <FileZipOutlined /> },
            ]} />

          <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
            {mode === 'files'
              ? `支持 JPG 和 PNG，单张不超过 100 MiB，一次最多 ${MAX_QUEUE_SIZE} 张。`
              : `一个压缩包最大 1.5 GB，里面最多 ${MAX_IMAGES_PER_ARCHIVE} 张 JPG / PNG，`
                + '单张不超过 100 MiB；包里的其他文件会被自动跳过。'}
            照片会直接进入这个选题的图库，由选题负责人和选片人后续处理；这里看不到别人传了什么。
          </Typography.Paragraph>

          {mode === 'files' ? <>
            <Upload.Dragger multiple accept="image/jpeg,image/png" showUploadList={false}
              disabled={running || !access?.allowUpload}
              beforeUpload={(_file, fileList) => {
                // 交给自己的队列跑：antd 的自动上传不认预签名地址那一套两步流程。
                // fileList 是这一次选中的全部文件，所以只在第一个回调里入队一次。
                if (_file === fileList[0]) enqueue(fileList as File[])
                return Upload.LIST_IGNORE
              }}>
              <p className="ant-upload-drag-icon"><InboxOutlined /></p>
              <p className="ant-upload-text">把照片拖到这里，或者点击选择</p>
              <p className="ant-upload-hint">可以一次选很多张，上传过程中请不要关闭这个页面</p>
            </Upload.Dragger>

            {items.length ? <List size="small" dataSource={items} rowKey="key"
              renderItem={item => <List.Item>
                <List.Item.Meta
                  title={<Space wrap>
                    <span>{item.file.name}</span>
                    {item.stage === 'done' && <Tag color="green">已完成</Tag>}
                    {item.stage === 'processing' && <Tag color="blue">处理中</Tag>}
                    {item.stage === 'waiting' && <Tag>等待上传</Tag>}
                    {item.stage === 'failed' && <Tag color="red">失败</Tag>}
                  </Space>}
                  description={item.stage === 'failed'
                    ? <Typography.Text type="danger">{item.error}</Typography.Text>
                    : <Progress percent={item.percent} size="small"
                        status={item.stage === 'processing' ? 'active' : undefined} />} />
              </List.Item>} />
              : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有选择照片" />}

            {!!summary.failed && !running && <Alert type="error" showIcon
              message={`有 ${summary.failed} 张没有传成功`}
              description="再点一次「开始上传」会只重传这几张。如果反复失败，多半是文件本身有问题。" />}
          </> : <>
            <Upload.Dragger accept=".zip,application/zip" maxCount={1} showUploadList={false}
              disabled={running || !access?.allowUpload}
              beforeUpload={file => {
                void uploadArchive(file as File)
                return Upload.LIST_IGNORE
              }}>
              <p className="ant-upload-drag-icon"><FileZipOutlined /></p>
              <p className="ant-upload-text">把 ZIP 拖到这里，或者点击选择</p>
              <p className="ant-upload-hint">
                选好之后会立刻开始上传，解压和整理都在后台完成；这期间请不要关闭页面
              </p>
            </Upload.Dragger>

            {archivePhase && <Space direction="vertical" style={{ width: '100%' }}>
              <Progress status="active"
                percent={archivePhase === 'uploading' ? archivePercent : 100} />
              <Typography.Text type="secondary">
                {ARCHIVE_PHASE_TEXT[archivePhase]}
                {archivePhase === 'uploading' && `… ${archivePercent}%`}
                {archivePhase === 'processing' && archiveBatch
                  && `：${archiveBatch.successCount + archiveBatch.failureCount} / ${archiveBatch.totalCount}`}
                {archivePhase !== 'uploading' && archivePhase !== 'processing' && '…'}
              </Typography.Text>
            </Space>}

            {archiveBatch && !archivePhase && (() => {
              const outcome = describeBatchOutcome(archiveBatch)
              return <Alert showIcon type={outcome.tone === 'success' ? 'success'
                : outcome.tone === 'warning' ? 'warning' : 'error'}
                message={outcome.tone === 'success' ? '打包上传完成' : '打包上传没有全部成功'}
                description={outcome.message} />
            })()}
          </>}
        </Space>
      </Card>
    </div>
    <SiteFooter />
  </main>
}
