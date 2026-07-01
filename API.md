# PhotoLib 接口文档（草案）

> 依据 `HELP.md` 与 `Diagram.jpg` 整理。本文仅定义接口契约，不包含实现代码。
>
> 版本：v1  
> Base URL：`/api/v1`

## 1. 业务范围

PhotoLib 是公众号摄影部的一站式图片工作站，覆盖：

1. 正副部长创建选题项目并发布图片需求。
2. 从历史图库检索、选择并采用旧图。
3. 校区负责人接受新图任务，收集并上传图片。
4. 记录摄影、修图工时。
5. 统计并导出照片采用情况、成员被采用张数及拍摄/修图工时。
6. 管理员维护账号及系统资源。

## 2. 角色与权限

| 角色代码 | 角色 | 权限 |
| --- | --- | --- |
| `ADMIN` | 管理员 | 管理全部账号和业务资源；拥有所有业务权限 |
| `MINISTER` | 摄影部正副部长 | 查看图库、上传/下载图片、创建项目、发布需求、采用图片、查看及导出成员统计 |
| `CAMPUS_MANAGER` | 校区负责人 | 查看并接受分配给本校区的需求、上传图片、填报工时 |

系统初始化时仅存在管理员账号，其他账号均由管理员创建。不提供公开注册接口。

## 3. 通用约定

### 3.1 认证

除登录和刷新会话接口外，所有接口均需携带访问令牌：

```http
Authorization: Bearer <access_token>
```

认证采用访问令牌与刷新令牌：

- 访问令牌用于调用业务接口，建议有效期为 15 分钟。
- 刷新令牌使用 HttpOnly、Secure、SameSite Cookie 保存，不在响应 JSON 中暴露。
- 用户连续 30 分钟无活动后刷新令牌失效；每次成功刷新会话后重新计算 30 分钟。
- 用户被禁用、密码被重置或修改后，撤销该用户的全部现有令牌。
- 令牌失效后返回 HTTP `401`。

### 3.2 数据格式

- 请求与响应：`application/json; charset=utf-8`
- 时间：ISO 8601，示例 `2026-07-01T10:30:00+08:00`
- 日期：`YYYY-MM-DD`
- 数据库主键：正整数，JSON 中使用 `number`
- 时长：统一使用分钟，字段后缀为 `Minutes`
- 分页从第 `1` 页开始
- 删除默认采用逻辑删除

### 3.3 通用成功响应

```json
{
  "code": "OK",
  "message": "success",
  "data": {}
}
```

分页响应：

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

创建成功使用 HTTP `201`；无响应体的删除操作使用 HTTP `204`。

