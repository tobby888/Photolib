# 教学资料模块 spec

## Problem Statement

图库成员需要一处统一的「教学资料」入口来获取课程文件（PPT/Word/PDF，首版先做 PDF），而不是散落在聊天或网盘里。目前系统没有这个概念——已有的「文档中心」是撰写型 Wiki（Markdown + PDF），面向 MEMBERS/PUBLIC 两态可见，既不面向「图库成员」这个受众，也不适合当下载型文件库。

## Solution

新增一个独立的「教学资料」模块：部长/管理员上传 PDF 课程文件并配上标题、简介、分类、作者等元数据；持有图库访问权限的图库成员可以浏览、搜索、在线预览 PDF、下载，并在有新资料时收到站内通知。

## User Stories

管理侧（持有 `TEACHING_MANAGE` 的部长/管理员）：

1. As a 部长/管理员, I want to upload a PDF teaching material with title, description, category, and an optional author, so that members can access course files.
2. As a 部长/管理员, I want to replace an existing material's PDF file in place, so that corrections keep the same material and link.
3. As a 部长/管理员, I want to edit a material's metadata (title/description/category/author) without re-uploading, so that I can fix typos or reorganize.
4. As a 部长/管理员, I want to soft-delete a material, so that it disappears from the member view but stays recoverable and auditable.
5. As a 部长/管理员, I want to create and rename categories, so that the library stays organized as the catalog grows.
6. As a 部长/管理员, I want the uploader (上传人) recorded automatically on every material, so that responsibility is traceable.
7. As a 部长/管理员, I want non-PDF or oversized uploads rejected with a clear error, so that only valid course files enter the library.
8. As a 部长/管理员, I want to see the download count per material, so that I know which files members actually use.

成员侧（持有 `PHOTO_VIEW` 的图库成员）：

9. As a 图库成员, I want to browse a list of teaching materials newest-first, so that I can find recent course files.
10. As a 图库成员, I want to filter by category and search by title or description, so that I can locate a specific file.
11. As a 图库成员, I want to preview a PDF in-browser, so that I can check its content before committing to a download.
12. As a 图库成员, I want to download a PDF, so that I can use it offline.
13. As a 图库成员, I want to see each material's metadata (title/description/category/author/upload time/file size/download count), so that I know what a file is before opening it.
14. As a 图库成员, I want to receive an in-app notification when a new material is published, so that I don't miss new course files.

权限边界：

15. As an authenticated user without `PHOTO_VIEW`, I should not be able to view or download teaching materials, so that access stays within the gallery.
16. As a user without `TEACHING_MANAGE`, I should not be able to access any management endpoint.
17. As an anonymous (unauthenticated) visitor, I should have no access to teaching materials at all.

审计：

18. As the system, I record write operations (upload/replace/delete/metadata change) in the audit log, so that changes are traceable.

## Implementation Decisions

