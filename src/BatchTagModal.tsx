import { App, Form, Modal, Typography } from 'antd'
import { useState } from 'react'
import { api } from './api'
import type { EntityId, TaggedPhoto } from './types'
import TagSelect from './TagSelect'
import { runInChunks } from './librarySelection'
import { normalizeTags, tagRules, tagsOnPhotos } from './photoTags'
import type { TaggablePhoto } from './photoTags'

export type BatchTagMode = 'add' | 'remove'

interface BatchTagModalProps {
  /** null 表示关闭。 */
  mode: BatchTagMode | null
  photos: (TaggablePhoto & { id: EntityId })[]
  /** 在选题详情页里操作时传入：后端据此校验图片确实在该选题中、并套用它的预设标签。 */
  projectId?: EntityId
  /**
   * 候选标签（预设 + 已用过的）。在选题里操作（传了 projectId）时只是候选，可以输入新标签；
   * 图库里操作时非空表示来源选题有预设，「添加」只能从中选择。 */
  presets?: string[]
  onClose: () => void
  onDone: (result: TaggedPhoto[]) => void
}

export default function BatchTagModal({ mode, photos, projectId, presets = [], onClose, onDone }: BatchTagModalProps) {
  const { message } = App.useApp()
  const [form] = Form.useForm<{ tags?: string[] }>()
  const [saving, setSaving] = useState(false)
  const restricted = mode === 'add' && !projectId && presets.length > 0
  const removable = tagsOnPhotos(photos)

  const submit = async () => {
    const values = await form.validateFields()
    const tags = normalizeTags(values.tags)
    if (!tags.length) {
      message.warning('请至少选择一个标签')
      return
    }
    setSaving(true)
    try {
      // 图库「筛选结果全选」能选上几百张，而后端一次最多改 200 张：按批顺序发。
      // 中途失败时前面几批已经改好了，照样回传给页面，并如实说明还剩多少没改。
      const result: TaggedPhoto[] = []
      const { error } = await runInChunks(photos.map(photo => photo.id), async batch => {
        result.push(...await api<TaggedPhoto[]>({
          method: 'POST',
          url: '/photos/batch-tags',
          data: {
            photoIds: batch,
            addTags: mode === 'add' ? tags : [],
            removeTags: mode === 'remove' ? tags : [],
            projectId: projectId ?? null,
          },
        }))
      })
      if (error) {
        message.error(result.length
          ? `已处理 ${result.length} 张，其余 ${photos.length - result.length} 张没有改动：${error.message}`
          : error.message)
        if (result.length) onDone(result)
        return
      }
      message.success(mode === 'add'
        ? `已为 ${result.length} 张图片添加标签`
        : `已从 ${result.length} 张图片移除标签`)
      onDone(result)
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setSaving(false)
    }
  }

  return <Modal
    title={mode === 'remove' ? `批量移除标签（${photos.length} 张）` : `批量添加标签（${photos.length} 张）`}
    open={!!mode}
    onCancel={() => { if (!saving) onClose() }}
    onOk={() => void submit()}
    okText={mode === 'remove' ? '移除所选标签' : '添加标签'}
    okButtonProps={{ danger: mode === 'remove' }}
    confirmLoading={saving}
    mask={{ closable: !saving }}
    closable={!saving}
    cancelButtonProps={{ disabled: saving }}
    afterClose={() => form.resetFields()}>
    <Typography.Paragraph type="secondary">
      {mode === 'remove'
        ? '从所选图片上移除这些标签；没有该标签的图片保持不变。'
        : restricted
          ? '这个选题设置了预设标签，只能从预设中选择。图片上已有的其他标签会保留。'
          : projectId
            ? '可以从预设标签和相册里已有的标签中选，也可以输入新标签，回车添加。图片上已有的标签会保留。'
            : '输入要添加的标签，图片上已有的标签会保留。通过需求上传的图片仍只能添加其所属选题的预设标签。'}
    </Typography.Paragraph>
    <Form form={form} layout="vertical" requiredMark={false}>
      <Form.Item name="tags" label={mode === 'remove' ? '要移除的标签' : '要添加的标签'}
        rules={[{ required: true, message: '请至少选择一个标签' }, ...tagRules]}>
        {mode === 'remove'
          ? <TagSelect restricted presets={removable}
              placeholder={removable.length ? '选择要移除的标签' : '所选图片还没有标签'} />
          : <TagSelect restricted={restricted} presets={presets}
              placeholder={restricted ? '从选题预设标签中选择' : '选择已有标签，或输入新标签后回车'} />}
      </Form.Item>
    </Form>
  </Modal>
}
