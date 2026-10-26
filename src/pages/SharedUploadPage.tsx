import {
  Alert, App, Button, Card, DatePicker, Empty, Form, Input, InputNumber, List, Modal, Progress, Result, Segmented,
  Skeleton, Space, Tag, Tooltip, Typography, Upload,
} from 'antd'
import type { FormRule } from 'antd'
import {
  ClockCircleOutlined, CloudUploadOutlined, FileZipOutlined, InboxOutlined, LockOutlined, SwapOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Navigate, useParams } from 'react-router-dom'
import { ApiError } from '../api'
import { BrandGlyph, useBranding } from '../branding'
import SiteFooter from '../SiteFooter'
import TagSelect from '../TagSelect'
import {
  clearShareSession, readStoredShareSession, shareApi, shareUploadApi, storeShareSession,
} from '../projectShare'
import type { ShareUploaderIdentity } from '../projectShare'
import { sha256Hex } from '../recruitmentUpload'
import {
  MAX_QUEUE_SIZE, addToQueue, canRetag, createUploadPump, describeBatchOutcome,
  isTerminalBatchStatus, markFailedForRetry, progressOf, readRememberedUploaders, rejectArchiveReason,
  rememberUploader, storeRememberedUploaders, summarize,
} from '../shareUpload'
import type { ShareUploadItem } from '../shareUpload'
import {
  MAX_WORKLOG_MINUTES, declaredCountOn, describeMinutes, describeWorklogStatus, rejectWorklogReason,
} from '../shareWorklog'
import { uploadToObjectStorage } from '../storageUpload'
import { describeBytes } from '../uploadLimits'
import { useUploadLimits } from '../useUploadLimits'
import type { ShareGuestAccess, ShareGuestWorklog, ShareUploadBatch } from '../types'

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

/** 进门和切换拍摄者共用的两条校验，与后端 `ProjectShareService.validatedIdentity` 对应。 */
const UPLOADER_NAME_RULES: FormRule[] = [{ required: true, message: '请填写姓名' }, { max: 50 }]
const UPLOADER_STUDENT_ID_RULES: FormRule[] = [{ required: true, message: '请填写学号' },
  { pattern: /^[A-Za-z0-9_-]{2,64}$/, message: '学号只能是字母、数字、下划线或连字符' }]

const describeUploader = (identity: ShareUploaderIdentity) =>
  `${identity.uploaderName}（${identity.uploaderStudentId}）`

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
 * 通讯录摆出来让站外的人挑更是直接泄露姓名和学号。替两个人交图时可以中途「切换拍摄者」，
 * 换的是会话上的整份快照；正在上传时不让切，免得一批图一半记在一个人名下。</p>
 *
 * <p>上传者可以给自己的图打标签：候选是选题的预设标签加相册里已有的标签，也可以自己输入新标签
 * （保留标签 deprecated 除外）。上传前选好整批的（ZIP 整包共用一个），传完之后还能在列表里
 * 逐张改（只限记在当前拍摄者名下、已经处理完的）。</p>
 *
 * <p>页面的最后一步是申报工时：传完图的人用同一份身份报这次拍了多久、修了多久，
 * 申报直接落成「待确认」进入部长和管理员的工时审核面板。姓名学号不再填一遍——
 * 服务端取的就是会话上那一份，和照片上的拍摄者是同一个人。申报人没有账号、收不到
 * 通知，所以审核结果（尤其是退回原因）就显示在这张卡片里。</p>
 */
