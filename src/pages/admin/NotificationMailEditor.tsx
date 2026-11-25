import { Alert, App, Button, Card, Col, Input, Popconfirm, Row, Tag, Tooltip, Typography } from 'antd'
import type { GetRef } from 'antd'
import { useEffect, useRef, useState } from 'react'
import { api } from '../../api'
import { DataState } from '../../components'
import { useLoad } from '../../hooks'
import MailPreviewFrame from '../../MailPreviewFrame'
import { insertVariable, unknownVariables } from '../../settlementMail'
import type { MailTemplateView, SettlementStatus } from '../../settlementMail'

type WorklogKind = 'WORKLOG_SUBMITTED' | 'WORKLOG_APPROVED'
type Preview = { subject: string; html: string }

export default function NotificationMailEditor({ kind, layoutRevision = 0, smtpRevision = 0, onLayoutSaved }: {
  kind?: WorklogKind; layoutRevision?: number; smtpRevision?: number; onLayoutSaved?: () => void
}) {
  const url = kind ? `/mail-templates/worklog/${kind}` : '/mail-templates/layout'
  const template = useLoad(() => api<MailTemplateView>({ url }), null as MailTemplateView | null, [url])
  return <DataState loading={template.loading} error={template.error} onRetry={template.reload}>
    {template.data && <Editor key={`${template.data.template.version}-${template.data.template.customized}`}
      view={template.data} kind={kind} url={url} layoutRevision={layoutRevision} smtpRevision={smtpRevision} onSaved={next => {
        template.setData(next)
        onLayoutSaved?.()
      }} />}
  </DataState>
}

