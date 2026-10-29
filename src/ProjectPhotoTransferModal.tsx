import { Alert, App, Modal, Radio, Select, Space, Tag, Typography } from 'antd'
import { useEffect, useMemo, useState } from 'react'
import { api, emptyPage, qs } from './api'
import { useLoad } from './hooks'
import { planPhotoRemoval } from './projectPhotoRemoval'
import { describeTransferResult } from './projectPhotoTransfer'
import type { TransferMode, TransferPhotosResult } from './projectPhotoTransfer'
import type { PageData, Photo, Project } from './types'

/**
 * 把选题相册里勾选的图片复制或移动到另一个进行中的选题。
 *
 * 只动相册归属：图片文件不复制，采用记录不带走（进了目标选题默认未被引）。移动时本选题
 * 已采纳的、在本选题上传的移不走（规则同「移出选题」），这里提前把张数摆出来，
 * 最终以服务端结果为准——校区范围账号在详情页拿不到采用记录。
 */
export default function ProjectPhotoTransferModal({
  open, projectId, projectActive, photos, adoptedIds, onClose, onDone,
}: {
  open: boolean
  projectId: string
  /** 本选题进行中才能移动；结束了的选题只能把图复制出去。 */
  projectActive: boolean
  photos: Photo[]
  adoptedIds: ReadonlySet<string>
  onClose: () => void
  onDone: (mode: TransferMode) => void
}) {
  const { message } = App.useApp()
  const [mode, setMode] = useState<TransferMode>('COPY')
  // 记下选中的整项而不只是 id：选完再搜别的词，它会从候选里消失，下拉框和成功提示都还要它的标题。
  const [target, setTarget] = useState<Pick<Project, 'id' | 'title' | 'type'> | null>(null)
  const [search, setSearch] = useState('')
  const [keyword, setKeyword] = useState('')
  const [saving, setSaving] = useState(false)

  // 输入停一下再查，免得每敲一个字发一次请求。
  useEffect(() => {
    const timer = window.setTimeout(() => setKeyword(search.trim()), 300)
    return () => window.clearTimeout(timer)
  }, [search])

  const { data: candidates, loading } = useLoad(
    () => open
      ? api<PageData<Project>>({ url: '/projects', params: qs({ page: 1, pageSize: 50, status: 'ACTIVE', keyword }) })
      : Promise.resolve(emptyPage<Project>()),
    emptyPage<Project>(),
    [open, keyword],
  )
  const options = useMemo(() => {
    const items = candidates.items
      .filter(project => String(project.id) !== String(projectId))
      .map(project => ({ value: String(project.id), label: project.title, type: project.type }))
    if (target && !items.some(item => item.value === String(target.id))) {
      items.unshift({ value: String(target.id), label: target.title, type: target.type })
    }
    return items
  }, [candidates.items, projectId, target])

  const plan = useMemo(() => planPhotoRemoval(photos, projectId, adoptedIds), [photos, projectId, adoptedIds])
  const keptCount = plan.adopted.length + plan.uploadedHere.length

  const submit = async () => {
    if (!target || !photos.length) return
    setSaving(true)
    try {
      const result = await api<TransferPhotosResult>({
        method: 'POST',
        url: `/projects/${projectId}/photos/transfer`,
        data: { targetProjectId: target.id, mode, photoIds: photos.map(photo => photo.id) },
      })
      message.success(describeTransferResult(result, mode, target.title))
      onDone(mode)
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setSaving(false)
    }
  }

  return <Modal title={`把 ${photos.length} 张图片复制或移动到其他选题`} open={open} width={560}
    onCancel={() => { if (!saving) onClose() }} onOk={() => void submit()} confirmLoading={saving}
    okText={mode === 'MOVE' ? '确认移动' : '确认复制'} okButtonProps={{ disabled: !target }} destroyOnHidden>
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <div>
        <Typography.Text strong>目标选题</Typography.Text>
        <Select style={{ width: '100%', marginTop: 8 }} showSearch filterOption={false} allowClear
          placeholder="搜索进行中的选题" value={target ? String(target.id) : undefined} loading={loading}
          onSearch={setSearch}
          onChange={(value?: string) => {
            const picked = options.find(option => option.value === value)
            setTarget(picked ? { id: picked.value, title: picked.label, type: picked.type } : null)
          }}
          notFoundContent={loading ? '正在加载…' : '没有可选的进行中选题'}
          options={options}
          optionRender={option => <Space>
            <span>{option.data.label}</span>
            {option.data.type === 'EVENT' && <Tag color="purple" variant="filled">活动选题</Tag>}
          </Space>} />
      </div>
      <div>
        <Typography.Text strong>方式</Typography.Text>
        <Radio.Group style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 8 }}
          value={mode} onChange={event => setMode(event.target.value)}>
          <Radio value="COPY">复制：图片同时出现在两个选题里</Radio>
          <Radio value="MOVE" disabled={!projectActive}>
            移动：放进目标选题，并从本选题移出{!projectActive && '（本选题已结束，只能复制）'}
          </Radio>
        </Radio.Group>
      </div>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
        只改变图片属于哪些选题，不会复制图片文件；采纳记录不会带过去，图片进入目标选题后默认未被引。
      </Typography.Paragraph>
      {mode === 'MOVE' && keptCount > 0 && <Alert type="info" showIcon
        message={`其中 ${[
          plan.adopted.length ? `${plan.adopted.length} 张在本选题已采纳` : '',
          plan.uploadedHere.length ? `${plan.uploadedHere.length} 张是在本选题上传的` : '',
        ].filter(Boolean).join('、')}`}
        description="这些图片会复制到目标选题，但仍保留在本选题：已采纳的要先取消采纳才能移走；在本选题上传的始终以本选题为来源。" />}
    </Space>
  </Modal>
}
