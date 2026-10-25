import { Alert, App, Checkbox, Form, Input, Modal, Select, Typography } from 'antd'
import { useState } from 'react'
import { api } from './api'
import { BATCH_REQUEST_LIMIT, runInChunks } from './librarySelection'
import { PhotographerFields, useAdminMetadataOptions } from './PhotographerFields'
import {
  BATCH_METADATA_FIELDS, PHOTO_METADATA_LIMITS, batchMetadataPayload, describeBatchMetadataResult,
} from './photoMetadata'
import type { BatchMetadataField, BatchMetadataResult, BatchMetadataValues } from './photoMetadata'
import type { EntityId } from './types'

/**
 * 管理员批量改图片基本信息（`POST /photos/batch-metadata`）。只改勾选了的那几项；
 * 选得多时按后端上限 200 张一批顺序发，中途失败如实告诉用户改了几张。
 */
export default function BatchMetadataModal({ open, photos, onClose, onDone }: {
  open: boolean
  photos: { id: EntityId; adoptionCount?: number }[]
  onClose: () => void
  /** 只要有一批成功就会调用，页面据此刷新列表。 */
  onDone: () => void
}) {
  const { message } = App.useApp()
  const [form] = Form.useForm<BatchMetadataValues>()
  const [saving, setSaving] = useState(false)
  const options = useAdminMetadataOptions(open)
  const fields = (Form.useWatch('fields', form) ?? []) as BatchMetadataField[]
  const adoptedCount = photos.filter(photo => photo.adoptionCount).length

  const submit = async () => {
    const values = await form.validateFields()
    if (!values.fields?.length) {
      message.warning('请至少勾选一项要修改的信息')
      return
    }
    setSaving(true)
    try {
      const results: BatchMetadataResult[] = []
      const { done, error } = await runInChunks(photos.map(photo => photo.id), async batch => {
        results.push(await api<BatchMetadataResult>({
          method: 'POST', url: '/photos/batch-metadata', data: batchMetadataPayload(batch, values),
        }))
      })
      if (error) {
        message.error(done
          ? `${describeBatchMetadataResult(results)}；其余 ${photos.length - done} 张没有改动：${error.message}`
          : error.message)
        if (done) onDone()
        return
      }
      message.success(describeBatchMetadataResult(results))
      onDone()
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setSaving(false)
    }
  }

  return <Modal title={`批量编辑图片信息（${photos.length} 张）`} width={680} open={open}
    onCancel={() => { if (!saving) onClose() }} onOk={() => void submit()}
    okText={`修改 ${photos.length} 张图片`} okButtonProps={{ disabled: !fields.length }}
    confirmLoading={saving} mask={{ closable: !saving }} closable={!saving}
    cancelButtonProps={{ disabled: saving }} destroyOnHidden>
    <Typography.Paragraph type="secondary">
      勾选要改的项，所选图片的这几项会被改成同一个值；没勾的项保持原样。
      标签请用「添加标签 / 移除标签」。
    </Typography.Paragraph>
    <Form form={form} layout="vertical" requiredMark={false} initialValues={{ fields: [], syncAdoptions: true }}>
      <Form.Item label="要修改的信息" name="fields">
        <Checkbox.Group options={BATCH_METADATA_FIELDS} />
      </Form.Item>
      {fields.includes('photographer') && <>
        <PhotographerFields members={options.members} loading={options.membersLoading} />
        <Form.Item name="syncAdoptions" valuePropName="checked"
          extra={adoptedCount
            ? `所选图片中有 ${adoptedCount} 张已被引用。成员核算、被引排行按被引记录上的拍摄者算；不勾选的话仍算在原拍摄者名下。`
            : '所选图片目前都没有被引记录，勾不勾都一样。'}>
          <Checkbox>同时更正已有被引记录上的拍摄者</Checkbox>
        </Form.Item>
      </>}
      {fields.includes('campus') && <Form.Item label="校区" name="campusId" extra="清空表示改成不属于任何校区">
        <Select allowClear loading={options.campusesLoading} options={options.campusOptions} placeholder="无校区" />
      </Form.Item>}
      {fields.includes('title') && <Form.Item label="图片标题" name="title" rules={[
        { required: true, whitespace: true, message: '请填写图片标题' },
        { max: PHOTO_METADATA_LIMITS.title, message: `不能超过 ${PHOTO_METADATA_LIMITS.title} 个字` },
      ]}>
        <Input />
      </Form.Item>}
      {fields.includes('description') && <Form.Item label="图片说明" name="description" extra="留空表示清空说明" rules={[
        { max: PHOTO_METADATA_LIMITS.description, message: `不能超过 ${PHOTO_METADATA_LIMITS.description} 个字` },
      ]}>
        <Input.TextArea rows={3} />
      </Form.Item>}
      {photos.length > BATCH_REQUEST_LIMIT && <Alert type="info" showIcon
        title={`将按每批 ${BATCH_REQUEST_LIMIT} 张分 ${Math.ceil(photos.length / BATCH_REQUEST_LIMIT)} 批提交，中途出错时前面的批次已经生效。`} />}
    </Form>
  </Modal>
}
