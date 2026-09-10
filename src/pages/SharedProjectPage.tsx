import {
  App, Button, Card, Checkbox, Col, Empty, Form, Input, Result, Row, Skeleton, Space, Tag, Typography,
} from 'antd'
import { DownloadOutlined, LinkOutlined, LockOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { ApiError } from '../api'
import { BrandGlyph, useBranding } from '../branding'
import SiteFooter from '../SiteFooter'
import { PhotoPlaceholder, pickPlaceholderImage, usePlaceholderImages } from '../photoPlaceholder'
import PreviewPhoto from '../PreviewPhoto'
import { useRefreshOnResume } from '../hooks'
import {
  clearShareSession, prepareSharedBatchDownload, readStoredShareSession, shareApi, storeShareSession,
} from '../projectShare'
import type { ShareGuestAccess, SharePhoto } from '../types'

const PAGE_SIZE = 60
const MAX_BATCH = 200

/**
 * 分享链接的访客页，不需要登录。
 *
 * <p>两个状态：没有会话时是密码页，拿到会话后是图片墙。会话失效（链接被撤销、
 * 权限被改到不可用、密码被重置、6 小时到期）在任何一次请求上都会以 403 回来，
 * 统一退回密码页——比停在一屏点什么都报错的画面清楚得多。</p>
 *
 * <p>能力（下载、标记被引）**不缓存在前端**：每次进入页面重新取一遍 access，
 * 并且服务端每个写接口都会再判一次。前端隐藏按钮只是体验，不是权限。</p>
 */
export default function SharedProjectPage() {
  const { token = '' } = useParams()
  const { message } = App.useApp()
  const branding = useBranding()
  const placeholderImages = usePlaceholderImages()
  const [passwordForm] = Form.useForm<{ password: string }>()

  const [session, setSession] = useState<string | null>(() => readStoredShareSession(token))
  const [access, setAccess] = useState<ShareGuestAccess | null>(null)
  const [linkUsable, setLinkUsable] = useState<boolean | null>(null)
  const [checking, setChecking] = useState(true)
  const [unlocking, setUnlocking] = useState(false)

  const [photos, setPhotos] = useState<SharePhoto[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [keyword, setKeyword] = useState('')
  const [loadingPhotos, setLoadingPhotos] = useState(false)
  const [selected, setSelected] = useState<string[]>([])
  const [batchDownloading, setBatchDownloading] = useState(false)
  const [markingPhotoId, setMarkingPhotoId] = useState<string | null>(null)

  const dropSession = useCallback((notice?: string) => {
    clearShareSession(token)
    setSession(null)
    setAccess(null)
    setPhotos([])
    setSelected([])
    if (notice) message.warning(notice)
  }, [message, token])

  // 403 在这条链路上只有一个意思：这把会话钥匙已经不作数了（链接被撤销、密码被
  // 重置、或者过期）。统一退回密码页，而不是把错误抛在图片墙上。
  const handleGuestError = useCallback((reason: unknown) => {
    if (reason instanceof ApiError && (reason.status === 403 || reason.status === 404)) {
      dropSession('分享访问已失效，请重新输入密码')
      return
    }
    message.error((reason as Error).message)
  }, [dropSession, message])

  useEffect(() => {
    let cancelled = false
    const check = async () => {
      setChecking(true)
      try {
        await shareApi.greet(token)
        if (cancelled) return
        setLinkUsable(true)
      } catch {
        if (!cancelled) setLinkUsable(false)
      } finally {
        if (!cancelled) setChecking(false)
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

  const loadPhotos = useCallback(async (quiet = false) => {
    if (!session) return
    if (!quiet) setLoadingPhotos(true)
    try {
      const result = await shareApi.photos(token, session, { page, pageSize: PAGE_SIZE, keyword: keyword || undefined })
      setPhotos(result.items)
      setTotal(result.total)
    } catch (reason) {
      if (!quiet) handleGuestError(reason)
    } finally {
      if (!quiet) setLoadingPhotos(false)
    }
  }, [handleGuestError, keyword, page, session, token])

  useEffect(() => { void loadPhotos() }, [loadPhotos])
  // 预签名预览地址只活 10 分钟，页面切回前台时静默重取一遍，理由见 src/previewFreshness.ts。
  useRefreshOnResume(useCallback(() => { void loadPhotos(true) }, [loadPhotos]))

  // 访客通道没有"单张图重签地址"的接口，重取这一页就是最小的办法——而且一屏
  // 图片的签名本来就是一起过期的，重取一次正好把整屏都换新。几十张图几乎同时
  // 失败，所以在途的那一次请求要共用，不能一张图一次。
  const refreshingPhotos = useRef<Promise<SharePhoto[]> | null>(null)
  const refreshPreviewUrl = useCallback(async (photoId: string) => {
    if (!session) return undefined
    refreshingPhotos.current ??= shareApi
      .photos(token, session, { page, pageSize: PAGE_SIZE, keyword: keyword || undefined })
      .then(result => {
        setPhotos(result.items)
        setTotal(result.total)
        return result.items
      })
      .finally(() => { refreshingPhotos.current = null })
    // 会话失效之类的错误在这里不弹提示：用户没有主动发起这次请求，页面上别的
    // 请求会把它说清楚，这里安静地让占位图顶上就行。
    const items = await refreshingPhotos.current.catch(() => [] as SharePhoto[])
    return items.find(item => item.id === photoId)?.thumbnailUrl
  }, [keyword, page, session, token])

  const unlock = async () => {
    const values = await passwordForm.validateFields()
    setUnlocking(true)
    try {
      const opened = await shareApi.openSession(token, values.password)
      storeShareSession(token, opened)
      setSession(opened.sessionToken)
      setAccess(opened.access)
      setPage(1)
      passwordForm.resetFields()
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setUnlocking(false)
    }
  }

  const toggleSelected = (photoId: string, checked: boolean) => {
    setSelected(current => checked
      ? current.includes(photoId) ? current : [...current, photoId].slice(0, MAX_BATCH)
      : current.filter(id => id !== photoId))
  }

  const downloadOne = async (photo: SharePhoto) => {
    if (!session) return
    try {
      const result = await shareApi.downloadUrl(token, session, photo.id)
      window.open(result.downloadUrl, '_blank', 'noopener')
    } catch (reason) {
      handleGuestError(reason)
    }
  }

  const batchDownload = async () => {
    if (!session || !selected.length) return
    setBatchDownloading(true)
    try {
      const downloadUrl = await prepareSharedBatchDownload(token, session, selected)
      if (downloadUrl) {
        window.location.assign(downloadUrl)
        setSelected([])
        message.success('所选图片已打包为 ZIP')
      } else {
        message.info('ZIP 仍在后台生成，请稍后重新发起下载')
      }
    } catch (reason) {
      handleGuestError(reason)
    } finally {
      setBatchDownloading(false)
    }
  }

  const toggleAdoption = async (photo: SharePhoto) => {
    if (!session) return
    setMarkingPhotoId(photo.id)
    try {
      const result = photo.adopted
        ? await shareApi.cancelAdoption(token, session, photo.id)
        : await shareApi.adopt(token, session, photo.id)
      setPhotos(current => current.map(item =>
        item.id === photo.id ? { ...item, adopted: result.adopted } : item))
      message.success(result.adopted ? '已标记为被引' : '已取消被引标记')
    } catch (reason) {
      handleGuestError(reason)
    } finally {
      setMarkingPhotoId(null)
    }
  }

  const selectAll = () => {
    const ids = photos.map(photo => photo.id).slice(0, MAX_BATCH)
    setSelected(ids)
    if (photos.length > MAX_BATCH) message.info(`单次最多下载 ${MAX_BATCH} 张，已选择当前页前 ${MAX_BATCH} 张`)
  }

  const header = <header className="share-header">
    <Space size={12}>
      <span className="brand-glyph"><BrandGlyph branding={branding} /></span>
      <div>
        <Typography.Title level={4} style={{ color: 'white', margin: 0 }}>
          {access?.projectTitle || `${branding.title} 图片分享`}
        </Typography.Title>
        <Typography.Text style={{ color: 'rgba(255,255,255,.62)' }}>
          {access ? (access.linkName || '通过分享链接查看项目图片') : '这是一个受密码保护的图片分享'}
        </Typography.Text>
      </div>
    </Space>
    {access && <Space wrap>
      {access.allowDownload && <Tag color="green">可下载</Tag>}
      {access.allowAdoption && <Tag color="gold">可标记被引</Tag>}
      {access.expiresAt && <Tag>有效期至 {dayjs(access.expiresAt).format('YYYY-MM-DD HH:mm')}</Tag>}
    </Space>}
  </header>

  if (checking) return <main className="share-page">{header}
    <div className="share-body"><Skeleton active paragraph={{ rows: 6 }} /></div>
    <SiteFooter />
  </main>

  if (linkUsable === false) return <main className="share-page">{header}
    <div className="share-body">
      <Result status="404" title="这个分享链接打不开了"
        subTitle="它可能已经被撤销或超过了有效期。请向分享给你的同学要一个新的链接。" />
    </div>
    <SiteFooter />
  </main>

  if (!session) return <main className="share-page">{header}
    <div className="share-body">
      <Card className="share-gate">
        <Typography.Title level={5}><LockOutlined /> 输入分享密码</Typography.Title>
        <Typography.Paragraph type="secondary">
          密码由分享给你的同学提供。通过后可以浏览这个选题项目里的图片。
        </Typography.Paragraph>
        <Form form={passwordForm} layout="vertical" requiredMark={false} onFinish={() => void unlock()}>
          <Form.Item label="分享密码" name="password" rules={[{ required: true, message: '请输入分享密码' }]}>
            <Input.Password autoFocus placeholder="请输入分享密码" maxLength={64} />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={unlocking}>进入相册</Button>
        </Form>
      </Card>
    </div>
    <SiteFooter />
  </main>

  return <main className="share-page">{header}
    <div className="share-body">
      <Card
        title={`项目图片（${total}）`}
        extra={<Space wrap>
          <Input.Search allowClear placeholder="搜索图片标题、描述或标签" style={{ maxWidth: 260 }}
            onSearch={value => { setKeyword(value); setPage(1) }} />
          {access?.allowDownload && <>
            <Button type="link" disabled={!photos.length || batchDownloading} onClick={selectAll}>全选本页</Button>
            {!!selected.length && <Button type="link" disabled={batchDownloading}
              onClick={() => setSelected([])}>清空选择</Button>}
            <Button type="primary" icon={<DownloadOutlined />} loading={batchDownloading}
              disabled={!selected.length} onClick={() => void batchDownload()}>
              打包下载{selected.length ? `（${selected.length}）` : ''}
            </Button>
          </>}
        </Space>}
      >
        {loadingPhotos ? <Skeleton active paragraph={{ rows: 6 }} />
          : photos.length ? <Row gutter={[16, 20]} className="photo-grid">
            {photos.map(photo => <Col xs={24} sm={12} lg={8} xxl={6} key={photo.id}>
              <Card className={`photo-card${selected.includes(photo.id) ? ' photo-card-selected' : ''}`}
                cover={<div className="photo-cover">
                  {photo.thumbnailUrl
                    ? <PreviewPhoto src={photo.thumbnailUrl} alt={photo.title || '项目图片'}
                        refresh={() => refreshPreviewUrl(photo.id)}
                        fallback={pickPlaceholderImage(placeholderImages, photo.id)} />
                    : <PhotoPlaceholder seed={photo.id}>
                      <span>{photo.title?.slice(0, 1) || '图'}</span>
                    </PhotoPlaceholder>}
                  <div className="photo-overlay">
                    {access?.allowDownload && <>
                      <Checkbox className="photo-select-checkbox" checked={selected.includes(photo.id)}
                        disabled={batchDownloading || (selected.length >= MAX_BATCH && !selected.includes(photo.id))}
                        onChange={event => toggleSelected(photo.id, event.target.checked)}
                        aria-label={`选择图片 ${photo.title || photo.id}`} />
                      <Button className="photo-download-button" shape="circle" icon={<DownloadOutlined />}
                        aria-label={`下载图片 ${photo.title || photo.id}`}
                        onClick={() => void downloadOne(photo)} />
                    </>}
                  </div>
                  {photo.adopted && <div className="photo-badges"><Tag color="gold">已被引</Tag></div>}
                </div>}
              >
                <Typography.Title level={5} ellipsis>{photo.title || '未命名图片'}</Typography.Title>
                <div className="photo-meta">
                  <span>{photo.photographerName}</span>
                  <span>{dayjs(photo.takenAt).format('YYYY.MM.DD')}</span>
                </div>
                {access?.allowAdoption && <Button block type={photo.adopted ? 'default' : 'primary'}
                  danger={photo.adopted} icon={<LinkOutlined />} loading={markingPhotoId === photo.id}
                  onClick={() => void toggleAdoption(photo)}>
                  {photo.adopted ? '取消被引' : '标记被引'}
                </Button>}
              </Card>
            </Col>)}
          </Row> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="这个项目还没有可查看的图片" />}
        {total > PAGE_SIZE && <div className="share-pager">
          <Space>
            <Button disabled={page <= 1 || loadingPhotos} onClick={() => setPage(current => current - 1)}>上一页</Button>
            <Typography.Text type="secondary">第 {page} / {Math.ceil(total / PAGE_SIZE)} 页</Typography.Text>
            <Button disabled={page >= Math.ceil(total / PAGE_SIZE) || loadingPhotos}
              onClick={() => setPage(current => current + 1)}>下一页</Button>
          </Space>
        </div>}
      </Card>
    </div>
    <SiteFooter />
  </main>
}
