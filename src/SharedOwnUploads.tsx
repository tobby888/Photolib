import {
  Alert, App, Button, Card, Checkbox, Col, DatePicker, Empty, Input, Row, Select, Skeleton, Space, Tag, Tooltip,
  Typography,
} from 'antd'
import { DeleteOutlined, FilterOutlined, ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useCallback, useEffect, useState } from 'react'
import PreviewPhoto from './PreviewPhoto'
import { PhotoPlaceholder, pickPlaceholderImage, usePlaceholderImages } from './photoPlaceholder'
import { shareUploadApi } from './projectShare'
import {
  MAX_OWN_DELETE, OWN_UPLOADS_PAGE_SIZE, describeOwnDeleteOutcome, emptyOwnUploadFilters, hasOwnUploadFilters,
  pageAfterDelete, pageFullySelected, toggleOwnSelection, togglePageSelection,
} from './shareOwnUploads'
import type { OwnUploadFilters } from './shareOwnUploads'
import type { EntityId, ShareGuestAccess, ShareOwnUpload } from './types'

interface SharedOwnUploadsProps {
  token: string
  session: string
  access: ShareGuestAccess
  /** 403/404 退回密码页，其余错误提示出来；与上传页同一个出口。 */
  onGuestError: (reason: unknown) => void
  /** 上传页每传完一批就加一：列表跟着刷新，刚传的图不用手动点刷新就出现。 */
  refreshSignal: number
}

/**
 * 上传链接页上的「我传的图片」：按学号认人，只列这条链接上记在当前拍摄者名下、处理完的图片，
 * 带缩略图、筛选和多选删除。切换拍摄者之后看到的是那个人的。
 *
 * 这是上传链接上唯一露出图片的地方，范围由服务端卡死（`ProjectShareUploadService.ownUploads`），
 * 前端不做任何放宽。被采用的、已加入其他选题的图片勾不上，卡片上写明原因；服务端删除时也会再跳过一次。
 */