### 3.4 通用错误响应

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
  "requestId": "01J2..."
}
```

| HTTP 状态码 | 错误码 | 含义 |
| --- | --- | --- |
| `400` | `VALIDATION_ERROR` | 参数校验失败 |
| `401` | `UNAUTHORIZED` | 未登录或令牌失效 |
| `403` | `FORBIDDEN` | 无操作权限 |
| `404` | `RESOURCE_NOT_FOUND` | 资源不存在 |
| `409` | `RESOURCE_STATE_CONFLICT` | 当前业务状态不允许操作 |
| `409` | `DUPLICATE_RESOURCE` | 唯一资源重复 |
| `413` | `FILE_TOO_LARGE` | 文件超过限制 |
| `415` | `UNSUPPORTED_FILE_TYPE` | 不支持的文件类型 |
| `500` | `INTERNAL_ERROR` | 服务内部错误 |

### 3.5 并发与幂等

- 更新请求可携带资源当前的 `version` 字段；版本不一致返回 `409`。
- 创建、接受任务、确认上传、采用图片等写操作建议支持：

```http
Idempotency-Key: <uuid>
```

## 4. 核心数据结构

### 4.1 用户 User

```json
{
  "id": 12,
  "username": "zhangsan",
  "displayName": "张三",
  "role": "CAMPUS_MANAGER",
  "campus": "南校区",
  "phone": "13800000000",
  "email": "zhangsan@example.edu.cn",
  "enabled": true,
  "mustChangePassword": false,
  "createdAt": "2026-07-01T10:30:00+08:00",
  "updatedAt": "2026-07-01T10:30:00+08:00"
}
```

`CAMPUS_MANAGER` 必须关联一个有效校区；其他角色可为空。`email` 由管理员登记，用于系统通知。

### 4.2 选题项目 Project

```json
{
  "id": 101,
  "title": "2026 毕业季",
  "description": "毕业典礼公众号选题",
  "status": "ACTIVE",
  "createdBy": {
    "id": 2,
    "displayName": "李部长"
  },
  "createdAt": "2026-07-01T10:30:00+08:00",
  "updatedAt": "2026-07-01T10:30:00+08:00",
  "version": 1
}
```

项目状态：

| 状态 | 含义 |
| --- | --- |
| `DRAFT` | 草稿 |
| `ACTIVE` | 进行中，可发布图片需求和采用图片 |
| `COMPLETED` | 已完成 |
| `CANCELLED` | 已取消 |

允许流转：`DRAFT → ACTIVE → COMPLETED`；`DRAFT/ACTIVE → CANCELLED`。`ADMIN` 可通过专用接口将 `COMPLETED` 项目重新开放为 `ACTIVE`。

### 4.3 图片需求 PhotoRequest

```json
{
  "id": 201,
  "projectId": 101,
  "title": "毕业典礼现场图",
  "description": "需要会场全景及学生特写",
  "campus": "南校区",
  "requiredCount": 20,
  "deadline": "2026-07-05T18:00:00+08:00",
  "status": "PUBLISHED",
  "participants": [],
  "firstAcceptedAt": null,
  "completedAt": null,
  "createdAt": "2026-07-01T10:30:00+08:00",
  "updatedAt": "2026-07-01T10:30:00+08:00",
  "version": 1
}
```

需求状态：

| 状态 | 含义 |
| --- | --- |
| `DRAFT` | 草稿 |
| `PUBLISHED` | 已发布，等待至少一名负责人接受 |
| `ACCEPTED` | 已有负责人接受，拍摄处理中；仍可由同校区其他负责人加入 |
| `SUBMITTED` | 已提交图片 |
| `COMPLETED` | 已完成 |
| `CANCELLED` | 已取消 |

允许流转：

```text
DRAFT → PUBLISHED → ACCEPTED → SUBMITTED → COMPLETED
  └───────────────→ CANCELLED ←──────────┘
