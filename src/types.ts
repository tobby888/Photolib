export type Role = 'ADMIN' | 'MINISTER' | 'CAMPUS_MANAGER'
export type EntityId = string

export interface User {
  id: EntityId
  username: string
  displayName: string
  role: Role
  campusId?: EntityId | null
  campus?: string | null
  phone?: string
  email?: string
  enabled?: boolean
  mustChangePassword?: boolean
  version?: number
}

export interface PageData<T> {
  items: T[]
  page: number
  pageSize: number
  total: number
  totalPages: number
}

export interface BaseEntity {
  id: EntityId
  createdAt: string
  updatedAt: string
  version: number
}

export interface Project extends BaseEntity {
  title: string
  description: string
  status: 'DRAFT' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED'
  createdBy: EntityId
  requestCount?: number
  photoCount?: number
  adoptionCount?: number
}

export interface PhotoRequest extends BaseEntity {
  projectId: EntityId
  title: string
  description: string
  campusId: EntityId
  requiredCount: number
  deadline: string
  status: 'DRAFT' | 'PUBLISHED' | 'ACCEPTED' | 'SUBMITTED' | 'COMPLETED' | 'CANCELLED'
  createdBy: EntityId
  firstAcceptedAt?: string
}

export interface Photo extends BaseEntity {
  requestId?: EntityId
  projectId?: EntityId
  title: string
  description: string
  photographerStudentId: string
  photographerName: string
  uploadedBy: EntityId
  campusId?: EntityId
  takenAt: string
  tags: string[]
  width?: number
  height?: number
  size: number
  contentType: string
  thumbnailUrl?: string
  storedFileName: string
  status: 'UPLOADING' | 'PROCESSING' | 'AVAILABLE' | 'ARCHIVED' | 'DELETED'
  adoptionCount?: number
}

export interface Adoption {
  id: EntityId
  projectId: EntityId
  photoId: EntityId
  photographerStudentId: string
  photographerName: string
  remark?: string
  adoptedBy: EntityId
  adoptedAt: string
  createdAt: string
}

export interface Worklog extends BaseEntity {
  requestId: EntityId
  requestTitle?: string
  userId: EntityId
  userDisplayName?: string
  memberName: string
  memberStudentId: string
  workDate: string
  shootingMinutes: number
  retouchingMinutes: number
  remark: string
  status: 'DRAFT' | 'SUBMITTED' | 'CONFIRMED' | 'REJECTED'
  rejectReason?: string
}

export interface Campus extends BaseEntity {
  code: string
  name: string
  enabled: boolean
}

export interface CampusMember extends BaseEntity {
  campusId: EntityId
  studentId: string
  name: string
  enabled: boolean
}

export interface MemberStats {
  userId: EntityId
  studentId: string
  displayName: string
  campus: string
  adoptedCount: number
  shootingMinutes: number
  retouchingMinutes: number
  totalMinutes: number
}

export interface BrandingSettings {
  title: string
  iconType: 'builtin' | 'custom'
  builtinIcon: 'camera' | 'aperture' | 'picture' | 'bulb' | 'star'
  customIconUrl?: string | null
  slogan: string
}

export interface Notification {
  id: EntityId
  eventType: string
  title: string
  content?: string | null
  actionUrl?: string | null
  senderId?: EntityId | null
  contentHtml?: string | null
  readAt?: string | null
  createdAt: string
}
