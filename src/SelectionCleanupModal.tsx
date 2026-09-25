import { App, Alert, Button, Empty, Modal, Radio, Space, Typography } from 'antd'
import { DeleteOutlined } from '@ant-design/icons'
import { useMemo, useState } from 'react'
import dayjs from 'dayjs'
import { api, qs } from './api'
import type { Photo, Project, SelectionCleanupPlan, SelectionCleanupResult } from './types'
import ProjectPhotoFilterBar from './ProjectPhotoFilterBar'
import { PreviewPhotoImg } from './PreviewPhoto'
import {
  DEPRECATED_TAG, DEPRECATED_TAG_LABEL, collectPhotographers, collectTagOptions, emptyProjectPhotoFilters,
  hasActiveFilters,
} from './photoTags'
import type { ProjectPhotoFilters } from './photoTags'

export type SelectionCleanupMode = 'complete' | 'cleanup'

interface SelectionCleanupModalProps {
  open: boolean
  /**
   * `complete`：结束选题时打开，先选「只完成」还是「完成并删除」，删除要在完成之后才执行；
   * `cleanup`：选题已经完成，直接选筛选条件删除。
   */
  mode: SelectionCleanupMode
  project: Project
  /** 选题相册里的图片，只用来给筛选下拉提供候选；真正命中哪些由后端按同一套规则算。 */
  photos: readonly Photo[]
  onCancel: () => void
  /** 把选题标记为已完成。出错直接抛出，弹窗会停在原地。 */
  onComplete: () => Promise<void>
  /** 流程走完（不论删没删）之后刷新页面。 */
  onFinished: () => Promise<void> | void
}

const tagLabel = (tag: string) => tag.toLowerCase() === DEPRECATED_TAG ? DEPRECATED_TAG_LABEL : tag

/** 筛选条件原样发给后端；多选发成 `tags=a&tags=b`，空条件不发。 */
const filterParams = (filters: ProjectPhotoFilters) => qs({
  ...filters,
  tags: filters.tags.length ? filters.tags : undefined,
  photographers: filters.photographers.length ? filters.photographers : undefined,
})

/**
 * 活动选题结束时的图片清理。
 *
 * 两步：先在和选题详情页同一排的筛选器里定下「删哪些」，再弹出确认框，把后端算出的
 * 数量和其中一部分图片摆出来，让人看清楚再删。默认条件是「不可用」标签，也就是
 * 选片人标掉的那些，负责人可以在这里改成任何组合。
 *
 * 已被任何选题采用的图片永远不删（后端的硬规则，确认框里会列出跳过了几张）。
 * 确认框里的结果带一个指纹，点确认时原样带回：预览之后有人改了标签、采用了图片，
 * 后端会拒绝这次删除，要求重新预览，而不是删掉预览里没出现的图。
 */