```

### 4.4 需求参与人 RequestParticipant

同一需求可由多名所属校区的负责人接受。每次接受创建一条参与关系：

```json
{
  "id": 251,
  "requestId": 201,
  "user": {
    "id": 12,
    "displayName": "张三"
  },
  "acceptedAt": "2026-07-02T09:00:00+08:00"
}
```

同一用户不能重复接受同一需求。存在参与人的需求状态为 `ACCEPTED`；单个参与人退出不改变其他人的参与关系。

### 4.5 图片 Photo

```json
{
  "id": 301,
  "requestId": 201,
  "projectId": 101,
  "title": "毕业典礼会场全景",
  "description": "典礼开始前拍摄",
  "photographer": {
    "studentId": "20260001",
    "name": "王同学"
  },
  "uploadedBy": {
    "id": 12,
    "displayName": "张三"
  },
  "campus": "南校区",
  "takenAt": "2026-07-03T09:10:00+08:00",
  "tags": ["毕业季", "典礼", "全景"],
  "width": 6000,
  "height": 4000,
  "size": 12345678,
  "contentType": "image/jpeg",
  "thumbnailUrl": "https://example.com/signed-thumbnail-url",
  "status": "AVAILABLE",
  "storedFileName": "张三-王同学-20260703T091000.jpg",
  "adoptionCount": 1,
  "uploadedAt": "2026-07-03T12:00:00+08:00"
}
```

图片状态：

| 状态 | 含义 |
| --- | --- |
| `UPLOADING` | 等待客户端上传 |
| `PROCESSING` | 服务端校验、规范命名或压缩处理中 |
| `AVAILABLE` | 可检索和下载 |
| `ARCHIVED` | 已归档，不参与默认检索 |
| `DELETED` | 已逻辑删除 |

OSS 的 `bucket`、`objectKey` 等内部存储信息不直接暴露给普通客户端。缩略图和下载地址应为短时有效的签名 URL。

拍摄者不是系统账号，使用上传时填写的学号和姓名作为业务快照。`uploadedBy` 单独记录实际上传的校区负责人。

### 4.6 工时 Worklog

```json
{
  "id": 401,
  "requestId": 201,
  "user": {
    "id": 12,
    "displayName": "张三"
  },
  "workDate": "2026-07-03",
  "shootingMinutes": 120,
  "retouchingMinutes": 60,
  "remark": "毕业典礼拍摄及基础调色",
  "status": "SUBMITTED",
  "createdAt": "2026-07-03T19:00:00+08:00",
  "updatedAt": "2026-07-03T19:00:00+08:00",
  "version": 1
}
```

工时状态：`DRAFT`（草稿）、`SUBMITTED`（已提交）、`CONFIRMED`（已确认）、`REJECTED`（已退回）。

### 4.7 图片采用记录 Adoption

```json
{
  "id": 501,
  "projectId": 101,
  "photoId": 301,
  "photographer": {
    "studentId": "20260001",
    "name": "王同学"
  },
  "quantity": 1,
  "remark": "用于推文头图",
  "adoptedBy": {
    "id": 2,
    "displayName": "李部长"
  },
  "adoptedAt": "2026-07-04T10:00:00+08:00"
}
```

同一项目中的同一图片只能存在一条有效采用记录。单张图片的 `quantity` 固定为 `1`；批量接口可一次采用多张图片。

## 5. 认证接口

### 5.1 登录

`POST /auth/login`

权限：公开。

请求：

```json
{
  "username": "admin",
  "password": "********"
}
```

响应：

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "accessToken": "<token>",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "mustChangePassword": true,
    "user": {
      "id": 1,
      "username": "admin",
      "displayName": "管理员",
      "role": "ADMIN",
      "campus": null
    }
  }
}
```

登录成功后，服务端同时通过 `Set-Cookie` 写入刷新令牌。首次登录且 `mustChangePassword=true` 时，仅允许访问当前用户信息和首次改密接口。

### 5.2 获取当前用户

`GET /auth/me`

权限：已登录用户。

### 5.3 刷新会话

`POST /auth/refresh`

权限：持有有效刷新令牌。无请求体，从 Cookie 读取刷新令牌并轮换令牌。响应返回新的访问令牌，并通过 `Set-Cookie` 写入新的刷新令牌。

### 5.4 首次登录修改密码

`PUT /auth/initial-password`

权限：已登录且 `mustChangePassword=true` 的用户。

```json
{
  "initialPassword": "system-generated-password",
  "newPassword": "new-password"
}
```

成功后将 `mustChangePassword` 设为 `false`，撤销其他会话，并返回新的访问令牌。

### 5.5 修改自己的密码

`PUT /auth/password`

权限：已登录用户。

```json
{
  "oldPassword": "old-password",
  "newPassword": "new-password"
}
```

### 5.6 退出登录

`POST /auth/logout`

权限：已登录用户。撤销当前刷新令牌并清除 Cookie。

## 6. 用户管理接口

### 6.1 创建用户

`POST /users`

权限：`ADMIN`。

```json
{
  "username": "zhangsan",
  "displayName": "张三",
  "role": "CAMPUS_MANAGER",
  "campusId": 3,
  "phone": "13800000000",
  "email": "zhangsan@example.edu.cn"
}
```

系统生成一次性初始密码，创建响应仅展示一次：

```json
{
  "user": {},
  "initialPassword": "system-generated-password"
}
```

管理员应通过安全渠道交付初始密码；用户首次登录后必须修改密码。

### 6.2 查询用户列表

`GET /users?page=1&pageSize=20&keyword=张&role=CAMPUS_MANAGER&campusId=3&enabled=true`

权限：`ADMIN`。`MINISTER` 可读取用于负责人选择和统计的精简用户信息。

### 6.3 查询用户详情

`GET /users/{userId}`

权限：`ADMIN`；用户本人可查看自己的详情。

### 6.4 更新用户

