import { Alert, App, DatePicker, Form, Input, InputNumber, Modal, Select, Space, Typography } from 'antd'
import { useEffect, useMemo, useState } from 'react'
import dayjs from 'dayjs'
import { api } from './api'
import type { PageData, PhotoRequest, Project, Worklog } from './types'

const STATUS_OPTIONS: { value: Worklog['status']; label: string }[] = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'SUBMITTED', label: '待确认' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'REJECTED', label: '已退回' },
]

/** 分页取完一个列表接口（每页 100 条）。管理员看得到全部需求 / 选题，一个部门的量级。 */
async function loadAll<T>(url: string): Promise<T[]> {
  const items: T[] = []
  for (let page = 1; ; page++) {
    const result = await api<PageData<T>>({ url, params: { page, pageSize: 100 } })
    items.push(...result.items)
    if (result.items.length === 0 || items.length >= Number(result.total)) return items
  }
}

/**
 * 管理员直接修改一条工时（`PUT /worklogs/{id}/correction`，后端只放行权限组 ADMIN）：姓名、学号、工作日期、
 * 两项时长、说明，以及状态（四种任选）、关联需求（全站任意一条）和自定义选题（手写）。
 * 姓名学号直接按填的保存，不对照通讯录。
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
  const [requests, setRequests] = useState<PhotoRequest[]>([])
  const [projects, setProjects] = useState<Project[]>([])
  const [optionsLoading, setOptionsLoading] = useState(true)
  const status = Form.useWatch('status', form) as Worklog['status'] | undefined
  const viaShareLink = Boolean(worklog.shareLinkId)

  useEffect(() => {
    let alive = true
    Promise.all([loadAll<PhotoRequest>('/requests'), loadAll<Project>('/projects')])
      .then(([requestItems, projectItems]) => {
        if (!alive) return
        setRequests(requestItems)
        setProjects(projectItems)
      })
      .catch(e => { if (alive) message.error(`需求列表加载失败：${(e as Error).message}`) })
      .finally(() => { if (alive) setOptionsLoading(false) })
    return () => { alive = false }
  }, [message])

  // 下拉显示「选题 / 需求」：同名需求很多，带上选题才分得清，也能按选题名搜。
  const requestOptions = useMemo(() => {
    const projectTitles = new Map(projects.map(project => [String(project.id), project.title]))
    const options = requests.map(request => ({
      value: String(request.id),
      label: `${projectTitles.get(String(request.projectId)) ?? '未知选题'} / ${request.title}`,
    }))
    // 当前挂的需求已被删除时列表里没有它，留一个占位，下拉才不显示成一串数字。
    if (worklog.requestId && !options.some(option => option.value === String(worklog.requestId))) {
      options.unshift({ value: String(worklog.requestId), label: worklog.requestTitle || `需求 #${worklog.requestId}` })
    }
    return options
  }, [projects, requests, worklog.requestId, worklog.requestTitle])

  const save = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      await api({
        method: 'PUT', url: `/worklogs/${worklog.id}/correction`,
        data: {
          memberName: values.memberName,
          memberStudentId: values.memberStudentId,
          workDate: values.workDate.format('YYYY-MM-DD'),
          shootingMinutes: values.shootingMinutes,
          retouchingMinutes: values.retouchingMinutes,
          remark: values.remark,
          status: values.status,
          requestId: values.requestId ? Number(values.requestId) : null,
          // 链接申报的工时可以不挂需求；后端把 requestId 为空理解成「不改」，清掉要明说。
          clearRequest: viaShareLink && !values.requestId,
          // 空串 = 清掉自定义选题（null 才是「不改」）。
          customProjectTitle: values.customProjectTitle ?? '',
          rejectReason: values.status === 'REJECTED' ? values.rejectReason : null,
          version: worklog.version,
        },
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
    confirmLoading={saving} width={620}>
    <Typography.Paragraph type="secondary">
      {viaShareLink
        ? `通过上传链接申报 · ${worklog.projectTitle || '已删除的选题'}${worklog.shareLinkName ? ` · ${worklog.shareLinkName}` : ''}。`
        : null}
      已确认的改完立即按新值计入统计；姓名和学号直接按填写的保存，不再对照通讯录。
    </Typography.Paragraph>
    <Form form={form} layout="vertical" requiredMark={false} initialValues={{
      memberName: worklog.memberName, memberStudentId: worklog.memberStudentId,
      workDate: dayjs(worklog.workDate), shootingMinutes: worklog.shootingMinutes,
      retouchingMinutes: worklog.retouchingMinutes, remark: worklog.remark || '',
      status: worklog.status, rejectReason: worklog.rejectReason || '',
      requestId: worklog.requestId ? String(worklog.requestId) : undefined,
      customProjectTitle: worklog.customProjectTitle || '',
    }}>
      <Form.Item label="状态" name="status" rules={[{ required: true, message: '请选择状态' }]}
        extra="改成已确认 / 已退回时审核人记为你；改回待确认 / 草稿会清掉审核人和退回原因。">
        <Select options={STATUS_OPTIONS} />
      </Form.Item>
      {status === 'REJECTED' && <Form.Item label="退回原因" name="rejectReason"
        rules={[{ max: 500, message: '退回原因最多 500 字' }]} extra="不填时记为「管理员修改为已退回」">
        <Input.TextArea rows={2} placeholder="退回原因（选填）" />
      </Form.Item>}
      <Form.Item label="关联需求" name="requestId"
        rules={viaShareLink ? [] : [{ required: true, message: '站内填报的工时必须关联一条需求' }]}
        extra="可以选全站任意一条需求，不限校区、参与人和选题是否结束。">
        <Select showSearch allowClear={viaShareLink} loading={optionsLoading} options={requestOptions}
          optionFilterProp="label" placeholder={viaShareLink ? '不关联需求' : '选择需求'}
          notFoundContent={optionsLoading ? '正在加载需求…' : '没有匹配的需求'} />
      </Form.Item>
      <Form.Item label="自定义选题" name="customProjectTitle"
        rules={[{ max: 200, message: '自定义选题最多 200 个字' }]}>
        <Input allowClear placeholder="留空则按关联需求（或上传链接）所属的选题算" />
      </Form.Item>
      <Alert type="info" showIcon style={{ marginTop: -8, marginBottom: 16 }}
        title="填写后，这条工时在成员核算、工时导出、清算单、工资计算和工时分析里都算在这个选题下，同名的合并成一组；按工作日期计入清算期，不再算进需求所属的选题。" />
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
