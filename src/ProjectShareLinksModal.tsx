import {
  Alert, App, Button, Checkbox, DatePicker, Form, Input, Modal, Popconfirm, Radio, Space, Tag, Typography,
} from 'antd'
import { CloudUploadOutlined, CopyOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useState } from 'react'
import { api } from './api'
import { ContentFitTable } from './ContentFitTable'
import { useLoad } from './hooks'
import type { EntityId, ProjectShareLink, ShareLinkPurpose } from './types'

interface IssuedSecret {
  linkId: EntityId
  password: string
}

/** 两种用途各有自己的访客页，链接也就不是同一个地址。 */
const linkUrl = (link: Pick<ProjectShareLink, 'token' | 'purpose'>) =>
  `${window.location.origin}/#/${link.purpose === 'UPLOAD' ? 'upload' : 'share'}/${link.token}`

/**
 * 选题项目的对外链接管理：分享（看相册）和上传（往里传图）两种。
 *
 * <p>密码只在创建和重置时由服务端回一次明文，之后库里只有哈希。因此这里把刚拿到的
 * 那一份放在组件状态里显著地摆出来并提示"只显示这一次"——关掉弹窗就再也看不到，
 * 想要新的只能重置。不要为了"方便"改成把明文存进数据库。</p>
 *
 * <p>权限开关（下载、标记被引）改完立刻对已经在看的访客生效：服务端每次请求都重新
 * 读链接行，不缓存在会话里。</p>
 *
 * <p>用途建立后不可改，两种能力互斥：上传链接看不到相册，分享链接传不了图。所以
 * 这里只在创建时让人选一次，表格里把它作为一列如实标出来——同一个列表里混着两种
 * 完全不同的东西，不写清楚就会有人把上传链接当相册发出去。上传链接只有进行中的
 * 选题能开（{@code uploadLinksAvailable}，创作选题和活动选题都可以），与服务端同一条规则。</p>
 */