`PUT /users/{userId}`

权限：`ADMIN`。

```json
{
  "displayName": "张三",
  "role": "CAMPUS_MANAGER",
  "campusId": 3,
  "phone": "13800000000",
  "email": "zhangsan@example.edu.cn",
  "enabled": true,
  "version": 1
}
```

### 6.5 重置密码

`PUT /users/{userId}/password`

权限：`ADMIN`。

系统重新生成一次性初始密码并仅在响应中展示一次；用户下次登录必须修改密码。该操作同时撤销用户全部现有会话。

### 6.6 停用/启用用户

- `POST /users/{userId}/disable`
- `POST /users/{userId}/enable`

权限：`ADMIN`。不得停用当前唯一可用的管理员账号。

### 6.7 校区管理

校区是管理员维护的独立资源：

- `POST /campuses`：创建校区。
- `GET /campuses?page=1&pageSize=100&keyword=南&enabled=true`：查询校区。
- `GET /campuses/{campusId}`：查询详情。
- `PUT /campuses/{campusId}`：更新名称、代码等信息。
- `POST /campuses/{campusId}/enable`：启用。
- `POST /campuses/{campusId}/disable`：停用。

写操作权限：`ADMIN`；查询权限：已登录用户。校区结构：

```json
{
  "id": 3,
  "code": "SOUTH",
  "name": "南校区",
  "enabled": true,
  "createdAt": "2026-07-01T10:30:00+08:00",
  "updatedAt": "2026-07-01T10:30:00+08:00",
  "version": 1
}
```

校区代码唯一且创建后不可修改。已关联用户、需求或图片的校区不可删除，只能停用；停用后不可再分配给新用户或新需求。

## 7. 选题项目接口

### 7.1 创建项目

`POST /projects`

权限：`MINISTER`、`ADMIN`。

```json
{
  "title": "2026 毕业季",
  "description": "毕业典礼公众号选题",
  "status": "DRAFT"
}
```

### 7.2 查询项目列表

`GET /projects?page=1&pageSize=20&keyword=毕业季&status=ACTIVE&createdBy=2`

权限：已登录用户。校区负责人仅返回与其校区需求有关的项目。

### 7.3 查询项目详情

`GET /projects/{projectId}`

权限：同项目列表。

响应中应包含需求数量、已提交图片数、已采用图片数等汇总字段。

### 7.4 更新项目

`PUT /projects/{projectId}`

权限：项目创建人、`ADMIN`。

```json
{
  "title": "2026 毕业季",
  "description": "毕业典礼公众号选题（更新）",
  "version": 1
}
```

### 7.5 变更项目状态

`POST /projects/{projectId}/status`

权限：项目创建人、`ADMIN`。

```json
{
  "status": "ACTIVE",
  "version": 1
}
```

### 7.6 删除项目

`DELETE /projects/{projectId}`

权限：项目创建人、`ADMIN`。仅无有效需求、采用记录和工时记录时允许删除，否则应取消或归档。

### 7.7 重新开放项目

`POST /projects/{projectId}/reopen`

权限：`ADMIN`。仅 `COMPLETED` 项目可重新开放为 `ACTIVE`，必须填写原因并记录审计日志。

```json
{
  "reason": "需要补充图片及修正采用记录",
  "version": 3
}
```

## 8. 图片需求接口

### 8.1 创建需求草稿

`POST /projects/{projectId}/requests`

权限：`MINISTER`、`ADMIN`。

```json
{
  "title": "毕业典礼现场图",
  "description": "需要会场全景及学生特写",
  "campus": "南校区",
  "requiredCount": 20,
  "deadline": "2026-07-05T18:00:00+08:00"
}
```

### 8.2 查询需求列表

`GET /requests?page=1&pageSize=20&projectId=101&status=PUBLISHED&campusId=3&participantId=12`

权限：

- `ADMIN`、`MINISTER`：可查看全部。
- `CAMPUS_MANAGER`：仅查看本校区已发布需求及本人已接受的需求。

### 8.3 查询需求详情

`GET /requests/{requestId}`

权限：同需求列表。

### 8.4 更新需求草稿

`PUT /requests/{requestId}`

