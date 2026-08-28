export type Role = 'ADMIN' | 'MINISTER' | 'CAMPUS_MANAGER'
export type EntityId = string
export type DataScope = 'NONE' | 'CAMPUS' | 'GLOBAL'
export type PermissionCode =
  | 'PROJECT_VIEW' | 'PROJECT_ADOPT' | 'PROJECT_CREATE' | 'PROJECT_COMPLETE' | 'PROJECT_DOWNLOAD'
  | 'REQUEST_VIEW' | 'REQUEST_CREATE' | 'REQUEST_DELETE' | 'REQUEST_CLOSE' | 'REQUEST_CONFIRM' | 'REQUEST_PHOTO_MANAGE'
  | 'PHOTO_VIEW' | 'PHOTO_DELETE' | 'PHOTO_UPLOAD' | 'PHOTO_DOWNLOAD'
  | 'WORKLOG_SUBMIT' | 'WORKLOG_CONFIRM' | 'WORKLOG_EXPORT'
  | 'DIRECTORY_VIEW' | 'DIRECTORY_MANAGE' | 'MESSAGE_SEND'
  | 'RECRUITMENT_VIEW' | 'RECRUITMENT_PUBLISH' | 'FEATURED_MANAGE'
  | 'STATISTICS_DOWNLOAD' | 'MANAGER_CAMPUS_ASSIGN'

export interface User {
  id: EntityId
  username: string
  displayName: string
  avatarUrl?: string | null
  role: Role
  permissionGroupId?: EntityId
  permissionGroupCode?: string
  permissionGroupName?: string
  dataScope?: DataScope
  permissions?: PermissionCode[]
  campusIds?: EntityId[]
  campusId?: EntityId | null
  campus?: string | null
  phone?: string
  email?: string
  enabled?: boolean
  mustChangePassword?: boolean
  version?: number
}

export interface PermissionDefinition {
  code: PermissionCode
  label: string
}

export interface PermissionCategoryDefinition {
  code: string
  label: string
  permissions: PermissionDefinition[]
}

export interface PermissionGroup {
  id: EntityId
  code: string
  name: string
  description?: string | null
  dataScope: DataScope
  builtIn: boolean
  lowest: boolean
  permissions: PermissionCode[]
  memberCount: number
  version: number
}

export interface MessageRecipient {
  id: EntityId
  displayName: string
  permissionGroupName: string
}

export interface CampusAssignmentUser {
  id: EntityId
  displayName: string
  permissionGroupName: string
  campusIds: EntityId[]
  version: number
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
  requiredCount?: number | null
  deadline: string
  status: 'DRAFT' | 'PUBLISHED' | 'ACCEPTED' | 'SUBMITTED' | 'COMPLETED' | 'CANCELLED'
  createdBy: EntityId
  firstAcceptedAt?: string
  returnReason?: string | null
  returnedBy?: EntityId | null
  returnedAt?: string | null
}

export interface BatchPublishResult {
  campusId: EntityId
  success: boolean
  request?: PhotoRequest | null
  errorCode?: string | null
  message?: string | null
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
  thumbnailSize?: number
  storedFileName: string
  status: 'UPLOADING' | 'PROCESSING' | 'AVAILABLE' | 'ARCHIVED' | 'DELETED'
  failureReason?: string
  adoptionCount?: number
  favorited: boolean
  relatedProjectIds?: EntityId[]
  relatedProjects?: { id: EntityId; title: string }[]
}

export type BatchUploadStatus = 'UPLOADING' | 'PROCESSING' | 'WAITING_METADATA'
  | 'PARTIALLY_SUCCEEDED' | 'SUCCEEDED' | 'FAILED'

export interface BatchUploadItem {
  id: EntityId
  originalFileName: string
  title?: string | null
  status: 'UPLOADING' | 'WAITING_METADATA' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED'
  failureReason?: string | null
  photoId?: EntityId | null
}

