import { Col, Form, Input, Row, Select } from 'antd'
import { api } from './api'
import { useLoad } from './hooks'
import { PHOTO_METADATA_LIMITS } from './photoMetadata'
import type { Campus, DedupedMember, EntityId } from './types'

/**
 * 管理员编辑图片信息用到的候选：校区和（去重后的）通讯录。弹窗打开时才取，
 * 只给管理员用，所以不按校区收窄。
 */
export function useAdminMetadataOptions(open: boolean) {
  const { data: campuses, loading: campusesLoading } = useLoad(
    () => open ? api<Campus[]>({ url: '/campuses' }) : Promise.resolve([] as Campus[]),
    [] as Campus[], [open],
  )
  const { data: members, loading: membersLoading } = useLoad(
    () => open ? api<DedupedMember[]>({ url: '/campus-members/deduped' }) : Promise.resolve([] as DedupedMember[]),
    [] as DedupedMember[], [open],
  )
  return {
    campusOptions: campuses.map(campus => ({
      value: campus.id, label: campus.enabled ? campus.name : `${campus.name}（已停用）`,
    })),
    campusesLoading,
    members,
    membersLoading,
  }
}

/**
 * 拍摄者姓名 + 学号两个输入框，外加「从通讯录填入」。
 * 管理员可以直接填通讯录里没有的人：迁移来的老图、上传链接进来的站外拍摄者都不在通讯录里。
 */
export function PhotographerFields({ members, loading, required = true }: {
  members: DedupedMember[]
  loading: boolean
  required?: boolean
}) {
  const form = Form.useFormInstance()
  return <>
    <Form.Item label="从通讯录填入" extra="选一位成员会自动填好下面的姓名和学号；不在通讯录里的人直接手填。">
      <Select<EntityId> showSearch allowClear optionFilterProp="label" loading={loading}
        placeholder="按姓名或学号搜索（可不选）"
        notFoundContent={loading ? '正在加载通讯录…' : '通讯录中没有匹配的成员'}
        options={members.map(member => ({ value: member.id, label: `${member.name} · ${member.studentId}` }))}
        onChange={id => {
          const member = members.find(item => String(item.id) === String(id))
          if (member) form.setFieldsValue({ photographerName: member.name, photographerStudentId: member.studentId })
        }} />
    </Form.Item>
    <Row gutter={16}>
      <Col xs={24} sm={12}>
        <Form.Item label="拍摄者姓名" name="photographerName" rules={[
          { required, whitespace: true, message: '请填写拍摄者姓名' },
          { max: PHOTO_METADATA_LIMITS.photographerName, message: `不能超过 ${PHOTO_METADATA_LIMITS.photographerName} 个字` },
        ]}>
          <Input />
        </Form.Item>
      </Col>
      <Col xs={24} sm={12}>
        <Form.Item label="学号" name="photographerStudentId" extra="不知道可以留空" rules={[
          { max: PHOTO_METADATA_LIMITS.photographerStudentId, message: `不能超过 ${PHOTO_METADATA_LIMITS.photographerStudentId} 个字符` },
        ]}>
          <Input />
        </Form.Item>
      </Col>
    </Row>
  </>
}