权限：需求创建人、`ADMIN`；仅 `DRAFT` 状态可编辑。

### 8.5 发布需求

`POST /requests/{requestId}/publish`

权限：需求创建人、`ADMIN`。

```json
{
  "version": 1
}
```

### 8.6 接受需求

`POST /requests/{requestId}/accept`

权限：需求所属校区的 `CAMPUS_MANAGER`、`ADMIN`。

```json
{
  "version": 2
}
```

接受成功后创建需求参与关系。一个需求允许多名同校区负责人接受；同一用户重复接受返回 `409`。首位负责人接受时，需求由 `PUBLISHED` 变为 `ACCEPTED`，后续负责人加入时状态保持不变。

### 8.7 查询需求参与人

`GET /requests/{requestId}/participants`

权限：可查看该需求的用户。

### 8.8 退出需求

`DELETE /requests/{requestId}/participants/me`

权限：已接受该需求的 `CAMPUS_MANAGER`。已上传图片或填报工时的参与人默认不可退出，由管理员处理；最后一名参与人退出时需求恢复为 `PUBLISHED`。

### 8.9 提交需求

`POST /requests/{requestId}/submit`

权限：任一任务参与人、`ADMIN`。

前置条件：至少存在一张 `AVAILABLE` 图片。

```json
{
  "version": 3
}
```

### 8.10 完成需求

`POST /requests/{requestId}/complete`

权限：`MINISTER`、`ADMIN`。

```json
{
  "version": 4
}
```

### 8.11 取消需求

`POST /requests/{requestId}/cancel`

权限：需求创建人、`ADMIN`。

```json
{
  "reason": "选题取消",
  "version": 2
}
```

## 9. 图片与 OSS 上传接口

采用“服务端签发上传凭证，客户端直传 OSS 临时对象，服务端确认并处理”的流程。OSS 密钥仅保存在服务端环境变量中，不返回永久密钥。

上传与处理规则：

1. 仅接受 JPEG（`.jpg`/`.jpeg`）和 PNG（`.png`），并校验文件魔数与 MIME 类型。
2. 大于 10 MiB（`10 * 1024 * 1024` 字节）的图片由后端压缩。后端以 JPEG 质量参数进行二分搜索，在不超过 10 MiB 的前提下选择最高可用质量；必要时再按比例缩小分辨率并重复搜索。
3. PNG 若含透明通道，转为 JPEG 前使用白色背景合成；不超过 10 MiB 的 PNG 可保留 PNG 编码。
4. 业务文件名格式为 `{上传负责人}-{拍摄者}-{拍摄时间}.{ext}`，例如 `张三-王同学-20260703T091000.jpg`。非法路径字符替换为下划线；OSS `objectKey` 另加不可预测唯一值，避免重名覆盖。
5. 图片处理采用异步状态：`UPLOADING → PROCESSING → AVAILABLE`；处理失败时记录失败原因，并允许重新上传。

### 9.1 创建上传任务

`POST /photos/upload-tickets`

权限：`MINISTER`、`CAMPUS_MANAGER`、`ADMIN`。校区负责人上传到需求时，必须是该需求的参与人。

```json
{
  "requestId": 201,
  "projectId": 101,
  "fileName": "IMG_0001.jpg",
  "contentType": "image/jpeg",
  "size": 12345678,
  "sha256": "64位十六进制摘要",
  "photographerStudentId": "20260001",
  "photographerName": "王同学",
  "takenAt": "2026-07-03T09:10:00+08:00"
}
```