export interface BatchUploadView {
  batch: {
    id: string
    mode: 'FILES' | 'ZIP'
    requestId?: EntityId | null
    projectId?: EntityId | null
    archiveFileName?: string | null
    status: BatchUploadStatus
    totalCount: number
    successCount: number
    failureCount: number
    failureReason?: string | null
  }
  items: BatchUploadItem[]
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

export interface DedupedMember {
  id: EntityId
  studentId: string
  name: string
  campusNames: string[]
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
  displayIconType?: 'builtin' | 'custom'
  displayIconUrl?: string | null
  nextIconRefreshAt?: string
}

export interface ScheduledBrandIcon {
  id: EntityId
  cronExpression: string
  iconUrl: string
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

export interface PreviewGenerationStatus {
  status: 'PENDING' | 'GENERATING' | 'SUCCEEDED' | 'FAILED'
  total: number
  processed: number
  percentage: number
  message: string
  errorMessage?: string | null
  startedAt?: string | null
  completedAt?: string | null
}

export interface AuditLog {
  id: EntityId
  operatorId?: EntityId | null
  operatorUsername?: string | null
  operatorDisplayName?: string | null
  action: 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  resourceType: string
  resourceId?: string | null
  requestId: string
  detailJson?: string | null
  ipAddress?: string | null
  createdAt: string
}

export type DatabaseBackupType = 'SCHEDULED' | 'MANUAL' | 'PRE_RESTORE' | 'UPLOADED'
export type DatabaseBackupStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED'

export interface DatabaseBackup {
  id: string
  type: DatabaseBackupType
  status: DatabaseBackupStatus
  sizeBytes?: number | null
  sha256?: string | null
  tableCount?: number | null
  rowCount?: number | null
  schemaVersion?: string | null
  errorMessage?: string | null
  sourceFileName?: string | null
  createdBy?: EntityId | null
  createdByName?: string | null
  startedAt: string
  finishedAt?: string | null
  downloadable: boolean
  restorable: boolean
}

export interface DatabaseRestore {
  id: string
  backupId: string
  safetyBackupId?: string | null
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  tableCount?: number | null
  rowCount?: number | null
  errorMessage?: string | null
  createdBy?: EntityId | null
  createdByName?: string | null
  startedAt: string
  finishedAt?: string | null
}

export interface DatabaseBackupDownload {
  url: string
  fileName: string
  expiresAt: string
}

export type FeaturedCollectionStatus = 'DRAFT' | 'PUBLISHED' | 'CLOSED'
export type FeaturedDocumentStatus = 'PENDING' | 'GENERATING' | 'READY' | 'FAILED'
export type FeaturedCloseReason = 'MANUAL' | 'DEADLINE'

export interface FeaturedCollection {
  id: EntityId
  title: string
  /** 已由服务端清洗过的要求正文，仍须经 RichTextContent 渲染，不要直接注入 DOM。 */
  requirementHtml?: string | null
  requirementText?: string | null
  startsAt: string
  endsAt: string
  status: FeaturedCollectionStatus
  assignAll: boolean
  entryLimit: number
  campusIds: EntityId[]
  userIds: EntityId[]
  documentStatus: FeaturedDocumentStatus
  documentGeneratedAt?: string | null
  documentSize?: number | null
  documentError?: string | null
  createdBy: EntityId
  creatorDisplayName?: string | null
  publishedAt?: string | null
  closedAt?: string | null
  closedReason?: FeaturedCloseReason | null
  entryCount: number
  /** 这份精选是否要求当前用户提交。 */
  assignedToMe: boolean
  /** 指派给我且正处在开始/截止时间之间——只有这时才能增删改条目。 */
  submissionOpen: boolean
  myEntryCount: number
  canManage: boolean
  createdAt: string
  version: number
}

export interface FeaturedEntry {
  id: EntityId
  collectionId: EntityId
  photoId: EntityId
  campusId?: EntityId | null
  campusName?: string | null
  photoTitle?: string | null
  previewUrl?: string | null
  /** 图片是否还在图库里。为 false 时条目只剩提交时的文字快照。 */
  photoAvailable: boolean
  idea: string
  location: string
  photographerName?: string | null
  photographerStudentId?: string | null
  takenAt?: string | null
  submittedBy: EntityId
  submitterDisplayName?: string | null
  sortOrder: number
  mine: boolean
  version: number
}

export interface FeaturedDocumentDownload {
  downloadUrl: string
  expiresAt: string
  fileName: string
}
