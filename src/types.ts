export type Role = 'ADMIN' | 'MINISTER' | 'CAMPUS_MANAGER'
export type EntityId = string
export type DataScope = 'NONE' | 'CAMPUS' | 'GLOBAL'
// 权限组的图库可见范围，与数据范围正交：SELF 仅本人上传、CAMPUS 授权校区内全部、GLOBAL 全站全部。
export type PhotoVisibility = 'SELF' | 'CAMPUS' | 'GLOBAL'
export type PermissionCode =
  | 'PROJECT_VIEW' | 'PROJECT_VIEW_ALL' | 'PROJECT_ADOPT' | 'PROJECT_CREATE' | 'PROJECT_COMPLETE'
  | 'PROJECT_DOWNLOAD' | 'PROJECT_SHARE'
  | 'REQUEST_VIEW' | 'REQUEST_CREATE' | 'REQUEST_DELETE' | 'REQUEST_CLOSE' | 'REQUEST_CONFIRM' | 'REQUEST_PHOTO_MANAGE'
  | 'PHOTO_VIEW' | 'PHOTO_DELETE' | 'PHOTO_UPLOAD' | 'PHOTO_DOWNLOAD'
  | 'WORKLOG_SUBMIT' | 'WORKLOG_SUBMIT_ANY' | 'WORKLOG_CONFIRM' | 'WORKLOG_EXPORT'
  | 'DIRECTORY_VIEW' | 'DIRECTORY_MANAGE' | 'MESSAGE_SEND'
  | 'RECRUITMENT_VIEW' | 'RECRUITMENT_PUBLISH' | 'FEATURED_MANAGE' | 'DOC_MANAGE'
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
  /** 企业微信通讯录 userid，通知投递的收件标识；未绑定则该成员只收站内信。 */
  wecomUserid?: string | null
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
  photoVisibility: PhotoVisibility
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

/**
 * 选题的工作流程种类（issue #94）。`CREATION` 是原有流程；`EVENT` 是活动选题：
 * 图片整批进库、由指定选片人打标签、结束后清理未选中的图片。
 * 存量选题在后端按 `CREATION` 兜底，所以这个字段在旧响应里可能缺失。
 */
export type ProjectType = 'CREATION' | 'EVENT'

/** 活动选题的选片人。 */
export interface ProjectSelector {
  userId: EntityId
  displayName: string
  username: string
}

export interface Project extends BaseEntity {
  title: string
  description: string
  status: 'DRAFT' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED'
  type?: ProjectType
  createdBy: EntityId
  /** 选题预设标签；为空表示上传者可以自定义标签。 */
  tags?: string[]
  requestCount?: number
  photoCount?: number
  adoptionCount?: number
  /** 以下四项只在选题详情（GET /projects/{id}）里返回，列表接口没有。 */
  selectors?: ProjectSelector[]
  /** 当前账号能不能进这个选题的选片页。 */
  canSelect?: boolean
  /** 当前账号能不能改选片人、能不能清理未选中的图片。 */
  canManageSelection?: boolean
  /** 相册里打了 deprecated 的图片数。 */
  deprecatedCount?: number
}

/** 选片页的图片。比 {@link Photo} 少：没有学号、上传者和校区。 */
export interface SelectionPhoto {
  id: EntityId
  title: string
  photographerName: string
  takenAt: string
  tags: string[]
  width?: number
  height?: number
  size: number
  contentType: string
  /** 480px 预览图（省流量）。 */
  thumbnailUrl?: string
  /** 成品图的内联签名地址，就是选片时要看的「原图」。 */
  imageUrl?: string
  status: Photo['status']
  version: number
}

/** 清理未选中图片前的预演。`ready` 为 false 表示选题还没完成，只能看数字。 */
export interface SelectionCleanupPlan {
  ready: boolean
  deletableCount: number
  adoptedSkippedCount: number
}

export interface SelectionCleanupResult {
  deletedCount: number
  skippedAdoptedCount: number
}

/** 选片页编辑保存用的直传票据。`sourceObjectKey` 要原样回传给 apply-edit。 */
export interface PhotoEditTicket {
  photoId: EntityId
  sourceObjectKey: string
  uploadUrl: string
  method?: string
  contentType: string
  expiresAt: string
}

export interface PhotoRequest extends BaseEntity {
  projectId: EntityId
  title: string
  description: string
  batchId?: string | null
  campusId: EntityId
  requiredCount?: number | null
  deadline: string
  status: 'DRAFT' | 'PUBLISHED' | 'ACCEPTED' | 'SUBMITTED' | 'COMPLETED' | 'CANCELLED'
  createdBy: EntityId
  assigneeId?: EntityId | null
  firstAcceptedAt?: string
  returnReason?: string | null
  returnedBy?: EntityId | null
  returnedAt?: string | null
}