响应：

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "photoId": 301,
    "uploadUrl": "https://oss.example.com/...",
    "method": "PUT",
    "headers": {
      "Content-Type": "image/jpeg"
    },
    "expiresAt": "2026-07-03T12:15:00+08:00"
  }
}
```

客户端声明的文件大小仅用于预校验，服务端仍需检查 OSS 中的实际对象。

### 9.2 确认上传完成

`POST /photos/{photoId}/complete-upload`

权限：上传任务创建人、`ADMIN`。

```json
{
  "title": "毕业典礼会场全景",
  "description": "典礼开始前拍摄",
  "photographerStudentId": "20260001",
  "photographerName": "王同学",
  "takenAt": "2026-07-03T09:10:00+08:00",
  "tags": ["毕业季", "典礼", "全景"]
}
```

服务端需向 OSS 校验对象存在、文件类型、文件大小和摘要，然后将图片置为 `PROCESSING` 并异步完成压缩、规范命名和缩略图生成。客户端通过图片详情接口轮询状态；处理成功后置为 `AVAILABLE`。

### 9.3 查询图库

`GET /photos?page=1&pageSize=30&keyword=毕业&projectId=101&requestId=201&photographerStudentId=20260001&photographerName=王&uploadedBy=12&campusId=3&tag=典礼&takenFrom=2026-01-01&takenTo=2026-12-31&adopted=true&sort=uploadedAt,desc`

权限：`MINISTER`、`ADMIN`；`CAMPUS_MANAGER` 仅查看本人上传或本人负责需求中的图片。

默认仅返回 `AVAILABLE` 图片。`keyword` 匹配标题、描述和标签。

### 9.4 查询图片详情

`GET /photos/{photoId}`

权限：与图库查询一致。

### 9.5 更新图片元数据

`PUT /photos/{photoId}`

权限：上传人、`MINISTER`、`ADMIN`。修改拍摄者学号或姓名后，已有采用记录中的作者快照不追溯修改。

```json
{
  "title": "毕业典礼会场全景",
  "description": "典礼开始前拍摄",
  "photographerStudentId": "20260001",
  "photographerName": "王同学",
  "takenAt": "2026-07-03T09:10:00+08:00",
  "tags": ["毕业季", "典礼", "全景"],
  "version": 1
}
```

### 9.6 获取原图下载地址

`POST /photos/{photoId}/download-url`

权限：`MINISTER`、`ADMIN`；上传人可下载本人图片。

```json
{
  "purpose": "公众号排版"
}
```

响应返回短时有效的 `downloadUrl`、`expiresAt` 和建议文件名。每次请求应记录下载审计日志。

### 9.7 批量下载

`POST /photos/batch-download`

权限：`MINISTER`、`ADMIN`。

```json
{
  "photoIds": [301, 302, 303],
  "purpose": "公众号排版"
}
```

响应返回异步导出任务 `jobId`；通过第 13 节导出任务接口查询 ZIP 生成结果。

### 9.8 归档/恢复图片

- `POST /photos/{photoId}/archive`
- `POST /photos/{photoId}/restore`

权限：`MINISTER`、`ADMIN`。

### 9.9 删除图片

`DELETE /photos/{photoId}`

权限：`ADMIN`；未被采用的图片可由上传人删除。已有采用记录时禁止删除，只能归档。

## 10. 图片采用接口

### 10.1 批量采用图片

`POST /projects/{projectId}/adoptions`

权限：`MINISTER`、`ADMIN`。

```json
{
  "photoIds": [301, 302],
  "remark": "用于毕业季推文"
}
```

响应返回各作者被采用的张数汇总：

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "adoptions": [
      {
        "id": 501,
        "photoId": 301,
        "photographerStudentId": "20260001",
        "photographerName": "王同学"
      }
    ],
    "authorSummary": [
      {
        "photographerStudentId": "20260001",
        "photographerName": "王同学",
        "adoptedCount": 2
      }
    ]
  }
}
```

### 10.2 查询项目采用记录

`GET /projects/{projectId}/adoptions?page=1&pageSize=50&photographerStudentId=20260001`

权限：`MINISTER`、`ADMIN`。

### 10.3 取消采用

`DELETE /projects/{projectId}/adoptions/{adoptionId}`

权限：`MINISTER`、`ADMIN`。项目完成后是否允许取消由业务规则决定，默认不允许。

### 10.4 查询照片采用排行

`GET /statistics/adoptions/ranking?from=2026-01-01&to=2026-12-31&projectId=101&campusId=3&page=1&pageSize=50`

权限：`MINISTER`、`ADMIN`。

返回字段：

```json
{
  "rank": 1,
  "photographerStudentId": "20260001",
  "photographerName": "王同学",
  "campus": "南校区",
  "adoptedCount": 36
}
```

