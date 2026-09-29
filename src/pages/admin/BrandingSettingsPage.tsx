import {
  Alert, App, Button, Card, Divider, Form, Input, Select, Space, Typography, Upload,
} from 'antd'
import {
  AimOutlined, BulbOutlined, CameraOutlined, ClockCircleOutlined, DeleteOutlined, PictureOutlined, PlusOutlined,
  StarOutlined, UploadOutlined,
} from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { api } from '../../api'
import type { BrandingSettings, ScheduledBrandIcon } from '../../types'
import { BRANDING_UPDATED_EVENT, defaultBranding } from '../../branding'
import { DataState } from '../../components'
import { useLoad } from '../../hooks'
import { describeBytes } from '../../uploadLimits'
import { useUploadLimits } from '../../useUploadLimits'

interface BrandingFormValues {
  title: string
  slogan: string
  iconChoice: BrandingSettings['builtinIcon'] | 'custom'
}

interface ScheduledIconDraft {
  key: string
  id?: string
  cronExpression: string
  iconUrl?: string
  file?: File
}

function ScheduledIconPreview({ file, iconUrl }: Pick<ScheduledIconDraft, 'file' | 'iconUrl'>) {
  const [previewUrl, setPreviewUrl] = useState(iconUrl)

  useEffect(() => {
    if (!file) {
      setPreviewUrl(iconUrl)
      return
    }
    const objectUrl = URL.createObjectURL(file)
    setPreviewUrl(objectUrl)
    return () => URL.revokeObjectURL(objectUrl)
  }, [file, iconUrl])

  return previewUrl ? <img src={previewUrl} alt="定时图标预览" /> : null
}

