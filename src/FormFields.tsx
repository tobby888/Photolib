import { DownloadOutlined, FileOutlined, UploadOutlined } from '@ant-design/icons'
import { App, Button, Checkbox, Descriptions, Empty, Form, Image, Input, Radio, Space, Typography, Upload } from 'antd'
import { useState } from 'react'
import { http } from './api'
import { formatBytes } from './components'
import { describeBytes } from './recruitmentUpload'
import { formAnswerText, FORM_FILE_LIMITS, type RecruitmentFormField } from './recruitmentForm'
import { validateFormFileSelection, type FormFileLimits, type FormFileView } from './formFiles'
import { useUploadLimits } from './useUploadLimits'

/** 招募报名页和问卷填写页共用的一道题。name 固定在 ['answers', field.id] 下。 */
export function FormAnswerField({ field, limits: givenLimits }: {
  field: RecruitmentFormField
  /** 问卷接口会随问卷下发；招募页不传，就用管理员设的当前值（FORM_FILE_MAX_BYTES）。 */
  limits?: FormFileLimits
}) {
  const uploadLimits = useUploadLimits()
  const limits = givenLimits ?? {
    maxFileBytes: uploadLimits.FORM_FILE_MAX_BYTES,
    maxFilesPerField: FORM_FILE_LIMITS.maxFilesPerField,
  }
  const common = {
    name: ['answers', field.id],
    label: field.label,
    extra: field.helpText,
    rules: field.required ? [{ required: true, message: `请填写“${field.label}”` }] : undefined,
  }
  if (field.type === 'LONG_TEXT') {
    return <Form.Item {...common}><Input.TextArea autoSize={{ minRows: 4, maxRows: 10 }} maxLength={5000}
      showCount placeholder={field.placeholder || '请输入'} /></Form.Item>
  }
  if (field.type === 'SINGLE_CHOICE') {
    return <Form.Item {...common}><Radio.Group options={(field.options || []).map(option => ({ label: option, value: option }))} /></Form.Item>
  }
  if (field.type === 'MULTIPLE_CHOICE') {
    return <Form.Item {...common}><Checkbox.Group options={(field.options || []).map(option => ({ label: option, value: option }))} /></Form.Item>
  }
  if (field.type === 'DATE') {
    return <Form.Item {...common}><Input type="date" style={{ maxWidth: 320 }} /></Form.Item>
  }
  if (field.type === 'FILE_UPLOAD') {
    return <Form.Item {...common} valuePropName="fileList"
      getValueFromEvent={(event: { fileList?: unknown[] }) => event?.fileList || []}
      extra={<>
        {field.helpText && <div>{field.helpText}</div>}
        <div>任意格式，最多 {limits.maxFilesPerField} 个，单个不超过 {describeBytes(limits.maxFileBytes)}。</div>
      </>}
      rules={[
        ...(field.required ? [{ required: true, type: 'array' as const, min: 1, message: `请给“${field.label}”传一个文件` }] : []),
        {
          validator: async (_: unknown, value: unknown) => {
            const files = Array.isArray(value)
              ? value.map(item => item as { name: string; size?: number; originFileObj?: File })
                .map(item => ({ name: item.name, size: item.originFileObj?.size ?? item.size ?? 0 }))
              : []
            const issue = validateFormFileSelection(files, limits)
            if (issue) throw new Error(issue)
          },
        },
      ]}>
      <Upload multiple beforeUpload={() => false} maxCount={limits.maxFilesPerField}>
        <Button icon={<UploadOutlined />}>选择文件</Button>
      </Upload>
    </Form.Item>
  }
  return <Form.Item {...common}><Input maxLength={500} showCount placeholder={field.placeholder || '请输入'} /></Form.Item>
}

function protectedApiPath(url: string) {
  if (url.startsWith('/api/v1/')) return url.slice('/api/v1'.length)
  if (url.startsWith('/')) return url
  return undefined
}

/** 一组已提交的文件：图片给个缩略图，其余只显示文件名；点下载走服务端签发的临时地址。 */
export function FormFileList({ files }: { files: FormFileView[] }) {
  const { message } = App.useApp()
  const [downloading, setDownloading] = useState<string>()
  if (!files.length) return <Typography.Text type="secondary">没有上传文件</Typography.Text>

  const download = async (file: FormFileView) => {
    const url = file.downloadUrl || ''
    if (!url) {
      message.error('没拿到这个文件的下载地址，刷新一下再试')
      return
    }
    const apiPath = protectedApiPath(url)
    if (!apiPath) {
      window.open(url, '_blank', 'noopener')
      return
    }
    setDownloading(file.id)
    try {
      const response = await http.get<Blob>(apiPath, { responseType: 'blob' })
      const objectUrl = URL.createObjectURL(response.data)
      const anchor = document.createElement('a')
      anchor.href = objectUrl
      anchor.download = file.fileName
      anchor.click()
      window.setTimeout(() => URL.revokeObjectURL(objectUrl), 0)
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setDownloading(undefined)
    }
  }

  return <Space orientation="vertical" size={8} style={{ width: '100%' }}>
    {files.map(file => <div key={file.id} className="form-file-row">
      {file.previewUrl && /^https?:\/\//.test(file.previewUrl)
        ? <Image src={file.previewUrl} alt={file.fileName} width={48} height={48} style={{ objectFit: 'cover', borderRadius: 6 }} />
        : <span className="form-file-icon"><FileOutlined /></span>}
      <div style={{ minWidth: 0, flex: 1 }}>
        <Typography.Text ellipsis={{ tooltip: file.fileName }} style={{ display: 'block' }}>{file.fileName}</Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{formatBytes(file.size)}</Typography.Text>
      </div>
      <Button type="link" size="small" icon={<DownloadOutlined />} loading={downloading === file.id}
        onClick={() => void download(file)}>下载</Button>
    </div>)}
  </Space>
}

/** 一份已提交答卷的只读展示：题目 + 答案，上传文件题显示文件列表。 */
export function FormAnswerView({ fields, answers, files }: {
  fields: RecruitmentFormField[]
  answers: Record<string, unknown>
  files: FormFileView[]
}) {
  if (!fields.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="这份问卷没有题目" />
  return <Descriptions column={1} bordered size="small" styles={{ label: { width: '32%' } }} items={fields.map(field => ({
    key: field.id,
    label: field.label,
    children: field.type === 'FILE_UPLOAD'
      ? <FormFileList files={files.filter(file => file.fieldId === field.id)} />
      : <Typography.Paragraph style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
          {formAnswerText(answers[field.id]) || <Typography.Text type="secondary">未填写</Typography.Text>}
        </Typography.Paragraph>,
  }))} />
}
