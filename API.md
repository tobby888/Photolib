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
5. 统计照片采用情况、成员被采用张数及工作酬劳。
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

除登录接口外，所有接口均需携带访问令牌：

```http
Authorization: Bearer <access_token>
```

建议访问令牌由 Spring Security 管理。令牌失效后返回 HTTP `401`。

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
  "enabled": true,
  "createdAt": "2026-07-01T10:30:00+08:00",
  "updatedAt": "2026-07-01T10:30:00+08:00"
}
```

`CAMPUS_MANAGER` 必须填写 `campus`；其他角色可为空。

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

允许流转：`DRAFT → ACTIVE → COMPLETED`；`DRAFT/ACTIVE → CANCELLED`。

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
  "assignee": null,
  "acceptedAt": null,
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
| `PUBLISHED` | 已发布，等待接受 |
| `ACCEPTED` | 已接受，拍摄处理中 |
| `SUBMITTED` | 已提交图片 |
| `COMPLETED` | 已完成 |
| `CANCELLED` | 已取消 |

允许流转：

```text
DRAFT → PUBLISHED → ACCEPTED → SUBMITTED → COMPLETED
  └───────────────→ CANCELLED ←──────────┘
```

### 4.4 图片 Photo

```json
{
  "id": 301,
  "requestId": 201,
  "projectId": 101,
  "title": "毕业典礼会场全景",
  "description": "典礼开始前拍摄",
  "author": {
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
  "adoptionCount": 1,
  "uploadedAt": "2026-07-03T12:00:00+08:00"
}
```

图片状态：

| 状态 | 含义 |
| --- | --- |
| `UPLOADING` | 等待上传或上传处理中 |
| `AVAILABLE` | 可检索和下载 |
| `ARCHIVED` | 已归档，不参与默认检索 |
| `DELETED` | 已逻辑删除 |

OSS 的 `bucket`、`objectKey` 等内部存储信息不直接暴露给普通客户端。缩略图和下载地址应为短时有效的签名 URL。

### 4.5 工时 Worklog

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

### 4.6 图片采用记录 Adoption

```json
{
  "id": 501,
  "projectId": 101,
  "photoId": 301,
  "author": {
    "id": 12,
    "displayName": "张三"
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
    "expiresIn": 7200,
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

### 5.2 获取当前用户

`GET /auth/me`

权限：已登录用户。

### 5.3 修改自己的密码

`PUT /auth/password`

权限：已登录用户。

```json
{
  "oldPassword": "old-password",
  "newPassword": "new-password"
}
```

## 6. 用户管理接口

### 6.1 创建用户

`POST /users`

权限：`ADMIN`。

```json
{
  "username": "zhangsan",
  "password": "initial-password",
  "displayName": "张三",
  "role": "CAMPUS_MANAGER",
  "campus": "南校区",
  "phone": "13800000000"
}
```

### 6.2 查询用户列表

`GET /users?page=1&pageSize=20&keyword=张&role=CAMPUS_MANAGER&campus=南校区&enabled=true`

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
  "campus": "南校区",
  "phone": "13800000000",
  "enabled": true,
  "version": 1
}
```

### 6.5 重置密码

`PUT /users/{userId}/password`

权限：`ADMIN`。

```json
{
  "newPassword": "new-initial-password"
}
```

### 6.6 停用/启用用户

- `POST /users/{userId}/disable`
- `POST /users/{userId}/enable`

权限：`ADMIN`。不得停用当前唯一可用的管理员账号。

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

`GET /requests?page=1&pageSize=20&projectId=101&status=PUBLISHED&campus=南校区&assigneeId=12`

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

单负责人模式下，已被接受的需求不可由其他人重复接受，冲突时返回 `409`。

### 8.7 提交需求

`POST /requests/{requestId}/submit`

权限：任务接受人、`ADMIN`。

前置条件：至少存在一张 `AVAILABLE` 图片。

```json
{
  "version": 3
}
```

### 8.8 完成需求

`POST /requests/{requestId}/complete`

权限：`MINISTER`、`ADMIN`。

```json
{
  "version": 4
}
```

### 8.9 取消需求

`POST /requests/{requestId}/cancel`

权限：需求创建人、`ADMIN`。

```json
{
  "reason": "选题取消",
  "version": 2
}
```

## 9. 图片与 OSS 上传接口

建议采用“服务端签发上传凭证，客户端直传 OSS，服务端确认”的三段式流程。OSS 密钥仅保存在服务端环境变量中，不返回永久密钥。

### 9.1 创建上传任务

`POST /photos/upload-tickets`

权限：`MINISTER`、`CAMPUS_MANAGER`、`ADMIN`。校区负责人上传到需求时，必须是该需求的接受人。

```json
{
  "requestId": 201,
  "projectId": 101,
  "fileName": "IMG_0001.jpg",
  "contentType": "image/jpeg",
  "size": 12345678,
  "sha256": "64位十六进制摘要"
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

允许的图片类型、单文件大小、账号配额应由服务端配置；服务端不可仅信任文件扩展名。

### 9.2 确认上传完成

`POST /photos/{photoId}/complete-upload`

权限：上传任务创建人、`ADMIN`。

```json
{
  "title": "毕业典礼会场全景",
  "description": "典礼开始前拍摄",
  "authorId": 12,
  "takenAt": "2026-07-03T09:10:00+08:00",
  "tags": ["毕业季", "典礼", "全景"]
}
```

服务端需向 OSS 校验对象存在、文件大小和摘要，再将图片置为 `AVAILABLE`。

### 9.3 查询图库

`GET /photos?page=1&pageSize=30&keyword=毕业&projectId=101&requestId=201&authorId=12&campus=南校区&tag=典礼&takenFrom=2026-01-01&takenTo=2026-12-31&adopted=true&sort=uploadedAt,desc`

权限：`MINISTER`、`ADMIN`；`CAMPUS_MANAGER` 仅查看本人上传或本人负责需求中的图片。

默认仅返回 `AVAILABLE` 图片。`keyword` 匹配标题、描述和标签。

### 9.4 查询图片详情

`GET /photos/{photoId}`

权限：与图库查询一致。

### 9.5 更新图片元数据

`PUT /photos/{photoId}`

权限：上传人、`MINISTER`、`ADMIN`。

```json
{
  "title": "毕业典礼会场全景",
  "description": "典礼开始前拍摄",
  "authorId": 12,
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
        "authorId": 12,
        "authorName": "张三"
      }
    ],
    "authorSummary": [
      {
        "authorId": 12,
        "authorName": "张三",
        "adoptedCount": 2
      }
    ]
  }
}
```

### 10.2 查询项目采用记录

`GET /projects/{projectId}/adoptions?page=1&pageSize=50&authorId=12`

权限：`MINISTER`、`ADMIN`。

### 10.3 取消采用

`DELETE /projects/{projectId}/adoptions/{adoptionId}`

权限：`MINISTER`、`ADMIN`。项目完成后是否允许取消由业务规则决定，默认不允许。

### 10.4 查询照片采用排行

`GET /statistics/adoptions/ranking?from=2026-01-01&to=2026-12-31&projectId=101&campus=南校区&page=1&pageSize=50`

权限：`MINISTER`、`ADMIN`。

返回字段：

```json
{
  "rank": 1,
  "userId": 12,
  "displayName": "张三",
  "campus": "南校区",
  "adoptedCount": 36
}
```

排名按 `adoptedCount` 降序；同数量采用并列排名。

## 11. 工时接口

### 11.1 新增工时

`POST /requests/{requestId}/worklogs`

权限：需求接受人、`ADMIN`。

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

## 12. 统计与酬劳接口

### 12.1 查询成员工作统计

`GET /statistics/members?from=2026-01-01&to=2026-12-31&projectId=101&campus=南校区&userId=12&page=1&pageSize=50`

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
  "totalMinutes": 1800,
  "shootingAmount": 0,
  "retouchingAmount": 0,
  "adoptionAmount": 0,
  "totalAmount": 0
}
```

金额单位为人民币元，保留两位小数。仅统计 `CONFIRMED` 工时；采用统计以未取消的采用记录为准。

### 12.2 查询统计总览

`GET /statistics/overview?from=2026-01-01&to=2026-12-31&projectId=101`

权限：`MINISTER`、`ADMIN`。

返回项目数、需求数、图库图片数、采用图片数、总工时和总酬劳等指标。

### 12.3 导出成员统计

`POST /statistics/members/export`

权限：`MINISTER`、`ADMIN`。

```json
{
  "from": "2026-01-01",
  "to": "2026-12-31",
  "projectId": null,
  "campus": null,
  "format": "XLSX"
}
```

响应返回异步导出任务 `jobId`。导出内容至少包含成员、校区、采用张数、拍摄工时、修图工时、总工时和酬劳。

### 12.4 酬劳规则管理

- `GET /compensation-rules`
- `PUT /compensation-rules`

权限：`ADMIN`。

建议按生效时间维护规则：

```json
{
  "effectiveFrom": "2026-01-01",
  "shootingHourlyRate": 0,
  "retouchingHourlyRate": 0,
  "adoptionUnitRate": 0,
  "version": 1
}
```

历史统计应使用工作或采用发生时有效的规则，避免修改单价后历史金额漂移。

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

## 15. 关键校验规则

1. 用户名唯一；禁用用户不得登录。
2. 校区负责人只能接受本人所属校区的需求。
3. 需求截止时间不得早于发布时间。
4. 仅 `ACTIVE` 项目可发布需求或新增采用记录。
5. 采用的图片必须为 `AVAILABLE`。
6. 图片作者必须是系统内有效用户；若需支持外部作者，应另建外部作者实体，不能用自由文本混用。
7. 工时日期不得晚于当前日期，且应位于任务接受至提交的合理时间范围内。
8. 已确认工时不得直接修改；应先退回或通过更正记录调整。
9. 下载、导出 URL 必须短时有效且与发起用户绑定。
10. OSS Bucket 默认私有，不允许匿名读取；数据库及 OSS 凭据仅从环境变量读取。

## 16. 待产品确认事项

以下内容未在 `HELP.md` 或业务图中明确，本文采用了便于落地的默认设计：

1. “正副部长”暂合并为同一角色 `MINISTER`；若权限不同需拆分。
2. 一个图片需求暂只允许一名校区负责人接受；若需多人协作，应增加任务成员接口。
3. 校区暂作为用户和需求上的字符串字段；正式实现前建议确认是否需要独立的校区管理模块。
4. 图片作者暂限定为系统用户；需确认上传人和实际摄影作者是否可能不同。
5. “照片被采用排行”按采用记录张数计算，同一图片在不同项目采用可重复计数。
6. 工时采用“成员填报、部长确认”流程，该确认环节由业务完整性推导而来。
7. 酬劳暂按拍摄时薪、修图时薪、单张采用单价计算；具体计价方式和历史规则需确认。
8. 项目完成后默认锁定采用记录和需求状态，修改需由管理员重新开放。
9. 上传格式、单文件上限、批量下载上限及签名 URL 有效期需在实现前确定。
10. 当前未定义刷新令牌、找回密码、消息通知和审批通知；如有需要应补充相应接口。



1. 角色统一没有问题
2. 一个图片需求可以由多个校区负责人接受
3. 管理员在在后台管理校区
4. 图片作者有上传者填写，填写学号和姓名
5. 可以重复计数
6. 是的，你的理解没有问题
7. 不需要你来计算薪资，你只需要导出工时和被引图片
8. 是的，这没有问题
9. 上传格式为jpg或png格式，如果上传的图片大小大于10MB，你需要后端进行压缩，采用二分法压缩，尽量保存图片质量。图片签名修改成上传的校区负责人-拍摄者-时间.jpg
10. 刷新令牌默认30分钟无活动失效。找回密码由管理员负责。管理员在创建账户后，系统生成初始密码，用户第一次登录后再设置密码。消息通知使用阿里云的DirectMail，密钥存储在环境变量中。管理员可以为用户登记常用邮箱，作为系统通知