export interface AssignableUser {
  id: EntityId
  username: string
  displayName: string
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

/** 往某个需求上传时可用的标签：restricted 为 true 时只能从 tags 里选。 */
export interface TagOptions {
  restricted: boolean
  tags: string[]
}

/** POST /photos/batch-tags 逐张返回的新标签与新版本号。 */
export interface TaggedPhoto {
  id: EntityId
  tags: string[]
  version: number
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

/**
 * 选题项目的对外分享链接（管理端视图）。
 *
 * 明文密码只在创建和重置密码的返回里出现一次，之后服务端只剩哈希——这个类型里
 * 因此没有 password 字段，要"再看一眼密码"只能重置。
 */
/**
 * 一条链接是用来看的还是用来传的。两种用途的能力互斥，建立后不可改
 * （后端 `ShareLinkPurpose`）。
 */
export type ShareLinkPurpose = 'BROWSE' | 'UPLOAD'

export interface ProjectShareLink {
  id: EntityId
  token: string
  projectId: EntityId
  name?: string | null
  purpose: ShareLinkPurpose
  allowDownload: boolean
  allowAdoption: boolean
  expiresAt?: string | null
  expired: boolean
  viewCount: number
  lastViewedAt?: string | null
  /** 通过这条上传链接传进来的图片张数；浏览链接恒为 0。 */
  uploadCount: number
  createdBy: EntityId
  createdAt: string
  version: number
}

/** 分享访客通过密码换来的会话，以及这条链接当下授予的能力。 */
export interface ShareGuestAccess {
  projectTitle: string
  projectStatus: Project['status']
  linkName?: string | null
  purpose: ShareLinkPurpose
  allowDownload: boolean
  allowAdoption: boolean
  /** 上传链接：选题当下还收不收图（选题结束后立刻变 false）。 */
  allowUpload: boolean
  /** 上传链接：访客进门时自报的姓名，回显用。 */
  uploaderName?: string | null
  expiresAt?: string | null
}

export interface ShareGuestSession {
  sessionToken: string
  expiresAt: string
  access: ShareGuestAccess
}

/**
 * 分享访客看到的图片。刻意比 {@link Photo} 少：没有学号、上传者和校区，
 * 站外的人没有理由拿到这些。被引状态和项目内是同一份数据。
 */
export interface SharePhoto {
  id: EntityId
  title: string
  description?: string
  photographerName: string
  takenAt: string
  width?: number
  height?: number
  size: number
  storedFileName: string
  thumbnailUrl?: string
  adopted: boolean
}

/** 上传链接签出来的一次性上传票据，与站内单张上传同一条流水线。 */
export interface ShareUploadTicket {
  photoId: EntityId
  uploadUrl: string
  method: string
  contentType: string
  expiresAt: string
}

/** 访客刚传的那一张当下的处理结果。失败时 status 会被打回 UPLOADING 并带上原因。 */
export interface ShareUploadedPhoto {
  photoId: EntityId
  title?: string | null
  status: Photo['status']
  failureReason?: string | null
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

export interface MemberWorklogDetail {
  worklogId: EntityId
  workDate: string
  requestId: EntityId
  requestTitle: string
  projectId: EntityId
  projectTitle: string
  campus: string
  shootingMinutes: number
  retouchingMinutes: number
  totalMinutes: number
}

export interface FooterLink {
  label: string
  url: string
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
  loginHeadline?: string
  loginSubheadline?: string
  loginHighlights?: string[]
  loginNotice?: string
  footerText?: string
  footerLinks?: FooterLink[]
  /** 管理员上传的缺图占位图，前端从中任取一张顶替加载不出来的图片。 */
  placeholderImageUrls?: string[]
}

export interface PlaceholderImage {
  id: EntityId
  fileName: string
  imageUrl: string
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

// ---------------------------------------------------------------------------
// 文档中心
// ---------------------------------------------------------------------------

/** FOLDER 是容器；DOCUMENT 的正文是 Markdown，PDF 的正文就是上传的那份文件。 */
export type DocNodeType = 'FOLDER' | 'DOCUMENT' | 'PDF'
/** PUBLIC：未登录也能看；MEMBERS：必须登录。与 published 正交，两个条件都要满足。 */
export type DocVisibility = 'PUBLIC' | 'MEMBERS'

/** 编辑视角的节点，包含草稿和仅限成员的文档。只有 DOC_MANAGE 拿得到。 */
export interface DocManageNode {
  id: EntityId
  publicId: string
  parentId?: EntityId | null
  nodeType: DocNodeType
  title: string
  sortOrder: number
  published: boolean
  visibility: DocVisibility
  /** 是否已经写过正文。没有正文的文档不允许发布。 */
  hasContent: boolean
  contentSize?: number | null
  summary?: string | null
  updaterDisplayName?: string | null
  updatedAt?: string | null
  version: number
  children: DocManageNode[]
}

/** 写操作统一返回整棵新树，前端直接替换，不做局部打补丁。 */
export interface DocTreeMutation {
  tree: DocManageNode[]
  focusId?: EntityId | null
}

export interface DocDocumentDetail {
  node: DocManageNode
  content: string
  breadcrumb: string[]
}

/**
 * 读者视角的节点。未登录时需要登录的文档根本不会出现在树里，
 * 所以 requiresLogin 只在已登录的读者那里才会为 true（用来显示一把锁）。
 */
export interface DocReaderNode {
  publicId: string
  nodeType: DocNodeType
  title: string
  summary?: string | null
  requiresLogin: boolean
  updatedAt?: string | null
  children: DocReaderNode[]
}

/**
 * 一篇打开的文档。两种叶子共用这一个结构：Markdown 文档带 content，
 * PDF 文档带 fileUrl（要带令牌取 Blob，不能直接塞给 iframe），另一个为空。
 */
export interface DocReaderDocument {
  publicId: string
  nodeType: DocNodeType
  title: string
  content: string
  /** 形如 `/api/v1/public/docs/{publicId}/file`，只有 PDF 文档有。 */
  fileUrl?: string | null
  fileSize?: number | null
  requiresLogin: boolean
  updatedAt?: string | null
  updaterDisplayName?: string | null
  breadcrumb: string[]
}
