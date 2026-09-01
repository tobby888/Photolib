# PhotoLib API 接口文档

> 本文依据当前后端控制器、Service、实体、安全配置以及现有 Web 客户端逐项核对，描述的是**已经实现的接口契约**，不是规划稿。
> 核对日期：2026-08-28
> API 版本：v1
> Base URL：`/api/v1`

本文面向新的 Web、桌面或移动客户端开发者。若本文与运行中的同版本服务存在差异，以服务实际响应为准；若修改后端接口，请同步更新本文和 `src/types.ts`。

## 1. 快速接入

### 1.1 服务地址

同源部署时直接使用：

```text
/api/v1
```

本地开发的默认地址：

```text
http://localhost:8080/api/v1
```

现有 Vite 客户端通过 `VITE_API_BASE_URL` 覆盖地址，未配置时使用 `/api/v1`。

除下列接口外，所有业务接口都需要登录：

- `POST /auth/login`
- `POST /auth/refresh`
- `GET /branding/icon`
- `GET /branding/scheduled-icons/{id}/icon`
- `GET /local-storage/objects/{token}` 和 `PUT /local-storage/objects/{token}`：仅本地存储模式下存在，且必须使用服务端签发的 token
- `GET /actuator/health`

注意：`GET /branding` 本身仍需要登录，只有图标二进制读取接口公开。

### 1.2 请求头与 Cookie

业务 JSON 请求：

```http
Authorization: Bearer <accessToken>
Content-Type: application/json
```

刷新令牌不出现在 JSON 中，而是 Cookie：

```text
photolib_refresh
```

Cookie 属性为 `HttpOnly`、`SameSite=Strict`、`Path=/api/v1/auth`；生产环境通常还带 `Secure`。浏览器客户端必须启用凭据，例如 Axios 的 `withCredentials: true` 或 Fetch 的 `credentials: "include"`。

### 1.3 统一成功响应

除文件流、CSV 和对象存储读写外，响应均使用统一信封：

```json
{
  "code": "OK",
  "message": "success",
  "data": {}
}
```

当前业务 Controller 的创建、更新、状态操作和删除成功均返回 HTTP `200`。删除类接口也返回信封，`data` 为 `null`，不要按 `204 No Content` 处理。

分页数据位于 `data` 内：

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "items": [],
    "page": 1,
    "pageSize": 20,
    "total": 0,
    "totalPages": 0
  }
}
```

分页从 `1` 开始。除审计日志会主动夹紧页码外，分页接口通常要求 `page >= 1`、`1 <= pageSize <= 100`。

### 1.4 统一错误响应

```json
{
  "code": "VALIDATION_ERROR",
  "message": "请求参数不合法",
  "details": [
    {
      "field": "title",
      "message": "标题不能为空"
    }
  ],
  "requestId": "c55e3668-5110-4e08-a1d9-f2d231df660e"
}
```

错误码与 HTTP 状态：

| HTTP | `code` | 含义 |
| --- | --- | --- |
| `400` | `VALIDATION_ERROR` | 参数、日期范围或业务输入不合法 |
| `401` | `UNAUTHORIZED` | 未登录、令牌过期、会话失效或密码校验失败 |
| `403` | `FORBIDDEN` | 已登录但无权限；首次登录未改密也使用此码 |
| `404` | `RESOURCE_NOT_FOUND` | 资源不存在或当前用户没有可见的目标资源 |
| `409` | `RESOURCE_STATE_CONFLICT` | 状态不允许、版本冲突或并发修改 |
| `409` | `DUPLICATE_RESOURCE` | 用户名、邮箱、图片 hash、采用记录等重复 |
| `413` | `FILE_TOO_LARGE` | 文件或 multipart 请求超过限制 |
| `415` | `UNSUPPORTED_FILE_TYPE` | 文件扩展名、MIME 或文件魔数不支持 |
| `500` | `INTERNAL_ERROR` | 未预期的服务端错误 |

`details` 可能为空，`requestId` 在部分由安全过滤器或上传大小过滤器直接产生的错误中可能缺失。客户端应优先显示 `details[].message`，其次显示顶层 `message`。

### 1.5 ID 类型：客户端必须归一化

业务主键大多是 Java `Long`。服务端会把超过 JavaScript 安全整数范围 `2^53 - 1` 的 Long 序列化成十进制字符串，小整数仍可能序列化成 JSON number：

```json
{ "id": "2012345678901234567" }
```

也可能是：

```json
{ "id": 12 }
```

客户端不要对 ID 做算术。推荐在数据边界统一执行 `String(id)`，内部一律使用字符串。批次 ID、导出任务 ID、说明图片 ID 和消息图片 ID 本来就是字符串。

### 1.6 日期与时间

应用业务时区为 `Asia/Shanghai`。

| Java 类型/用途 | JSON 形式 | 示例 |
| --- | --- | --- |
| `LocalDate` | `YYYY-MM-DD` | `2026-07-24` |
| 业务 `LocalDateTime` | 无时区 ISO 字符串 | `2026-07-24T18:30:00` |
| 签名 URL 的 `Instant` | UTC，通常以 `Z` 结尾 | `2026-07-24T10:45:00Z` |
| 品牌图标刷新时间 `OffsetDateTime` | 含偏移 | `2026-07-25T00:00:00+08:00` |

创建需求、上传图片等请求中的 `deadline`、`takenAt` 应发送无偏移的上海本地时间，例如 `YYYY-MM-DDTHH:mm:ss`。不要把它先转换成 UTC 再去掉 `Z`。

日期范围中的 `to` 通常包含整天：审计和采用排行会转换成 `< to + 1 day`；工时列表使用 `<= to`。每个统计接口的口径见对应章节。

### 1.7 当前不支持的通用能力

- 服务端未实现 `Idempotency-Key`。
- 列表接口没有统一 `sort` 参数；按接口内置顺序返回。
- 不支持公开注册和“忘记密码”；账号创建、删除和密码重置由管理员完成。
- 除明确列出的字段外，不应假定服务端接受额外筛选条件。

## 2. 推荐客户端封装

### 2.1 TypeScript 基础类型

```ts
export type EntityId = string

export interface ApiEnvelope<T> {
  code: 'OK'
  message: string
  data: T
}

export interface ApiErrorBody {
  code: string
  message: string
  details?: Array<{ field?: string; message: string }>
  requestId?: string | null
}

export interface PageData<T> {
  items: T[]
  page: number
  pageSize: number
  total: number
  totalPages: number
}

export const normalizeId = (value: string | number): EntityId => String(value)
```

建议在 DTO 映射层递归归一化所有 `id`、`...Id` 和 ID 数组，不要仅依赖 TypeScript 的静态声明。

### 2.2 Axios 认证与单次刷新

```ts
import axios, { type AxiosRequestConfig } from 'axios'

const http = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
  timeout: 20_000,
})

