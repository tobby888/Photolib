import {
  Alert, App, Button, Checkbox, DatePicker, Form, Input, Modal, Popconfirm, Space, Tag, Typography,
} from 'antd'
import { CopyOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useState } from 'react'
import { api } from './api'
import { ContentFitTable } from './ContentFitTable'
import { useLoad } from './hooks'
import type { EntityId, ProjectShareLink } from './types'

interface IssuedSecret {
  linkId: EntityId
  password: string
}

const shareUrl = (token: string) => `${window.location.origin}/#/share/${token}`

/**
 * 选题项目的分享链接管理。
 *
 * <p>密码只在创建和重置时由服务端回一次明文，之后库里只有哈希。因此这里把刚拿到的
 * 那一份放在组件状态里显著地摆出来并提示"只显示这一次"——关掉弹窗就再也看不到，
 * 想要新的只能重置。不要为了"方便"改成把明文存进数据库。</p>
 *
 * <p>权限开关（下载、标记被引）改完立刻对已经在看的访客生效：服务端每次请求都重新
 * 读链接行，不缓存在会话里。</p>
 */
export default function ProjectShareLinksModal({ projectId, open, onClose }: {
  projectId: string
  open: boolean
  onClose: () => void
}) {
  const { message } = App.useApp()
  const [createForm] = Form.useForm()
  const [createOpen, setCreateOpen] = useState(false)
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
    const values = await createForm.validateFields()
    setSaving(true)
    try {
      const created = await api<{ link: ProjectShareLink; password: string }>({
        method: 'POST',
        url: `/projects/${projectId}/share-links`,
        data: {
          name: values.name || null,
          password: values.password || null,
          allowDownload: !!values.allowDownload,
          allowAdoption: !!values.allowAdoption,
          expiresAt: values.expiresAt ? values.expiresAt.format('YYYY-MM-DDTHH:mm:ss') : null,
        },
      })
      setSecret({ linkId: created.link.id, password: created.password })
      setCreateOpen(false)
      createForm.resetFields()
      message.success('分享链接已生成')
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

  return <Modal title="对外分享链接" width={980} open={open} onCancel={onClose} footer={null} destroyOnHidden>
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Paragraph type="secondary">
        分享链接让没有账号的人凭“链接 + 密码”查看这个项目里的图片。是否允许下载、是否允许标记被引，
        由每条链接单独决定，随时可改。链接看到的图片和被引状态与项目内完全同步。
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

      <Space>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>生成分享链接</Button>
      </Space>

      <ContentFitTable<ProjectShareLink> rowKey="id" size="small" loading={loading} dataSource={links}
        pagination={false} locale={{ emptyText: '还没有为这个项目生成分享链接' }}
        columns={[
          {
            title: '链接', dataIndex: 'token', render: (token: string, link) => <div className="table-title">
              <strong>{link.name || '未命名分享'}</strong>
              <span className="table-ellipsis-text" title={shareUrl(token)}>{shareUrl(token)}</span>
            </div>,
          },
          {
            title: '访客权限', render: (_, link) => <Space direction="vertical" size={2}>
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
            title: '访问次数', dataIndex: 'viewCount', render: (value: number, link) => <div className="table-title">
              <strong>{value}</strong>
              <span>{link.lastViewedAt ? `最近 ${dayjs(link.lastViewedAt).format('MM-DD HH:mm')}` : '尚未被访问'}</span>
            </div>,
          },
          {
            title: '操作', render: (_, link) => <Space wrap>
              <Button type="link" icon={<CopyOutlined />}
                onClick={() => void copy(shareUrl(link.token), '分享链接')}>复制链接</Button>
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

    <Modal title="生成分享链接" open={createOpen} onCancel={() => setCreateOpen(false)} onOk={create}
      okText="生成链接" confirmLoading={saving} destroyOnHidden>
      <Form form={createForm} layout="vertical" requiredMark={false}
        initialValues={{ allowDownload: true, allowAdoption: false }}>
        <Form.Item label="备注" name="name" extra="只给自己看，例如“校报编辑部”" rules={[{ max: 100 }]}>
          <Input placeholder="选填" />
        </Form.Item>
        <Form.Item label="访问密码" name="password"
          extra="留空则自动生成一个 10 位随机密码。生成后只显示一次。"
          rules={[{ min: 6, message: '密码至少 6 位' }, { max: 64 }]}>
          <Input placeholder="留空自动生成" />
        </Form.Item>
        <Form.Item label="失效时间" name="expiresAt" extra="留空表示长期有效，可随时删除链接">
          <DatePicker showTime format="YYYY-MM-DD HH:mm" style={{ width: '100%' }}
            disabledDate={date => date.isBefore(dayjs(), 'day')} />
        </Form.Item>
        <Form.Item name="allowDownload" valuePropName="checked">
          <Checkbox>允许访客下载图片（含批量打包下载）</Checkbox>
        </Form.Item>
        <Form.Item name="allowAdoption" valuePropName="checked">
          <Checkbox>允许访客标记被引（与项目内的被引记录同一份）</Checkbox>
        </Form.Item>
      </Form>
    </Modal>
  </Modal>
}