/** 管理员面板 · 面板品牌：左侧导航的品牌名称、Slogan、图标，以及按日期生效的定时图标。 */
export default function BrandingSettingsPage() {
  const { message } = App.useApp()
  const uploadLimits = useUploadLimits()
  const iconMaxBytes = uploadLimits.BRAND_ICON_MAX_BYTES
  const [brandingForm] = Form.useForm<BrandingFormValues>()
  const { data: branding, loading: brandingLoading, error: brandingError, reload: reloadBranding } = useLoad(
    () => api<BrandingSettings>({ url: '/branding' }),
    defaultBranding, [],
  )
  const {
    data: scheduledIcons,
    loading: scheduledIconsLoading,
    error: scheduledIconsError,
    reload: reloadScheduledIcons,
  } = useLoad(
    () => api<ScheduledBrandIcon[]>({ url: '/branding/scheduled-icons' }),
    [] as ScheduledBrandIcon[], [],
  )
  const [scheduledIconDrafts, setScheduledIconDrafts] = useState<ScheduledIconDraft[]>([])
  const [savingScheduledIcons, setSavingScheduledIcons] = useState(false)

  useEffect(() => {
    setScheduledIconDrafts(scheduledIcons.map(icon => ({
      key: icon.id,
      id: icon.id,
      cronExpression: icon.cronExpression,
      iconUrl: icon.iconUrl,
    })))
  }, [scheduledIcons])

  const saveBranding = async () => {
    try {
      const values = await brandingForm.validateFields()
      await api<BrandingSettings>({ method: 'PUT', url: '/branding', data: {
        title: values.title,
        slogan: values.slogan,
        iconType: values.iconChoice === 'custom' ? 'custom' : 'builtin',
        builtinIcon: values.iconChoice === 'custom' ? branding.builtinIcon : values.iconChoice,
      } })
      window.dispatchEvent(new Event(BRANDING_UPDATED_EVENT))
      message.success('面板品牌设置已保存')
      await reloadBranding()
    } catch (e) { message.error((e as Error).message) }
  }
  const uploadIcon = async (file: File) => {
    if (!['image/png', 'image/jpeg'].includes(file.type)) {
      message.error('图标仅支持 PNG 或 JPEG')
      return
    }
    if (file.size > iconMaxBytes) {
      message.error(`图标不能超过 ${describeBytes(iconMaxBytes)}`)
      return
    }
    try {
      const data = new FormData()
      data.append('file', file)
      await api<BrandingSettings>({ method: 'POST', url: '/branding/icon', data })
      window.dispatchEvent(new Event(BRANDING_UPDATED_EVENT))
      message.success('自定义图标已上传并启用')
      await reloadBranding()
    } catch (e) { message.error((e as Error).message) }
  }
  const addScheduledIcon = () => {
    if (scheduledIconDrafts.length >= 20) {
      message.warning('定时图标规则不能超过 20 条')
      return
    }
    setScheduledIconDrafts(items => [...items, {
      key: crypto.randomUUID(), cronExpression: '',
    }])
  }
  const updateScheduledIcon = (key: string, values: Partial<ScheduledIconDraft>) => {
    setScheduledIconDrafts(items => items.map(item => item.key === key ? { ...item, ...values } : item))
  }
  const chooseScheduledIcon = (key: string, file: File) => {
    if (!['image/png', 'image/jpeg'].includes(file.type)) {
      message.error('图标仅支持 PNG 或 JPEG')
      return
    }
    if (file.size > iconMaxBytes) {
      message.error(`图标不能超过 ${describeBytes(iconMaxBytes)}`)
      return
    }
    updateScheduledIcon(key, { file })
  }
  const saveScheduledIcons = async () => {
    const missingExpression = scheduledIconDrafts.findIndex(item => !item.cronExpression.trim())
    if (missingExpression >= 0) {
      message.error(`请填写第 ${missingExpression + 1} 条 Cron 表达式`)
      return
    }
    const missingIcon = scheduledIconDrafts.findIndex(item => !item.id && !item.file)
    if (missingIcon >= 0) {
      message.error(`请为第 ${missingIcon + 1} 条规则上传图标`)
      return
    }

    setSavingScheduledIcons(true)
    try {
      const files: File[] = []
      const rules = scheduledIconDrafts.map(item => {
        const fileIndex = item.file ? files.push(item.file) - 1 : undefined
        return { id: item.id, cronExpression: item.cronExpression.trim(), fileIndex }
      })
      const data = new FormData()
      data.append('rules', new Blob([JSON.stringify(rules)], { type: 'application/json' }))
      files.forEach(file => data.append('files', file))
      await api<ScheduledBrandIcon[]>({ method: 'PUT', url: '/branding/scheduled-icons', data })
      await reloadScheduledIcons()
      window.dispatchEvent(new Event(BRANDING_UPDATED_EVENT))
      message.success('定时图标规则已保存')
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSavingScheduledIcons(false)
    }
  }

  return <DataState loading={brandingLoading} error={brandingError} onRetry={reloadBranding}>
    <div className="branding-settings">
      <div className="tab-toolbar"><div>
        <Typography.Title level={4}>面板标识</Typography.Title>
        <Typography.Text type="secondary">修改左侧导航栏显示的品牌名称、Slogan 和图标，保存后立即生效。</Typography.Text>
      </div></div>
      <Form form={brandingForm} layout="vertical" initialValues={{
        title: branding.title,
        slogan: branding.slogan,
        iconChoice: branding.iconType === 'custom' ? 'custom' : branding.builtinIcon,
      }} key={`${branding.title}-${branding.iconType}-${branding.builtinIcon}-${branding.slogan}-${branding.customIconUrl || ''}`}
        className="branding-form">
        <Form.Item label="品牌名称" name="title" rules={[
          { required: true, whitespace: true, message: '请输入品牌名称' },
          { max: 40, message: '品牌名称不能超过 40 个字符' },
        ]}>
          <Input showCount maxLength={40} placeholder="例如：摄影工作站" />
        </Form.Item>
        <Form.Item label="面板图标" name="iconChoice" rules={[{ required: true }]}>
          <Select size="large" options={[
            { value: 'camera', label: <Space><CameraOutlined />相机</Space> },
            { value: 'aperture', label: <Space><AimOutlined />光圈</Space> },
            { value: 'picture', label: <Space><PictureOutlined />图片</Space> },
            { value: 'bulb', label: <Space><BulbOutlined />灵感</Space> },
            { value: 'star', label: <Space><StarOutlined />星标</Space> },
            ...(branding.customIconUrl ? [{ value: 'custom', label: '已上传的自定义图片' }] : []),
          ]} />
        </Form.Item>
        <Form.Item label="上传自定义图标"
          extra={`支持 PNG、JPEG；文件不超过 ${describeBytes(iconMaxBytes)}，尺寸不超过 1024 × 1024 像素。`}>
          <Upload accept="image/png,image/jpeg" maxCount={1} showUploadList={false}
            beforeUpload={file => { void uploadIcon(file); return false }}>
            <Button icon={<UploadOutlined />}>选择图片并上传</Button>
          </Upload>
          {branding.customIconUrl && <div className="custom-icon-preview">
            <img src={branding.customIconUrl} alt="当前自定义图标" />
            <Typography.Text type="secondary">当前已上传的图标</Typography.Text>
          </div>}
        </Form.Item>
        <Form.Item label="Slogan" name="slogan" rules={[
          { required: true, whitespace: true, message: '请输入 Slogan' },
          { max: 80, message: 'Slogan 不能超过 80 个字符' },
        ]}>
          <Input showCount maxLength={80} placeholder="例如：影像协作平台" />
        </Form.Item>
        <Button type="primary" onClick={() => void saveBranding()}>保存品牌设置</Button>
      </Form>
      <Divider />
      <div className="branding-form scheduled-icon-settings">
        <div className="tab-toolbar"><div>
          <Typography.Title level={4}><ClockCircleOutlined /> 定时图标</Typography.Title>
          <Typography.Text type="secondary">
            为不同日期配置专属图标；未命中规则时继续使用上方的面板图标。
          </Typography.Text>
        </div></div>
        <Alert type="info" showIcon message="使用 Spring 6 段 Cron：秒 分 时 日 月 周"
          description="表达式只要在某一天内触发一次，图标就在该日全天生效（Asia/Shanghai）。例如国庆节：0 0 0 1 10 *。保存时后端会检查完整日历周期内是否有规则冲突。" />
        <DataState loading={scheduledIconsLoading} error={scheduledIconsError} onRetry={reloadScheduledIcons}>
          <Space direction="vertical" size="middle" style={{ width: '100%', marginTop: 16 }}>
            {scheduledIconDrafts.map((item, index) => <Card key={item.key} size="small"
              title={`规则 ${index + 1}`}
              extra={<Button type="text" danger icon={<DeleteOutlined />}
                aria-label={`删除规则 ${index + 1}`}
                onClick={() => setScheduledIconDrafts(items => items.filter(rule => rule.key !== item.key))} />}>
              <Space direction="vertical" style={{ width: '100%' }}>
                <Input value={item.cronExpression} maxLength={128}
                  placeholder="例如：0 0 0 1 10 *"
                  onChange={event => updateScheduledIcon(item.key, { cronExpression: event.target.value })} />
                <Space wrap>
                  <Upload accept="image/png,image/jpeg" maxCount={1} showUploadList={false}
                    beforeUpload={file => { chooseScheduledIcon(item.key, file); return false }}>
                    <Button icon={<UploadOutlined />}>{item.id ? '替换图标' : '选择图标'}</Button>
                  </Upload>
                  <div className="custom-icon-preview scheduled-icon-preview">
                    <ScheduledIconPreview file={item.file} iconUrl={item.iconUrl} />
                    <Typography.Text type="secondary">
                      {item.file ? item.file.name : item.id ? '已保存的图标' : '尚未选择图标'}
                    </Typography.Text>
                  </div>
                </Space>
              </Space>
            </Card>)}
            {!scheduledIconDrafts.length && <Typography.Text type="secondary">暂无定时图标规则。</Typography.Text>}
            <Space wrap>
              <Button icon={<PlusOutlined />} disabled={scheduledIconDrafts.length >= 20}
                onClick={addScheduledIcon}>添加规则</Button>
              <Button type="primary" loading={savingScheduledIcons}
                onClick={() => void saveScheduledIcons()}>保存定时图标</Button>
            </Space>
          </Space>
        </DataState>
      </div>
    </div>
  </DataState>
}