export default function SharedOwnUploads({ token, session, access, onGuestError, refreshSignal }: SharedOwnUploadsProps) {
  const { message, modal } = App.useApp()
  const placeholderImages = usePlaceholderImages()
  const [photos, setPhotos] = useState<ShareOwnUpload[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(true)
  const [reloadKey, setReloadKey] = useState(0)
  const [filters, setFilters] = useState<OwnUploadFilters>(emptyOwnUploadFilters)
  const [searchText, setSearchText] = useState('')
  const [tagOptions, setTagOptions] = useState<string[]>([])
  const [selected, setSelected] = useState<EntityId[]>([])
  const [deleting, setDeleting] = useState(false)
  // 选择跟着拍摄者走：切换拍摄者之后上一个人的勾选没有意义，服务端也会整批拒绝。
  const owner = access.uploaderPublic ? 'public' : access.uploaderStudentId ?? ''

  const query = useCallback((targetPage: number) => shareUploadApi.ownUploads(token, session, {
    page: targetPage,
    pageSize: OWN_UPLOADS_PAGE_SIZE,
    keyword: filters.keyword.trim() || undefined,
    tags: filters.tags,
    takenFrom: filters.takenFrom,
    takenTo: filters.takenTo,
  }), [filters, session, token])

  useEffect(() => {
    setSelected([])
    setPage(1)
  }, [owner])

  useEffect(() => {
    if (access.uploaderPublic) {
      setPhotos([])
      setTotal(0)
      setLoading(false)
      return
    }
    let cancelled = false
    setLoading(true)
    query(page).then(result => {
      if (cancelled) return
      setPhotos(result.items)
      setTotal(result.total)
    }, reason => {
      if (!cancelled) onGuestError(reason)
    }).finally(() => {
      if (!cancelled) setLoading(false)
    })
    return () => { cancelled = true }
  }, [access.uploaderPublic, onGuestError, owner, page, query, refreshSignal, reloadKey])

  useEffect(() => {
    if (access.uploaderPublic) {
      setTagOptions([])
      return
    }
    let cancelled = false
    shareUploadApi.ownUploadFilterOptions(token, session).then(options => {
      if (!cancelled) setTagOptions(options.tags)
    }, () => { /* 候选取不到不影响列表，下拉为空即可 */ })
    return () => { cancelled = true }
  }, [access.uploaderPublic, owner, refreshSignal, reloadKey, session, token])

  const changeFilters = (next: OwnUploadFilters) => {
    setFilters(next)
    setPage(1)
  }

  /** 缩略图签名过期时重取这一页，换回这一张的新地址。 */
  const refreshThumbnail = (photoId: EntityId) => async () => {
    const result = await query(page)
    setPhotos(result.items)
    return result.items.find(item => item.id === photoId)?.thumbnailUrl ?? null
  }

  const confirmDelete = () => {
    const count = selected.length
    if (!count) return
    modal.confirm({
      title: `删除选中的 ${count} 张图片？`,
      content: '删除后这些图片会从选题图库里移除，不能恢复。已被采用或已加入其他选题的图片不会被删除。',
      okText: '删除', okButtonProps: { danger: true }, cancelText: '取消',
      onOk: () => void deleteSelected(),
    })
  }

  const deleteSelected = async () => {
    setDeleting(true)
    try {
      const result = await shareUploadApi.deleteOwnUploads(token, session, selected)
      const outcome = describeOwnDeleteOutcome(result)
      if (outcome.tone === 'success') message.success(outcome.message)
      else message.warning(outcome.message)
      setSelected([])
      setPage(current => pageAfterDelete(current, total, result.deleted + result.alreadyDeleted))
      setReloadKey(current => current + 1)
    } catch (reason) {
      // 409（混进了别人的图、选题已结束）原样说给人听；403/404 才退回密码页。
      onGuestError(reason)
      setReloadKey(current => current + 1)
    } finally {
      setDeleting(false)
    }
  }

  const selectedIds = new Set(selected)
  const canDelete = access.allowUpload && !deleting
  const filtering = hasOwnUploadFilters(filters)
  const fullPage = pageFullySelected(selected, photos)
  const totalPages = Math.max(Math.ceil(total / OWN_UPLOADS_PAGE_SIZE), 1)

  return <Card className="own-uploads-card" title={`我传的图片${access.uploaderPublic ? '' : `（${filtering ? `符合筛选 ${total} 张` : total}）`}`}
    extra={!access.uploaderPublic && <Space wrap>
      <Button icon={<ReloadOutlined />} disabled={loading} onClick={() => setReloadKey(current => current + 1)}>
        刷新
      </Button>
      <Button type="link" disabled={!canDelete || !photos.some(photo => photo.deletable)}
        onClick={() => setSelected(current => togglePageSelection(current, photos))}>
        {fullPage ? '取消本页' : '全选本页'}
      </Button>
      {!!selected.length && <Button type="link" disabled={deleting} onClick={() => setSelected([])}>清空选择</Button>}
      <Button danger type="primary" icon={<DeleteOutlined />} loading={deleting}
        disabled={!selected.length || !access.allowUpload} onClick={confirmDelete}>
        删除{selected.length ? `（${selected.length}）` : ''}
      </Button>
    </Space>}>
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
        这里只列出通过这条链接、以当前拍摄者
        {access.uploaderName && !access.uploaderPublic ? `「${access.uploaderName}」` : ''}
        的学号传成功的图片，看不到别人传的。传错了、传重了可以勾选后删除；替别人传的图要先切换到那位拍摄者。
        已被采用或已加入其他选题的图片不能删除。
      </Typography.Paragraph>

      {access.uploaderPublic && <Alert type="info" showIcon message="现在传的是公共照片"
        description="公共照片不属于任何人，不能在这里管理。要管理自己传的图片，请先在上面切换成本人。" />}
      {!access.uploaderPublic && !access.allowUpload && <Alert type="warning" showIcon
        message="选题已经结束，不能再删除图片"
        description="如果有传错的图片需要删除，请联系发给你链接的同学。" />}

      {!access.uploaderPublic && <>
        <div className="project-photo-filters">
          <FilterOutlined className="project-photo-filters-icon" />
          <Input.Search allowClear placeholder="搜索标题或标签" style={{ maxWidth: 240, flex: '1 1 200px' }}
            value={searchText} onChange={event => setSearchText(event.target.value)}
            onSearch={value => changeFilters({ ...filters, keyword: value.trim() })} />
          <Select mode="multiple" allowClear showSearch maxTagCount="responsive"
            style={{ minWidth: 200, flex: '1 1 200px' }}
            placeholder="按标签筛选（同时包含）" value={filters.tags}
            options={tagOptions.map(tag => ({ value: tag, label: tag }))}
            notFoundContent="这些图片还没有标签"
            onChange={tags => changeFilters({ ...filters, tags })} />
          <DatePicker.RangePicker allowEmpty={[true, true]} style={{ flex: '0 1 280px' }}
            placeholder={['拍摄开始日期', '拍摄结束日期']}
            value={[
              filters.takenFrom ? dayjs(filters.takenFrom) : null,
              filters.takenTo ? dayjs(filters.takenTo) : null,
            ]}
            onChange={range => changeFilters({
              ...filters,
              takenFrom: range?.[0]?.format('YYYY-MM-DD') || null,
              takenTo: range?.[1]?.format('YYYY-MM-DD') || null,
            })} />
          {filtering && <Button type="link" onClick={() => {
            setSearchText('')
            changeFilters(emptyOwnUploadFilters)
          }}>清空筛选</Button>}
        </div>

        {loading && !photos.length ? <Skeleton active paragraph={{ rows: 4 }} />
          : photos.length ? <Row gutter={[16, 16]} className="photo-grid own-upload-grid">
            {photos.map(photo => {
              const checked = selectedIds.has(photo.id)
              const blocked = !photo.deletable
                || (!checked && selected.length >= MAX_OWN_DELETE)
              return <Col xs={12} sm={8} lg={6} key={photo.id}>
                <Card size="small" className={`photo-card${checked ? ' photo-card-selected' : ''}`}
                  cover={<div className="photo-cover"
                    role={photo.deletable && canDelete ? 'button' : undefined}
                    tabIndex={photo.deletable && canDelete ? 0 : undefined}
                    aria-pressed={photo.deletable && canDelete ? checked : undefined}
                    aria-label={`选择图片 ${photo.title || photo.id}`}
                    onClick={() => {
                      if (canDelete) setSelected(current => toggleOwnSelection(current, photo, !checked))
                    }}
                    onKeyDown={event => {
                      if (event.target !== event.currentTarget || !canDelete) return
                      if (event.key !== ' ' && event.key !== 'Enter') return
                      event.preventDefault()
                      setSelected(current => toggleOwnSelection(current, photo, !checked))
                    }}>
                    {photo.thumbnailUrl
                      ? <PreviewPhoto src={photo.thumbnailUrl} alt={photo.title || '我传的图片'} preview={false}
                          refresh={refreshThumbnail(photo.id)}
                          fallback={pickPlaceholderImage(placeholderImages, photo.id)} />
                      : <PhotoPlaceholder seed={photo.id}><span>{photo.title?.slice(0, 1) || '图'}</span></PhotoPlaceholder>}
                    <div className="photo-overlay" onClick={event => event.stopPropagation()}>
                      <Tooltip title={photo.lockedReason || undefined}>
                        <Checkbox className="photo-select-checkbox" checked={checked}
                          disabled={!canDelete || blocked}
                          onChange={event => setSelected(current =>
                            toggleOwnSelection(current, photo, event.target.checked))}
                          aria-label={`选择图片 ${photo.title || photo.id}`} />
                      </Tooltip>
                    </div>
                    {photo.lockedReason && <div className="photo-badges">
                      <Tag color="gold">{photo.lockedReason}</Tag>
                    </div>}
                  </div>}>
                  <Typography.Text strong ellipsis={{ tooltip: photo.title }} style={{ display: 'block' }}>
                    {photo.title || '未命名图片'}
                  </Typography.Text>
                  <div className="photo-card-tags">
                    {photo.tags.length
                      ? photo.tags.map(tag => <Tag key={tag} variant="filled" className="clickable-tag"
                          color={filters.tags.includes(tag) ? 'blue' : undefined}
                          onClick={() => {
                            if (!filters.tags.includes(tag)) changeFilters({ ...filters, tags: [...filters.tags, tag] })
                          }}>{tag}</Tag>)
                      : <Typography.Text type="secondary">无标签</Typography.Text>}
                  </div>
                  {photo.takenAt && <Typography.Text type="secondary">
                    拍摄于 {dayjs(photo.takenAt).format('YYYY.MM.DD HH:mm')}
                  </Typography.Text>}
                </Card>
              </Col>
            })}
          </Row> : filtering
            ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有符合筛选条件的图片" />
            : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有以当前拍摄者传成功的图片" />}

        {total > OWN_UPLOADS_PAGE_SIZE && <div className="share-pager">
          <Space wrap>
            <Button disabled={page <= 1 || loading} onClick={() => setPage(current => current - 1)}>上一页</Button>
            <Typography.Text type="secondary">第 {page} / {totalPages} 页</Typography.Text>
            <Button disabled={page >= totalPages || loading} onClick={() => setPage(current => current + 1)}>下一页</Button>
            {!!selected.length && <Typography.Text type="secondary">已跨页选择 {selected.length} 张</Typography.Text>}
          </Space>
        </div>}
      </>}
    </Space>
  </Card>
}
