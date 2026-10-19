import { App, DatePicker, Form, Input, InputNumber, Modal, Space, Typography } from 'antd'
import { useState } from 'react'
import dayjs from 'dayjs'
import { api } from './api'
import { StatusTag } from './components'
import type { Worklog } from './types'

/**
 * 管理员更正一条已经报上来的工时：姓名、学号、工作日期、两项时长和说明（`PUT /worklogs/{id}/correction`，
 * 后端只放行权限组 ADMIN）。状态不变，姓名学号直接按填的保存，不对照通讯录。
 *
 * 只在打开时挂载：每次打开都是一份新的表单实例，按这一行当前的值起步。共用一个 `useForm`
 * 的话，rc-field-form 重新挂载时会让上一次留在 store 里的值盖过新的 initialValues。
 */
export default function WorklogCorrectionModal({ worklog, currentUserId, onClose, onSaved }: {
  worklog: Worklog
  currentUserId?: Worklog['userId']
  onClose: () => void
  onSaved: () => void | Promise<void>
}) {
  const { message } = App.useApp()
  const [form] = Form.useForm()
  const [saving, setSaving] = useState(false)

  const save = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      await api({
        method: 'PUT', url: `/worklogs/${worklog.id}/correction`,
        data: { ...values, workDate: values.workDate.format('YYYY-MM-DD'), version: worklog.version },
      })
      message.success(worklog.userId === currentUserId ? '工时已修改' : '工时已修改，填报账号会收到通知')
      onClose()
      await onSaved()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  return <Modal title="修改工时" open onCancel={onClose} onOk={() => void save()} okText="保存修改"
    confirmLoading={saving}>
    <Typography.Paragraph type="secondary">
      {worklog.shareLinkId
        ? `通过上传链接申报 · ${worklog.projectTitle || '已删除的选题'}`
        : `需求：${worklog.requestTitle || '—'}`}
      。状态保持 <StatusTag value={worklog.status} /> 不变，已确认的改完立即按新值计入统计；
      姓名和学号直接按填写的保存，不再对照通讯录。
    </Typography.Paragraph>
    <Form form={form} layout="vertical" requiredMark={false} initialValues={{
      memberName: worklog.memberName, memberStudentId: worklog.memberStudentId,
      workDate: dayjs(worklog.workDate), shootingMinutes: worklog.shootingMinutes,
      retouchingMinutes: worklog.retouchingMinutes, remark: worklog.remark || '',
    }}>
      <Space size={16} align="start" className="form-grid">
        <Form.Item label="姓名" name="memberName"
          rules={[{ required: true, whitespace: true, message: '请填写姓名' }, { max: 100, message: '姓名最多 100 个字' }]}>
          <Input placeholder="工作人员姓名" />
        </Form.Item>
        <Form.Item label="学号" name="memberStudentId" extra="只能包含字母、数字、下划线或连字符"
          rules={[{ required: true, whitespace: true, message: '请填写学号' }, { max: 64, message: '学号最多 64 位' }]}>
          <Input placeholder="学号" />
        </Form.Item>
      </Space>
      <Form.Item label="工作日期" name="workDate" rules={[{ required: true, message: '请选择工作日期' }]}>
        <DatePicker maxDate={dayjs()} allowClear={false} style={{ width: '100%' }} />
      </Form.Item>
      <Space size={16} align="start" className="form-grid">
        <Form.Item label="拍摄时长" name="shootingMinutes" rules={[{ required: true, message: '请填写拍摄时长' }]}>
          <InputNumber min={0} max={1440} precision={0} suffix="分钟" />
        </Form.Item>
        <Form.Item label="修图时长" name="retouchingMinutes" rules={[{ required: true, message: '请填写修图时长' }]}>
          <InputNumber min={0} max={1440} precision={0} suffix="分钟" />
        </Form.Item>
      </Space>
      <Form.Item label="工作说明" name="remark" rules={[{ max: 1000, message: '说明最多 1000 字' }]}>
        <Input.TextArea rows={3} />
      </Form.Item>
    </Form>
  </Modal>
}