排名按 `adoptedCount` 降序；同数量采用并列排名。

## 11. 工时接口

### 11.1 新增工时

`POST /requests/{requestId}/worklogs`

权限：需求参与人、`ADMIN`。

```json
{
  "workDate": "2026-07-03",
  "shootingMinutes": 120,
  "retouchingMinutes": 60,
  "remark": "毕业典礼拍摄及基础调色",
  "status": "SUBMITTED"
}
```

`shootingMinutes` 与 `retouchingMinutes` 不得同时为 `0`。

### 11.2 查询工时

`GET /worklogs?page=1&pageSize=20&requestId=201&userId=12&status=SUBMITTED&from=2026-07-01&to=2026-07-31`

权限：

- `CAMPUS_MANAGER`：仅本人。
- `MINISTER`、`ADMIN`：全部。

### 11.3 更新工时

`PUT /worklogs/{worklogId}`

权限：填报人、`ADMIN`；仅 `DRAFT` 或 `REJECTED` 状态可编辑。

### 11.4 提交工时

`POST /worklogs/{worklogId}/submit`

权限：填报人、`ADMIN`。

### 11.5 确认工时

`POST /worklogs/{worklogId}/confirm`

权限：`MINISTER`、`ADMIN`。

### 11.6 退回工时

`POST /worklogs/{worklogId}/reject`

权限：`MINISTER`、`ADMIN`。

```json
{
  "reason": "拍摄时长需重新核对",
  "version": 2
}
```

### 11.7 删除工时

`DELETE /worklogs/{worklogId}`

权限：填报人、`ADMIN`；仅 `DRAFT` 或 `REJECTED` 状态可删除。

## 12. 统计与导出接口

### 12.1 查询成员工作统计

`GET /statistics/members?from=2026-01-01&to=2026-12-31&projectId=101&campusId=3&userId=12&page=1&pageSize=50`

权限：`MINISTER`、`ADMIN`。

返回：

```json
{
  "userId": 12,
  "displayName": "张三",
  "campus": "南校区",
  "adoptedCount": 36,
  "shootingMinutes": 1200,
  "retouchingMinutes": 600,
  "totalMinutes": 1800
}
```

仅统计 `CONFIRMED` 工时；采用统计以未取消的采用记录为准。被采用图片按拍摄者学号和姓名聚合，工时按系统用户聚合，两者不强行合并为同一成员。

### 12.2 查询统计总览

`GET /statistics/overview?from=2026-01-01&to=2026-12-31&projectId=101`

权限：`MINISTER`、`ADMIN`。

返回项目数、需求数、图库图片数、采用图片数、拍摄总工时和修图总工时等指标。

### 12.3 导出成员统计

`POST /statistics/members/export`

权限：`MINISTER`、`ADMIN`。

```json
{
  "from": "2026-01-01",
  "to": "2026-12-31",
  "projectId": null,
  "campusId": null,
  "format": "XLSX"
}
```

响应返回异步导出任务 `jobId`。XLSX 至少包含两个工作表：

- `工时统计`：负责人、校区、拍摄工时、修图工时、总工时。
- `被引图片`：拍摄者学号、拍摄者姓名、被引张数，并可附项目维度明细。

系统只导出原始统计数据，不计算薪资或酬劳。

## 13. 导出任务接口

### 13.1 查询导出任务

`GET /export-jobs/{jobId}`

权限：任务创建人、`ADMIN`。

```json
{
  "id": "01J2...",
  "type": "MEMBER_STATISTICS",
  "status": "SUCCEEDED",
  "progress": 100,
  "downloadUrl": "https://example.com/signed-export-url",
  "expiresAt": "2026-07-01T12:00:00+08:00",
  "errorMessage": null
}
```

状态：`PENDING`、`PROCESSING`、`SUCCEEDED`、`FAILED`、`EXPIRED`。

## 14. 管理与审计接口

### 14.1 查询审计日志

`GET /audit-logs?page=1&pageSize=20&operatorId=1&action=PHOTO_DOWNLOAD&resourceType=PHOTO&from=2026-07-01&to=2026-07-31`

权限：`ADMIN`。

