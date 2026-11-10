import {
  Alert, App, AutoComplete, Button, List, Modal, Popconfirm, Popover, Space, Spin, Tag, Typography,
} from 'antd'
import { EditOutlined, FolderOutlined, PictureOutlined } from '@ant-design/icons'
import { useEffect, useMemo, useState } from 'react'
import TagSelect from './TagSelect'
import { tagDisplayName } from './photoTags'
import {
  archiveItemKey, initialItemTags, isAllowedTag, recognizedTags, rejectedTags, removeTag, renameTag, tagUsage,
} from './zipFolderTags'
import type { ArchiveItem, ItemTags, TagRules } from './zipFolderTags'
import { createZipThumbnailer, type ZipThumbnailer } from './zipThumbnails'

const PAGE_SIZE = 20
const THUMB = 64

type Props = {
  items: ArchiveItem[]
  /** 上传者刚选的那个压缩包；拿不到（比如页面刷新过）时没有缩略图，只显示占位图。 */
  archive: File | null
  /** 整批共用的标签，打开时已经并进每张图里。 */
  sharedTags: string[]
  /** 标签框的候选（预设、相册里已有的等）。 */
  candidates: string[]
  rules: TagRules
  /** 提交逐张标签。抛出的错误显示在对话框里，对话框不关，改完可以再点一次。 */
  onConfirm: (tags: ItemTags) => Promise<void>
  /** 放弃这次上传：批次停在待整理，图片不会导入。 */
  onCancel: () => void
}

/**
 * ZIP 解包之后、落成照片之前的「确认标签」对话框（站内批量上传页和上传链接页共用）。
 *
 * <p>每层文件夹名识别成一个标签（规则见 zipFolderTags.ts），这里把每张图的缩略图、
 * 文件名、所在文件夹和标签摆出来让上传者核对：可以逐张改，也可以对某个标签整体改名或
 * 去掉——整个压缩包外面套了一层「照片导出」之类的文件夹时，一次去掉就好。确认之后
 * 才提交，提交前对话框关不掉（误触遮罩就丢了整批的修改）。</p>
 */
