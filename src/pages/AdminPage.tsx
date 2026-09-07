import {
  Alert, App, Button, Card, Divider, Form, Input, Modal, Select, Space, Switch, Tabs, Tag, Typography, Upload,
} from 'antd'
import {
  AimOutlined, BgColorsOutlined, BulbOutlined, CameraOutlined, DatabaseOutlined, PictureOutlined,
  ClockCircleOutlined, DeleteOutlined, EditOutlined, FileTextOutlined, LayoutOutlined, PlusOutlined,
  SafetyCertificateOutlined, StarOutlined, TeamOutlined, UploadOutlined,
} from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { api, emptyPage } from '../api'
import type {
  BrandingSettings, Campus, FooterLink, PageData, PermissionGroup, PlaceholderImage, ScheduledBrandIcon, User,
} from '../types'
import { BRANDING_UPDATED_EVENT, defaultBranding } from '../branding'
import { DataState, PageTitle } from '../components'
import { ContentFitTable } from '../ContentFitTable'
import { useLoad } from '../hooks'
import AuditLogsPanel from '../AuditLogsPanel'
import DatabaseBackupPanel from '../DatabaseBackupPanel'
import PermissionGroupsPanel from '../PermissionGroupsPanel'
import UserAvatar from '../UserAvatar'
import { USER_ACTION_MIN_WIDTH } from '../tableActionWidths'

interface BrandingFormValues {
  title: string
  slogan: string
  iconChoice: BrandingSettings['builtinIcon'] | 'custom'
}

interface SiteContentFormValues {
  loginHeadline?: string
  loginSubheadline?: string
  loginHighlights?: string[]
  loginNotice?: string
  footerText?: string
  footerLinks?: Partial<FooterLink>[]
}

interface AccountFormValues {
  email?: string
  wecomUserid?: string
}

/** 企业微信通讯录对 userid 的约束：数字、字母和 _-@. 四种半角符号，1~64 字节。 */
const WECOM_USERID_PATTERN = /^[A-Za-z0-9_@.-]{1,64}$/

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