建议审计登录、账号变更、需求状态变更、上传、下载、采用、工时确认、统计导出和删除操作。

### 14.2 查询基础选项

`GET /metadata/options`

权限：已登录用户。

返回校区、角色、项目状态、需求状态、允许的图片类型及上传大小限制等前端枚举。

### 14.3 邮件通知

系统使用阿里云 DirectMail 发送通知，AccessKey、发信地址等配置均从环境变量读取。用户不直接调用发信接口，以下业务事件触发系统通知：

- 图片需求发布：通知对应校区且已登记邮箱的负责人。
- 需求被接受：通知需求发布人。
- 需求提交：通知需求发布人。
- 工时被确认或退回：通知工时填报人。
- 管理员重置密码：通知用户密码已重置，但邮件中不发送明文初始密码。

投递失败不回滚主业务事务，应记录失败原因并异步重试。管理员可通过以下接口查看及重试：

- `GET /notification-logs?page=1&pageSize=20&status=FAILED&userId=12`
- `POST /notification-logs/{notificationId}/retry`

权限：`ADMIN`。

## 15. 关键校验规则

1. 用户名唯一；禁用用户不得登录。
2. 校区负责人只能接受本人所属校区的需求。
3. 需求截止时间不得早于发布时间。
4. 仅 `ACTIVE` 项目可发布需求或新增采用记录。
5. 采用的图片必须为 `AVAILABLE`。
6. 图片拍摄者学号和姓名必填；同一学号在历史记录中出现不同姓名时，应向上传人提示确认，但保留每次上传时的快照。
7. 工时日期不得晚于当前日期，且应位于任务接受至提交的合理时间范围内。
8. 已确认工时不得直接修改；应先退回或通过更正记录调整。
9. 下载、导出 URL 必须短时有效且与发起用户绑定。
10. OSS Bucket 默认私有，不允许匿名读取；数据库、OSS 和 DirectMail 凭据仅从环境变量读取。
11. JPG/PNG 原始文件大于 10 MiB 时必须完成后端压缩后才能进入 `AVAILABLE`。
12. 多负责人参与同一需求时，每条工时仍归属于实际填报人，不在参与人之间自动分摊。

## 16. 已确认的产品决策

1. 正副部长统一使用 `MINISTER` 角色。
2. 一个图片需求可由多名同校区负责人接受。
3. 校区由管理员在后台作为独立资源维护。
4. 上传者填写拍摄者学号和姓名，拍摄者无需是系统账号。
5. 同一图片在不同项目中被采用时可重复计入采用统计。
6. 工时由成员填报，部长或管理员确认。
7. 系统不计算薪资，只导出工时和被引图片统计。
8. 项目完成后锁定采用记录和需求状态，管理员可执行重新开放。
9. 仅支持 JPG/PNG；大于 10 MiB 的图片由后端采用二分质量搜索压缩。
10. 图片按“上传负责人-拍摄者-时间”生成业务文件名。
11. 刷新令牌连续 30 分钟无活动后失效。
12. 账号由管理员创建并生成初始密码，用户首次登录必须修改密码；找回或重置密码由管理员处理。
13. 系统通过阿里云 DirectMail 向管理员登记的用户常用邮箱发送通知。

## 17. 剩余待确认事项

1. 单个原始文件是否还需要设置高于 10 MiB 的硬上限；当前建议设为 100 MiB。
2. PNG 透明背景转 JPEG 时的背景色；当前默认白色。
3. 原图及压缩后图片的保留策略；当前建议保留原图 30 天后删除，仅长期保存处理后的图片。
4. 批量下载数量上限和下载签名有效期；当前建议单次 200 张、签名 15 分钟。
5. DirectMail 需要覆盖的最终通知事件、邮件模板和重试次数；当前建议最多重试 3 次。



1. 需要，设置为100MB
2. 不需要格式转换，上传仅作压缩，不做格式转换
3. 同意你的建议，可以每天0点检查
4. 批量上传需要限制数量，限制为100张。可以上传图片压缩包，压缩包大小限制为1.5GB。签名有效期没有问题
5. 重试3次后还是失败，尝试通知管理员
