import { Alert, App, Button, Modal, Space, Typography } from 'antd'
import { CopyOutlined } from '@ant-design/icons'
import { useMemo } from 'react'
import { api } from './api'
import { useLoad } from './hooks'
import { ContentFitTable } from './ContentFitTable'
import type { AdoptionPhotographer, EntityId } from './types'

interface AdoptedPhotographersModalProps {
  open: boolean
  projectId: EntityId
  onClose: () => void
}

/** 没有拍摄者姓名的图片（历史数据或分享链接访客没报名字）归到这一栏，而不是显示空白行。 */
const UNKNOWN_NAME = '未填写拍摄者'

const nameOf = (item: AdoptionPhotographer) => item.photographerName?.trim() || UNKNOWN_NAME

/**
 * 选题里被引图片的作者名单。
 *
 * 选题结束要给被引用了图片的人署名、发工时，而这件事此前只能一张张翻相册卡片看拍摄者，
 * 或者去统计页的采用排名——后者要 `STATISTICS_DOWNLOAD`，部长和选题负责人常常没有。
 * 名单由后端一次算齐（`GET /projects/{id}/adoptions/photographers`），凭据与读采用记录
 * 一致：选题可见 + 校区范围收窄，所以校区范围账号在这里看到的也只是自己授权校区的那部分。
 *
 * 两个复制按钮对应两种实际用法：发公众号/海报署名要的是一串姓名，核工时要的是带学号和
 * 张数的明细。剪贴板在非安全上下文（http 访问）下不可用，这时把内容摆出来让人自己复制。
 */
export default function AdoptedPhotographersModal({
  open, projectId, onClose,
}: AdoptedPhotographersModalProps) {
  const { message } = App.useApp()
  const { data: photographers, loading, error, reload } = useLoad(
    () => open
      ? api<AdoptionPhotographer[]>({ url: `/projects/${projectId}/adoptions/photographers` })
      : Promise.resolve([] as AdoptionPhotographer[]),
    [] as AdoptionPhotographer[],
    [open, projectId],
  )

  const adoptedTotal = useMemo(
    () => photographers.reduce((total, item) => total + (item.adoptedCount || 0), 0),
    [photographers],
  )

  const copy = async (text: string, label: string) => {
    if (!text) {
      message.info('还没有被引图片，没有可复制的名单')
      return
    }
    try {
      await navigator.clipboard.writeText(text)
      message.success(`${label}已复制`)
    } catch {
      // 非安全上下文或用户拒绝了剪贴板权限：把内容摆出来让人自己复制。
      message.info(`${label}：${text}`)
    }
  }

  // 姓名按名单顺序（被引多的在前）去重：同名不同人在这里只能合成一个，但署名本来就只写姓名，
  // 写两遍反而像笔误；要区分到人就用明细，它带学号。
  const nameList = useMemo(
    () => [...new Set(photographers.map(nameOf))].join('、'),
    [photographers],
  )
  const detailList = useMemo(
    () => photographers
      .map(item => `${nameOf(item)}\t${item.photographerStudentId || ''}\t${item.adoptedCount}`)
      .join('\n'),
    [photographers],
  )

  return <Modal title="被引图片作者" width={720} open={open} onCancel={onClose} footer={null} destroyOnHidden>
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Paragraph type="secondary">
        本选题里已标记被引的图片，按拍摄者汇总。拍摄者取图片上现在填的那个人，
        因此采用之后改过拍摄者的图片，这里和相册里显示的一致。
      </Typography.Paragraph>

      {error && <Alert type="error" showIcon message="作者名单没能加载出来" description={error}
        action={<Button size="small" onClick={reload}>重试</Button>} />}

      <Space wrap>
        <Button type="primary" icon={<CopyOutlined />} disabled={loading || !photographers.length}
          onClick={() => void copy(nameList, '作者姓名')}>复制姓名</Button>
        <Button icon={<CopyOutlined />} disabled={loading || !photographers.length}
          onClick={() => void copy(detailList, '作者明细')}>复制明细（姓名/学号/张数）</Button>
        {!!photographers.length && <Typography.Text type="secondary">
          共 {photographers.length} 位作者、{adoptedTotal} 张被引图片
        </Typography.Text>}
      </Space>

      <ContentFitTable<AdoptionPhotographer>
        rowKey={item => `${item.photographerStudentId || ''}|${nameOf(item)}`}
        size="small" loading={loading} dataSource={photographers} pagination={false}
        locale={{ emptyText: '这个选题还没有被引图片' }}
        columns={[
          { title: '作者', render: (_, item) => nameOf(item) },
          {
            title: '学号', dataIndex: 'photographerStudentId',
            render: (studentId: string | null | undefined) => studentId
              || <Typography.Text type="secondary">—</Typography.Text>,
          },
          { title: '被引张数', dataIndex: 'adoptedCount', align: 'right' },
        ]} />
    </Space>
  </Modal>
}