http.interceptors.request.use(config => {
  const token = localStorage.getItem('photolib_access_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

let refreshing: Promise<string> | null = null

http.interceptors.response.use(undefined, async error => {
  const original = error.config as AxiosRequestConfig & { _retry?: boolean }
  const isAuthEndpoint = String(original.url || '').includes('/auth/')
  if (error.response?.status !== 401 || original._retry || isAuthEndpoint) throw error

  original._retry = true
  try {
    refreshing ??= http.post('/auth/refresh')
      .then(response => {
        const token = response.data.data.accessToken as string
        localStorage.setItem('photolib_access_token', token)
        return token
      })
      .finally(() => { refreshing = null })

    const token = await refreshing
    original.headers = { ...original.headers, Authorization: `Bearer ${token}` }
    return http(original)
  } catch (refreshError) {
    localStorage.removeItem('photolib_access_token')
    localStorage.removeItem('photolib_user')
    throw refreshError
  }
})
```

并发请求同时收到 `401` 时必须合并为一次刷新。刷新令牌会轮换，如果并发调用多次 `/auth/refresh`，后发请求可能使用已撤销的旧 Cookie 导致整个会话退出。

### 2.3 二进制资源

以下响应不是 `ApiEnvelope`：

- 审计日志 CSV：`GET /audit-logs/export`
- 品牌图标：`GET /branding/icon`
- 定时品牌图标：`GET /branding/scheduled-icons/{id}/icon`
- 用户头像：`GET /users/me/avatar`、`GET /users/{id}/avatar`
- 说明图片：`GET /description-images/{id}`
- 消息图片：`GET /notifications/images/{id}`
- 对象存储签名 URL 的 `GET`/`PUT`

用户头像、说明图片和消息图片读取接口需要 Bearer token。若内容要显示在头像、Markdown 或富文本中，应先用带认证的 HTTP 客户端读取为 Blob，再创建 `URL.createObjectURL(blob)`；不要把这些受鉴权路径直接作为普通 `<img src>`。组件销毁或图片更新时调用 `URL.revokeObjectURL`。

## 3. 角色、枚举和权限缩写

文中使用以下缩写：

| 缩写 | 角色代码 | 含义 |
| --- | --- | --- |
| A | `ADMIN` | 管理员 |
| M | `MINISTER` | 正副部长 |
| C | `CAMPUS_MANAGER` | 校区负责人 |

枚举：

```ts
type Role = 'ADMIN' | 'MINISTER' | 'CAMPUS_MANAGER'
type ProjectStatus = 'DRAFT' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED'
type RequestStatus = 'DRAFT' | 'PUBLISHED' | 'ACCEPTED' | 'SUBMITTED' | 'COMPLETED' | 'CANCELLED'
type PhotoStatus = 'UPLOADING' | 'PROCESSING' | 'AVAILABLE' | 'ARCHIVED' | 'DELETED'
type WorklogStatus = 'DRAFT' | 'SUBMITTED' | 'CONFIRMED' | 'REJECTED'
type BatchMode = 'FILES' | 'ZIP'
type BatchStatus =
  | 'UPLOADING' | 'PROCESSING' | 'WAITING_METADATA'
  | 'PARTIALLY_SUCCEEDED' | 'SUCCEEDED' | 'FAILED'
type BatchItemStatus = 'UPLOADING' | 'WAITING_METADATA' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED'
```

权限最终以后端校验为准。页面隐藏按钮只是体验优化，不能代替授权。

## 4. 核心返回结构

### 4.1 用户 `UserView`

```json
{
  "id": "2012345678901234567",
  "username": "zhangsan",
  "displayName": "张三",
  "role": "CAMPUS_MANAGER",
  "campusId": "2012345678901234001",
  "phone": "13800000000",
  "email": "zhangsan@example.edu.cn",
  "wecomUserid": "ZhangSan",
  "enabled": true,
  "mustChangePassword": false,
  "avatarUrl": "/api/v1/users/2012345678901234567/avatar?v=3",
  "createdAt": "2026-07-24T10:00:00",
  "updatedAt": "2026-07-24T10:00:00",
  "version": 3
}
```

`avatarUrl` 可空。非空值指向受鉴权的头像读取接口，并带有用于缓存更新的 `v` revision 查询参数；它不会暴露对象存储 key。值为 `null` 时表示用户没有自定义头像，现有 Web 客户端使用 Ant Design 的 `UserOutlined` 作为默认头像。

`/auth/me` 返回的是面向当前会话的 `AuthenticatedUser`：包含 `id`、`username`、`displayName`、`role`、`campusId`、`mustChangePassword`、权限组字段（`permissionGroupId`、`permissionGroupCode`、`permissionGroupName`）、`dataScope`、`photoVisibility`、`permissions`、`campusIds` 和 `avatarUrl`，不包含联系方式及实体审计字段。

### 4.2 基础实体字段

项目、需求、校区、通讯录成员和工时等直接返回 MyBatis 实体时，通常还包含：

```json
{
  "id": "...",
  "version": 1,
  "deleted": false,
  "createdAt": "2026-07-24T10:00:00",
  "updatedAt": "2026-07-24T10:00:00"
}
```

`deleted` 是实现字段，正常列表中应为 `false`；客户端不应依赖它执行权限或状态判断。

### 4.3 项目实体与详情

项目列表、创建、更新和状态操作返回 `ProjectEntity`：

```json
{
  "id": "...",
  "version": 1,
  "deleted": false,
  "createdAt": "2026-07-24T10:00:00",
  "updatedAt": "2026-07-24T10:00:00",
  "title": "2026 毕业季",
  "description": "Markdown 文本",
  "status": "ACTIVE",
  "createdBy": "...",
  "completedAt": null
}
```

`GET /projects/{id}` 返回 `ProjectDetail`，字段为：

```json
{
  "id": "...",
  "title": "2026 毕业季",
  "description": "Markdown 文本",
  "status": "ACTIVE",
  "createdBy": "...",
  "createdAt": "2026-07-24T10:00:00",
  "updatedAt": "2026-07-24T10:00:00",
  "version": 1,
  "requestCount": 3,
  "photoCount": 40,
  "adoptionCount": 12
}
```

项目相册归属和 `photoCount` 以多对多表为准。一张图片可以属于多个项目；`Photo.projectId` 只是主/来源项目。

### 4.4 图片需求 `PhotoRequestEntity`

```json
{
  "id": "...",
  "version": 2,
  "deleted": false,
  "createdAt": "2026-07-24T10:00:00",
  "updatedAt": "2026-07-24T10:10:00",
  "projectId": "...",
  "title": "毕业典礼现场图",
  "description": "Markdown 文本",
  "campusId": "...",
  "requiredCount": null,
  "deadline": "2026-07-31T18:00:00",
  "status": "PUBLISHED",
  "createdBy": "...",
  "firstAcceptedAt": null,
  "completedAt": null,
  "cancelReason": null,
  "returnReason": null,
  "returnedBy": null,
  "returnedAt": null
}
```

`requiredCount` 可空；传值时至少为 `1`。需求允许零图片提交，客户端不得自行强制“至少上传一张”。

### 4.5 图片 `PhotoView`

图片接口不直接返回存储 object key，而返回：

```json
{
  "id": "...",
  "requestId": "...",
  "projectId": "...",
  "title": "会场全景",
  "description": "典礼开始前拍摄",
  "photographerStudentId": "20260001",
  "photographerName": "王同学",
  "uploadedBy": "...",
  "campusId": "...",
  "takenAt": "2026-07-24T09:10:00",
  "tagsJson": "[\"毕业季\",\"典礼\"]",
  "width": 6000,
  "height": 4000,
  "size": 9845123,
  "contentType": "image/jpeg",
  "storedFileName": "张三-王同学-20260724T091000.jpg",
  "thumbnailUrl": "https://signed.example/...",
  "thumbnailSize": 152340,
  "status": "AVAILABLE",
  "failureReason": null,
  "uploadedAt": "2026-07-24T12:00:00",
  "version": 3,
  "adoptionCount": 1,
  "relatedProjectIds": ["..."],
  "relatedProjects": [{ "id": "...", "title": "2026 毕业季" }]
}
```

重要：服务端当前字段名是 `tagsJson`，值是 JSON 数组字符串，不是 `tags: string[]`。客户端应安全解析：

```ts
function parseTags(value?: string | null): string[] {
  try {
    const parsed = JSON.parse(value || '[]')
    return Array.isArray(parsed) ? parsed.filter(v => typeof v === 'string') : []
  } catch {
    return []
  }
}
```

`thumbnailUrl` 和下载 URL 均为短期签名地址，默认有效期约 15 分钟，不要持久化。`uploadedAt` 对应数据库创建时间；`PhotoView` 没有 `createdAt`/`updatedAt` 字段。

### 4.6 工时 `WorklogEntity`

```json
{
  "id": "...",
  "version": 1,
  "deleted": false,
  "createdAt": "2026-07-24T19:00:00",
  "updatedAt": "2026-07-24T19:00:00",
  "requestId": "...",
  "userId": "...",
  "memberName": "王同学",
  "memberStudentId": "20260001",
  "workDate": "2026-07-24",
  "shootingMinutes": 120,
  "retouchingMinutes": 60,
  "remark": "现场拍摄与基础调色",
  "status": "SUBMITTED",
  "rejectReason": null,
  "confirmedBy": null,
  "confirmedAt": null,
  "requestTitle": "毕业典礼现场图",
  "userDisplayName": "张三"
}
```

`requestTitle`、`userDisplayName` 由工时列表接口补充；创建或状态操作的响应中可能为 `null`。

## 5. 认证接口

### 5.1 登录

`POST /auth/login`，公开。

```json
{
  "username": "admin-or-email@example.edu.cn",
  "password": "********"
}
```

`username` 实际是登录标识，可传用户名或邮箱。用户名精确匹配；用户名未命中时，邮箱会转小写匹配。字段限制：登录标识非空且最长 320，密码非空且最长 72。

响应：

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "accessToken": "...",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "mustChangePassword": true,
    "user": {
      "id": "...",
      "username": "admin",
      "displayName": "系统管理员",
      "role": "ADMIN",
      "campusId": null,
      "mustChangePassword": true,
      "avatarUrl": null
    }
  }
}
```

同时通过 `Set-Cookie` 写入刷新令牌。错误信息统一为“账号、邮箱或密码错误”，客户端不要据此判断账号是否存在。

### 5.2 当前用户

`GET /auth/me`，已登录。

返回 `AuthenticatedUser`。首次登录未改密时仍允许调用。

### 5.3 刷新会话

`POST /auth/refresh`，公开但需要有效 `photolib_refresh` Cookie，无请求体。

成功响应与登录相同，并轮换刷新 Cookie。访问令牌默认 15 分钟；会话连续 30 分钟无活动后失效。成功的已认证业务请求会延长空闲过期时间。

### 5.4 首次登录修改密码

`PUT /auth/initial-password`，首次登录账号可用。

```json
{
  "initialPassword": "管理员提供的初始密码",
  "newPassword": "newPassword123"
}
```

新密码必须为 10～72 个字符，且至少包含一个字母和一个数字。成功后撤销旧会话，响应返回一套新的登录 token 和 Cookie，客户端应立即替换本地 token。

`mustChangePassword=true` 时，Bearer token 只允许访问：

- `GET /auth/me`
- `PUT /auth/initial-password`
- `POST /auth/logout`

访问其他接口返回 `403 FORBIDDEN`，消息为“首次登录必须先修改密码”。

### 5.5 修改自己的密码

`PUT /auth/password`，已登录。

```json
{
  "oldPassword": "oldPassword123",
  "newPassword": "newPassword123"
}
```

成功后撤销该用户全部会话、清除刷新 Cookie 并返回 `data: null`。当前访问令牌随即失效，客户端应清理登录状态并跳转登录页。

### 5.6 退出

`POST /auth/logout`，已登录，无请求体。

撤销当前刷新会话并清除 Cookie。客户端无论请求是否成功，都应清理本地访问令牌和用户快照。

## 6. 用户、校区与通讯录

### 6.1 用户接口

| 方法与路径 | 权限 | 请求/查询 | 返回与说明 |
| --- | --- | --- | --- |
| `POST /users` | A | `CreateUserRequest` | `CreatedUser`，初始密码只返回一次 |
| `GET /users` | A/M | `page`、`pageSize`、`keyword?`、`role?`、`campusId?`、`enabled?` | `PageData<UserView>`，按创建时间倒序 |
| `GET /users/{id}` | A 或本人 | — | `UserView` |
| `PUT /users/{id}` | A | `UpdateUserRequest` | `UserView` |
| `PUT /users/{id}/campus` | A/M | `campusId`、`version` | 仅可修改 `CAMPUS_MANAGER` 的校区 |
| `PUT /users/{id}/password` | A | 无请求体 | `{ "initialPassword": "..." }` |
| `POST /users/{id}/enable` | A | 无请求体 | `UserView` |
| `POST /users/{id}/disable` | A | 无请求体 | `UserView`，并撤销其全部会话 |
| `DELETE /users/{id}` | A | 无请求体 | `data: null`，软删除并释放用户名 |
| `PUT /users/me/avatar` | 所有登录用户 | `multipart/form-data`，字段 `file` | `{ "avatarUrl": "..." }` |
| `DELETE /users/me/avatar` | 所有登录用户 | 无请求体 | `{ "avatarUrl": null }`，恢复默认头像 |
| `GET /users/me/avatar` | 所有登录用户 | — | 当前用户头像二进制，不使用统一信封 |
| `GET /users/{id}/avatar` | 所有登录用户 | — | 指定用户头像二进制，不使用统一信封 |

创建请求：

```json
{
  "username": "zhangsan",
  "displayName": "张三",
  "role": "CAMPUS_MANAGER",
  "campusId": "...",
  "phone": "13800000000",
  "email": "zhangsan@example.edu.cn",
  "wecomUserid": "ZhangSan"
}
```

校验：

- `username`：`^[A-Za-z0-9_.-]{3,64}$`
- `displayName`：非空，最多 100
- `phone`：可空，最多 32
- `email`：可空，合法邮箱，最多 255；保存时 trim 并转小写，未删除用户中唯一
- `wecomUserid`：可空，`^[A-Za-z0-9_@.-]{1,64}$`；保存时 trim、保留大小写，未删除用户中唯一。留空表示该成员只收站内信
- `CAMPUS_MANAGER` 必须指定已启用校区

创建响应：

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "user": { "id": "...", "username": "zhangsan", "version": 1 },
    "initialPassword": "P1!..."
  }
}
```

更新请求：

```json
{
  "displayName": "张三",
  "role": "CAMPUS_MANAGER",
  "campusId": "...",
  "phone": "13800000000",
  "email": "zhangsan@example.edu.cn",
  "wecomUserid": "ZhangSan",
  "enabled": true,
  "version": 1
}
```

更新、停用、重置密码、修改密码和删除账号会按相应规则撤销会话。不能删除当前登录账号，也不能停用或删除唯一仍启用的管理员。删除账号会保留历史业务引用，但释放原用户名、邮箱和企业微信 UserID，以后可重新创建同名账号并绑定同一个 UserID。

#### 用户头像

上传头像使用 `PUT /users/me/avatar`，请求为 `multipart/form-data`，文件字段名是 `file`。只支持 JPEG、PNG，文件不得超过 1 MiB，宽、高分别不得超过 1024 像素。服务端会校验声明的 MIME、文件魔数、真实解码格式和实际尺寸，并在规范化后再次检查大小；不能只依赖客户端校验或文件扩展名。

成功响应仍使用统一信封：

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "avatarUrl": "/api/v1/users/2012345678901234567/avatar?v=3"
  }
}
```

头像经统一的 `ObjectStorageService` 保存，生产环境存入私有 OSS。`avatarUrl` 是稳定的业务读取路径，revision 查询参数用于替换头像后的缓存失效；客户端不得推导、保存或依赖底层对象 key。

`DELETE /users/me/avatar` 删除当前自定义头像并恢复默认头像，返回 `data.avatarUrl: null`；用户本来没有自定义头像时也可重复调用。

`GET /users/me/avatar` 和 `GET /users/{id}/avatar` 仅登录用户可读，直接返回 `image/jpeg` 或 `image/png` 二进制以及私有、需重新验证的缓存头，不使用 `ApiEnvelope`。目标用户或头像不存在时返回 `404 RESOURCE_NOT_FOUND`。浏览器客户端必须使用带 Bearer token 的 HTTP 客户端读取 Blob，再把临时 Blob URL 交给头像组件；不能把 `avatarUrl` 直接放进不会附带 Authorization header 的 `<img src>`。

### 6.2 校区接口

| 方法与路径 | 权限 | 请求/查询 | 返回 |
| --- | --- | --- | --- |
| `POST /campuses` | A | `{ code, name }` | `CampusEntity` |
| `GET /campuses` | A/M/C | `enabled?` | `CampusEntity[]`，不是分页 |
| `GET /campuses/{id}` | A/M/C | — | `CampusEntity` |
| `PUT /campuses/{id}` | A | `{ name, enabled, version }` | `CampusEntity` |
| `POST /campuses/{id}/enable` | A | 无 | `CampusEntity` |
| `POST /campuses/{id}/disable` | A | 无 | `CampusEntity` |

创建示例：

```json
{
  "code": "SOUTH",
  "name": "南校区"
}
```

`code` 需匹配 `^[A-Za-z0-9_-]{2,32}$`，服务端转大写并保证唯一；创建后没有修改 code 的接口。`name` 最多 100。当前没有删除校区接口。

### 6.3 校区通讯录

照片拍摄者和工时成员必须从 `campus_member` 选择。照片和工时只保存姓名、学号快照，不保存 contactId，因此通讯录修改或删除不会改写历史数据。

| 方法与路径 | 权限 | 请求/查询 | 返回/作用域 |
| --- | --- | --- | --- |
| `GET /campus-members` | A/M/C | `campusId?`、`enabled?` | `CampusMemberEntity[]`；C 始终被限制到本人校区 |
| `GET /campus-members/deduped` | A/M | — | `DedupedMember[]` |
| `POST /campus-members` | A/M/C | `{ campusId?, studentId, name }` | `CampusMemberEntity` |
| `PUT /campus-members/{id}` | A/M/C | `{ studentId, name, enabled, version }` | `CampusMemberEntity` |
| `DELETE /campus-members/{id}` | A/M/C | — | 物理删除，`data: null` |

当前代码允许 A、M 维护任意所选校区，C 只能维护本人校区。C 创建时可不传 `campusId`，服务端使用账号校区；A、M 创建时必须传。已停用校区不能维护成员或作为拍摄者来源。

创建同校区、同学号的现有成员时，服务端会更新姓名并重新启用该行，而不是返回重复错误。更新可能因唯一约束返回 `DUPLICATE_RESOURCE`。

去重视图结构：

```json
{
  "id": "代表 contactId",
  "studentId": "20260001",
  "name": "王同学",
  "campusNames": ["南校区", "北校区"]
}
```

只汇总已启用校区中的已启用成员，按学号去重，取最小成员 ID 作为可提交的 `photographerContactId`。

## 7. 项目接口

### 7.1 路由

| 方法与路径 | 权限 | 请求/查询 | 返回 |
| --- | --- | --- | --- |
| `POST /projects` | A/M | `{ title, description?, status }` | `ProjectEntity` |
| `GET /projects` | A/M/C | `page`、`pageSize`、`keyword?`、`status?` | `PageData<ProjectEntity>` |
| `GET /projects/{id}` | A/M/C | — | `ProjectDetail` |
| `POST /projects/{id}/photos` | A/M/C | `{ photoIds }` | `data: null` |
| `PUT /projects/{id}` | A/M | `{ title, description?, version }` | `ProjectEntity` |
| `POST /projects/{id}/status` | A/M | `{ status, version }` | `ProjectEntity` |
| `POST /projects/{id}/reopen` | A | `{ reason, version }` | `ProjectEntity` |
| `DELETE /projects/{id}` | A/M | — | `data: null` |

创建请求：

```json
{
  "title": "2026 毕业季",
  "description": "支持 GFM 的 Markdown，最多 5000 字符",
  "status": "DRAFT"
}
```

新项目状态只能是 `DRAFT` 或 `ACTIVE`。标题最多 200。

列表的 `keyword` 匹配标题和说明，默认按 `createdAt` 倒序。C 只能看到自己已参与需求所属的项目；无参与项目时返回空页。

### 7.2 所有者与状态规则

项目更新、状态变更和删除不仅检查角色，还要求当前用户是 `createdBy` 或 A。也就是说，M 不能修改另一位 M 创建的项目。

允许的状态流转：

```text
DRAFT  -> ACTIVE
DRAFT  -> CANCELLED
ACTIVE -> COMPLETED
ACTIVE -> CANCELLED
COMPLETED -> ACTIVE 仅管理员通过 /reopen
```

完成时服务端设置 `completedAt`，重新开放时清空。`reopen.reason` 必填且最多 500；当前实现不把该原因写回项目实体。

项目已经关联任何需求、采用记录或项目相册图片时不能删除，只能取消。删除使用逻辑删除。

### 7.3 加入项目相册

`POST /projects/{id}/photos`

```json
{
  "photoIds": ["...", "..."]
}
```

规则：

- 1～200 个 ID；重复 ID 会去重。
- 项目必须是 `ACTIVE`。
- 图片必须是 `AVAILABLE`。
- 操作是幂等的，已经属于相册的图片不会导致整个请求失败。
- C 只能向自己可见的项目加入本人上传的图片。
- 加入相册不会创建采用记录；采用是另一个动作。

项目相册列表通过 `GET /photos?projectId={id}` 获取。

## 8. 图片需求接口

### 8.1 路由

| 方法与路径 | 权限 | 请求/查询 | 返回 |
| --- | --- | --- | --- |
| `POST /projects/{projectId}/requests` | A/M | `CreateRequest` | `PhotoRequestEntity`，固定为 `DRAFT` |
| `POST /projects/{projectId}/requests/batch-publish` | A/M | `BatchPublishRequest` | `BatchPublishResult[]` |
| `GET /requests` | A/M/C | 分页与筛选 | `PageData<PhotoRequestEntity>` |
| `GET /requests/{id}` | A/M/C | — | `PhotoRequestEntity` |
| `PUT /requests/{id}` | A/M | `UpdateRequest` | `PhotoRequestEntity` |
| `POST /requests/{id}/publish` | A/M | `{ version }` | `PhotoRequestEntity` |
| `POST /requests/{id}/accept` | A/C | 无请求体 | `PhotoRequestEntity` |
| `GET /requests/{id}/participants` | A/M/C | — | `RequestParticipantEntity[]` |
| `DELETE /requests/{id}/participants/me` | C | 无 | `data: null` |
| `POST /requests/{id}/submit` | A/C | `{ version }` | `PhotoRequestEntity` |
| `POST /requests/{id}/complete` | A/M | `{ version }` | `PhotoRequestEntity` |
| `POST /requests/{id}/return` | A/M | `{ reason, version }` | `PhotoRequestEntity` |
| `POST /requests/{id}/cancel` | A/M | `{ reason, version }` | `PhotoRequestEntity` |
| `DELETE /requests/{id}` | A | 无 | 逻辑删除，`data: null` |

创建请求：

```json
{
  "title": "毕业典礼现场图",
  "description": "Markdown，最多 5000 字符",
  "campusId": "...",
  "requiredCount": null,
  "deadline": "2026-07-31T18:00:00"
}
```

标题最多 200；`campusId`、未来的 `deadline` 必填；`requiredCount` 可空，非空时至少 1。已完成或已取消项目不能创建需求；`DRAFT` 和 `ACTIVE` 项目均可创建草稿。

更新请求与创建相同，并增加：

```json
{ "version": 1 }
```

只有 `DRAFT` 可编辑。编辑、发布、取消要求需求创建者或 A；M 角色本身不能操作另一位 M 创建的需求。

### 8.2 列表与可见性

`GET /requests?page=1&pageSize=20&projectId=...&status=PUBLISHED&campusId=...&participantId=...`

筛选均可选。A/M 可查看全部。C 的行为：

- `campusId` 会被强制替换为本人校区。
- 列表隐藏 `DRAFT`。
- 未传 `participantId` 时，可看到本人校区内的非草稿需求，便于接单。
- 传了任意 `participantId` 时，都会按当前 C 本人参与关系筛选。
- 详情和参与人接口比列表更严格：C 必须已经参与该需求；未接受的已发布需求可以出现在列表中，但详情可能返回 `403`。

客户端的接单列表应直接使用列表项中的 ID 调用 `/accept`，不要以“必须先成功获取详情”为前置条件。

### 8.3 多校区批量发布

`POST /projects/{projectId}/requests/batch-publish`

```json
{
  "title": "毕业典礼现场图",
  "description": "说明",
  "campusIds": ["...", "..."],
  "requiredCount": null,
  "deadline": "2026-07-31T18:00:00"
}
```

`campusIds` 为 1～50 个。项目必须 `ACTIVE`，截止时间必须在未来。每个校区在独立事务中创建一条直接为 `PUBLISHED` 的需求；一个校区失败不会回滚其他校区。

HTTP 层整体成功时仍可能存在逐项失败：

```json
[
  {
    "campusId": "...",
    "success": true,
    "request": { "id": "...", "status": "PUBLISHED" },
    "errorCode": null,
    "message": null
  },
  {
    "campusId": "...",
    "success": false,
    "request": null,
    "errorCode": "VALIDATION_ERROR",
    "message": "不能向已停用校区发布需求"
  }
]
```

同一请求中的重复校区以逐项 `VALIDATION_ERROR` 返回。客户端应保留失败校区供重试。

### 8.4 接受、退出与状态流转

`POST /requests/{id}/accept` 无请求体，也不接收版本号。C 只能接受本人校区的 `PUBLISHED` 或 `ACCEPTED` 需求；同一用户重复接受返回 `DUPLICATE_RESOURCE`。A 也可以接受，并成为参与人。

参与人结构：

```json
{
  "id": "...",
  "requestId": "...",
  "userId": "...",
  "acceptedAt": "2026-07-24T10:00:00",
  "createdAt": "2026-07-24T10:00:00"
}
```

最后一位参与人退出后，需求回到 `PUBLISHED` 并清空 `firstAcceptedAt`。参与人已经为该需求上传图片或创建工时后不能退出。

主状态流转：

```text
DRAFT -> PUBLISHED -> ACCEPTED -> SUBMITTED -> COMPLETED
                      ^             |
                      |--- return ---|

DRAFT/PUBLISHED/ACCEPTED/SUBMITTED -> CANCELLED
```

- `submit`：仅参与人或 A；只要求当前为 `ACCEPTED`，**不要求已有图片**。
- `return`：仅把 `SUBMITTED` 打回 `ACCEPTED`，原因必填、最多 500，并通知参与人和创建者。
- 再次 `submit` 或 `complete` 会清空打回字段。
- `complete`：仅 `SUBMITTED`。
- `cancel`：已完成或已取消需求不能再取消，原因必填、最多 500。
- 管理员删除只逻辑删除需求，不级联删除历史图片、工时或参与记录。

## 9. 图片、单张上传与批量上传

### 9.1 图片列表和管理路由

| 方法与路径 | 权限 | 请求/查询 | 返回 |
| --- | --- | --- | --- |
| `GET /photos` | A/M/C | 分页和筛选 | `PageData<PhotoView>` |
| `GET /photos/{id}` | A/M/C | — | `PhotoView` |
| `PUT /photos/{id}` | 上传人或 A/M | `MetadataRequest` | `PhotoView` |
| `PATCH /photos/{id}/campus` | A | `{ campusId, version }` | `PhotoView` |
| `POST /photos/{id}/download-url` | 上传人或 A/M | 无请求体 | `DownloadUrl` |
| `POST /photos/{id}/archive` | A/M | 无请求体 | `PhotoView` |
| `POST /photos/{id}/restore` | A/M | 无请求体 | `PhotoView` |
| `DELETE /photos/{id}` | A/M | 无请求体 | `data: null` |

列表：

```text
GET /photos?page=1&pageSize=30
  &keyword=毕业
  &projectId=...
  &requestId=...
  &photographerStudentId=20260001
  &photographerName=王
  &uploadedBy=...
  &campusId=...
  &status=AVAILABLE
  &includeAllStatuses=false
  &favoritesOnly=false
  &selectableOnly=false
```

实际支持的查询参数只有上表这些；当前不支持 `tag`、`takenFrom`、`takenTo`、`adopted` 或 `sort`。`keyword` 模糊匹配标题、说明和 `tagsJson`。

可见范围：

- 默认按调用方权限组的**图库可见范围**（`photoVisibility`）过滤：`SELF` 只返回本人上传的图片，`CAMPUS` 返回授权校区内的全部图片，`GLOBAL` 不受校区授权限制。数据范围为 `CAMPUS` 的账号只有在 `GLOBAL` 档才能跨校区，也只有这时 `campusId` 筛选对它生效。
- `favoritesOnly=true` 在上述范围上再叠加"当前用户收藏过"，需要 `PHOTO_VIEW`。
- `selectableOnly=true` 改用**好图精选选图范围**（授权校区内的全部图片，与 `photoVisibility` 无关），需要 `PHOTO_VIEW`。选图弹窗用它保证列出的图片都能真正提交。

状态规则：

- 明确传 `status` 时按该状态筛选。
- 未传 `status` 且 `includeAllStatuses=false` 时默认只返回 `AVAILABLE`。
- 未传 `status` 且 `includeAllStatuses=true` 时不加状态条件，项目详情页用它让相册数量与 `photoCount` 对齐。
- C 的 `uploadedBy` 会被强制替换为当前用户，只能列出本人上传图片。
- C 查询单张详情时也必须是上传人。
- 默认按上传时间倒序。

元数据更新：

```json
{
  "title": "毕业典礼会场全景",
  "description": "说明",
  "photographerContactId": "...",
  "takenAt": "2026-07-24T09:10:00",
  "tags": ["毕业季", "典礼"],
  "version": 3
}
```

标题必填、最多 200；说明最多 5000；标签最多 30 个、每个最多 50；拍摄时间不能在未来。拍摄者必须是符合图片校区范围的启用通讯录成员。更新会覆盖照片上的姓名/学号快照，但不会追溯修改已有采用记录的作者快照。

修改校区时 `campusId` 可为 `null`，用于清空旧数据校区；非空时校区必须存在。该操作使用乐观锁。

归档只允许 `AVAILABLE -> ARCHIVED`，恢复只允许 `ARCHIVED -> AVAILABLE`。M 删除已被采用图片时返回冲突，A 可删除；删除会逻辑删除数据库行，并 best-effort 清理成品、预览和原图对象。

下载地址响应：

```json
{
  "downloadUrl": "https://signed.example/...",
  "expiresAt": "2026-07-24T10:45:00Z",
  "fileName": "张三-王同学-20260724T091000.jpg"
}
```

只有 `AVAILABLE` 或 `ARCHIVED` 可下载。该接口没有请求体，旧客户端不要再发送 `purpose`。

### 9.2 单张上传完整流程

上传使用三阶段：

```text
创建票据 -> 浏览器 PUT 到签名 URL -> complete-upload -> 轮询图片状态
```

#### 第一步：创建上传票据

`POST /photos/upload-tickets`，A/M/C 均可调用。

```json
{
  "requestId": "...",
  "projectId": "...",
  "fileName": "IMG_0001.jpg",
  "contentType": "image/jpeg",
  "size": 12345678,
  "sha256": "64位十六进制SHA-256",
  "photographerContactId": "...",
  "takenAt": "2026-07-24T09:10:00"
}
```

`requestId`、`projectId` 可空，表示普通图库上传。存在 `requestId` 时服务端会使用需求的 `projectId` 和 `campusId`，C 必须是需求参与人；因此单张需求上传中的 `projectId` 即使传错也不会成为最终来源项目。

规则：

- 只支持 JPG/JPEG 和 PNG。
- `fileName` 扩展名必须与 `contentType` 一致。
- 单张大小 `1..104857600` 字节（100 MiB）。
- `sha256` 必须为 64 位十六进制；服务端转小写并对未删除图片做全库查重。
- 拍摄者必须来自通讯录。需求上传按需求校区校验；C 的普通图库上传按本人校区校验；A/M 普通图库上传可使用 `/campus-members/deduped` 返回的代表 ID。
- 文件校验和参与权限在拍摄者校验之前执行，客户端不要依赖错误顺序之外的推断。

响应：

```json
{
  "photoId": "...",
  "uploadUrl": "https://...",
  "method": "PUT",
  "contentType": "image/jpeg",
  "expiresAt": "2026-07-24T10:45:00Z"
}
```

#### 第二步：直传对象存储

必须向完整的 `uploadUrl` 发起原始文件 PUT，不使用 `/api/v1` 基础地址，也不加业务 Bearer token：

```ts
await axios.request({
  method: ticket.method || 'PUT',
  url: ticket.uploadUrl,
  data: file,
  headers: { 'Content-Type': ticket.contentType },
  transformRequest: [value => value],
})
```

`Content-Type` 必须与票据的 `contentType` **完全一致**。生产环境为 OSS URL，本地环境为 `/api/v1/local-storage/objects/{token}`。成功时 OSS 通常返回 `200`，本地存储 PUT 返回 `204`；客户端只应判断 2xx。

#### 第三步：确认上传

`POST /photos/{id}/complete-upload`，其中 `{id}` 是票据返回的 `photoId`；仅票据创建者或 A。

```json
{
  "title": "毕业典礼会场全景",
  "description": "典礼开始前拍摄",
  "tags": ["毕业季", "典礼", "全景"]
}
```

服务端检查对象存在与实际大小，把图片从 `UPLOADING` 原子切换到 `PROCESSING`，然后异步校验文件魔数、MIME、SHA-256，压缩成品并生成预览图。响应中的图片通常仍是 `PROCESSING`。

#### 第四步：轮询处理结果

每 1.5～2 秒调用 `GET /photos/{photoId}`：

- `AVAILABLE`：成功。
- `PROCESSING`：继续等待。
- `UPLOADING` 且 `failureReason` 非空：处理失败，可提示用户重新上传/重新创建票据。
- `ARCHIVED`：可视为已处理成功但已归档。

现有 Web 客户端最多等待约 90 秒。不要在 `complete-upload` 返回 200 后立即假定图片已经可检索。

处理成功后，JPEG 仍为 JPEG、PNG 仍为 PNG；成品目标不超过 10 MiB，PNG 保留透明通道。`PhotoView.size`、`width`、`height` 是处理后成品值，不一定等于客户端原文件。可用成品生成后，原始对象默认再保留 30 天，并由后台任务在到期后清理。

### 9.3 批量上传票据

`POST /photos/batch-upload-tickets`，A/M/C 均可调用。

批量模式：

- `FILES`：1～100 个 JPG/PNG，每个最多 100 MiB，每个必须提供 SHA-256。
- `ZIP`：一个最多 1,500,000,000 字节的 ZIP；解压后只接受 JPG/JPEG/PNG，最多 100 张有效图片，单张最多 100 MiB，总展开大小最多 10 GiB。

ZIP 请求：

```json
{
  "mode": "ZIP",
  "requestId": "...",
  "projectId": "...",
  "archiveFileName": "毕业典礼图片.zip",
  "archiveSize": 123456789
}
```

FILES 请求：

```json
{
  "mode": "FILES",
  "requestId": null,
  "projectId": "...",
  "files": [
    {
      "fileName": "IMG_0001.jpg",
      "contentType": "image/jpeg",
      "size": 12345678,
      "sha256": "64位十六进制SHA-256"
    }
  ]
}
```

需求批量上传时，C 必须是参与人。与单张上传不同，当前批量服务只校验 `requestId`，**不会自动把 `projectId` 改成需求的项目**；客户端必须同时传入 `request.projectId`，否则新照片的来源项目/项目相册归属可能为空或错误。

批量票据响应：

```json
{
  "batchId": "01K...",
  "mode": "ZIP",
  "tickets": [
    {
      "itemId": null,
      "fileName": "毕业典礼图片.zip",
      "uploadUrl": "https://...",
      "contentType": "application/zip",
      "expiresAt": "2026-07-24T10:45:00Z"
    }
  ]
}
```

FILES 每项的 `itemId` 非空，ZIP 的归档票据 `itemId` 为 `null`。用与单张上传相同的方式逐个 PUT，且精确使用票据 `contentType`。

### 9.4 批次确认、查询和元数据

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `POST /photos/batches/{id}/complete-upload` | 创建者或 A | `{id}` 为 `batchId`；确认对象上传完成，开始校验/解压 |
| `GET /photos/batches/{id}` | 创建者或 A | `{id}` 为 `batchId`；查询批次和所有条目 |
| `PUT /photos/batches/{batchId}/items/{itemId}/metadata` | 创建者或 A | 单条填写元数据 |
| `PUT /photos/batches/{batchId}/metadata` | 创建者或 A | 对所有等待条目应用共同元数据 |

批次查询响应：

```json
{
  "batch": {
    "id": "01K...",
    "mode": "ZIP",
    "requestId": "...",
    "projectId": "...",
    "createdBy": "...",
    "archiveObjectKey": "temporary/batches/.../archive.zip",
    "archiveFileName": "毕业典礼图片.zip",
    "archiveSize": 123456789,
    "status": "WAITING_METADATA",
    "totalCount": 20,
    "successCount": 0,
    "failureCount": 0,
    "failureReason": null,
    "createdAt": "2026-07-24T10:00:00",
    "updatedAt": "2026-07-24T10:01:00"
  },
  "items": [
    {
      "id": "...",
      "batchId": "01K...",
      "originalFileName": "IMG_0001.jpg",
      "tempObjectKey": "temporary/batches/...",
      "contentType": "image/jpeg",
      "size": 12345678,
      "sha256": null,
      "title": null,
      "description": null,
      "photographerStudentId": null,
      "photographerName": null,
      "takenAt": null,
      "tagsJson": null,
      "status": "WAITING_METADATA",
      "failureReason": null,
      "photoId": null,
      "createdAt": "2026-07-24T10:01:00",
      "updatedAt": "2026-07-24T10:01:00"
    }
  ]
}
```

`tempLocalPath` 被服务端隐藏，但批次和条目仍返回若干存储实现字段；客户端只应使用状态、计数、文件名、失败原因和 `photoId`，不要依赖 `archiveObjectKey`/`tempObjectKey`。

ZIP 确认后异步解压：

```text
UPLOADING -> PROCESSING -> WAITING_METADATA -> PROCESSING
                                              -> SUCCEEDED
                                              -> PARTIALLY_SUCCEEDED
                                              -> FAILED
```

路径穿越、绝对路径、图片数量/单图/展开总量超限等会使批次失败；目录和非图片条目会跳过。最终没有有效图片也会失败。

单条元数据：

```json
{
  "title": "毕业典礼会场全景",
  "description": "说明",
  "photographerContactId": "...",
  "takenAt": "2026-07-24T09:10:00",
  "tags": ["毕业季", "典礼"]
}
```

批量共同元数据：

```json
{
  "description": "毕业典礼交付",
  "photographerContactId": "...",
  "takenAt": "2026-07-24T09:10:00",
  "tags": ["毕业季", "典礼"]
}
```

共同元数据接口会为每个 `WAITING_METADATA` 条目创建照片，标题取 ZIP/条目原始文件名去掉最后一个扩展名，最长 200 个 Unicode code point。当前前端 ZIP 流程统一使用这一个接口。

建议先每 2 秒轮询到 `WAITING_METADATA`，提交元数据后再轮询到 `SUCCEEDED`、`PARTIALLY_SUCCEEDED` 或 `FAILED`。大型 ZIP 现有客户端最多等待约 10 分钟。

批量上传与单张上传的去重差异：

- FILES 会保存并在处理时校验每项 SHA-256，但创建批次时不做全库重复拦截。
- ZIP 解压条目当前不计算内容 hash，照片中写入 64 个 `0` 作为跳过 hash 校验哨兵。
- 因此客户端不能假定批量路径与单张路径具有相同的全库查重语义。

### 9.5 预览图后台状态

`GET /preview-generation/status`，A/M/C。

```json
{
  "status": "GENERATING",
  "total": 500,
  "processed": 120,
  "percentage": 24,
  "message": "预览图正在后台生成",
  "errorMessage": null,
  "startedAt": "2026-07-24T02:00:00Z",
  "completedAt": null
}
```

状态为 `PENDING`、`GENERATING`、`SUCCEEDED`、`FAILED`。这是进程内状态，重启后会从 `PENDING` 重新核对。失败不影响其他业务使用；完成后客户端应刷新图库，以取得新 `thumbnailUrl` 和 `thumbnailSize`。

## 10. 项目采用接口

### 10.1 加入采用

`POST /projects/{projectId}/adoptions`，A/M/C 均可调用，但受服务层可见性约束。

```json
{
  "photoIds": ["...", "..."],
  "remark": "用于毕业季推文"
}
```

返回 `AdoptionEntity[]`：

```json
{
  "id": "...",
  "projectId": "...",
  "photoId": "...",
  "photographerStudentId": "20260001",
  "photographerName": "王同学",
  "remark": "用于毕业季推文",
  "adoptedBy": "...",
  "adoptedAt": "2026-07-24T10:00:00",
  "deleted": false,
  "createdAt": "2026-07-24T10:00:00"
}
```

规则：

- 1～200 张，重复 ID 去重。
- 项目必须 `ACTIVE`。
- 图片必须 `AVAILABLE`，并且已经通过 `/projects/{id}/photos` 属于目标项目相册。
- 同一项目同一图片只有一条有效采用；重复采用返回 `DUPLICATE_RESOURCE`。
- 以前取消的记录会恢复并更新作者快照、备注、操作者和时间。
- C 只能操作本人已参与需求所属的可见项目，并且只能采用本人上传的图片。

### 10.2 查询与取消

| 方法与路径 | 权限 | 查询/说明 |
| --- | --- | --- |
| `GET /projects/{projectId}/adoptions` | A/M | `page=1`、`pageSize=50`、`photographerStudentId?` |
| `DELETE /projects/{projectId}/adoptions/{id}` | A/M | `{id}` 为采用记录 ID；项目不是 `COMPLETED` 时可取消 |

取消采用只逻辑删除采用记录，不删除项目相册归属，图片仍显示在项目详情中。

### 10.3 采用排行

`GET /statistics/adoptions/ranking?from=2026-01-01&to=2026-12-31&projectId=...&campusId=...`，A/M。

返回非分页数组：

```json
{
  "rank": 1,
  "photographerStudentId": "20260001",
  "photographerName": "王同学",
  "adoptedCount": 36
}
```

按采用记录的 `adoptedAt` 过滤，`to` 包含全天；按采用数降序，同数量并列排名。返回结构没有 `campus` 字段。

## 11. 工时接口

### 11.1 路由

| 方法与路径 | 权限 | 请求/查询 | 返回 |
| --- | --- | --- | --- |
| `POST /requests/{requestId}/worklogs` | A/C | `WorklogRequest` | `WorklogEntity` |
| `GET /worklogs` | A/M/C | 分页和筛选 | `PageData<WorklogEntity>` |
| `PUT /worklogs/{id}?version=1` | 本人或 A | `WorklogRequest` | `WorklogEntity`，状态重置为 `DRAFT` |
| `POST /worklogs/{id}/submit` | 本人或 A | `{ version }` | `WorklogEntity` |
| `POST /worklogs/{id}/confirm` | A/M | `{ version }` | `WorklogEntity` |
| `POST /worklogs/{id}/reject` | A/M | `{ reason, version }` | `WorklogEntity` |
| `DELETE /worklogs/{id}` | A/M/C | 无 | `data: null` |

创建/编辑请求：

```json
{
  "workDate": "2026-07-24",
  "memberContactId": "...",
  "shootingMinutes": 120,
  "retouchingMinutes": 60,
  "remark": "现场拍摄及基础调色",
  "status": "SUBMITTED"
}
```

规则：

- `workDate` 不能晚于今天。
- 两类分钟均为 `0..1440`，且不能同时为 0。
- `remark` 最多 1000。
- `memberContactId` 必须是需求校区的已启用通讯录成员；服务端把姓名/学号快照写入工时。
- C 必须是需求参与人；A 可代建。
- 创建时只有传 `DRAFT` 会保存为草稿，传其他任意工时枚举都会保存为 `SUBMITTED`。客户端只应发送 `DRAFT` 或 `SUBMITTED`。
- 编辑仅允许 `DRAFT`/`REJECTED`，并固定重置为 `DRAFT`、清空退回原因。版本号是 query 参数，不在请求体中。
- 提交仅允许 `DRAFT`/`REJECTED`。
- 确认/退回仅允许 `SUBMITTED`，并记录审核人和时间。
- C 只能编辑/提交自己的填报记录。M 是审核人，不能编辑他人记录。
- A/M 可删除任意状态；普通填报人只能删除自己的 `DRAFT`/`REJECTED`。

列表：

```text
GET /worklogs?page=1&pageSize=20
  &requestId=...
  &userId=...
  &status=SUBMITTED
  &from=2026-07-01
  &to=2026-07-31
```

C 的 `userId` 会被强制为本人。日期按 `workDate >= from AND workDate <= to`。默认按 `workDate` 倒序。

## 12. 统计、导出与批量下载

### 12.1 成员统计

`GET /statistics/members?from=2026-01-01&to=2026-12-31&projectId=...&campusId=...&userId=...`，A/M。

返回非分页数组：

```json
{
  "userId": "...",
  "studentId": "20260001",
  "displayName": "王同学",
  "campus": "南校区",
  "shootingMinutes": 1200,
  "retouchingMinutes": 600,
  "totalMinutes": 1800,
  "adoptedCount": 36
}
```

口径：

- 先选出状态为 `COMPLETED` 且 `completedAt` 落在 `[from, to + 1 day)` 的项目。
- 工时只统计这些项目需求中的 `CONFIRMED` 记录；这里不是按 `workDate` 直接筛选。
- 采用按摄影师学号关联，统计这些已完成项目中的 `COUNT(DISTINCT photo_id)`。
- `campusId` 同时限制需求校区和照片校区；`userId` 限制工时填报账号。
- `from`/`to` 可空；空值相当于极宽时间范围。

### 12.2 统计总览

`GET /statistics/overview?from=2026-01-01&to=2026-12-31&projectId=...`，A/M。

```json
{
  "projects": 10,
  "requests": 30,
  "photos": 500,
  "adoptions": 120,
  "shootingMinutes": 10000,
  "retouchingMinutes": 8000
}
```

`projects`、`requests`、`photos`、`adoptions` 是当前数据库计数，只受 `projectId` 影响，不受 `from`/`to` 影响；其中照片只计 `AVAILABLE`，项目过滤按项目相册多对多关系。时间范围只通过成员统计口径影响两类工时分钟数。

### 12.3 创建导出任务

统计 XLSX：

`POST /statistics/members/export`，A/M。

```json
{
  "from": "2026-01-01",
  "to": "2026-12-31",
  "projectId": null,
  "campusId": null,
  "format": "XLSX"
}
```

工时 XLSX：

`POST /worklogs/export`，A/M。

```json
{
  "from": "2026-07-01",
  "to": "2026-07-31",
  "format": "XLSX"
}
```

工时导出要求 `from <= to`，日期均必填；统计导出的日期可空。`format` 字段必填，但当前实现始终生成 XLSX，客户端固定传 `XLSX`。

批量图片 ZIP：

`POST /photos/batch-download`，A/M/C。

```json
{
  "photoIds": ["...", "..."],
  "purpose": "公众号排版"
}
```

1～200 张，去重后必须全部存在且状态为 `AVAILABLE` 或 `ARCHIVED`。C 只能下载同时满足“本人上传”和“本人校区”的图片。`purpose` 当前可选且未参与后端逻辑。

三个接口都立即返回 `ExportJobEntity`：

```json
{
  "id": "01K...",
  "type": "PHOTO_BATCH",
  "status": "PENDING",
  "progress": 0,
  "createdBy": "...",
  "objectKey": null,
  "errorMessage": null,
  "expiresAt": null,
  "createdAt": "2026-07-24T10:00:00",
  "updatedAt": "2026-07-24T10:00:00"
}
```

类型为 `MEMBER_STATISTICS`、`WORKLOGS` 或 `PHOTO_BATCH`。

### 12.4 查询导出任务

`GET /export-jobs/{id}`，任务创建者或 A。

响应不是扁平 Job，而是：

```json
{
  "job": {
    "id": "01K...",
    "type": "PHOTO_BATCH",
    "status": "SUCCEEDED",
    "progress": 100,
    "createdBy": "...",
    "objectKey": "exports/01K....zip",
    "errorMessage": null,
    "expiresAt": "2026-07-25T10:00:00",
    "createdAt": "2026-07-24T10:00:00",
    "updatedAt": "2026-07-24T10:00:05"
  },
  "downloadUrl": "https://signed.example/...",
  "expiresAt": "2026-07-24T10:15:05Z"
}
```

当前实际状态迁移为：

```text
PENDING -> SUCCEEDED
PENDING -> FAILED
```

实现目前不会显式写入 `PROCESSING`，也不会把任务状态改成 `EXPIRED`。任务文件有效期默认为生成后 1 天；过期后 `job.status` 仍可能是 `SUCCEEDED`，但顶层 `downloadUrl` 和 `expiresAt` 为 `null`。顶层 `expiresAt` 是本次签名 URL 的约 15 分钟到期时间，和 `job.expiresAt` 不是同一个概念。

建议每秒轮询，成功后使用顶层 `downloadUrl`，失败时显示 `job.errorMessage`。ZIP 条目名为 `{photoId}-{storedFileName}`，路径分隔符会被清理并处理重名。

## 13. 站内通知、管理消息与邮件日志

### 13.1 当前用户通知

| 方法与路径 | 权限 | 请求/查询 | 返回 |
| --- | --- | --- | --- |
| `GET /notifications` | A/M/C | `unreadOnly=false` | 当前用户最近 50 条 `UserNotificationEntity[]` |
| `GET /notifications/{id}` | 所有登录用户 | — | 仅本人通知 |
| `GET /notifications/unread-count` | 所有登录用户 | — | `{ count }` |
| `POST /notifications/{id}/read` | 所有登录用户 | 无 | 标为已读，幂等 |
| `POST /notifications/read-all` | 所有登录用户 | 无 | 全部标为已读 |
| `POST /notifications/messages` | A/M | `SendMessageRequest` | `{ recipientCount }` |

通知结构：

```json
{
  "id": "...",
  "userId": "...",
  "eventType": "REQUEST_PUBLISHED",
  "title": "新的图片需求",
  "content": "纯文本摘要",
  "actionUrl": "/requests",
  "senderId": null,
  "contentHtml": null,
  "readAt": null,
  "createdAt": "2026-07-24T10:00:00"
}
```

列表不是分页接口，固定最多 50 条。系统通知通常只有 `content`；人工管理消息可有 `contentHtml`。

### 13.2 广播与定向消息

`POST /notifications/messages`

广播：

```json
{
  "broadcast": true,
  "targetUserId": null,
  "title": "本周工作提醒",
  "contentHtml": "<p>请及时提交工时。</p>"
}
```

定向：

```json
{
  "broadcast": false,
  "targetUserId": "...",
  "title": "补充材料提醒",
  "contentHtml": "<p>请补充原图。</p>"
}
```

标题最多 100，HTML 最多 20000。定向发送必须提供已启用用户。广播发给所有已启用用户，包括发送者本人。

消息除写入站内信外，还会推送到收件人的企业微信（见 13.4）；未绑定企业微信的收件人只有站内信。企业微信消息不支持图片，正文里的消息图片显示为占位，跳转链接指向该条站内消息。

服务端会清理 HTML，只保留受控的基础格式以及 `p`、`div`、`h1`～`h3`、`img` 等；图片 `src` 只允许 `/api/v1/notifications/images/{26位PublicId}`。客户端必须把服务端清理后的 `contentHtml` 当成最终内容。

### 13.3 消息图片

上传：`POST /notifications/images`，A/M，`multipart/form-data`，字段名 `file`。

```json
{ "url": "/api/v1/notifications/images/01K..." }
```

只支持 JPEG、PNG、WebP，最大 5 MiB，同时校验声明 MIME 与文件魔数。

读取：`GET /notifications/images/{id}`，A/M/C，返回图片二进制，`Cache-Control: private, max-age=7 days`。

### 13.4 通知投递日志

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /notification-logs?status=FAILED&userId=...` | A | 最近 200 条，非分页 |
| `POST /notification-logs/{id}/retry` | A | 重置重试计数并立即投递 |

日志字段：`id`、`userId`、`channel`、`recipient`、`email`、`eventType`、`actionPath`、`status`、`retryCount`、`lastError`、`payloadJson`、`createdAt`、`updatedAt`。`actionPath` 是消息里跳转链接指向的站内路径（管理消息指向消息本身），V39 之前的记录为空，投递时兜底跳站内信列表。状态会出现 `PENDING`、`RETRYING`、`SENT`、`FAILED`。`payloadJson` 是服务端内部载荷，客户端只应展示，不应解析或回传。

`channel` 为 `WECOM` 时 `recipient` 是企业微信 UserID，这是当前唯一会新增的通道；`EMAIL` 只出现在 V38 之前的历史记录里，`recipient` 与 `email` 都是收件邮箱。未绑定企业微信的成员只有站内信，不会产生投递记录。

系统通知和管理消息（广播、定向）都会产生投递记录。广播是**每个绑定了企业微信的收件人一条记录、一次调用**，未绑定的收件人只有站内信。

外发失败不会回滚站内通知或主业务。自动重试最多 3 次，最终失败会创建 `NOTIFICATION_DELIVERY_FAILED` 管理员告警。

## 14. Markdown 说明图片

项目和需求说明是受控 Markdown，后端只保存文本；前端建议使用 GFM 渲染并禁止任意 HTML。

上传：`POST /description-images`，A/M，`multipart/form-data`，字段名 `file`。

```json
{ "url": "/api/v1/description-images/01K..." }
```

只支持 JPEG、PNG、WebP，最大 5 MiB，同时校验文件魔数。把响应 URL 原样插入 Markdown：

```md
![现场参考图](/api/v1/description-images/01K...)
```

读取：`GET /description-images/{id}`，返回二进制并使用私有 7 天缓存。权限：

- A/M 可读取所有说明图片。
- 上传者可读取自己上传的图片。
- C 只有在图片被其可见的项目或已参与的非草稿需求说明引用时可读取。

当前没有删除接口、引用计数或自动垃圾回收。客户端不要构造外部图片 URL；现有 Markdown 渲染策略只支持站内说明图片。

## 15. 品牌设置

### 15.1 基础品牌

`GET /branding`，已登录。

```json
{
  "title": "PhotoLib",
  "iconType": "builtin",
  "builtinIcon": "camera",
  "customIconUrl": null,
  "slogan": "摄影工作站",
  "displayIconType": "builtin",
  "displayIconUrl": null,
  "nextIconRefreshAt": "2026-07-25T00:00:00+08:00"
}
```

`iconType` 是持久配置；`displayIconType`/`displayIconUrl` 已考虑当天定时图标，是客户端当前应展示的图标。`customIconUrl` 和 `displayIconUrl` 是以 `/api/v1` 开头的站内路径。

更新：`PUT /branding`，A。

```json
{
  "title": "校园摄影部",
  "iconType": "builtin",
  "builtinIcon": "camera",
  "slogan": "摄影工作站"
}
```

标题最多 40，Slogan 最多 80，均非空。内置图标为：`camera`、`aperture`、`picture`、`bulb`、`star`。选择 `custom` 前必须已经上传自定义图标。

上传自定义图标：`POST /branding/icon`，A，`multipart/form-data` 字段 `file`。只支持 PNG/JPEG，最大 512 KiB，宽高均不超过 1024；上传成功后自动把 `iconType` 切为 `custom`。

读取自定义图标：`GET /branding/icon`，公开，返回二进制；不存在时返回裸 `404`。

### 15.2 定时品牌图标

| 方法与路径 | 权限 | 返回 |
| --- | --- | --- |
| `GET /branding/scheduled-icons` | A | `ScheduledIconView[]` |
| `PUT /branding/scheduled-icons` | A | 全量替换并返回新数组 |
| `GET /branding/scheduled-icons/{id}/icon` | 公开 | 图片二进制 |

视图结构：

```json
{
  "id": "...",
  "cronExpression": "0 0 0 1 10 *",
  "iconUrl": "/api/v1/branding/scheduled-icons/.../icon?v=..."
}
```

替换接口使用 multipart：

- `rules`：`application/json` 的规则数组。
- `files`：0～20 个重复文件 part。
- 新规则 `id=null` 且必须提供 `fileIndex`。
- 保留旧图的规则传旧 `id`，可不传 `fileIndex`。
- `fileIndex` 是 `files` 数组的零基索引，每个文件只能引用一次。
- 未出现在新数组的旧规则会删除；传空数组会清空全部规则。

浏览器示例：

```ts
const form = new FormData()
form.append('rules', new Blob([JSON.stringify([
  { id: null, cronExpression: '0 0 0 1 10 *', fileIndex: 0 },
])], { type: 'application/json' }))
form.append('files', iconFile)
await http.put('/branding/scheduled-icons', form)
```

Cron 使用 Spring 六字段表达式，最长 128。最多 20 条；服务端会检查不同规则未来日期冲突。图标限制与普通品牌图标相同。

## 16. 管理员告警、审计与基础选项

### 16.1 管理员告警

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /admin-alerts?resolved=false` | A | 最多 500 条，按创建时间倒序 |
| `POST /admin-alerts/{id}/resolve` | A | 标记已处理；不存在的 ID 也返回成功 |

结构：

```json
{
  "id": "...",
  "type": "NOTIFICATION_DELIVERY_FAILED",
  "message": "通知连续投递失败：...",
  "resourceType": "NOTIFICATION",
  "resourceId": "...",
  "resolved": false,
  "createdAt": "2026-07-24T10:00:00",
  "resolvedAt": null,
  "resolvedBy": null
}
```

### 16.2 审计日志

`GET /audit-logs`，A。

查询参数：

```text
operatorId? action? resourceType? keyword? from? to? page=1 pageSize=20
```

- `action` 实际为 HTTP 方法：`POST`、`PUT`、`PATCH`、`DELETE`。
- `resourceType` 是路径 `/api/v1/` 后第一段的大写形式，例如 `PHOTOS`、`REQUESTS`。
- `keyword` 匹配操作者用户名/姓名、资源 ID、请求 ID 和 IP。
- `from`/`to` 为日期，`to` 包含全天。
- `page` 小于 1 会夹到 1，`pageSize` 会夹在 1～100。

返回 `PageData<AuditLogView>`：

```json
{
  "id": "...",
  "operatorId": "...",
  "operatorUsername": "admin",
  "operatorDisplayName": "系统管理员",
  "action": "POST",
  "resourceType": "PHOTOS",
  "resourceId": "...",
  "requestId": "...",
  "detailJson": "{\"path\":\"/api/v1/photos/...\",\"status\":200}",
  "ipAddress": "127.0.0.1",
  "createdAt": "2026-07-24T10:00:00"
}
```

导出：`GET /audit-logs/export`，A。查询筛选与列表相同，不含分页。响应为带 UTF-8 BOM 的 `text/csv`，所有字段双引号转义，最大 100,000 条。客户端必须以 Blob/文件方式处理，不要按统一 JSON 信封解析。

审计当前只记录写操作，登录和刷新被排除；日志写入失败不会让主业务失败。

### 16.3 基础选项

`GET /metadata/options`，A/M/C。

```json
{
  "roles": ["ADMIN", "MINISTER", "CAMPUS_MANAGER"],
  "projectStatuses": ["DRAFT", "ACTIVE", "COMPLETED", "CANCELLED"],
  "requestStatuses": ["DRAFT", "PUBLISHED", "ACCEPTED", "SUBMITTED", "COMPLETED", "CANCELLED"],
  "photoStatuses": ["UPLOADING", "PROCESSING", "AVAILABLE", "ARCHIVED", "DELETED"],
  "worklogStatuses": ["DRAFT", "SUBMITTED", "CONFIRMED", "REJECTED"],
  "allowedImageTypes": ["image/jpeg", "image/png"],
  "singleImageMaxBytes": 104857600,
  "batchImageMaxCount": 100,
  "zipMaxBytes": 1500000000,
  "batchDownloadMaxCount": 200
}
```

客户端可用它生成筛选选项和上传限制，但角色对应的中文标签仍由客户端本地化。

### 16.4 数据库备份与回滚

仅 A。这组接口刻意不对应任何权限码，因此不会出现在权限面板，也无法授予自定义权限组；后端按"系统管理员"直接判定。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/database-backups` | 立即发起一次手动备份，异步执行 |
| POST | `/database-backups/upload` | 导入管理员上传的备份文件（multipart，字段名 `file`），同步校验后入库 |
| GET | `/database-backups?page&pageSize` | 备份列表，按开始时间倒序 |
| GET | `/database-backups/{id}` | 单条备份 |
| GET | `/database-backups/{id}/download` | 取备份文件的短期签名下载地址 |
| POST | `/database-backups/{id}/restore` | 回滚到该备份，异步执行 |
| GET | `/database-restores?page&pageSize` | 回滚记录列表 |
| GET | `/database-restores/{id}` | 单条回滚记录 |

备份实体：

```json
{
  "id": "01J8Z...",
  "type": "SCHEDULED",
  "status": "SUCCEEDED",
  "sizeBytes": 2906,
  "sha256": "…64 位小写…",
  "tableCount": 36,
  "rowCount": 65,
  "schemaVersion": "33",
  "errorMessage": null,
  "createdBy": null,
  "createdByName": null,
  "startedAt": "2026-08-27T00:00:00",
  "finishedAt": "2026-08-27T00:00:03",
  "downloadable": true,
  "restorable": true
}
```

- `type`：`SCHEDULED`（每天凌晨 0 点自动）、`MANUAL`（管理员手动）、`PRE_RESTORE`（回滚前自动生成的兜底备份）、`UPLOADED`（管理员上传导入）。`UPLOADED` 会额外返回 `sourceFileName`（原始文件名）。
- `status`：`RUNNING`、`SUCCEEDED`、`FAILED`、`EXPIRED`（超过保留期，文件已删除，记录保留）。
- `restorable` 为 `false` 时不要展示回滚入口：备份只含数据不含表结构，`schemaVersion` 与当前库不一致时不能回滚。
- `POST` 两个接口都立刻返回 `RUNNING` 记录，实际执行是异步的；客户端应轮询列表或单条记录直到状态不再是 `RUNNING`。已有任务在执行时再次发起会返回 `409 RESOURCE_STATE_CONFLICT`。
- 回滚会先自动生成一份 `PRE_RESTORE` 备份并记录在 `safetyBackupId` 上，再用目标备份整体替换数据库；备份文件的大小和 SHA-256 校验不通过时会中止，不会改动数据。
- 回滚只替换业务数据，不会删除对象存储中的图片文件；但数据库中已不存在的图片记录将无法再访问。回滚后所有会话可能失效，客户端应能正常走 401 → refresh → 登录页链路。
- 下载响应是 `{ "url": "...", "fileName": "...", "expiresAt": "..." }`，`url` 是短期签名地址，直接跳转下载，不要加 Bearer 头。
- **导入备份文件**：`POST /database-backups/upload` 是 `multipart/form-data`，字段名固定为 `file`，只接受本系统导出的 `.jsonl.gz`。该接口**同步**完成校验和入库，成功返回一条 `status=SUCCEEDED` 的 `UPLOADED` 备份，随后按普通备份调用 `/restore` 回滚；导入本身不会改动数据库。校验不通过时不会留下任何记录，按错误码区分：`413 FILE_TOO_LARGE`（体积或解压体积超限）、`415 UNSUPPORTED_FILE_TYPE`（不是 gzip 文件）、`409 RESOURCE_STATE_CONFLICT`（当前库缺少备份里的表，或结构版本不一致）、`400 VALIDATION_ERROR`（归档损坏、格式无法识别、表或字段与当前表结构对不上、行结构非法），`message` 会指出具体是哪张表、哪个字段。

## 17. 好图精选

部长发布征集要求，被指派的校区负责人从图库选图并补充拍摄思路与地点；到达截止时间（或部长手动截止）后，服务端生成一份按校区分章的 Word 文档。

**权限分三层**：发布、编辑、删除、手动截止、重新生成文档需要 `FEATURED_MANAGE`（默认 A、M）；查看与下载只要求登录，不对应任何权限码；填报条目不看权限码，只看这份精选有没有指派到当前用户（`assignedToMe`）。

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/featured-collections?page&pageSize&keyword&status` | 登录 | 列表，按创建时间倒序 |
| GET | `/featured-collections/{id}` | 登录 | 详情 |
| GET | `/featured-collections/{id}/entries` | 登录 | 全部条目，已按校区、填报顺序排好 |
| GET | `/featured-collections/{id}/document` | 登录 | 取 Word 文档的短期签名下载地址 |
| GET | `/featured-collections/assignable-managers` | `FEATURED_MANAGE` | 可指派的校区负责人 |
| POST | `/featured-collections` | `FEATURED_MANAGE` | 新建，落到 `DRAFT` |
| PUT | `/featured-collections/{id}` | `FEATURED_MANAGE` | 编辑（`DRAFT` 全字段；`PUBLISHED` 不能改开始时间） |
| POST | `/featured-collections/{id}/publish` | `FEATURED_MANAGE` | 发布并通知被指派的负责人 |
| POST | `/featured-collections/{id}/close` | `FEATURED_MANAGE` | 手动截止并开始生成文档 |
| POST | `/featured-collections/{id}/document` | `FEATURED_MANAGE` | 重新生成文档（仅 `CLOSED` 且当前不在生成中） |
| DELETE | `/featured-collections/{id}?version` | `FEATURED_MANAGE` | 逻辑删除 |
| POST | `/featured-collections/{id}/entries` | 被指派 | 新增条目 |
| PUT | `/featured-collections/{id}/entries/{entryId}` | 条目提交人 | 改拍摄思路与地点 |
| DELETE | `/featured-collections/{id}/entries/{entryId}` | 条目提交人 | 撤下条目（物理删除） |

枚举：

```ts
type FeaturedCollectionStatus = 'DRAFT' | 'PUBLISHED' | 'CLOSED'
type FeaturedDocumentStatus = 'PENDING' | 'GENERATING' | 'READY' | 'FAILED'
type FeaturedCloseReason = 'MANUAL' | 'DEADLINE'
```

创建/编辑请求体（编辑时额外带 `version`）：

```json
{
  "title": "2026 年春季好图精选",
  "requirementHtml": "<p>要求正文</p>",
  "startsAt": "2026-05-01T09:00:00",
  "endsAt": "2026-05-10T18:00:00",
  "assignAll": false,
  "entryLimit": 5,
  "campusIds": [1, 2],
  "userIds": [17]
}
```

- `assignAll=true` 表示全部校区负责人，此时忽略 `campusIds` / `userIds`；为 `false` 时两者至少填一个。`campusIds` 指该校区的全部负责人，`userIds` 是单独点名。"负责人"= 权限组数据范围为 `CAMPUS` 的启用账号。
- `requirementHtml` 是受控富文本，服务端会再清洗一次，并且**只保留 `src` 形如 `/api/v1/description-images/{26 位 id}` 的图片**。配图请上传到 `POST /description-images`（见 §14），不要用消息图片接口——那类图片只对消息收件人可读。
- `entryLimit` 是**每人**的提交上限，范围 1～50。

条目请求体：`{ "photoId": 12, "idea": "拍摄思路", "location": "拍摄地点" }`（`PUT` 额外带 `version`，且 `photoId` 必须与原条目一致，换图请撤下后重选）。**拍摄人和拍摄时间不接受输入**，服务端从图库记录快照写入。选图沿用图库可见范围：校区范围账号只能选自己上传、且在授权校区内的 `AVAILABLE`/`ARCHIVED` 图片。

精选实体的客户端相关字段：

```json
{
  "id": 3, "title": "…", "requirementHtml": "<p>…</p>", "requirementText": "…",
  "startsAt": "2026-05-01T09:00:00", "endsAt": "2026-05-10T18:00:00",
  "status": "PUBLISHED", "assignAll": false, "entryLimit": 5,
  "campusIds": [1], "userIds": [17],
  "documentStatus": "PENDING", "documentGeneratedAt": null, "documentSize": null,
  "documentError": null, "createdBy": 2, "creatorDisplayName": "部长",
  "publishedAt": "2026-04-28T10:00:00", "closedAt": null, "closedReason": null,
  "entryCount": 12, "assignedToMe": true, "submissionOpen": true,
  "myEntryCount": 2, "canManage": false, "createdAt": "…", "version": 3
}
```

- `submissionOpen` 已经综合了状态、指派关系和时间窗口，客户端直接用它决定要不要显示填报入口，不要自己再算一遍。
- 时间边界：**开始时间含，截止时间不含**。到点后即使定时任务还没扫到（`status` 仍是 `PUBLISHED`），写接口也会返回 `409`，界面此时应显示"待生成文档"而不是"征集中"。
- 条目实体带 `campusId`/`campusName`（图片所属校区，决定它进文档的哪一章，不是填报人的校区）、`previewUrl`（短期签名地址，可能为空）、`photoAvailable`（图片是否还在图库里；为 `false` 时条目只剩提交时的文字快照）、`mine`。
- 条目返回顺序已经按"校区编码 → 填报顺序 → id"排好，与 Word 文档章节一致，**客户端不要再排序**。
- 文档在截止后异步生成，`documentStatus` 从 `GENERATING` 变为 `READY` 或 `FAILED`；界面应轮询或提供刷新。`READY` 之前调用下载接口返回 `409`。下载响应是 `{ "downloadUrl", "expiresAt", "fileName" }`，`downloadUrl` 是短期签名地址，直接下载，不要加 Bearer 头；`fileName` 含中文。

## 18. 文档中心

管理员和部长写文档，读者在登录页前面就能读。结构是一棵 Obsidian 式的树：`FOLDER` 只是容器，`DOCUMENT`（Markdown 正文）和 `PDF`（直接上传的 PDF 文件）都是叶子。正文、插图和 PDF 都存在对象存储里；Markdown 接口返回正文文本本身，PDF 则给一个仍需鉴权的接口地址，两者都不是签名地址。

**读文档不需要任何权限码，也不需要已分配权限组**：只要账号登录成功（初始密码已改），它读到的就是"已登录成员"能看到的那份目录，包括 `MEMBERS` 文档。还没被管理员分配权限组的账号进不了系统的其他部分，但文档中心照常可读——要求登录才能看的入部材料正是给这类账号准备的。

**两条访问路径，授权模型不同**：`/docs/**` 是编辑接口，整组要 `DOC_MANAGE`（默认 A、M）；`/public/docs/**` 是阅读接口，不带令牌也能调用。注意路径里的 `public` 指的是"不需要登录就能调用"，**不是"返回的都是公开内容"**——同一个接口带令牌调用会多返回仅限成员的文档。

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/public/docs` | 无 | 读者目录树；未登录只含公开文档，带令牌则含仅成员文档 |
| GET | `/public/docs/{publicId}` | 无 | 打开一篇已发布文档；Markdown 给正文，PDF 给 `fileUrl` |
| GET | `/public/docs/{publicId}/file` | 无 | PDF 文档的二进制，可见范围与正文接口完全一致 |
| GET | `/public/docs/assets/{assetId}` | 无 | 正文插图的二进制，可见范围跟随所属文档 |
| GET | `/docs/tree` | `DOC_MANAGE` | 编辑视角的整棵树，含草稿 |
| GET | `/docs/{id}` | `DOC_MANAGE` | 单个节点的元数据 + 正文 + 面包屑 |
| POST | `/docs` | `DOC_MANAGE` | 新建文件夹或文档 |
| PUT | `/docs/{id}/title` | `DOC_MANAGE` | 重命名 |
| PUT | `/docs/{id}/content` | `DOC_MANAGE` | 保存 Markdown 正文 |
| POST | `/docs/{id}/publication` | `DOC_MANAGE` | 发布 / 退回草稿 |
| POST | `/docs/{id}/visibility` | `DOC_MANAGE` | 设定是否需要登录才能查看 |
| POST | `/docs/{id}/move` | `DOC_MANAGE` | 拖拽移动 + 同级排序 |
| DELETE | `/docs/{id}?version` | `DOC_MANAGE` | 逻辑删除，连同整棵子树 |
| POST | `/docs/{id}/assets` | `DOC_MANAGE` | 上传正文插图（multipart，字段名 `file`） |
| POST | `/docs/pdf` | `DOC_MANAGE` | 上传一份 PDF 作为新文档（multipart：`file`、`title`、可选 `parentId`） |
| PUT | `/docs/{id}/pdf?version` | `DOC_MANAGE` | 换掉这份 PDF 的文件（multipart，字段名 `file`） |
| GET | `/docs/{id}/file` | `DOC_MANAGE` | 编辑器预览 PDF，草稿也给 |

枚举：

```ts
type DocNodeType = 'FOLDER' | 'DOCUMENT' | 'PDF'
type DocVisibility = 'PUBLIC' | 'MEMBERS'   // PUBLIC=未登录可读，MEMBERS=必须登录
```

### 18.1 可见性

一篇文档要被读者看到，`published` 和 `visibility` **两个条件都要满足**，它们是正交的两个开关，各有独立接口：

- `published=false`：草稿。对所有读者（包括已登录的普通成员）都是 `404`。
- `published=true` + `visibility='PUBLIC'`：任何人都能读。
- `published=true` + `visibility='MEMBERS'`：**未登录看不到**。目录里整条隐藏（连标题都不返回），直链打开返回 `403`，正文里的插图匿名请求同样被拒。

新建文档默认 `MEMBERS`，需要一次显式的"设为公开"才会对匿名访客可见。

**未登录直链打开仅成员文档返回 `403` 而不是 `404`**，这是刻意的：成员把链接分享给还没登录的同学时，客户端应据此提示"请先登录"。区分 `403`（要登录）与 `404`（不存在或未发布）来决定提示语。文件夹没有这两个开关——它在子树里有读者可见的文档时自动出现，否则整个不返回。

### 18.2 请求与响应

新建：`{ "parentId": null, "nodeType": "DOCUMENT", "title": "快速上手" }`（`parentId=null` 表示根目录，非空时必须是文件夹）。**`nodeType` 只能是 `FOLDER` 或 `DOCUMENT`**：PDF 必须带着文件走 `POST /docs/pdf`，一个没有文件的 PDF 节点既发布不了也预览不了。同一目录下两个叶子不能重名（Markdown 和 PDF 之间也算），但文件夹可以和叶子同名。

保存正文：`{ "content": "# 标题\n正文", "version": 3 }`，正文上限 100,000 字符。

发布：`{ "published": true, "version": 3 }`；可见范围：`{ "visibility": "PUBLIC", "version": 3 }`。**没有正文的文档不能发布**，会返回 `409`。

移动：`{ "parentId": 7, "index": 2, "version": 3 }`。`index` 是在目标父节点下的落点，**语义是"把被移动的节点从兄弟列表里摘掉之后的序号"**。同层往后拖时如果按摘除前的序号发，落点会整体偏一位——这个偏差不会报错，只会让文档悄悄排到别处。

写操作统一返回整棵新树，客户端直接替换即可，不要逐节点打补丁：

```json
{ "tree": [ /* ManageNode[] */ ], "focusId": 12 }
```

编辑视角的节点（`ManageNode`，`children` 递归嵌套）：

```json
{
  "id": 12, "publicId": "XK54YN0XKN1E657AHQZZ3TQDQ9", "parentId": 7,
  "nodeType": "DOCUMENT", "title": "快速上手", "sortOrder": 0,
  "published": true, "visibility": "MEMBERS", "hasContent": true,
  "contentSize": 480, "summary": "目录里显示的一句话摘要",
  "updaterDisplayName": "部长", "updatedAt": "2026-08-30T10:00:00",
  "version": 3, "children": []
}
```

读者视角的节点（`ReaderNode`）只有 `publicId`、`nodeType`、`title`、`summary`、`requiresLogin`、`updatedAt`、`children`——**没有数字 id**，读者路径一律用 `publicId` 寻址。`requiresLogin` 只对已登录读者有意义（未登录时这类文档根本不返回），用来显示一把锁。

打开文档返回 `{ "publicId", "nodeType", "title", "content", "fileUrl", "fileSize", "requiresLogin", "updatedAt", "updaterDisplayName", "breadcrumb" }`，`breadcrumb` 是从根到本篇的名称链（含自身）。两种叶子共用这一个结构：`DOCUMENT` 带 `content`、`fileUrl` 为 `null`；`PDF` 的 `content` 是空串，`fileUrl` 形如 `/api/v1/public/docs/{publicId}/file`。按 `nodeType` 决定渲染方式，不要靠 `content` 是否为空来猜。

### 18.3 PDF 文档

`POST /docs/pdf` 只接受 `application/pdf`，单份最大 50 MiB，文件头必须真的是 `%PDF-`（声明的 `Content-Type` 一律不可信）。上传后的节点**仍然是草稿、仍然默认仅成员可见**——发布和可见范围走的还是原来那两个开关，和 Markdown 文档完全一致。

替换文件用 `PUT /docs/{id}/pdf?version=3`，对象键跟着 `publicId` 走，所以读者手上的链接在替换后继续有效。

**客户端不要把 `fileUrl` 直接塞给 `<iframe src>`**：和插图同理，仅成员的 PDF 需要 Bearer 头，`<iframe>` 不会带令牌，只会显示一个 403 白框；编辑器预览草稿用的 `/docs/{id}/file` 更是要 `DOC_MANAGE`。统一用带鉴权头的请求取 Blob 再渲染，并在切换文档和卸载时 `URL.revokeObjectURL`——一份几十 MiB 的 PDF 留在内存里，点几篇就能把标签页撑爆。

### 18.4 插图

`POST /docs/{id}/assets` 接受 JPEG / PNG / WebP，单张最大 5 MiB，声明的 `Content-Type` 必须与文件魔数一致。返回 `{ "id", "url" }`，`url` 形如 `/api/v1/public/docs/assets/{assetId}`，把它写进 Markdown 的 `![](...)` 即可。

插图必须挂在一篇已存在的文档上（所以是 `POST /docs/{id}/assets` 而不是一个全局上传接口）：图片的可见范围完全跟随所属文档，没有归属就无从判定。

**客户端不要用 `<img src>` 直接加载插图**：仅限成员的文档里的插图需要带 Bearer 头，`<img>` 标签不会带上令牌，会显示成一片碎图。统一用带鉴权头的请求取 Blob 再渲染，并在卸载时 `URL.revokeObjectURL`。

### 18.5 限速

四个公开阅读接口按客户端地址分别计数，**只对未登录请求生效**（带有效令牌的请求直接放行）：目录 60 次 / 10 分钟，正文 120 次 / 10 分钟，插图 600 次 / 10 分钟，PDF 文件 60 次 / 10 分钟。超出返回 `429 RATE_LIMITED`。

正常阅读不会触碰到这些额度；会撞上的是遍历式抓取。限速基于服务端进程内的固定窗口，反向代理后面无法区分真实客户端时会放行，由网关限流负责。

## 19. 客户端实现检查清单

### 19.1 登录与会话

- 请求实例开启 Cookie credentials，并在业务请求中添加 Bearer token。
- 并发 `401` 只触发一次 refresh；刷新失败清理本地和内存登录状态。
- `mustChangePassword=true` 立即进入首次改密页，不提前请求其他业务接口。
- 修改密码、停用、删除或密码重置后，旧会话可能立即失效。

### 19.2 数据解析

- 所有 Long ID 在进入状态管理前执行 `String(id)`。
- 不把业务 `LocalDateTime` 当 UTC；签名 URL 的 `Instant` 则按标准 UTC 解析。
- 把 `PhotoView.tagsJson` 解析成客户端 `tags`，解析失败回退为空数组。
- 忽略实体中的 `deleted` 和批次中的对象存储 key，不据此构造 URL。
- 只使用服务端返回的 `thumbnailUrl`、`downloadUrl`、图片 `url`。

### 19.3 上传与异步任务

- 客户端计算 64 位小写 SHA-256，创建票据后再直传。
- 直传的 `Content-Type` 与票据完全一致，不使用 API 客户端的 base URL 或 Bearer 拦截器改写签名请求。
- `complete-upload` 后轮询图片，批量 metadata 后轮询批次。
- 需求批量上传显式同时传 `requestId` 和需求的 `projectId`。
- 导出任务从 `data.id` 取 job ID，从查询响应的 `data.job.status` 取状态，从 `data.downloadUrl` 取下载地址。
- Blob URL 用完后释放。

### 19.4 业务状态与并发

- 更新项目、需求、图片元数据、校区、用户、通讯录成员、工时等时提交最近响应的 `version`。
- 收到 `409 RESOURCE_STATE_CONFLICT` 后重新拉取资源，不在客户端盲目增加 version 重试。
- 项目相册归属和采用分别调用，不把“加入项目”当成“已采用”。
- 取消采用后保留图片在项目相册中。
- 允许需求零图片提交，`requiredCount` 可空。
- 工时拍摄者和照片拍摄者都提交通讯录 contact ID，不提交自由文本姓名/学号。

### 19.5 权限界面

- A：全部后台和业务能力。
- M：项目/需求创建与审核、图库管理、采用、工时审核、统计导出、消息发送；部分写操作仍受“资源创建者”约束。
- C：本人校区接单、本人图片、本人填报工时；可把本人图片加入本人可见项目并采用。
- 对 `403` 做正常权限反馈，不把它统一当成登录过期；只有 `401` 才进入 refresh 流程。

## 20. 当前契约中容易误读的点

1. 所有普通业务成功目前是 HTTP 200，不是创建 201/删除 204。
2. ID 可能是 JSON 字符串，也可能是小整数；统一按字符串处理。
3. `PhotoView` 返回 `tagsJson` 和 `uploadedAt`，不是 `tags` 和 `createdAt`。
4. `GET /projects/{id}` 返回带计数的详情结构，与列表中的项目实体字段不完全相同。
5. C 可在需求列表看到同校区未接受需求，但详情接口要求已经参与。
6. `POST /requests/{id}/accept` 没有请求体和版本号。
7. 需求提交不要求存在图片。
8. 工时更新的 version 位于 query 参数，更新后状态固定回到 `DRAFT`。
9. 单张上传会由 `requestId` 推导项目；批量上传不会，批量需求上传必须显式传正确 `projectId`。
10. 单张上传做全库 SHA-256 查重，批量上传目前没有相同语义。
11. 单张下载 URL 接口没有 body；批量下载虽然接受 `purpose`，当前没有使用。
12. 导出任务查询响应是 `{ job, downloadUrl, expiresAt }`，不是扁平结构；任务当前不会显式进入 `PROCESSING` 或 `EXPIRED`。
13. 统计总览中的资源数量不受日期过滤，日期只影响工时统计。
14. 通知、邮件日志、管理员告警均为固定上限数组，不是分页接口。
15. 说明图片和消息图片读取需要认证；在 HTML `<img>` 中直接使用路径不会自动携带 Bearer header，跨客户端应使用认证 Blob 加载器。