export default function SelectionCleanupModal({
  open, mode, project, photos, onCancel, onComplete, onFinished,
}: SelectionCleanupModalProps) {
  const { message } = App.useApp()
  const [removePhotos, setRemovePhotos] = useState(mode === 'cleanup')
  const [filters, setFilters] = useState<ProjectPhotoFilters>(
    { ...emptyProjectPhotoFilters, tags: [DEPRECATED_TAG] })
  const [plan, setPlan] = useState<SelectionCleanupPlan | null>(null)
  const [planning, setPlanning] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  // 「不可用」总是排在最前面：这是最常用的条件，哪怕眼下还没有一张图被标上。
  const tagOptions = useMemo(() => {
    const collected = collectTagOptions(project.tags, photos)
    return [DEPRECATED_TAG, ...collected.filter(tag => tag.toLowerCase() !== DEPRECATED_TAG)]
  }, [photos, project.tags])
  const photographerOptions = useMemo(() => collectPhotographers(photos), [photos])
  const filtersActive = hasActiveFilters(filters)
  const completing = mode === 'complete'

  const finish = async () => {
    setPlan(null)
    onCancel()
    await onFinished()
  }

  const completeOnly = async () => {
    setSubmitting(true)
    try {
      await onComplete()
      message.success('选题已完成')
      await finish()
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  const preview = async () => {
    setPlanning(true)
    try {
      setPlan(await api<SelectionCleanupPlan>({
        url: `/projects/${project.id}/selection/cleanup`,
        params: filterParams(filters),
        // Spring 的 List 参数只认 tags=a&tags=b；axios 默认的 tags[]=a 绑不上。
        paramsSerializer: { indexes: null },
      }))
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setPlanning(false)
    }
  }

  const confirmDelete = async () => {
    if (!plan) return
    setSubmitting(true)
    let completed = false
    try {
      if (completing) {
        await onComplete()
        completed = true
      }
      const result = await api<SelectionCleanupResult>({
        method: 'POST',
        url: `/projects/${project.id}/selection/cleanup`,
        data: { ...filterParams(filters), planToken: plan.planToken },
      })
      message.success(`${completing ? '选题已完成，' : ''}已删除 ${result.deletedCount} 张图片`
        + (result.skippedAdoptedCount ? `，跳过已被采用的 ${result.skippedAdoptedCount} 张` : ''))
      await finish()
    } catch (reason) {
      const text = (reason as Error).message
      if (completed) {
        // 选题已经完成了，只是删除没做成：不能停在「完成选题」的弹窗里让人再点一次完成。
        message.error(`选题已完成，但图片没有删除：${text}。可以在选题页用「清理图片」重新筛选后删除`)
        await finish()
      } else {
        message.error(text)
      }
    } finally {
      setSubmitting(false)
    }
  }

  const filterFooter = <Space>
    <Button onClick={onCancel} disabled={submitting}>取消</Button>
    {completing && !removePhotos
      ? <Button type="primary" loading={submitting} onClick={() => void completeOnly()}>确认完成</Button>
      : <Button type="primary" danger icon={<DeleteOutlined />} loading={planning}
          disabled={!filtersActive} onClick={() => void preview()}>预览要删除的图片</Button>}
  </Space>

  const deletable = plan?.deletableCount || 0
  const confirmFooter = plan && <Space wrap>
    <Button onClick={() => setPlan(null)} disabled={submitting}>返回修改筛选</Button>
    {!deletable && completing &&
      <Button type="primary" loading={submitting} onClick={() => void completeOnly()}>只完成选题</Button>}
    {!!deletable && <Button type="primary" danger loading={submitting} onClick={() => void confirmDelete()}>
      {completing ? `完成选题并删除 ${deletable} 张` : `确认删除 ${deletable} 张`}</Button>}
  </Space>

  return <>
    <Modal open={open && !plan} width={760} destroyOnHidden
      title={completing ? '完成选题' : '清理选题图片'}
      onCancel={submitting ? undefined : onCancel} footer={filterFooter}>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        {completing && <Radio.Group value={removePhotos} onChange={event => setRemovePhotos(event.target.value)}
          options={[
            { value: false, label: '只完成选题，保留全部图片' },
            { value: true, label: '完成选题，并删除按条件筛选出的图片' },
          ]} style={{ display: 'flex', flexDirection: 'column', gap: 8 }} />}
        {removePhotos && <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
            筛选条件和选题相册的筛选一致：标签同时包含、拍摄日期含首尾两天、拍摄者任选其一、
            被引指在本选题中被采用。默认只删标记为「{DEPRECATED_TAG_LABEL}」的图片。
            下一步会先列出命中的图片，确认后才会删除。
          </Typography.Paragraph>
          <ProjectPhotoFilterBar value={filters} onChange={setFilters}
            tagOptions={tagOptions} photographerOptions={photographerOptions} tagLabel={tagLabel} />
          {!filtersActive && <Alert type="warning" showIcon
            message="请至少设置一个筛选条件。不设条件等于整个相册，这里不允许这样删除。" />}
          <Alert type="info" showIcon
            message="已被任何选题采用的图片始终保留，不论筛选条件怎么选。" />
        </>}
      </Space>
    </Modal>

    <Modal open={open && !!plan} width={880} destroyOnHidden
      title={deletable ? `确认删除这 ${deletable} 张图片？` : '没有可删除的图片'}
      onCancel={submitting ? undefined : () => setPlan(null)} footer={confirmFooter}>
      {plan && <Space direction="vertical" size={16} style={{ width: '100%' }}>
        {deletable
          ? <Alert type="error" showIcon message={<>
              将永久删除 <strong>{deletable}</strong> 张图片，包括它们在对象存储里的原图、成品图和预览图。
              <strong>此操作不可撤销。</strong>
            </>} />
          : <Empty description={plan.adoptedSkippedCount
              ? `符合条件的 ${plan.adoptedSkippedCount} 张图片都已被采用，不会删除`
              : '没有图片符合这些筛选条件'} />}
        {!!deletable && !!plan.adoptedSkippedCount && <Typography.Text type="secondary">
          另有 {plan.adoptedSkippedCount} 张符合条件，但已经被采用，会保留。</Typography.Text>}
        {!!plan.samples.length && <>
          <Typography.Text type="secondary">
            {plan.samples.length < deletable
              ? `以下是从中均匀挑出的 ${plan.samples.length} 张，请确认没有要保留的图片：`
              : '以下是全部将被删除的图片：'}
          </Typography.Text>
          <div className="cleanup-sample-grid">
            {plan.samples.map(sample => <figure key={sample.id} className="cleanup-sample">
              {sample.thumbnailUrl
                ? <PreviewPhotoImg src={sample.thumbnailUrl} alt={sample.title || '待删除的图片'} />
                : <span className="cleanup-sample-empty">无预览</span>}
              <figcaption>
                <span className="cleanup-sample-title">{sample.title || '未命名图片'}</span>
                <span>{sample.photographerName} · {dayjs(sample.takenAt).format('MM-DD HH:mm')}</span>
                {!!sample.tags.length && <span>{sample.tags.map(tagLabel).join('、')}</span>}
              </figcaption>
            </figure>)}
          </div>
        </>}
        {completing && !!deletable && <Typography.Text type="secondary">
          确认后会先把选题标记为已完成，再删除这些图片。</Typography.Text>}
      </Space>}
    </Modal>
  </>
}