export default function ZipTagReviewModal({
  items, archive, sharedTags, candidates, rules, onConfirm, onCancel,
}: Props) {
  const { message } = App.useApp()
  const [tags, setTags] = useState<ItemTags>(() => initialItemTags(items, sharedTags))
  const [confirming, setConfirming] = useState(false)
  const [page, setPage] = useState(1)
  const [thumbnailer, setThumbnailer] = useState<ZipThumbnailer | null>(null)

  // 放在 effect 里而不是 useMemo：开发模式的 StrictMode 会先跑一遍清理，useMemo 留下的会是已回收的那个。
  useEffect(() => {
    if (!archive) return
    const created = createZipThumbnailer(archive)
    setThumbnailer(created)
    return () => created.dispose()
  }, [archive])

  const recognized = useMemo(() => recognizedTags(items), [items])
  const usage = useMemo(() => tagUsage(items, tags), [items, tags])
  const rejected = useMemo(() => rejectedTags(tags, rules), [tags, rules])
  const options = useMemo(() => [...new Set([...rules.presets, ...candidates, ...usage.map(entry => entry.tag)])],
    [rules.presets, candidates, usage])

  const confirm = async () => {
    setConfirming(true)
    try {
      await onConfirm(tags)
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setConfirming(false)
    }
  }

  return <Modal open width={960} title="确认识别出的标签" mask={{ closable: false }} closable={false} keyboard={false}
    // 手机上两颗按钮会折成两行，内容区再高就把「确认」挤出屏幕了。
    styles={{ body: { maxHeight: 'calc(100vh - 280px)', overflowY: 'auto' } }}
    footer={<Space wrap style={{ justifyContent: 'flex-end' }}>
      <Popconfirm title="放弃这次上传？" description="压缩包里的图片都不会导入，需要的话重新上传一次。"
        okText="放弃" okButtonProps={{ danger: true }} cancelText="再想想" onConfirm={onCancel}
        disabled={confirming}>
        <Button disabled={confirming}>放弃这次上传</Button>
      </Popconfirm>
      <Button type="primary" loading={confirming} disabled={!!rejected.length} onClick={() => void confirm()}>
        确认标签，完成上传（{items.length} 张）
      </Button>
    </Space>}>
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Paragraph style={{ marginBottom: 0 }}>
        按压缩包里的文件夹识别出 {recognized.length} 个标签：每一层文件夹的名称就是一个标签，
        已经分给了文件夹里的每一张图{sharedTags.length ? `；整批的标签「${sharedTags.join('、')}」也加在了每张图上` : ''}。
        请核对下面每张图的标签，有不对的直接改；<Typography.Text strong>确认之后这批图片才会导入</Typography.Text>。
      </Typography.Paragraph>

      <div>
        <Typography.Text type="secondary">
          所有标签（点铅笔整体改名，点叉从所有图片上去掉）：
        </Typography.Text>
        <div style={{ marginTop: 8, display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {usage.length ? usage.map(({ tag, count }) =>
            <Tag key={tag} closable color={isAllowedTag(tag, rules) ? 'blue' : 'error'} style={{ marginInlineEnd: 0 }}
              onClose={event => {
                event.preventDefault()
                setTags(current => removeTag(current, tag))
              }}>
              {tagDisplayName(tag)} · {count} 张
              <RenameTag tag={tag} options={options} rules={rules}
                onRename={to => setTags(current => renameTag(current, tag, to))} />
            </Tag>)
            : <Typography.Text type="secondary">现在没有任何标签</Typography.Text>}
        </div>
      </div>

      {!!rejected.length && <Alert type="error" showIcon
        message={`有 ${rejected.length} 个标签不能用：${rejected.map(tagDisplayName).join('、')}`}
        description={rules.restricted
          ? `这个选题只能用预设标签（${rules.presets.join('、') || '无'}）。请把它们整体改名成预设标签，或者去掉。`
          : '标签最长 50 个字，也不能用保留标签「不可用」。请改名或去掉。'} />}

      <List dataSource={items} rowKey="id"
        pagination={items.length > PAGE_SIZE
          ? { current: page, pageSize: PAGE_SIZE, onChange: setPage, size: 'small', showSizeChanger: false }
          : false}
        renderItem={item => <List.Item>
          <div style={{ display: 'flex', gap: 12, width: '100%', alignItems: 'flex-start' }}>
            <ArchiveThumbnail thumbnailer={thumbnailer} itemKey={archiveItemKey(item)} name={item.fileName} />
            <Space direction="vertical" size={4} style={{ flex: 1, minWidth: 0 }}>
              <Typography.Text strong ellipsis={{ tooltip: item.fileName }}>{item.fileName}</Typography.Text>
              <Typography.Text type="secondary" ellipsis={{ tooltip: item.folderPath || undefined }}>
                <FolderOutlined /> {item.folderPath ? item.folderPath.split('/').join(' / ') : '压缩包根目录'}
              </Typography.Text>
              <TagSelect size="small" presets={options} restricted={rules.restricted} value={tags[item.id] ?? []}
                onChange={value => setTags(current => ({ ...current, [item.id]: value ?? [] }))}
                placeholder="这张不打标签" style={{ width: '100%' }}
                tagRender={({ value, closable, onClose }) =>
                  <Tag color={isAllowedTag(String(value), rules) ? undefined : 'error'} closable={closable}
                    onClose={onClose} onMouseDown={event => event.preventDefault()} style={{ marginInlineEnd: 4 }}>
                    {tagDisplayName(String(value))}
                  </Tag>} />
            </Space>
          </div>
        </List.Item>} />
    </Space>
  </Modal>
}

function RenameTag({ tag, options, rules, onRename }: {
  tag: string
  options: string[]
  rules: TagRules
  onRename: (to: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [value, setValue] = useState(tag)
  const target = value.trim()
  const allowed = !!target && isAllowedTag(target, rules)
  const submit = () => {
    if (!allowed) return
    onRename(target)
    setOpen(false)
  }
  return <Popover open={open} trigger="click" title={`把「${tagDisplayName(tag)}」改成`}
    onOpenChange={next => {
      setOpen(next)
      if (next) setValue(tag)
    }}
    content={<Space direction="vertical" size={6}>
      <Space.Compact>
        <AutoComplete value={value} onChange={setValue} style={{ width: 200 }} autoFocus
          options={options.filter(option => option !== tag).map(option => ({ value: option }))}
          filterOption={(input, option) => String(option?.value ?? '').includes(input)}
          onKeyDown={event => { if (event.key === 'Enter') submit() }} />
        <Button type="primary" disabled={!allowed} onClick={submit}>确定</Button>
      </Space.Compact>
      {!!target && !allowed && <Typography.Text type="danger">
        {rules.restricted ? '只能改成选题的预设标签' : '标签最长 50 个字，也不能用保留标签'}
      </Typography.Text>}
    </Space>}>
    <EditOutlined style={{ marginInlineStart: 6, cursor: 'pointer' }} aria-label={`给标签「${tag}」改名`} />
  </Popover>
}

function ArchiveThumbnail({ thumbnailer, itemKey, name }: {
  thumbnailer: ZipThumbnailer | null
  itemKey: string
  name: string
}) {
  // 按 key 记结果：翻页复用同一个组件实例时，旧的那张不会串到新的一行上。
  const [result, setResult] = useState<{ key: string; url: string | null } | null>(null)
  useEffect(() => {
    if (!thumbnailer) return
    let alive = true
    void thumbnailer.thumbnail(itemKey).then(url => { if (alive) setResult({ key: itemKey, url }) })
    return () => { alive = false }
  }, [thumbnailer, itemKey])

  const box = {
    width: THUMB, height: THUMB, flex: 'none', borderRadius: 6, overflow: 'hidden',
    background: 'var(--ant-color-fill-tertiary, rgba(0,0,0,0.04))',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
  } as const
  const current = result?.key === itemKey ? result : null
  if (current?.url) {
    return <div style={box}>
      <img src={current.url} alt={name} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
    </div>
  }
  if (thumbnailer && !current) return <div style={box}><Spin size="small" /></div>
  return <div style={box} title="没能生成预览"><PictureOutlined style={{ fontSize: 22, opacity: 0.45 }} /></div>
}
