import { api } from '../../api'
import type { Campus, PermissionGroup } from '../../types'
import { useLoad } from '../../hooks'
import RegistrationCodesPanel from '../../RegistrationCodesPanel'

/** 管理员面板 · 注册码。面板要用权限组和校区做下拉选项，这一页负责取来。 */
export default function RegistrationCodesPage() {
  const { data: campuses } = useLoad(() => api<Campus[]>({ url: '/campuses' }), [] as Campus[], [])
  const { data: permissionGroups } = useLoad(
    () => api<PermissionGroup[]>({ url: '/permission-groups' }), [] as PermissionGroup[], [],
  )
  return <RegistrationCodesPanel permissionGroups={permissionGroups} campuses={campuses} />
}