export default function AdminPage() {
  const { message, modal } = App.useApp()
  const [userForm] = Form.useForm()
  const [accountForm] = Form.useForm<AccountFormValues>()
  const [campusForm] = Form.useForm()
  const [brandingForm] = Form.useForm<BrandingFormValues>()
  const [siteForm] = Form.useForm<SiteContentFormValues>()
  const [userOpen, setUserOpen] = useState(false)
  const [editingUser, setEditingUser] = useState<User | null>(null)
  const [campusOpen, setCampusOpen] = useState(false)
  const [userSearchText, setUserSearchText] = useState('')
  const [userKeyword, setUserKeyword] = useState('')
  const { data: users, loading, error, reload } = useLoad(
    () => api<PageData<User>>({
      url: '/users',
      params: { page: 1, pageSize: 100, keyword: userKeyword || undefined },
    }), emptyPage<User>(), [userKeyword],
  )
  const { data: campuses, reload: reloadCampuses } = useLoad(
    () => api<Campus[]>({ url: '/campuses' }), [] as Campus[], [],
  )
  const { data: permissionGroups } = useLoad(
    () => api<PermissionGroup[]>({ url: '/permission-groups' }), [] as PermissionGroup[], [],
  )
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
    if (file.size > 512 * 1024) {
      message.error('图标不能超过 512 KiB')
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
    if (file.size > 512 * 1024) {
      message.error('图标不能超过 512 KiB')
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
    const tooLarge = files.find(file => file.size > 3 * 1024 * 1024)
    if (tooLarge) {
      message.error(`${tooLarge.name}：占位图不能超过 3 MiB`)
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
  const createUser = async () => {
    try {
      const result = await api<{ user: User; initialPassword: string }>({ method: 'POST', url: '/users', data: await userForm.validateFields() })
      setUserOpen(false); userForm.resetFields(); await reload()
      modal.success({ title: '账号创建成功', content: <div><p>请通过安全渠道把初始密码交给用户，此密码只显示一次。</p><Typography.Text copyable code>{result.initialPassword}</Typography.Text></div> })
    } catch (e) { message.error((e as Error).message) }
  }
  const createCampus = async () => {
    try {
      await api({ method: 'POST', url: '/campuses', data: await campusForm.validateFields() })
      message.success('校区已创建'); setCampusOpen(false); campusForm.resetFields(); await reloadCampuses()
    } catch (e) { message.error((e as Error).message) }
  }
  const toggleUser = async (user: User) => {
    try {
      await api({ method: 'POST', url: `/users/${user.id}/${user.enabled ? 'disable' : 'enable'}` })
      message.success(user.enabled ? '账号已停用' : '账号已启用'); await reload()
    } catch (e) { message.error((e as Error).message) }
  }
  const openAccountEditor = (user: User) => {
    setEditingUser(user)
    accountForm.setFieldsValue({ email: user.email, wecomUserid: user.wecomUserid ?? undefined })
  }
  const updateUserAccount = async () => {
    if (!editingUser || editingUser.version == null) return
    try {
      const values = await accountForm.validateFields()
      await api<User>({
        method: 'PUT',
        url: `/users/${editingUser.id}`,
        data: {
          displayName: editingUser.displayName,
          permissionGroupId: editingUser.permissionGroupId,
          campusIds: editingUser.campusIds || [],
          role: null,
          campusId: editingUser.campusId,
          phone: editingUser.phone,
          email: values.email?.trim() || null,
          wecomUserid: values.wecomUserid?.trim() || null,
          enabled: editingUser.enabled ?? true,
          version: editingUser.version,
        },
      })
      message.success('账号信息已更新')
      setEditingUser(null)
      accountForm.resetFields()
      await reload()
    } catch (e) { message.error((e as Error).message) }
  }
  const deleteUser = (user: User) => {
    modal.confirm({
      title: '删除账号', okText: '删除', okButtonProps: { danger: true }, cancelText: '取消',
      content: <span>确定删除账号 <strong>{user.displayName}</strong>（@{user.username}）吗？账号将被停用并从列表中移除，其历史记录仍会保留。</span>,
      onOk: async () => {
        try {
          await api({ method: 'DELETE', url: `/users/${user.id}` })
          message.success('账号已删除'); await reload()
        } catch (e) { message.error((e as Error).message); throw e }
      },
    })
  }
  return <>
    <PageTitle eyebrow="ADMINISTRATION" title="系统管理" description="维护账号、校区和系统运行秩序。" />
    <Card>
      <Tabs items={[
        { key: 'branding', label: <span><BgColorsOutlined /> 面板品牌</span>, children:
          <DataState loading={brandingLoading} error={brandingError} onRetry={reloadBranding}>
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
                <Form.Item label="上传自定义图标" extra="支持 PNG、JPEG；文件不超过 512 KiB，尺寸不超过 1024 × 1024 像素。">
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
          </DataState> },
        { key: 'site', label: <span><LayoutOutlined /> 页面定制</span>, children:
          <DataState loading={brandingLoading} error={brandingError} onRetry={reloadBranding}>
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
                    一张都不传就沿用内置的灰底占位。最多 12 张，单张不超过 3 MiB。
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
          </DataState> },
        { key: 'users', label: <span><TeamOutlined /> 账号管理</span>, children: <>
          <div className="tab-toolbar"><div><Typography.Title level={4}>成员账号</Typography.Title><Typography.Text type="secondary">系统不开放注册，账号均由管理员创建。</Typography.Text></div>
            <Space wrap>
              <Input.Search
                allowClear
                value={userSearchText}
                placeholder="搜索成员姓名、账号或邮箱"
                style={{ width: 280 }}
                onChange={event => {
                  setUserSearchText(event.target.value)
                  if (!event.target.value) setUserKeyword('')
                }}
                onSearch={value => setUserKeyword(value.trim())}
              />
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setUserOpen(true)}>创建账号</Button>
            </Space></div>
          <DataState loading={loading} error={error} empty={!users.items.length} onRetry={reload}
            emptyText={userKeyword ? `没有匹配“${userKeyword}”的账号` : '还没有创建任何成员账号'}
            emptyHint={userKeyword
              ? '姓名、账号和邮箱都会被搜索，换个词再试试。'
              : '系统不开放注册，成员账号都要在这里创建。'}>
            <ContentFitTable rowKey="id" dataSource={users.items} pagination={{ pageSize: 12 }} columns={[
              { title: '成员', render: (_, item) => <Space>
                <UserAvatar size={38} avatarUrl={item.avatarUrl} label={item.displayName}
                  style={{ background: '#edf3f0', color: '#28594f' }} />
                <div className="table-title"><strong>{item.displayName}</strong><span>@{item.username}</span></div>
              </Space> },
              { title: '权限组', dataIndex: 'permissionGroupName', render: value => <Tag variant="filled">{value || '待分配'}</Tag> },
              { title: '授权校区', dataIndex: 'campusIds', render: (values: string[] = []) => values.length
                ? values.map(value => campuses.find(c => c.id === value)?.name || `#${value}`).join('、') : '-' },
              { title: '联系方式', render: (_, item) => item.email || item.phone || '-' },
              // 没绑企微 = 收不到任何外发通知，这一列让管理员一眼看出还差谁。
              { title: '通知', render: (_, item) => item.wecomUserid
                ? <Tag color="green" variant="filled">企微 {item.wecomUserid}</Tag>
                : <Tag variant="filled">仅站内信</Tag> },
              { title: '状态', dataIndex: 'enabled', render: value => <Tag color={value ? 'green' : 'default'} variant="filled">{value ? '正常' : '已停用'}</Tag> },
              { title: '操作', fixed: 'right', minWidth: USER_ACTION_MIN_WIDTH, className: 'table-action-cell', render: (_, item) => <Space><Switch size="small" checked={item.enabled} onChange={() => void toggleUser(item)} /><Button type="link" icon={<EditOutlined />} onClick={() => openAccountEditor(item)}>修改账号</Button><Button type="link" onClick={async () => {
                try { const result = await api<{ initialPassword: string }>({ method: 'PUT', url: `/users/${item.id}/password` }); modal.warning({ title: '密码已重置', content: <Typography.Text copyable code>{result.initialPassword}</Typography.Text> }) } catch (e) { message.error((e as Error).message) }
              }}>重置密码</Button><Button type="link" danger onClick={() => deleteUser(item)}>删除</Button></Space> },
            ]} />
          </DataState>
        </> },
        { key: 'permissions', label: <span><SafetyCertificateOutlined /> 权限管理</span>, children: <PermissionGroupsPanel /> },
        { key: 'campuses', label: <span><SafetyCertificateOutlined /> 校区管理</span>, children: <>
          <div className="tab-toolbar"><div><Typography.Title level={4}>校区资源</Typography.Title><Typography.Text type="secondary">校区代码创建后不可修改。</Typography.Text></div>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCampusOpen(true)}>新建校区</Button></div>
          <ContentFitTable rowKey="id" dataSource={campuses} pagination={false} columns={[
            { title: '代码', dataIndex: 'code', render: value => <Typography.Text code>{value}</Typography.Text> },
            { title: '校区名称', dataIndex: 'name' }, { title: '状态', dataIndex: 'enabled', render: value => <Tag color={value ? 'green' : 'default'}>{value ? '已启用' : '已停用'}</Tag> },
            { title: '版本', dataIndex: 'version', render: value => `v${value}` },
          ]} />
        </> },
        { key: 'audit-logs', label: <span><FileTextOutlined /> 操作日志</span>, children: <AuditLogsPanel /> },
        // 数据库备份/回滚只对系统管理员开放，刻意没有对应的权限项，因此不出现在权限面板里。
        { key: 'backups', label: <span><DatabaseOutlined /> 数据备份</span>, children: <DatabaseBackupPanel /> },
      ]} />
    </Card>
    <Modal title="创建成员账号" open={userOpen} onCancel={() => setUserOpen(false)} onOk={createUser} okText="创建账号">
      <Form form={userForm} layout="vertical" requiredMark={false}>
        <Form.Item label="登录账号" name="username" rules={[{ required: true }, { pattern: /^[A-Za-z0-9_.-]{3,64}$/, message: '3-64 位字母、数字或 ._-' }]}><Input /></Form.Item>
        <Form.Item label="显示姓名" name="displayName" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item label="权限组" name="permissionGroupId" rules={[{ required: true }]}>
          <Select options={permissionGroups.map(group => ({ value: group.id, label: group.name }))} />
        </Form.Item>
        <Form.Item noStyle shouldUpdate={(prev, next) => prev.permissionGroupId !== next.permissionGroupId}>
          {({ getFieldValue }) => permissionGroups.find(group => group.id === getFieldValue('permissionGroupId'))?.dataScope === 'CAMPUS'
            && <Form.Item label="授权校区" name="campusIds" preserve={false}
              rules={[{ required: true, message: '请至少选择一个校区' }]}>
              <Select mode="multiple" options={campuses.filter(c => c.enabled).map(c => ({ value: c.id, label: c.name }))} />
            </Form.Item>}
        </Form.Item>
        <Form.Item label="邮箱" name="email" extra="填写后，用户可使用该邮箱登录。" rules={[{ type: 'email' }, { max: 255 }]}><Input /></Form.Item>
        <Form.Item label="手机号" name="phone"><Input /></Form.Item>
        <Form.Item label="企业微信 UserID" name="wecomUserid"
          extra="企业微信管理后台「通讯录」中该成员的账号；留空则该成员只收站内信。"
          rules={[{ pattern: WECOM_USERID_PATTERN, message: '只能包含字母、数字和 _-@. ，且不超过 64 个字符' }]}>
          <Input placeholder="例如 ZhangSan" />
        </Form.Item>
      </Form>
    </Modal>
    <Modal
      title={`修改 ${editingUser?.displayName || ''} 的账号信息`}
      open={editingUser !== null}
      onCancel={() => { setEditingUser(null); accountForm.resetFields() }}
      onOk={() => void updateUserAccount()}
      okText="保存"
    >
      <Typography.Paragraph type="secondary">邮箱用于登录，不区分大小写，留空则取消邮箱登录；企业微信 UserID 用于接收通知，留空则该成员只收站内信。</Typography.Paragraph>
      <Form form={accountForm} layout="vertical" requiredMark={false}>
        <Form.Item label="邮箱" name="email" rules={[{ type: 'email', message: '请输入有效的邮箱地址' }, { max: 255, message: '邮箱不能超过 255 个字符' }]}>
          <Input placeholder="例如 name@example.com" autoComplete="email" />
        </Form.Item>
        <Form.Item label="企业微信 UserID" name="wecomUserid"
          extra="企业微信管理后台「通讯录」中该成员的账号，注意不是姓名或手机号。"
          rules={[{ pattern: WECOM_USERID_PATTERN, message: '只能包含字母、数字和 _-@. ，且不超过 64 个字符' }]}>
          <Input placeholder="例如 ZhangSan" autoComplete="off" />
        </Form.Item>
      </Form>
    </Modal>
    <Modal title="新建校区" open={campusOpen} onCancel={() => setCampusOpen(false)} onOk={createCampus}>
      <Form form={campusForm} layout="vertical" requiredMark={false}>
        <Form.Item label="校区代码" name="code" rules={[{ required: true }, { pattern: /^[A-Za-z0-9_-]{2,32}$/ }]}><Input placeholder="例如 SOUTH" /></Form.Item>
        <Form.Item label="校区名称" name="name" rules={[{ required: true }]}><Input placeholder="例如 南校区" /></Form.Item>
      </Form>
    </Modal>
  </>
}