export default function ProjectShareLinksModal({
  projectId, open, onClose, uploadLinksAvailable, selectedPhotoIds = [], startWithSelected,
}: {
  projectId: string
  open: boolean
  onClose: () => void
  /** 进行中的选题才能开上传链接；其余状态只显示分享链接的入口。 */
  uploadLinksAvailable?: boolean
  /** 相册里此刻勾选的图片。生成分享链接时可以只开放这几张。 */
  selectedPhotoIds?: string[]
  /** 从相册工具栏「分享所选」进来：直接打开生成表单，并默认只开放所选图片。 */
  startWithSelected?: boolean
}) {
  const { message } = App.useApp()
  const [createForm] = Form.useForm()
  const [createPurpose, setCreatePurpose] = useState<ShareLinkPurpose | null>(
    startWithSelected && selectedPhotoIds.length ? 'BROWSE' : null)
  // 表单的默认范围跟着入口走：从「分享所选」进来默认只开放所选；在弹窗里点「生成分享链接」默认整个相册。
  // 生成表单是 destroyOnHidden 的，每次打开都按 initialValues 重新挂载，所以在打开前定好即可。
  const [defaultScope, setDefaultScope] = useState<'ALL' | 'SELECTED'>(
    startWithSelected && selectedPhotoIds.length ? 'SELECTED' : 'ALL')
  const photoScope = Form.useWatch('photoScope', createForm) as 'ALL' | 'SELECTED' | undefined
  const [saving, setSaving] = useState(false)
  const [secret, setSecret] = useState<IssuedSecret | null>(null)
  const { data: links, loading, error, reload } = useLoad(
    () => open
      ? api<ProjectShareLink[]>({ url: `/projects/${projectId}/share-links` })
      : Promise.resolve([]),
    [] as ProjectShareLink[],
    [open, projectId],
  )

  const copy = async (text: string, label: string) => {
    try {
      await navigator.clipboard.writeText(text)
      message.success(`${label}已复制`)
    } catch {
      // 非安全上下文或用户拒绝了剪贴板权限：把内容摆出来让人自己复制。
      message.info(`${label}：${text}`)
    }
  }

  const create = async () => {
    const purpose = createPurpose
    if (!purpose) return
    const values = await createForm.validateFields()
    setSaving(true)
    try {
      const created = await api<{ link: ProjectShareLink; password: string }>({
        method: 'POST',
        url: `/projects/${projectId}/share-links`,
        data: {
          purpose,
          name: values.name || null,
          password: values.password || null,
          // 上传链接没有这两项能力，服务端也会强制落成 false，这里不发出去以免看着像可选的。
          allowDownload: purpose === 'BROWSE' && !!values.allowDownload,
          allowAdoption: purpose === 'BROWSE' && !!values.allowAdoption,
          // 只开放所选：发的是此刻的勾选。范围建立后不可改，要换一批图就另开一条链接。
          photoIds: purpose === 'BROWSE' && values.photoScope === 'SELECTED' ? selectedPhotoIds : null,
          expiresAt: values.expiresAt ? values.expiresAt.format('YYYY-MM-DDTHH:mm:ss') : null,
        },
      })
      setSecret({ linkId: created.link.id, password: created.password })
      setCreatePurpose(null)
      createForm.resetFields()
      message.success(purpose === 'UPLOAD' ? '上传链接已生成' : '分享链接已生成')
      await reload()
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const updatePermissions = async (link: ProjectShareLink, changes: Partial<ProjectShareLink>) => {
    try {
      await api({
        method: 'PUT',
        url: `/projects/${projectId}/share-links/${link.id}`,
        data: {
          name: link.name ?? null,
          allowDownload: changes.allowDownload ?? link.allowDownload,
          allowAdoption: changes.allowAdoption ?? link.allowAdoption,
          expiresAt: link.expiresAt ?? null,
          version: link.version,
        },
      })
      message.success('分享权限已更新，正在浏览的访客立即生效')
      await reload()
    } catch (reason) {
      message.error((reason as Error).message)
    }
  }

  const resetPassword = async (link: ProjectShareLink) => {
    try {
      const result = await api<{ link: ProjectShareLink; password: string }>({
        method: 'POST',
        url: `/projects/${projectId}/share-links/${link.id}/password`,
        data: { password: null },
      })
      setSecret({ linkId: link.id, password: result.password })
      message.success('密码已重置，之前发出的访问全部失效')
      await reload()
    } catch (reason) {
      message.error((reason as Error).message)
    }
  }

  const remove = async (link: ProjectShareLink) => {
    try {
      await api({ method: 'DELETE', url: `/projects/${projectId}/share-links/${link.id}` })
      if (secret?.linkId === link.id) setSecret(null)
      message.success('分享链接已删除')
      await reload()
    } catch (reason) {
      message.error((reason as Error).message)
    }
  }

  return <Modal title="对外链接" width={980} open={open} onCancel={onClose} footer={null} destroyOnHidden>
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Paragraph type="secondary">
        分享链接让没有账号的人凭“链接 + 密码”查看这个项目里的图片。是否允许下载、是否允许标记被引，
        由每条链接单独决定，随时可改。链接看到的图片和被引状态与项目内完全同步。
        {uploadLinksAvailable && '上传链接则相反：拿到的人只能把照片传进这个选题，看不到里面已有的任何图片；'
          + '传完之后可以在同一页申报工时，申报直接进入工时审核面板。'}
      </Typography.Paragraph>

      {secret && <Alert type="success" showIcon
        message="密码只显示这一次，请立即复制保存"
        description={<Space wrap>
          <Typography.Text code style={{ fontSize: 16 }}>{secret.password}</Typography.Text>
          <Button size="small" icon={<CopyOutlined />}
            onClick={() => void copy(secret.password, '密码')}>复制密码</Button>
        </Space>}
        closable onClose={() => setSecret(null)} />}

      {error && <Alert type="error" showIcon message="分享链接没能加载出来" description={error}
        action={<Button size="small" onClick={reload}>重试</Button>} />}

      <Space wrap>
        <Button type="primary" icon={<PlusOutlined />}
          onClick={() => { setDefaultScope('ALL'); setCreatePurpose('BROWSE') }}>生成分享链接</Button>
        {uploadLinksAvailable && <Button icon={<CloudUploadOutlined />}
          onClick={() => setCreatePurpose('UPLOAD')}>生成上传链接</Button>}
      </Space>

      <ContentFitTable<ProjectShareLink> rowKey="id" size="small" loading={loading} dataSource={links}
        pagination={false} locale={{ emptyText: '还没有为这个项目生成对外链接' }}
        columns={[
          {
            title: '链接', render: (_, link) => <div className="table-title">
              <strong>{link.name || (link.purpose === 'UPLOAD' ? '未命名上传链接' : '未命名分享')}</strong>
              <span className="table-ellipsis-text" title={linkUrl(link)}>{linkUrl(link)}</span>
            </div>,
          },
          {
            // 限定了范围的链接要一眼认得出来：同样是「查看相册」，一条给了整个相册、一条只给了几张。
            // 和用途放在同一格里，单独占一列会把操作列挤出弹窗。
            title: '用途', dataIndex: 'purpose', render: (purpose: ShareLinkPurpose, link) =>
              purpose === 'UPLOAD'
                ? <Tag color="purple">上传图片</Tag>
                : <Space orientation="vertical" size={2}>
                  <Tag color="blue">查看相册</Tag>
                  {link.selectedPhotosOnly
                    ? <Tag color="gold">仅指定的 {link.selectedPhotoCount} 张</Tag>
                    : <Typography.Text type="secondary">整个相册</Typography.Text>}
                </Space>,
          },
          {
            // 上传链接没有这两项能力，服务端一律按 false 算，所以这里不给可点的开关——
            // 摆一个点不动的勾选框只会让人以为是坏了。
            title: '访客权限', render: (_, link) => link.purpose === 'UPLOAD'
              ? <Typography.Text type="secondary">仅上传，看不到相册</Typography.Text>
              : <Space direction="vertical" size={2}>
                <Checkbox checked={link.allowDownload}
                  onChange={event => void updatePermissions(link, { allowDownload: event.target.checked })}>
                  下载图片
                </Checkbox>
                <Checkbox checked={link.allowAdoption}
                  onChange={event => void updatePermissions(link, { allowAdoption: event.target.checked })}>
                  标记被引
                </Checkbox>
              </Space>,
          },
          {
            title: '有效期', dataIndex: 'expiresAt', render: (value: string | null, link) => link.expired
              ? <Tag color="red">已失效</Tag>
              : value ? dayjs(value).format('YYYY-MM-DD HH:mm') : <Tag>长期有效</Tag>,
          },
          {
            title: '使用情况', dataIndex: 'viewCount', render: (value: number, link) => <div className="table-title">
              <strong>{link.purpose === 'UPLOAD' ? `已传 ${link.uploadCount} 张` : `${value} 次访问`}</strong>
              <span>{link.lastViewedAt ? `最近 ${dayjs(link.lastViewedAt).format('MM-DD HH:mm')}` : '尚未被访问'}</span>
            </div>,
          },
          {
            title: '操作', render: (_, link) => <Space wrap>
              <Button type="link" icon={<CopyOutlined />}
                onClick={() => void copy(linkUrl(link), link.purpose === 'UPLOAD' ? '上传链接' : '分享链接')}>
                复制链接</Button>
              <Popconfirm title="重置这条链接的密码？" description="重置后旧密码立即失效，正在浏览的访客也要重新输入。"
                okText="确认重置" onConfirm={() => resetPassword(link)}>
                <Button type="link" icon={<ReloadOutlined />}>重置密码</Button>
              </Popconfirm>
              <Popconfirm title="删除这条分享链接？" description="删除后该链接立即失效，已经打开页面的访客也会被挡住。"
                okText="确认删除" okButtonProps={{ danger: true }} onConfirm={() => remove(link)}>
                <Button type="link" danger>删除</Button>
              </Popconfirm>
            </Space>,
          },
        ]} />
    </Space>

    <Modal title={createPurpose === 'UPLOAD' ? '生成上传链接' : '生成分享链接'}
      open={!!createPurpose} onCancel={() => setCreatePurpose(null)} onOk={create}
      okText="生成链接" confirmLoading={saving} destroyOnHidden>
      <Form form={createForm} layout="vertical" requiredMark={false}
        initialValues={{ allowDownload: true, allowAdoption: false, photoScope: defaultScope }}>
        {createPurpose === 'UPLOAD' && <Typography.Paragraph type="secondary">
          拿到链接和密码的人可以直接把照片传进这个选题的图库。他们进门时要填写本人姓名和学号，
          作为所传照片的拍摄者；这条链接看不到选题里已有的任何图片。传完之后他们可以用同一份姓名学号
          申报工时，申报直接进入「工时记录」等部长或管理员确认，退回通知会发给你。
        </Typography.Paragraph>}
        <Form.Item label="备注" name="name"
          extra={createPurpose === 'UPLOAD' ? '只给自己看，例如“活动当天的摄影组”；工时审核面板里也会显示它' : '只给自己看，例如“校报编辑部”'}
          rules={[{ max: 100 }]}>
          <Input placeholder="选填" />
        </Form.Item>
        <Form.Item label="访问密码" name="password"
          extra="留空则自动生成一个 10 位随机密码。生成后只显示一次。"
          rules={[{ min: 6, message: '密码至少 6 位' }, { max: 64 }]}>
          <Input placeholder="留空自动生成" />
        </Form.Item>
        <Form.Item label="失效时间" name="expiresAt"
          extra={createPurpose === 'UPLOAD'
            ? '建议设成拍摄结束后几天。留空表示长期有效，可随时删除链接'
            : '留空表示长期有效，可随时删除链接'}>
          <DatePicker showTime format="YYYY-MM-DD HH:mm" style={{ width: '100%' }}
            disabledDate={date => date.isBefore(dayjs(), 'day')} />
        </Form.Item>
        {createPurpose === 'BROWSE' && <>
          {/* preserve={false}：表单仓库默认留着上次的值，而它比 initialValues 优先，
              取消后再从另一个入口打开就会带着上一次的范围。 */}
          <Form.Item label="可查看的图片" name="photoScope" preserve={false}
            extra={photoScope === 'SELECTED'
              ? '访客只看得到这几张；之后新加进相册的图片不会出现在这条链接里。图片被移出选题后，访客也看不到了。'
              : selectedPhotoIds.length
                ? '访客看到的与选题相册同步，之后新加的图片也会出现'
                : '想只分享其中几张？先在相册里勾选图片，再点「分享所选」'}>
            <Radio.Group>
              <Radio value="ALL">整个相册</Radio>
              <Radio value="SELECTED" disabled={!selectedPhotoIds.length}>
                仅已勾选的 {selectedPhotoIds.length} 张
              </Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item name="allowDownload" valuePropName="checked">
            <Checkbox>允许访客下载图片（含批量打包下载）</Checkbox>
          </Form.Item>
          <Form.Item name="allowAdoption" valuePropName="checked">
            <Checkbox>允许访客标记被引（与项目内的被引记录同一份）</Checkbox>
          </Form.Item>
        </>}
      </Form>
    </Modal>
  </Modal>
}
