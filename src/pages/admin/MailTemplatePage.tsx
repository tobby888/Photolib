import {
  Alert, App, Button, Card, Col, DatePicker, Input, Popconfirm, Row, Select, Space, Tag, Tooltip, Typography,
} from 'antd'
import { MailOutlined, RollbackOutlined, SaveOutlined, SendOutlined, SnippetsOutlined } from '@ant-design/icons'
import type { GetRef } from 'antd'
import { useEffect, useMemo, useRef, useState } from 'react'
import dayjs from 'dayjs'
import { api } from '../../api'
import { DataState } from '../../components'
import { useLoad } from '../../hooks'
import MailPreviewFrame from '../../MailPreviewFrame'
import { hasRecords, insertVariable, isSelectable, unknownVariables, userKey } from '../../settlementMail'
import type { MailTemplateView, SettlementPreview, SettlementRecipient, SettlementStatus } from '../../settlementMail'

type TextAreaRef = GetRef<typeof Input.TextArea>

const lastMonth = (): [string, string] => [
  dayjs().subtract(1, 'month').startOf('month').format('YYYY-MM-DD'),
  dayjs().subtract(1, 'month').endOf('month').format('YYYY-MM-DD'),
]

/**
 * 管理员面板「邮件模板」：自定义清算单邮件的主题和 HTML 正文，右边用真实数据实时预览。
 * 没保存过就是内置模板；「恢复默认」删掉自定义，以后内置模板改版会自动跟上。
 */
export default function MailTemplatePage() {
  const template = useLoad(() => api<MailTemplateView>({ url: '/settlement-mails/template' }), null as MailTemplateView | null, [])
  return <DataState loading={template.loading} error={template.error} onRetry={template.reload}>
    {template.data && <TemplateEditor key={`${template.data.template.version}-${template.data.template.customized}`}
      view={template.data} onSaved={next => template.setData(next)} />}
  </DataState>
}