export default function SharedUploadPage() {
  const { token = '' } = useParams()
  const { message } = App.useApp()
  const branding = useBranding()
  const uploadLimits = useUploadLimits()
  const [gateForm] = Form.useForm<{ password: string; uploaderName: string; uploaderStudentId: string }>()
  const [worklogForm] = Form.useForm<{
    workDate: dayjs.Dayjs; shootingMinutes: number; retouchingMinutes: number; remark?: string
  }>()

  const [session, setSession] = useState<string | null>(() => readStoredShareSession(token))
  const [access, setAccess] = useState<ShareGuestAccess | null>(null)
  const [linkState, setLinkState] = useState<'checking' | 'usable' | 'broken' | 'browse'>('checking')
  const [unlocking, setUnlocking] = useState(false)
  const [items, setItems] = useState<ShareUploadItem[]>([])
  // 队列的真相放在 ref 里同步改：入队要按刚改完的列表判重，而连着两次拖拽之间
  // 等不到 React 把 state 提交回来。state 只是它的一份快照，用来渲染。
  const itemsRef = useRef<ShareUploadItem[]>([])
  const sessionRef = useRef(session)
  const [queueRunning, setQueueRunning] = useState(false)
  /** 只管 ZIP 那一条；逐张上传的忙闲看 {@link queueRunning}。 */
  const [running, setRunning] = useState(false)
  const [mode, setMode] = useState<UploadMode>('files')
  const [archivePhase, setArchivePhase] = useState<ArchivePhase | null>(null)
  const [archivePercent, setArchivePercent] = useState(0)
  const [archiveBatch, setArchiveBatch] = useState<ShareUploadBatch | null>(null)
  const [worklogs, setWorklogs] = useState<ShareGuestWorklog[]>([])
  const [declaring, setDeclaring] = useState(false)
  const worklogDate = Form.useWatch('workDate', worklogForm)
  /** 「这批照片的标签」：入队时抄到每一张上，ZIP 整包共用。 */
  const [batchTags, setBatchTags] = useState<string[]>([])
  const [switchForm] = Form.useForm<ShareUploaderIdentity>()
  const [switchOpen, setSwitchOpen] = useState(false)
  const [switching, setSwitching] = useState(false)
  const [uploaders, setUploaders] = useState<ShareUploaderIdentity[]>(() => readRememberedUploaders(token))
  // 签票据时要知道「现在记在谁名下」，而 worker 是常驻队列里的闭包，读 state 会读到旧的。
  const accessRef = useRef(access)
  useEffect(() => { accessRef.current = access }, [access])

  const remember = useCallback((identity: ShareUploaderIdentity) => {
    setUploaders(current => {
      const next = rememberUploader(current, identity)
      storeRememberedUploaders(token, next)
      return next
    })
  }, [token])

  // 刷新页面、换个标签页回来时会话还在，但进门那一刻填的身份只在服务端：从 access 里补记一次。
  useEffect(() => {
    if (access?.uploaderName && access.uploaderStudentId) {
      remember({ uploaderName: access.uploaderName, uploaderStudentId: access.uploaderStudentId })
    }
  }, [access?.uploaderName, access?.uploaderStudentId, remember])

  const dropSession = useCallback((notice?: string) => {
    clearShareSession(token)
    setSession(null)
    setAccess(null)
    setWorklogs([])
    if (notice) message.warning(notice)
  }, [message, token])

  useEffect(() => { sessionRef.current = session }, [session])

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

  // 申报记录要等确认了这是一条上传链接再取：浏览链接调这个接口一律 403，而这一页对
  // 403 的处理是清会话退回密码页——拿着相册会话误开到这里的人，会话会在跳走之前被清掉。
  // 服务端按当前拍摄者的学号列，所以切换拍摄者之后要重取一次。
  const currentStudentId = access?.uploaderStudentId
  useEffect(() => {
    if (!session || access?.purpose !== 'UPLOAD') return
    let cancelled = false
    const load = async () => {
      try {
        const declared = await shareUploadApi.worklogs(token, session)
        if (!cancelled) setWorklogs(declared)
      } catch (reason) {
        if (!cancelled) handleGuestError(reason)
      }
    }
    void load()
    return () => { cancelled = true }
  }, [access?.purpose, currentStudentId, handleGuestError, session, token])

  const declareWorklog = async () => {
    if (!session) return
    const values = await worklogForm.validateFields()
    const draft = {
      workDate: values.workDate.format('YYYY-MM-DD'),
      shootingMinutes: values.shootingMinutes ?? 0,
      retouchingMinutes: values.retouchingMinutes ?? 0,
    }
    // 同一天可以报多条（一天两场活动、上午拍下午修），这里不再拦，只在表单上提醒一句。
    const reason = rejectWorklogReason(draft, dayjs().format('YYYY-MM-DD'))
    if (reason) {
      message.error(reason)
      return
    }
    setDeclaring(true)
    try {
      const declared = await shareUploadApi.declareWorklog(token, session, {
        ...draft, remark: values.remark?.trim() || null,
      })
      setWorklogs(current => [declared, ...current.filter(item => item.id !== declared.id)])
      worklogForm.resetFields()
      message.success('工时已提交，等待部长或管理员确认')
    } catch (reasonThrown) {
      // 这里的 403/404 才是会话失效；409（还没交图、选题已结束）要原样说给人听。
      handleGuestError(reasonThrown)
    } finally {
      setDeclaring(false)
    }
  }

  /** 切换拍摄者。成功后 access 换成新身份，申报记录随之重取（见上面那个 effect）。 */
  const applyUploader = async (identity: ShareUploaderIdentity) => {
    if (!session) return
    setSwitching(true)
    try {
      const next = await shareUploadApi.switchUploader(token, session, {
        uploaderName: identity.uploaderName.trim(),
        uploaderStudentId: identity.uploaderStudentId.trim(),
      })
      setAccess(next)
      setSwitchOpen(false)
      switchForm.resetFields()
      message.success(`已切换拍摄者为「${next.uploaderName}」，之后上传的照片和申报的工时都记在 TA 名下`)
    } catch (reason) {
      handleGuestError(reason)
    } finally {
      setSwitching(false)
    }
  }

  // 每张图的标签保存排成一条链：多选框里连点两个标签会连发两次，并发的话后一次会撞上
  // 服务端的版本条件被拒。confirmed 是服务端最后一次确认的值，seq 用来只让最后一次改动落到界面上。
  const tagSaves = useRef(new Map<string, { chain: Promise<void>; seq: number; confirmed: string[] }>())

  /**
   * 改列表里一张图的标签。先在列表上改掉，失败再换回服务端确认过的值：标签框是受控的，
   * 等请求回来再改会让人以为点了没反应。
   */
  const retag = (item: ShareUploadItem, tags: string[]) => {
    const guestSession = session
    const photoId = item.photoId
    if (!guestSession || !photoId) return
    const state = tagSaves.current.get(item.key)
      ?? { chain: Promise.resolve(), seq: 0, confirmed: item.tags }
    tagSaves.current.set(item.key, state)
    const seq = ++state.seq
    patch(item.key, { tags })
    state.chain = state.chain.then(async () => {
      try {
        state.confirmed = (await shareUploadApi.updateTags(token, guestSession, photoId, tags)).tags
      } catch (reason) {
        handleGuestError(reason)
      }
      if (seq === state.seq) patch(item.key, { tags: state.confirmed })
    })
  }

  const unlock = async () => {
    const values = await gateForm.validateFields()
    setUnlocking(true)
    try {
      const identity = {
        uploaderName: values.uploaderName.trim(),
        uploaderStudentId: values.uploaderStudentId.trim(),
      }
      const opened = await shareApi.openSession(token, values.password, identity)
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

  const updateItems = (change: (current: ShareUploadItem[]) => ShareUploadItem[]) => {
    itemsRef.current = change(itemsRef.current)
    setItems(itemsRef.current)
  }

  const patch = (key: string, changes: Partial<ShareUploadItem>) =>
    updateItems(current => current.map(item => item.key === key ? { ...item, ...changes } : item))

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
    // 失败重传会签一张新票据，记在当时的拍摄者名下：旧的那份先清掉，别让列表说错。
    patch(item.key, {
      stage: 'uploading', percent: 0, error: undefined,
      photographerName: undefined, photographerStudentId: undefined,
    })
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
      // 拍摄者在签票据这一刻定下来（服务端取会话上的身份），之后切换拍摄者也不会改这一张。
      patch(item.key, {
        photoId: ticket.photoId,
        photographerName: ticket.photographerName ?? accessRef.current?.uploaderName ?? undefined,
        photographerStudentId: accessRef.current?.uploaderStudentId ?? undefined,
      })
      await uploadToObjectStorage(ticket, item.file,
        percent => patch(item.key, { percent }))
      patch(item.key, { stage: 'processing', percent: 100 })
      await shareUploadApi.complete(token, guestSession, ticket.photoId, {
        // 原始文件名是访客和选片人之间唯一的共同语言，留作标题好让人对得上。
        title: item.file.name.slice(0, 200),
        tags: item.tags,
      })
      await waitForProcessing(guestSession, item, String(ticket.photoId))
    } catch (reason) {
      if (reason instanceof ApiError && (reason.status === 403 || reason.status === 404)) throw reason
      patch(item.key, { stage: 'failed', error: (reason as Error).message })
    }
  }

  // 会话失效要停下整条队列，但不能靠让 worker 抛出来做这件事：几个并发的 worker
  // 会同时抛。记一个旗子，后面排队的直接标成失败，队列空了再统一退回密码页。
  const expiredRef = useRef<unknown>(null)
  // 队列常驻整个页面：拖进来的每一批都接到同一条队列上，并发位是共用的。
  // 它只创建一次，所以经由 ref 取最新的那份 worker 和收尾——闭包里的会话会过期。
  const runItemRef = useRef<(item: ShareUploadItem) => Promise<void>>(async () => {})
  const settleRef = useRef<() => void>(() => {})
  const [pump] = useState(() => createUploadPump<ShareUploadItem>(
    item => runItemRef.current(item),
    () => settleRef.current(),
  ))

  runItemRef.current = async item => {
    const guestSession = sessionRef.current
    if (expiredRef.current || !guestSession) {
      patch(item.key, { stage: 'failed', error: '上传链接已失效，请重新输入密码后再传这几张' })
      return
    }
    try {
      await uploadOne(guestSession, item)
    } catch (reason) {
      expiredRef.current = reason
      patch(item.key, { stage: 'failed', error: '上传链接已失效，请重新输入密码后再传这几张' })
    }
  }
  settleRef.current = () => {
    setQueueRunning(false)
    const expired = expiredRef.current
    expiredRef.current = null
    if (expired) handleGuestError(expired)
  }

  const startQueue = (queued: ShareUploadItem[]) => {
    if (!queued.length) return
    setQueueRunning(true)
    pump.push(queued)
  }

  const retryFailed = () => {
    const { items: next, retried } = markFailedForRetry(itemsRef.current)
    updateItems(() => next)
    startQueue(retried)
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
    if (!session || running) return
    const reason = rejectArchiveReason(file, uploadLimits)
    if (reason) {
      message.error(`${file.name}：${reason}`)
      return
    }
    // 标签在拖进来这一刻定下：后面解压要好几分钟，期间改了标签框也不该影响这一包。
    const archiveTags = [...batchTags]
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
        dayjs(file.lastModified).format('YYYY-MM-DDTHH:mm:ss'), archiveTags))

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

  /** 拖进来即开传，不再要访客另点一个按钮；正在传的时候再拖一批就排到后面。 */
  const enqueue = (files: File[]) => {
    const added = addToQueue(itemsRef.current, files, uploadLimits, batchTags)
    added.errors.forEach(error => message.error(error))
    if (added.dropped) message.warning(`一次最多 ${MAX_QUEUE_SIZE} 张，多出的 ${added.dropped} 张请分批上传`)
    updateItems(current => [...current, ...added.items])
    startQueue(added.items)
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
      {access.uploaderName && <Tag color="blue">拍摄者 {access.uploaderName}</Tag>}
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
          密码由发给你链接的同学提供。姓名和学号会作为你这次上传的每一张照片的拍摄者，
          传完之后申报工时用的也是这一份，请填写本人的。替别的同学交图时，进去之后可以切换拍摄者。
        </Typography.Paragraph>
        <Form form={gateForm} layout="vertical" requiredMark={false} onFinish={() => void unlock()}>
          <Form.Item label="上传密码" name="password" rules={[{ required: true, message: '请输入上传密码' }]}>
            <Input.Password autoFocus placeholder="请输入上传密码" maxLength={64} />
          </Form.Item>
          <Form.Item label="你的姓名" name="uploaderName" rules={UPLOADER_NAME_RULES}>
            <Input placeholder="例如：张三" maxLength={50} />
          </Form.Item>
          <Form.Item label="你的学号" name="uploaderStudentId"
            extra="用于统计照片归属和工时，请填写本人学号"
            rules={UPLOADER_STUDENT_ID_RULES}>
            <Input placeholder="例如：20230001" maxLength={64} />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={unlocking}>进入上传</Button>
        </Form>
      </Card>
    </div>
    <SiteFooter />
  </main>

  // 这一页上有没有真的交上去过图：只用来决定那句「别忘了申报工时」的提醒出不出现，
  // 能不能申报由服务端按「这条链接 + 这个学号」现查，换设备、隔天再来的人照样能报。
  const handedInHere = summary.done > 0 || (archiveBatch?.successCount ?? 0) > 0
  const sameDayCount = worklogDate ? declaredCountOn(worklogs, worklogDate.format('YYYY-MM-DD')) : 0
  // 候选：预设在前，其次是相册里已有的，再其次是这一页上刚造出来的（还没出现在相册里）。
  const tagCandidates = [...new Set([
    ...(access?.presetTags ?? []), ...(access?.existingTags ?? []),
    ...batchTags, ...items.flatMap(item => item.tags),
  ])]
  const progress = progressOf(items)
  // 正在传的时候不让切：队列里排着的那几张会在切换之后才签票据，一批图就分到了两个人名下。
  const uploadBusy = queueRunning || running
  const currentUploader = access?.uploaderName && access.uploaderStudentId
    ? { uploaderName: access.uploaderName, uploaderStudentId: access.uploaderStudentId } : null
  const otherUploaders = uploaders.filter(item => item.uploaderStudentId !== currentStudentId)

  /** 列表里一张传完的图的标签框：只有记在当前拍摄者名下的才能改。 */
  const renderItemTags = (item: ShareUploadItem) => {
    const editable = canRetag(item, currentStudentId) && !!access?.allowUpload
    const someoneElses = !!item.photographerStudentId && item.photographerStudentId !== currentStudentId
    return <Space direction="vertical" size={2} style={{ width: '100%' }}>
      <TagSelect presets={tagCandidates} size="small" value={item.tags}
        onChange={value => retag(item, value ?? [])} disabled={!editable}
        placeholder="给这张打标签（可不选）" style={{ width: '100%', maxWidth: 420 }} />
      {someoneElses && <Typography.Text type="secondary">
        这张记在「{item.photographerName}」名下，切换回 TA 之后才能改标签
      </Typography.Text>}
    </Space>
  }

  return <main className="share-page">{header}
    <div className="share-body">
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card title={mode === 'files'
        ? `上传照片${summary.total ? `（${summary.done} / ${summary.total}）` : ''}`
        : '打包上传'}
        extra={mode === 'files' ? <Space wrap>
          {queueRunning && <Tag icon={<CloudUploadOutlined />} color="processing">
            正在上传，还剩 {summary.pending} 张
          </Tag>}
          {!!summary.done &&
            <Button onClick={() => updateItems(current => current.filter(item => item.stage !== 'done'))}>
              清掉已完成
            </Button>}
          {!!summary.failed && <Button type="primary" danger disabled={!access?.allowUpload}
            onClick={retryFailed}>
            重试失败的 {summary.failed} 张
          </Button>}
        </Space> : null}>
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {access && !access.allowUpload && <Alert type="warning" showIcon
            message="这个选题已经不再接收上传"
            description="选题已经结束了。如果还需要补交照片，请联系发给你链接的同学。" />}

          {/* 拍摄者放在拖拽区上面而不是只在页头：替两个人交图时，每拖一批之前都该看一眼现在记在谁名下。 */}
          <Space wrap align="center">
            <Typography.Text>
              现在上传的照片记在{' '}
              <Typography.Text strong>
                {currentUploader ? describeUploader(currentUploader) : (access?.uploaderName || '—')}
              </Typography.Text>{' '}名下
            </Typography.Text>
            <Tooltip title={uploadBusy ? '正在上传，传完这一批再切换' : '替别的同学交图时用'}>
              <Button size="small" icon={<SwapOutlined />} disabled={uploadBusy || !access}
                onClick={() => setSwitchOpen(true)}>
                切换拍摄者
              </Button>
            </Tooltip>
          </Space>

          {/* 候选是预设加相册里已有的标签，也可以直接输入新标签，回车添加。 */}
          <Space direction="vertical" size={4} style={{ width: '100%' }}>
            <Typography.Text>这批照片的标签（可不选）</Typography.Text>
            <TagSelect presets={tagCandidates} value={batchTags}
              onChange={value => setBatchTags(value ?? [])}
              disabled={!access?.allowUpload || running}
              placeholder="选择预设或已有标签，也可以输入新标签后回车；之后放进来的照片都会带上"
              style={{ width: '100%' }} />
            <Typography.Text type="secondary">
              {mode === 'files'
                ? '改了之后只影响接下来放进来的照片；传完的可以在下面的列表里逐张改。'
                : '整个压缩包共用这一组标签。'}
            </Typography.Text>
          </Space>

          {/* 几十上百张时打包传一次比逐张省事得多，所以两种方式都给，默认逐张。 */}
          <Segmented value={mode} disabled={running || queueRunning}
            onChange={value => setMode(value as UploadMode)}
            options={[
              { label: '逐张上传', value: 'files', icon: <CloudUploadOutlined /> },
              { label: '打包上传（ZIP）', value: 'archive', icon: <FileZipOutlined /> },
            ]} />

          <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
            {mode === 'files'
              ? `支持 JPG 和 PNG，单张不超过 ${describeBytes(uploadLimits.PHOTO_IMAGE_MAX_BYTES)}，`
                + `一次最多 ${MAX_QUEUE_SIZE} 张。`
              : `一个压缩包最大 ${describeBytes(uploadLimits.PHOTO_ZIP_MAX_BYTES)}，`
                + `里面最多 ${uploadLimits.PHOTO_ZIP_MAX_IMAGES} 张 JPG / PNG，`
                + `单张不超过 ${describeBytes(uploadLimits.PHOTO_IMAGE_MAX_BYTES)}；包里的其他文件会被自动跳过。`}
            照片会直接进入这个选题的图库，由选题负责人后续处理；这里看不到别人传了什么。
          </Typography.Paragraph>

          {mode === 'files' ? <>
            {/* 传的过程中也能接着拖：新的一批排到队尾，不用等前一批传完。 */}
            <Upload.Dragger multiple accept="image/jpeg,image/png" showUploadList={false}
              disabled={!access?.allowUpload}
              beforeUpload={(_file, fileList) => {
                // 交给自己的队列跑：antd 的自动上传不认预签名地址那一套两步流程。
                // fileList 是这一次选中的全部文件，所以只在第一个回调里入队一次。
                if (_file === fileList[0]) enqueue(fileList as File[])
                return Upload.LIST_IGNORE
              }}>
              <p className="ant-upload-drag-icon"><InboxOutlined /></p>
              <p className="ant-upload-text">把照片拖到这里，或者点击选择</p>
              <p className="ant-upload-hint">
                放进来就会立刻开始上传，传的时候也可以接着添加；上传过程中请不要关闭这个页面
              </p>
            </Upload.Dragger>

            {/* 整批的进度：逐张的条只管自己，一次拖几十张时这一条才回答「还要多久」。 */}
            {!!progress.total && (progress.settled < progress.total || queueRunning) && (
              <Space direction="vertical" size={4} style={{ width: '100%' }}>
                <Progress percent={progress.percent} status="active" />
                <Typography.Text type="secondary">
                  图片处理进度：已完成 {progress.settled} / {progress.total} 张
                  {!!progress.uploading && `，上传中 ${progress.uploading} 张`}
                  {!!progress.processing && `，后台压缩中 ${progress.processing} 张`}
                  {!!progress.waiting && `，排队 ${progress.waiting} 张`}
                </Typography.Text>
              </Space>
            )}

            {items.length ? <List size="small" dataSource={items} rowKey="key"
              renderItem={item => <List.Item>
                <List.Item.Meta
                  title={<Space wrap>
                    <span>{item.file.name}</span>
                    {item.stage === 'done' && <Tag color="green">已完成</Tag>}
                    {item.stage === 'processing' && <Tag color="blue">处理中</Tag>}
                    {item.stage === 'uploading' && <Tag color="processing">上传中</Tag>}
                    {item.stage === 'waiting' && <Tag>排队中</Tag>}
                    {item.stage === 'failed' && <Tag color="red">失败</Tag>}
                    {item.photographerName && <Typography.Text type="secondary">
                      拍摄者 {item.photographerName}
                    </Typography.Text>}
                  </Space>}
                  description={item.stage === 'failed'
                    ? <Typography.Text type="danger">{item.error}</Typography.Text>
                    : item.stage === 'done'
                      ? renderItemTags(item)
                      : <Progress percent={item.percent} size="small"
                          status={item.stage === 'processing' ? 'active' : undefined}
                          format={item.stage === 'processing' ? () => '处理中' : undefined} />} />
              </List.Item>} />
              : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有选择照片" />}

            {!!summary.failed && !queueRunning && <Alert type="error" showIcon
              message={`有 ${summary.failed} 张没有传成功`}
              description="点右上角「重试失败的」会只重传这几张。如果反复失败，多半是文件本身有问题。" />}
          </> : <>
            <Upload.Dragger accept=".zip,application/zip" maxCount={1} showUploadList={false}
              disabled={running || !access?.allowUpload}
              beforeUpload={(file, fileList) => {
                // antd 对拖进来的每个文件都调一次，一次拖两个包就会并发跑两条上传，
                // 而进度、结果和 running 只有一份——先跑完的那个会把拖拽区重新启用，
                // 结果提示也只剩最后写入的那次。与逐张那一侧同样只认第一个。
                if (file !== fileList[0]) return Upload.LIST_IGNORE
                if (fileList.length > 1) message.warning('一次只能传一个压缩包，这里只取了第一个')
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
                percent={archivePhase === 'uploading' ? archivePercent
                  : archivePhase === 'processing' && archiveBatch?.totalCount
                    ? Math.floor((archiveBatch.successCount + archiveBatch.failureCount) * 100 / archiveBatch.totalCount)
                    : 100} />
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

      {/* 最后一步：传完图申报这次的工时。排在上传之后，是因为它本来就是上传之后的事——
          服务端也要求先通过这条链接交过图。 */}
      <Card title="申报工时" extra={<Tag icon={<ClockCircleOutlined />}>传完照片后填写</Tag>}>
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
            照片传完之后，在这里申报这次拍摄和修图花的时间。提交后直接进入部长和管理员的工时审核，
            确认或退回的结果会显示在下面。申报人是当前拍摄者
            {access?.uploaderName ? `「${access.uploaderName}」` : ''}，需要先以这个身份通过这条链接传成功至少一张照片；
            替别人报工时请先在上面切换拍摄者。同一天有几段工作可以分几条报。
          </Typography.Paragraph>

          {access && !access.allowWorklog && <Alert type="warning" showIcon
            message="这个选题已经结束，不再接收工时申报"
            description="已经提交的申报不受影响，照常审核。如果还有工时没报，请联系发给你链接的同学。" />}
          {handedInHere && access?.allowWorklog && !worklogs.length && <Alert type="info" showIcon
            message="照片已经交上去了，别忘了申报这次的工时" />}

          <Form form={worklogForm} layout="vertical" requiredMark={false}
            disabled={!access?.allowWorklog || declaring}
            initialValues={{ workDate: dayjs(), shootingMinutes: 0, retouchingMinutes: 0 }}
            onFinish={() => void declareWorklog()}>
            {/* 同一天可以报多条，这里只提醒一句，不拦：访客页一刷新表单就回到初始状态，手一抖就交两次。 */}
            <Form.Item label="工作日期" name="workDate"
              rules={[{ required: true, message: '请选择工作日期' }]}
              extra={sameDayCount
                ? `这一天你已经报过 ${sameDayCount} 条。是另一段工作就继续提交；同一段工作不要重复报。`
                : undefined}>
              <DatePicker maxDate={dayjs()} allowClear={false} style={{ width: '100%' }} />
            </Form.Item>
            <Space size={16} align="start" wrap>
              <Form.Item label="拍摄时长" name="shootingMinutes">
                <InputNumber min={0} max={MAX_WORKLOG_MINUTES} precision={0} suffix="分钟" />
              </Form.Item>
              <Form.Item label="修图时长" name="retouchingMinutes">
                <InputNumber min={0} max={MAX_WORKLOG_MINUTES} precision={0} suffix="分钟" />
              </Form.Item>
            </Space>
            <Form.Item label="工作说明" name="remark" rules={[{ max: 1000 }]}>
              <Input.TextArea rows={3} maxLength={1000} placeholder="简要说明完成的工作，例如：开幕式全程跟拍，精修 20 张" />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={declaring}
              disabled={!access?.allowWorklog}>提交工时</Button>
          </Form>

          {!!worklogs.length && <List size="small" header="我在这条链接上申报过的工时"
            dataSource={worklogs} rowKey="id"
            renderItem={worklog => {
              const status = describeWorklogStatus(worklog)
              return <List.Item>
                <List.Item.Meta
                  title={<Space wrap>
                    <span>{worklog.workDate}</span>
                    <Tag color={status.color}>{status.text}</Tag>
                    <Typography.Text type="secondary">
                      拍摄 {describeMinutes(worklog.shootingMinutes)} · 修图 {describeMinutes(worklog.retouchingMinutes)}
                    </Typography.Text>
                  </Space>}
                  description={<>
                    {worklog.remark && <div>{worklog.remark}</div>}
                    <Typography.Text type={worklog.status === 'REJECTED' ? 'danger' : 'secondary'}>
                      {status.hint}
                    </Typography.Text>
                  </>} />
              </List.Item>
            }} />}
        </Space>
      </Card>
      </Space>
    </div>

    <Modal title="切换拍摄者" open={switchOpen} destroyOnHidden okText="切换" cancelText="取消"
      confirmLoading={switching}
      onCancel={() => setSwitchOpen(false)}
      onOk={() => void switchForm.validateFields().then(applyUploader)}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          替别的同学交图时用。切换之后放进来的照片、打包上传的照片和申报的工时，都记在新拍摄者名下；
          已经传上去的照片不受影响。
        </Typography.Paragraph>
        {currentUploader && <Typography.Text>当前：{describeUploader(currentUploader)}</Typography.Text>}
        {/* 来回切的人最常用的就是这几个：一键切回，免得重敲学号敲错一位记到别人名下。 */}
        {!!otherUploaders.length && <Space direction="vertical" size={4}>
          <Typography.Text type="secondary">这台设备上用过的：</Typography.Text>
          <Space wrap>
            {otherUploaders.map(identity => <Button key={identity.uploaderStudentId} disabled={switching}
              onClick={() => void applyUploader(identity)}>
              {describeUploader(identity)}
            </Button>)}
          </Space>
        </Space>}
        <Form form={switchForm} layout="vertical" requiredMark={false} preserve={false}
          onFinish={values => void applyUploader(values)}>
          <Form.Item label="拍摄者姓名" name="uploaderName" rules={UPLOADER_NAME_RULES}>
            <Input placeholder="例如：李四" maxLength={50} />
          </Form.Item>
          <Form.Item label="拍摄者学号" name="uploaderStudentId" rules={UPLOADER_STUDENT_ID_RULES}
            style={{ marginBottom: 0 }}>
            <Input placeholder="例如：20230002" maxLength={64} />
          </Form.Item>
        </Form>
      </Space>
    </Modal>
    <SiteFooter />
  </main>
}