- **独立模块**：新增 `teaching` 域（Controller/Service/Mapper/Entity），不复用也不扩展现有 `doc`（见 ADR 0001）。
- **数据模型**：`teaching_material` 表存 标题、简介、分类、作者（可选，用户引用）、格式（枚举，首版仅 PDF，预留 WORD/PPT）、对象键、文件大小、下载次数、上传人/更新人、时间戳、软删除标记；新增 Flyway 迁移（现有到 V54，新增 V55）。
- **作者 vs 上传人**：`作者` 是内容原作者（从图库成员中选，可选）；`上传人` 是录入系统的管理者（`TEACHING_MANAGE` 持有者，系统自动记录）。两者是不同字段。
- **分类**：单层分类字符串；`TEACHING_MANAGE` 可新建/重命名分类。
- **存储**：复用 `ObjectStorageService`；对象键 `teaching/<publicId>/document.pdf`（对齐 doc 的 `docs/<publicId>/document.pdf`）。下载和文档中心一样经服务端流式回吐（`ObjectStorageService.open` → `InputStreamResource`），不生成预签名地址。
- **文件校验**：仅接受 PDF（魔数校验，而非只看 Content-Type），大小上限沿用项目唯一的 PDF 上限 `PdfUpload.MAX_BYTES`（50 MiB，与文档中心同一条规则）；被拒的上传不留脏数据，且上传接口在 `EndpointUploadLimitFilter` 里提前卡住上限，不让超大正文先落盘再被拒。
- **更新方式**：原地替换文件（保留 publicId/对象键），乐观锁版本号，拒绝过期编辑（对齐 doc 的 replacePdf）。
- **下载计数**：下载接口计数 +1（一次写操作），进入统计模块。
- **PDF 预览**：复用前端 PDF 查看组件；在线预览用 inline，下载用 attachment。
- **权限**：新增 `PermissionCode.TEACHING_MANAGE`（新 `PermissionCategory.TEACHING`），默认授予 ADMIN + MINISTER；读侧由 `PHOTO_VIEW` 控制。管理接口走 `TEACHING_MANAGE`，成员读接口走 `PHOTO_VIEW`。
- **通知**：新资料发布时，向所有 `PHOTO_VIEW` 用户发站内通知（复用 `NotificationService.notifyUser`）。注意 `notifyUser` 同时会为绑定了企业微信 `wecom_userid` 的成员排一条企业微信投递——这是全仓既有约定（`REQUEST_PUBLISHED` / `FEATURED_PUBLISHED` 同样如此），首版沿用。
- **发布生命周期**：上传即对成员可见并触发通知（无草稿态）。〔假设项：Q9 未明确答复，暂按 9A 处理；若要 9B 草稿态请指出〕
- **审计**：写入操作自动进审计（复用 AuditInterceptor），确认资源类型/资源 ID/请求 ID/详情被捕获。
- **API**：管理面在 `/api/v1/teaching/**`（物品级操作是 `/api/v1/teaching/{id}`），成员读面在 `/api/v1/teaching/materials/**`。分开不只是清晰：审计拦截器按"路径第 3 段资源类型、第 4 段资源 id"归档，管理端点的 id 必须紧跟 `teaching`，否则写操作的资源 id 会落成 null。

## Testing Decisions

- 只测外部行为，不测实现细节。
- **主 seam（Service 层）**：`TeachingService` 用 `@SpringBootTest` + `@Transactional` + 真实本地存储 + 真实库测试（对齐 `DocServiceTests`）。覆盖：非 PDF 拒绝、超限拒绝、原地替换保留对象键、下载计数递增、软删除、分类、作者引用、发布触发通知。
- **次 seam（Controller 授权）**：`TeachingControllerSecurityTests` 用 `@WithMockUser`/`@WithAnonymousUser` + `@MockitoBean` Service，断言 `TEACHING_MANAGE` 门禁管理面、`PHOTO_VIEW` 门禁成员读面、无权限/匿名一律拒绝（对齐 `DocControllerSecurityTests`）。
- **不新增 HTTP seam**：教学资料没有匿名/公开读面，不需要 doc 模块那样的 HTTP 路由测试。
- 先例：`DocServiceTests`、`DocControllerSecurityTests`。

## Out of Scope

- Word/PPT 的上传与展示（延后；数据模型预留格式字段）。
- Word/PPT 在线预览。
- 未登录访问（教学资料无公开面）。
- 校区级（`DataScope.CAMPUS`）受众/管理——首版受众为全局 `PHOTO_VIEW`。
- 企业微信/邮件通知（首版仅站内）。
- 「课程」作为一级实体的层级（首版仅单层分类）。
- 草稿/发布两态（首版上传即发布）。

## Further Notes

- Q9（发布生命周期）未明确答复，本 spec 暂按「上传即发布」处理，见 Implementation Decisions 标注的假设项。
- 表结构迁移需新增 Flyway 脚本，并遵循「不修改已发布迁移」的约定。
- 权限码新增后，需同步权限组种子数据，并核对 `StepUpPermissionCoverageTests` 的覆盖（若涉及两步验证）。