function TemplateEditor({ view, onSaved }: { view: MailTemplateView; onSaved: (next: MailTemplateView) => void }) {
  const { message } = App.useApp()
  const [subject, setSubject] = useState(view.template.subject)
  const [html, setHtml] = useState(view.template.html)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [range, setRange] = useState<[string, string]>(lastMonth)
  const [sampleUserId, setSampleUserId] = useState<string>()
  const [preview, setPreview] = useState<SettlementPreview | null>(null)
  const [previewError, setPreviewError] = useState('')
  const editor = useRef<TextAreaRef>(null)
  const dirty = subject !== view.template.subject || html !== view.template.html

  const status = useLoad(() => api<SettlementStatus>({ url: '/settlement-mails/status' }), null as SettlementStatus | null, [])
  const recipients = useLoad(
    () => api<SettlementRecipient[]>({ url: '/settlement-mails/recipients', params: { from: range[0], to: range[1] } }),
    [] as SettlementRecipient[], [range[0], range[1]])
  const samples = useMemo(() => recipients.data.filter(isSelectable)
    .sort((a, b) => Number(hasRecords(b)) - Number(hasRecords(a))), [recipients.data])
  const sample = samples.find(r => userKey(r.userId) === sampleUserId) ?? samples[0]
  const unknown = useMemo(() => unknownVariables(`${subject}\n${html}`, view.variables), [subject, html, view.variables])

  // 正文停手 600ms 后再请求预览，免得每敲一个字发一次。
  useEffect(() => {
    if (!sample) return
    let cancelled = false
    const timer = window.setTimeout(async () => {
      try {
        const next = await api<SettlementPreview>({ method: 'POST', url: '/settlement-mails/preview',
          data: { userId: sample.userId, from: range[0], to: range[1], subject, html } })
        if (!cancelled) { setPreview(next); setPreviewError('') }
      } catch (e) {
        if (!cancelled) setPreviewError((e as Error).message)
      }
    }, 600)
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [sample, range, subject, html])

  const insert = (name: string) => {
    const area = editor.current?.resizableTextArea?.textArea
    const start = area?.selectionStart ?? html.length
    const end = area?.selectionEnd ?? html.length
    const next = insertVariable(html, start, end, name)
    setHtml(next.text)
    window.requestAnimationFrame(() => {
      area?.focus()
      area?.setSelectionRange(next.cursor, next.cursor)
    })
  }

  const save = async () => {
    setSaving(true)
    try {
      const next = await api<MailTemplateView>({ method: 'PUT', url: '/settlement-mails/template',
        data: { subject, html, version: view.template.customized ? view.template.version : 0 } })
      message.success('邮件模板已保存，之后发出的清算单都用这一版')
      onSaved(next)
    } catch (e) { message.error((e as Error).message) }
    finally { setSaving(false) }
  }

  const reset = async () => {
    try {
      const next = await api<MailTemplateView>({ method: 'DELETE', url: '/settlement-mails/template' })
      message.success('已恢复内置模板')
      onSaved(next)
    } catch (e) { message.error((e as Error).message) }
  }

  const sendTest = async () => {
    if (!sample) return
    setTesting(true)
    try {
      const result = await api<{ email: string }>({ method: 'POST', url: '/settlement-mails/template/test',
        data: { userId: sample.userId, from: range[0], to: range[1], subject, html } })
      message.success(`测试邮件已发到 ${result.email}`)
    } catch (e) { message.error((e as Error).message) }
    finally { setTesting(false) }
  }

  return <div className="mail-template-page">
    {status.data && !status.data.configured && <Alert type="warning" showIcon className="settlement-mail-alert"
      title="邮件服务还没有配置"
      description="在服务器 .env 中填写 SMTP_HOST、SMTP_PORT、SMTP_USERNAME（发信邮箱）和 SMTP_PASSWORD（授权码）并重启后才能发信。模板可以先编辑、预览。" />}
    <Row gutter={[16, 16]}>
      <Col xs={24} xl={11}>
        <Card title={<Space><MailOutlined />清算单邮件</Space>} extra={view.template.customized
          ? <Tag color="blue">自定义{view.template.updatedByName ? ` · ${view.template.updatedByName}` : ''}
            {view.template.updatedAt ? ` · ${dayjs(view.template.updatedAt).format('MM-DD HH:mm')}` : ''}</Tag>
          : <Tag>内置模板</Tag>}>
          <div className="mail-template-field">
            <Typography.Text strong>邮件主题</Typography.Text>
            <Input value={subject} maxLength={200} showCount onChange={event => setSubject(event.target.value)} />
          </div>
          <div className="mail-template-field">
            <div className="mail-template-field-head">
              <Typography.Text strong>HTML 正文</Typography.Text>
              <Button size="small" icon={<SnippetsOutlined />} onClick={() => setHtml(view.defaultHtml)}>
                载入内置模板</Button>
            </div>
            <Input.TextArea ref={editor} className="mail-template-code" value={html} spellCheck={false}
              autoSize={{ minRows: 18, maxRows: 34 }} onChange={event => setHtml(event.target.value)} />
            <Typography.Text type="secondary" className="mail-template-tip">
              用行内样式和表格排版：多数邮件客户端会丢掉 &lt;style&gt;、不支持 flex / grid，也不显示外链图片。
              点下面的变量插入到光标处；<code>{'{{#siteUrl}}…{{/siteUrl}}'}</code> 包住的内容只在配置了站点地址时出现。
            </Typography.Text>
          </div>
          {unknown.length > 0 && <Alert type="warning" showIcon style={{ marginBottom: 12 }}
            title={`模板里有系统不认识的变量：${unknown.map(name => `{{${name}}}`).join('、')}`}
            description="它们会原样出现在邮件里，多半是拼错了。" />}
          <div className="mail-template-variables">
            {Object.entries(view.variables).map(([name, label]) => <Tooltip key={name} title={label}>
              <Button size="small" className="mail-template-variable" onClick={() => insert(name)}>{`{{${name}}}`}</Button>
            </Tooltip>)}
          </div>
          <div className="mail-template-actions">
            <Button type="primary" icon={<SaveOutlined />} loading={saving} disabled={!dirty || !subject.trim() || !html.trim()}
              onClick={() => void save()}>保存模板</Button>
            <Button disabled={!dirty} onClick={() => { setSubject(view.template.subject); setHtml(view.template.html) }}>
              撤销修改</Button>
            <Popconfirm title="恢复内置模板？" description="会删除当前的自定义模板，无法撤销。"
              okText="恢复" okButtonProps={{ danger: true }} onConfirm={() => void reset()} disabled={!view.template.customized}>
              <Button icon={<RollbackOutlined />} disabled={!view.template.customized}>恢复默认</Button>
            </Popconfirm>
            <Tooltip title={!status.data?.configured ? '邮件服务未配置' : '用当前编辑中的模板和右侧的预览数据，发一封到你自己的邮箱'}>
              <Button icon={<SendOutlined />} loading={testing} disabled={!sample || !status.data?.configured}
                onClick={() => void sendTest()}>发测试邮件给自己</Button>
            </Tooltip>
          </div>
        </Card>
      </Col>
      <Col xs={24} xl={13}>
        <Card title="实时预览" className="mail-template-preview-card" extra={<Space wrap>
          <DatePicker.RangePicker size="small" allowClear={false} value={[dayjs(range[0]), dayjs(range[1])]}
            onChange={values => values?.[0] && values[1]
              && setRange([values[0].format('YYYY-MM-DD'), values[1].format('YYYY-MM-DD')])} />
          <Select size="small" showSearch style={{ width: 160 }} placeholder="预览对象" value={sample ? userKey(sample.userId) : undefined}
            loading={recipients.loading} onChange={setSampleUserId} optionFilterProp="label"
            options={samples.map(r => ({ value: userKey(r.userId),
              label: hasRecords(r) ? `${r.displayName}（有记录）` : r.displayName }))} />
        </Space>}>
          {!sample && !recipients.loading && <Typography.Text type="secondary">
            没有可以预览的账号：至少要有一个填了邮箱、且不与别人同名的账号。</Typography.Text>}
          {previewError && <Alert type="error" showIcon title="预览失败" description={previewError} />}
          {preview && <MailPreviewFrame subject={preview.subject} html={preview.html} height={760} />}
        </Card>
      </Col>
    </Row>
  </div>
}
