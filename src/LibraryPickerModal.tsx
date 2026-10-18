import { App, Button, DatePicker, Input, Modal, Select, Space, Tag, Typography } from 'antd'
import { useMemo, useRef, useState } from 'react'
import dayjs from 'dayjs'
import { api, emptyPage } from './api'
import type { EntityId, PageData, Photo } from './types'
import { ContentFitTable } from './ContentFitTable'
import { useLoad } from './hooks'
import PreviewPhoto from './PreviewPhoto'
import { refreshPhotoPreviewUrl } from './previewRefresh'
import { pickPlaceholderImage, usePlaceholderImages } from './photoPlaceholder'
import TagSelect from './TagSelect'
import { normalizeTags } from './photoTags'
import {
  DEFAULT_PHOTO_LIBRARY_FILTERS, hasExtraLibraryFilters, isTagTooLong, normalizePhotographerFilter,
  photoLibraryRequestParams,
} from './photoLibrarySearch'
import type { PhotoLibraryFilters, PhotoLibraryFlag } from './photoLibrarySearch'
import { MAX_LIBRARY_SELECTION, collectAllPages, mergeById, runInChunks } from './librarySelection'
import { TABLE_PAGE_SIZES, serverTablePagination, turnPage } from './pagination'

/**
 * 选题详情页的「从图库添加图片」。
 *
 * 以前这里只取图库的第一页 100 张、只能按关键词搜，图库一大就找不到要的那几张，
 * 也没法「把 5 月 3 日那场的图全加进来」。现在和图库页是同一套筛选（关键词、标签含「任意其一」、
 * 拍摄者、拍摄日期、被引与否），列表走服务端分页，并能把**整个筛选结果**一次选上。
 *
 * 已经在本选题里的图片照常列出来（否则「筛出来 30 张、表里只有 26 张」会让人以为筛错了），
 * 但勾不上，「全选」也会跳过它们。
 */
