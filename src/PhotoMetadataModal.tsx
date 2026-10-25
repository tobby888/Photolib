import { Alert, App, Checkbox, Col, DatePicker, Form, Input, Modal, Row, Select } from 'antd'
import type { Dayjs } from 'dayjs'
import dayjs from 'dayjs'
import { useState } from 'react'
import { api } from './api'
import { PhotographerFields, useAdminMetadataOptions } from './PhotographerFields'
import { PHOTO_METADATA_LIMITS, photographerChanged } from './photoMetadata'
import { normalizeTags, tagRules } from './photoTags'
import TagSelect from './TagSelect'
import type { EntityId, Photo } from './types'

interface FormValues {
  title: string
  takenAt: Dayjs
  photographerName: string
  photographerStudentId?: string
  campusId?: EntityId | null
  tags?: string[]
  description?: string
  syncAdoptions: boolean
}

/** 管理员直接改一张图片的基本信息（`PUT /photos/{id}/admin-metadata`）。 */
export default function PhotoMetadataModal({ photo, open, onClose, onSaved }: {
  photo: Photo
  open: boolean
  onClose: () => void
  onSaved: (photo: Photo) => void
}) {
  const { message } = App.useApp()
  const [form] = Form.useForm<FormValues>()
  const [saving, setSaving] = useState(false)
  const options = useAdminMetadataOptions(open)
  const name = Form.useWatch('photographerName', form)
  const studentId = Form.useWatch('photographerStudentId', form)
  const askSync = !!photo.adoptionCount && photographerChanged(photo, name, studentId)


  const submit = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const saved = await api<Photo>({
        method: 'PUT',
        url: `/photos/${photo.id}/admin-metadata`,
        data: {
          title: values.title.trim(),
          description: values.description ?? '',
          photographerName: values.photographerName.trim(),
          photographerStudentId: (values.photographerStudentId ?? '').trim(),
          takenAt: values.takenAt.format('YYYY-MM-DDTHH:mm:ss'),
          campusId: values.campusId ?? null,
          tags: normalizeTags(values.tags),
          version: photo.version,
          syncAdoptions: askSync ? values.syncAdoptions : false,
        },
      })
      message.success('图片信息已更新')
      onSaved(saved)
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setSaving(false)
    }
  }

  return <Modal title="编辑图片信息" width={680} open={open}
    onCancel={() => { if (!saving) onClose() }} onOk={() => void submit()}
    okText="保存" confirmLoading={saving} mask={{ closable: !saving }} closable={!saving}
    cancelButtonProps={{ disabled: saving }} destroyOnHidden>
    <Form form={form} layout="vertical" requiredMark={false} initialValues={{
      title: photo.title,
      takenAt: dayjs(photo.takenAt),
      photographerName: photo.photographerName,
      photographerStudentId: photo.photographerStudentId,
      campusId: photo.campusId ?? null,
      tags: photo.tags ?? [],
      description: photo.description ?? '',
      syncAdoptions: true,
    }}>
      <Row gutter={16}>
        <Col xs={24} sm={14}>
          <Form.Item label="图片标题" name="title" rules={[
            { required: true, whitespace: true, message: '请填写图片标题' },
            { max: PHOTO_METADATA_LIMITS.title, message: `不能超过 ${PHOTO_METADATA_LIMITS.title} 个字` },
          ]}>
            <Input />
          </Form.Item>
        </Col>
        <Col xs={24} sm={10}>
          <Form.Item label="拍摄时间" name="takenAt" rules={[{ required: true, message: '请选择拍摄时间' }]}>
            <DatePicker showTime style={{ width: '100%' }} disabledDate={date => date.isAfter(dayjs(), 'day')} />
          </Form.Item>
        </Col>
      </Row>
      <PhotographerFields members={options.members} loading={options.membersLoading} />
      {askSync && <Form.Item name="syncAdoptions" valuePropName="checked"
        extra={`这张图有 ${photo.adoptionCount} 条被引记录。成员核算、被引排行按被引记录上的拍摄者算；`
          + '不勾选的话这些记录仍算在原拍摄者名下。'}>
        <Checkbox>同时更正已有被引记录上的拍摄者</Checkbox>
      </Form.Item>}
      <Form.Item label="校区" name="campusId" extra="清空表示不属于任何校区">
        <Select allowClear loading={options.campusesLoading} options={options.campusOptions}
          placeholder="无校区" />
      </Form.Item>
      <Form.Item label="标签" name="tags" rules={tagRules}
        extra={photo.projectId ? '通过选题上传的图片，新加的标签仍须来自该选题的预设标签。' : undefined}>
        <TagSelect />
      </Form.Item>
      <Form.Item label="图片说明" name="description" rules={[
        { max: PHOTO_METADATA_LIMITS.description, message: `不能超过 ${PHOTO_METADATA_LIMITS.description} 个字` },
      ]}>
        <Input.TextArea rows={3} />
      </Form.Item>
      {photo.status === 'ARCHIVED' && <Alert type="info" showIcon title="这张图片已归档，修改后仍保持归档状态。" />}
    </Form>
  </Modal>
}