function Editor({ view, kind, url, layoutRevision, smtpRevision, onSaved }: {
  view: MailTemplateView; kind?: WorklogKind; url: string; layoutRevision: number; smtpRevision: number; onSaved: (view: MailTemplateView) => void
}) {
  const { message } = App.useApp()
  const [subject, setSubject] = useState(view.template.subject)
  const [html, setHtml] = useState(view.template.html)
  const [preview, setPreview] = useState<Preview>()
  const [previewError, setPreviewError] = useState('')
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const editor = useRef<GetRef<typeof Input.TextArea>>(null)
  const status = useLoad(() => api<SettlementStatus>({ url: '/settlement-mails/status' }), null as SettlementStatus | null, [smtpRevision])
  const sampleUrl = `/mail-templates/worklog/${kind ?? 'WORKLOG_SUBMITTED'}`
  const dirty = subject !== view.template.subject || html !== view.template.html
  const unknown = unknownVariables(`${subject}\n${html}`, view.variables)
  const valid = !!html.trim() && (kind ? !!subject.trim() : html.split('{{content}}').length === 2)

  useEffect(() => {
    if (!valid) return
    const controller = new AbortController()
    const timer = window.setTimeout(async () => {
      try {
        const next = await api<Preview>({ method: 'POST', url: `${sampleUrl}/preview`, signal: controller.signal,
          data: kind ? { subject, html } : { layout: html } })
        if (!controller.signal.aborted) { setPreview(next); setPreviewError('') }
      } catch (error) {
        if (!controller.signal.aborted) setPreviewError((error as Error).message)
      }
    }, 600)
    return () => { controller.abort(); window.clearTimeout(timer) }
  }, [valid, sampleUrl, kind, subject, html, layoutRevision])

  const insert = (name: string) => {
    const area = editor.current?.resizableTextArea?.textArea
    const next = insertVariable(html, area?.selectionStart ?? html.length, area?.selectionEnd ?? html.length, name)
    setHtml(next.text)
    window.requestAnimationFrame(() => {
      area?.focus()
      area?.setSelectionRange(next.cursor, next.cursor)
    })
  }

  const save = async (method: 'PUT' | 'DELETE') => {
    setSaving(true)
    try {
      const version = view.template.customized ? view.template.version : 0
      const next = await api<MailTemplateView>({ method, url, data: method === 'PUT'
        ? { html, version, ...(kind ? { subject } : {}) } : undefined })
      message.success(method === 'PUT' ? '已保存，之后生成的邮件将使用新版' : '已恢复内置版本')
      onSaved(next)
    } catch (error) { message.error((error as Error).message) }
    finally { setSaving(false) }
  }

  const sendTest = async () => {
    setTesting(true)
    try {
      const result = await api<{ email: string }>({ method: 'POST', url: `${sampleUrl}/test`,
        data: kind ? { subject, html } : { layout: html } })
      message.success(`示例邮件已发到 ${result.email}`)
    } catch (error) { message.error((error as Error).message) }
    finally { setTesting(false) }
  }

  return <div className="mail-template-page">
    {status.data && !status.data.configured && <Alert type="warning" showIcon className="settlement-mail-alert"
      title="邮件服务还没有配置" description="可以编辑和预览；请切换到「发信邮箱」标签页添加 SMTP 邮箱（支持多邮箱自动顺延），或配置服务器 .env。" />}
    <Row gutter={[16, 16]}>
      <Col xs={24} xl={11}>
        <Card title={kind ? '主题与正文' : '邮件公共版式'} extra={view.template.customized ? <Tag color="blue">自定义</Tag> : <Tag>内置</Tag>}>
          {kind && <div className="mail-template-field">
            <Typography.Text strong>邮件主题</Typography.Text>
            <Input aria-label="邮件主题" value={subject} maxLength={200} showCount onChange={event => setSubject(event.target.value)} />
          </div>}
          <div className="mail-template-field">
            <div className="mail-template-field-head">
              <Typography.Text strong>{kind ? 'HTML 正文' : 'HTML 版式'}</Typography.Text>
              <Button size="small" onClick={() => { setSubject(view.defaultSubject); setHtml(view.defaultHtml) }}>载入内置版本</Button>
            </div>
            <Input.TextArea ref={editor} aria-label={kind ? 'HTML 正文' : 'HTML 版式'} className="mail-template-code"
              value={html} spellCheck={false} autoSize={{ minRows: 14, maxRows: 30 }} onChange={event => setHtml(event.target.value)} />
            <Typography.Text type="secondary" className="mail-template-tip">
              {kind ? '正文会套用公共版式，请用表格行（<tr>）和行内样式；点击变量可插入正文，也可手动填入主题。'
                : <>此处管理页眉、Logo、页脚和整体样式。表格中必须保留一个 <code>{'{{content}}'}</code> 来放入正文；旧清算单完整 HTML 不受此设置影响。</>}
            </Typography.Text>
          </div>
          {!valid && <Alert type="warning" showIcon title={kind ? '请填写主题和正文' : '版式必须包含且仅包含一个 {{content}}'} />}
          {unknown.length > 0 && <Alert type="warning" showIcon title={`未识别的变量：${unknown.join('、')}`} />}
          <div className="mail-template-variables">
            {Object.entries(view.variables).map(([name, label]) => <Tooltip key={name} title={label}>
              <Button size="small" className="mail-template-variable" onClick={() => insert(name)}>{`{{${name}}}`}</Button>
            </Tooltip>)}
          </div>
          <div className="mail-template-actions">
            <Button type="primary" loading={saving} disabled={!dirty || !valid} onClick={() => void save('PUT')}>保存</Button>
            <Button disabled={!dirty} onClick={() => { setSubject(view.template.subject); setHtml(view.template.html) }}>撤销修改</Button>
            <Popconfirm title="恢复内置版本？" description="会删除已保存的自定义内容。" okText="恢复"
              okButtonProps={{ danger: true }} onConfirm={() => save('DELETE')} disabled={!view.template.customized}>
              <Button disabled={!view.template.customized || saving}>恢复默认</Button>
            </Popconfirm>
            <Button loading={testing} disabled={!valid || !status.data?.configured} onClick={() => void sendTest()}>发测试邮件给自己</Button>
          </div>
        </Card>
      </Col>
      <Col xs={24} xl={13}>
        <Card title="实时预览（示例数据）" className="mail-template-preview-card">
          {valid && previewError && <Alert type="error" showIcon title="预览失败" description={previewError} />}
          {valid && preview && <MailPreviewFrame subject={preview.subject} html={preview.html} height={620} />}
        </Card>
      </Col>
    </Row>
  </div>
}