export default function LibraryPickerModal({ open, projectId, onClose, onAdded }: {
  open: boolean
  projectId: EntityId
  onClose: () => void
  onAdded: (count: number) => void
}) {
  const { message } = App.useApp()
  const placeholderImages = usePlaceholderImages()
  const [filters, setFilters] = useState<PhotoLibraryFilters>({ ...DEFAULT_PHOTO_LIBRARY_FILTERS, pageSize: 10 })
  const [searchText, setSearchText] = useState('')
  const [selected, setSelected] = useState<Photo[]>([])
  const [selectingAll, setSelectingAll] = useState(false)
  const [saving, setSaving] = useState(false)
  const filterKey = JSON.stringify({ ...filters, page: 0 })
  const filterKeyRef = useRef(filterKey)
  filterKeyRef.current = filterKey

  const { data, loading, error } = useLoad(
    () => open
      ? api<PageData<Photo>>({ url: '/photos', params: photoLibraryRequestParams(filters) })
      : Promise.resolve(emptyPage<Photo>()),
    emptyPage<Photo>(), [open, filters.page, filterKey],
  )
  const { data: photographerNames } = useLoad(
    () => open ? api<string[]>({ url: '/photos/photographers' }) : Promise.resolve([] as string[]),
    [] as string[], [open],
  )
  const photographerOptions = [...new Set([...photographerNames, ...filters.photographers])]
    .map(name => ({ value: name, label: name }))

  const inThisProject = (photo: Photo) =>
    photo.relatedProjectIds?.some(id => String(id) === String(projectId))
    || photo.relatedProjects?.some(project => String(project.id) === String(projectId))
    || false
  const selectedIds = useMemo(() => selected.map(photo => String(photo.id)), [selected])
  const filtersActive = Boolean(filters.keyword || filters.tags.length || filters.photographers.length
    || hasExtraLibraryFilters(filters))
  const update = (next: Partial<PhotoLibraryFilters>, removed: (keyof PhotoLibraryFilters)[] = []) => {
    const merged: PhotoLibraryFilters = { ...filters, ...next, page: 1 }
    for (const key of removed) delete merged[key]
    setFilters(merged)
  }

  const selectAllFiltered = async () => {
    const operationKey = filterKeyRef.current
    setSelectingAll(true)
    try {
      const { items, total } = await collectAllPages((page, pageSize) => api<PageData<Photo>>({
        url: '/photos', params: photoLibraryRequestParams({ ...filters, pageSize }, { page }),
      }))
      if (filterKeyRef.current !== operationKey) return
      const result = mergeById(selected, items.filter(photo => !inThisProject(photo)))
      setSelected(result.selected)
      if (total > MAX_LIBRARY_SELECTION || result.truncated) {
        message.warning(`筛选结果共 ${total} 张，单次最多选择 ${MAX_LIBRARY_SELECTION} 张，已选到上限为止`)
      } else {
        message.success(result.added ? `已选中 ${result.added} 张` : '可添加的图片都已经选上了')
      }
    } catch (e) {
      message.error(`全选失败：${(e as Error).message}`)
    } finally {
      setSelectingAll(false)
    }
  }

  const submit = async () => {
    if (!selected.length) {
      message.warning('请至少选择一张图库图片')
      return
    }
    setSaving(true)
    const ids = selected.map(photo => photo.id)
    try {
      // 后端一次最多收 200 张，多了按批顺序发；中途失败时前面几批已经加进去了，如实说明。
      const { done, error: failure } = await runInChunks(ids, batch => api({
        method: 'POST', url: `/projects/${projectId}/photos`, data: { photoIds: batch },
      }))
      if (failure) {
        message.error(done ? `已添加 ${done} 张，其余 ${ids.length - done} 张没有添加：${failure.message}`
          : failure.message)
        if (done) {
          setSelected(current => current.slice(done))
          onAdded(done)
        }
        return
      }
      message.success(`已向项目相册添加 ${ids.length} 张图片，请按需标注采纳`)
      setSelected([])
      onAdded(ids.length)
      onClose()
    } finally {
      setSaving(false)
    }
  }

  return <Modal title="从图库添加图片" width={960} open={open}
    onCancel={() => { if (!saving) onClose() }} onOk={() => void submit()}
    okText={`添加所选图片${selected.length ? `（${selected.length}）` : ''}`}
    okButtonProps={{ disabled: !selected.length }} confirmLoading={saving}>
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
        选择你有权查看的可用图库图片。添加后只会进入项目相册，默认不标记为采纳；管理员可在项目图片中按需标注。
      </Typography.Paragraph>
      <Space wrap>
        <Input.Search allowClear placeholder="搜索图片标题、描述或标签" style={{ width: 260 }}
          value={searchText} onChange={event => setSearchText(event.target.value)}
          onSearch={keyword => update({ keyword })} />
        <TagSelect value={filters.tags} style={{ width: 220 }} placeholder="按标签筛选（回车添加）"
          onChange={input => {
            const tags = normalizeTags(input).filter(tag => !isTagTooLong(tag))
            update({ tags }, tags.length > 1 ? [] : ['tagMatch'])
          }} />
        {filters.tags.length > 1 && <Select value={filters.tagMatch ?? 'all'} style={{ width: 120 }}
          aria-label="多个标签的匹配方式" options={[
            { value: 'all', label: '同时包含' }, { value: 'any', label: '任意其一' },
          ]} onChange={(match: 'all' | 'any') => match === 'any' ? update({ tagMatch: 'any' }) : update({}, ['tagMatch'])} />}
        {photographerOptions.length > 1 && <Select mode="multiple" allowClear maxTagCount="responsive"
          optionFilterProp="label" style={{ width: 200 }} placeholder="按拍摄者筛选"
          options={photographerOptions} value={filters.photographers}
          onChange={(names: string[]) => update({ photographers: normalizePhotographerFilter(names) })} />}
        <DatePicker.RangePicker allowEmpty={[true, true]} placeholder={['拍摄日期起', '拍摄日期止']}
          value={[filters.takenFrom ? dayjs(filters.takenFrom) : null, filters.takenTo ? dayjs(filters.takenTo) : null]}
          onChange={values => {
            const from = values?.[0]?.format('YYYY-MM-DD')
            const to = values?.[1]?.format('YYYY-MM-DD')
            update({ ...(from ? { takenFrom: from } : {}), ...(to ? { takenTo: to } : {}) },
              [...(from ? [] : ['takenFrom' as const]), ...(to ? [] : ['takenTo' as const])])
          }} />
        <Select value={filters.adopted ?? ''} style={{ width: 120 }} aria-label="被引状态" options={[
          { value: '', label: '被引不限' }, { value: 'yes', label: '已被引用' }, { value: 'no', label: '未被引用' },
        ]} onChange={(value: PhotoLibraryFlag | '') => value ? update({ adopted: value }) : update({}, ['adopted'])} />
        {filtersActive && <Button type="link" onClick={() => {
          setSearchText('')
          setFilters({ ...DEFAULT_PHOTO_LIBRARY_FILTERS, pageSize: filters.pageSize })
        }}>清空筛选</Button>}
      </Space>
      <div className="library-selection-bar">
        <Typography.Text type="secondary">{filtersActive ? `筛选出 ${data.total} 张` : `图库共 ${data.total} 张`}</Typography.Text>
        <Button type="link" size="small" loading={selectingAll} disabled={!data.total}
          onClick={() => void selectAllFiltered()}>
          {filtersActive ? '全选筛选结果' : '全选'}（{Math.min(data.total, MAX_LIBRARY_SELECTION)}）
        </Button>
        {!!selected.length && <>
          <Typography.Text>已选 {selected.length} 张</Typography.Text>
          <Button type="link" size="small" onClick={() => setSelected([])}>清空选择</Button>
        </>}
      </div>
      {error && <Typography.Text type="danger">图库加载失败：{error}</Typography.Text>}
      <ContentFitTable<Photo> rowKey="id" size="small" loading={loading} dataSource={data.items}
        pagination={serverTablePagination(filters, data.total,
          (page, pageSize) => setFilters(turnPage(filters, page, pageSize)),
          { sizes: TABLE_PAGE_SIZES, size: 'small', showTotal: total => `共 ${total} 张` })}
        rowSelection={{
          // 服务端分页：没有 preserveSelectedRowKeys，翻页就会把别的页的勾选滤掉（见 src/rowSelection.ts）。
          preserveSelectedRowKeys: true,
          selectedRowKeys: selectedIds,
          getCheckboxProps: photo => ({ disabled: inThisProject(photo) }),
          onChange: (keys, rows) => {
            const wanted = new Set(keys.map(String))
            const kept = selected.filter(photo => wanted.has(String(photo.id)))
            const result = mergeById(kept, rows.filter(Boolean))
            setSelected(result.selected)
            if (result.truncated) message.warning(`单次最多选择 ${MAX_LIBRARY_SELECTION} 张，已选到上限`)
          },
        }}
        locale={{ emptyText: filtersActive ? '没有匹配的图库图片，放宽筛选条件再看看' : '图库里还没有可用图片' }}
        columns={[
          { title: '预览', width: 92, render: (_, photo) =>
            photo.thumbnailUrl ? <PreviewPhoto width={68} height={48} style={{ objectFit: 'contain' }}
              preview={false} src={photo.thumbnailUrl}
              refresh={() => refreshPhotoPreviewUrl(photo.id)}
              fallback={pickPlaceholderImage(placeholderImages, photo.id)} /> : '-' },
          { title: '图片', dataIndex: 'title', render: (value, photo) =>
            <div className="table-title"><strong>{value || '未命名图片'}</strong>
              <span>{photo.photographerName} · {dayjs(photo.takenAt).format('YYYY-MM-DD')}</span></div> },
          { title: '标签', dataIndex: 'tags', render: tags =>
            <Space size={4} wrap>{tags?.slice(0, 3).map((tag: string) => <Tag key={tag}>{tag}</Tag>)}</Space> },
          { title: '状态', width: 110, render: (_, photo) => inThisProject(photo)
            ? <Tag color="blue">已在本选题</Tag>
            : photo.adoptionCount ? <Tag color="gold">已被引 × {photo.adoptionCount}</Tag> : null },
        ]} />
    </Space>
  </Modal>
}
