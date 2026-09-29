import {
  App, Button, Divider, Form, Input, Select, Space, Typography, Upload,
} from 'antd'
import { DeleteOutlined, PictureOutlined, PlusOutlined, UploadOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { api } from '../../api'
import type { BrandingSettings, FooterLink, PlaceholderImage } from '../../types'
import { BRANDING_UPDATED_EVENT, defaultBranding } from '../../branding'
import { DataState } from '../../components'
import { useLoad } from '../../hooks'
import { describeBytes } from '../../uploadLimits'
import { useUploadLimits } from '../../useUploadLimits'

interface SiteContentFormValues {
  loginHeadline?: string
  loginSubheadline?: string
  loginHighlights?: string[]
  loginNotice?: string
  footerText?: string
  footerLinks?: Partial<FooterLink>[]
}

/** 管理员面板 · 页面定制：登录页文案、全站页脚和缺图占位图。 */
export default function SiteContentPage() {
  const { message, modal } = App.useApp()
  const uploadLimits = useUploadLimits()
  const placeholderMaxBytes = uploadLimits.PLACEHOLDER_IMAGE_MAX_BYTES
  const [siteForm] = Form.useForm<SiteContentFormValues>()
  const { data: branding, loading: brandingLoading, error: brandingError, reload: reloadBranding } = useLoad(
    () => api<BrandingSettings>({ url: '/branding' }),
    defaultBranding, [],
  )
  const {
    data: placeholderImages,
    loading: placeholderImagesLoading,
    error: placeholderImagesError,
    reload: reloadPlaceholderImages,
  } = useLoad(
    () => api<PlaceholderImage[]>({ url: '/branding/placeholder-images' }),
    [] as PlaceholderImage[], [],
  )
  const [savingSiteContent, setSavingSiteContent] = useState(false)
  const [uploadingPlaceholderImages, setUploadingPlaceholderImages] = useState(false)

  const saveSiteContent = async () => {
    setSavingSiteContent(true)
    try {
      const values = await siteForm.validateFields()
      await api<BrandingSettings>({ method: 'PUT', url: '/branding/site', data: {
        loginHeadline: values.loginHeadline || '',
        loginSubheadline: values.loginSubheadline || '',
        loginHighlights: values.loginHighlights || [],
        loginNotice: values.loginNotice || '',
        footerText: values.footerText || '',
        // 只把填了内容的那几行发出去：多点几下"添加链接"留下的空行不该报错。
        footerLinks: (values.footerLinks || []).filter(link => link?.label?.trim() || link?.url?.trim()),
      } })
      window.dispatchEvent(new Event(BRANDING_UPDATED_EVENT))
      message.success('页面定制已保存')
      await reloadBranding()
    } catch (e) {
      if ((e as { errorFields?: unknown[] }).errorFields) return
      message.error((e as Error).message)
    } finally { setSavingSiteContent(false) }
  }
  const uploadPlaceholderImages = async (files: File[]) => {
    const invalid = files.find(file => !['image/png', 'image/jpeg'].includes(file.type))
    if (invalid) {
      message.error(`${invalid.name}：占位图仅支持 PNG 或 JPEG`)
      return
    }
    const tooLarge = files.find(file => file.size > placeholderMaxBytes)
    if (tooLarge) {
      message.error(`${tooLarge.name}：占位图不能超过 ${describeBytes(placeholderMaxBytes)}`)
      return
    }
    if (placeholderImages.length + files.length > 12) {
      message.error(`占位图最多保存 12 张，当前已有 ${placeholderImages.length} 张`)
      return
    }
    setUploadingPlaceholderImages(true)
    try {
      const data = new FormData()
      files.forEach(file => data.append('files', file))
      await api<PlaceholderImage[]>({ method: 'POST', url: '/branding/placeholder-images', data })
      message.success(`已上传 ${files.length} 张占位图`)
      await reloadPlaceholderImages()
      window.dispatchEvent(new Event(BRANDING_UPDATED_EVENT))
    } catch (e) {
      message.error((e as Error).message)
    } finally { setUploadingPlaceholderImages(false) }
  }
  const removePlaceholderImage = (image: PlaceholderImage) => {
    modal.confirm({
      title: '删除占位图', okText: '删除', okButtonProps: { danger: true }, cancelText: '取消',
      content: <span>确定删除占位图 <strong>{image.fileName}</strong> 吗？删除后不再参与随机展示。</span>,
      onOk: async () => {
        try {
          await api<PlaceholderImage[]>({ method: 'DELETE', url: `/branding/placeholder-images/${image.id}` })
          message.success('占位图已删除')
          await reloadPlaceholderImages()
          window.dispatchEvent(new Event(BRANDING_UPDATED_EVENT))
        } catch (e) { message.error((e as Error).message); throw e }
      },
    })
  }

  return <DataState loading={brandingLoading} error={brandingError} onRetry={reloadBranding}>
    <div className="branding-settings">
      <div className="tab-toolbar"><div>
        <Typography.Title level={4}>登录页与页脚</Typography.Title>
        <Typography.Text type="secondary">
          登录页的宣传文案和全站页脚。页脚对所有角色显示；每一项留空就整块不显示。
        </Typography.Text>
      </div></div>
      <Form form={siteForm} layout="vertical" className="branding-form"
        key={`site-${branding.loginHeadline || ''}-${branding.footerText || ''}-${(branding.footerLinks || []).length}`}
        initialValues={{
          loginHeadline: branding.loginHeadline || '',
          loginSubheadline: branding.loginSubheadline || '',
          loginHighlights: branding.loginHighlights || [],
          loginNotice: branding.loginNotice || '',
          footerText: branding.footerText || '',
          footerLinks: branding.footerLinks || [],
        }}>
        <Form.Item label="登录页主标题" name="loginHeadline"
          extra="登录页左侧的大标题，支持换行。" rules={[{ max: 120, message: '主标题不能超过 120 个字符' }]}>
          <Input.TextArea rows={2} showCount maxLength={120}
            placeholder="例如：让每一次快门，都抵达它该去的地方。" />
        </Form.Item>
        <Form.Item label="登录页副标题" name="loginSubheadline"
          rules={[{ max: 200, message: '副标题不能超过 200 个字符' }]}>
          <Input.TextArea rows={2} showCount maxLength={200}
            placeholder="例如：从拍摄需求到图片采纳，把散落的协作收进一条清晰的工作流。" />
        </Form.Item>
        <Form.Item label="登录页关键词" name="loginHighlights"
          extra="显示在登录页左下角，最多 6 个，每个不超过 12 个字符。输入后回车添加。"
          rules={[{ validator: (_, value: string[] = []) => value.length > 6
            ? Promise.reject(new Error('关键词最多 6 个'))
            : value.some(item => item.trim().length > 12)
              ? Promise.reject(new Error('每个关键词不能超过 12 个字符'))
              : Promise.resolve() }]}>
          <Select mode="tags" open={false} suffixIcon={null}
            tokenSeparators={[',', '，']} placeholder="例如：项目协作" />
        </Form.Item>
        <Form.Item label="登录页底部提示" name="loginNotice"
          rules={[{ max: 200, message: '底部提示不能超过 200 个字符' }]}>
          <Input showCount maxLength={200} placeholder="例如：首次登录后，系统会引导你修改初始密码" />
        </Form.Item>
        <Divider />
        <Form.Item label="页脚文案" name="footerText"
          extra="显示在工作台、登录页和公开页面的底部，支持换行。"
          rules={[{ max: 300, message: '页脚文案不能超过 300 个字符' }]}>
          <Input.TextArea rows={2} showCount maxLength={300}
            placeholder="例如：© 2026 校园摄影部 · 技术支持 xxx" />
        </Form.Item>
        <Form.List name="footerLinks">
          {(fields, { add, remove }) => <Form.Item label="页脚链接"
            extra="最多 6 条；地址需以 http://、https://、mailto: 或站内的 #/ 开头。">
            <Space orientation="vertical" size="small" style={{ width: '100%' }}>
              {fields.map(({ key, name, ...rest }, index) => <Space key={key} align="baseline" wrap>
                <Form.Item {...rest} name={[name, 'label']} noStyle
                  rules={[{ max: 20, message: '名称不能超过 20 个字符' }]}>
                  <Input placeholder="名称，例如：使用指南" style={{ width: 180 }} />
                </Form.Item>
                <Form.Item {...rest} name={[name, 'url']} noStyle
                  rules={[{ max: 200, message: '地址不能超过 200 个字符' }]}>
                  <Input placeholder="地址，例如：https://example.com" style={{ width: 320 }} />
                </Form.Item>
                <Button type="text" danger icon={<DeleteOutlined />}
                  aria-label={`删除第 ${index + 1} 条页脚链接`} onClick={() => remove(name)} />
              </Space>)}
              <Button icon={<PlusOutlined />} disabled={fields.length >= 6}
                onClick={() => add({ label: '', url: '' })}>添加链接</Button>
            </Space>
          </Form.Item>}
        </Form.List>
        <Button type="primary" loading={savingSiteContent}
          onClick={() => void saveSiteContent()}>保存页面定制</Button>
      </Form>
      <Divider />
      <div className="branding-form placeholder-image-settings">
        <div className="tab-toolbar"><div>
          <Typography.Title level={4}><PictureOutlined /> 缺图占位图</Typography.Title>
          <Typography.Text type="secondary">
            图片缺失、因存储故障加载不出来，或图片已被删除时，系统会从这些图片里任选一张顶上。
            一张都不传就沿用内置的灰底占位。最多 12 张，单张不超过 {describeBytes(placeholderMaxBytes)}。
          </Typography.Text>
        </div></div>
        <DataState loading={placeholderImagesLoading} error={placeholderImagesError}
          onRetry={reloadPlaceholderImages}>
          <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
            <Upload accept="image/png,image/jpeg" multiple showUploadList={false}
              beforeUpload={(file, fileList) => {
                // beforeUpload 每个文件调一次，只在第一份上把整批一起提交，
                // 免得一次选 5 张就发 5 个请求、各自去撞 12 张的上限。
                if (file === fileList[0]) void uploadPlaceholderImages(fileList)
                return false
              }}>
              <Button icon={<UploadOutlined />} loading={uploadingPlaceholderImages}
                disabled={placeholderImages.length >= 12}>上传占位图</Button>
            </Upload>
            {placeholderImages.length
              ? <div className="placeholder-image-grid">
                {placeholderImages.map(image => <div className="placeholder-image-item" key={image.id}>
                  <img src={image.imageUrl} alt={image.fileName} />
                  <div>
                    <Typography.Text ellipsis title={image.fileName}>{image.fileName}</Typography.Text>
                    <Button type="text" danger size="small" icon={<DeleteOutlined />}
                      aria-label={`删除占位图 ${image.fileName}`}
                      onClick={() => removePlaceholderImage(image)} />
                  </div>
                </div>)}
              </div>
              : <Typography.Text type="secondary">还没有上传占位图。</Typography.Text>}
          </Space>
        </DataState>
      </div>
    </div>
  </DataState>
}
