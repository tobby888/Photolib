# PhotoLib — 架构梳理与本地测试环境部署

> 生成日期：2026-07-18 | 适用分支：master

---

## 目录

1. [项目概览](#1-项目概览)
2. [整体架构图](#2-整体架构图)
3. [前端结构详解](#3-前端结构详解)
4. [后端结构详解](#4-后端结构详解)
5. [数据库与 Flyway 迁移](#5-数据库与-flyway-迁移)
6. [存储抽象层](#6-存储抽象层)
7. [原生图片处理组件](#7-原生图片处理组件)
8. [角色与权限模型](#8-角色与权限模型)
9. [关键业务流程](#9-关键业务流程)
10. [本地开发环境部署（完整步骤）](#10-本地开发环境部署完整步骤)
11. [常用开发命令](#11-常用开发命令)
12. [验证与测试策略](#12-验证与测试策略)
13. [关键约定与陷阱](#13-关键约定与陷阱)

---

## 1. 项目概览

PhotoLib 是面向校园摄影部门的一站式图片工作站，覆盖完整业务闭环：

| 功能域 | 说明 |
|---|---|
| 选题与需求 | 创建项目，按校区发布图片需求，多人共同参与 |
| 图片工作流 | 单图 / 批量文件 / ZIP 上传，拍摄者 + 时间维护，检索、归档、限时下载 |
| 采用与统计 | 记录项目采用图片，统计被采用张数，汇总工时 |
| 工时管理 | 校区负责人填报，部长/管理员确认或退回 |
| 异步导出 | XLSX 统计 / 最多 200 张 ZIP 异步导出 |
| 系统管理 | 账号、校区、通知、审计日志、管理员告警 |
| 安全控制 | access+refresh token、首次登录改密、预签名 URL、乐观锁、幂等键 |

---

## 2. 整体架构图

```
┌──────────────────────────────────────────────────────────────────┐
│  浏览器  Hash Router (/#/projects)                               │
│  React 19 · TypeScript · Ant Design 6 · Vite 7                  │
│  src/api.ts (ApiResponse unwrap)  src/storageUpload.ts (OSS PUT) │
└──────────────────────┬───────────────────────────────────────────┘
                       │ /api/**
                       │ (开发: Vite proxy :5173→:8080)
                       │ (生产: 同源，JAR 内置 static/)
┌──────────────────────▼───────────────────────────────────────────┐
│  Spring Boot 4 · Java 21 · :8080                                 │
│                                                                  │
│  auth/  ──────── AccessTokenFilter  SecurityConfig               │
│  common/ ─────── ApiResponse  GlobalExceptionHandler             │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  业务域（各自独立包）                                    │   │
│  │  project  request  photo  adoption  worklog              │   │
│  │  statistics  notification  audit  admin  campus  user    │   │
│  │  migration  content                                      │   │
│  │  每个域：Controller → Service → Mapper (MyBatis-Plus)    │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  storage/ObjectStorageService ← (接口)                           │
│    ├── LocalObjectStorageService   (STORAGE_MODE=local)          │
│    └── AliyunObjectStorageService  (STORAGE_MODE=oss)            │
│                                                                  │
│  photo/NativeImageProcessor                                      │
│    └── 从 JAR 提取 .dll/.so → JNA 调用                           │
│                                                                  │
│  notification/MailGateway  (DirectMail，异步，失败不阻塞)         │
│  audit/AuditInterceptor    (写操作自动记录)                       │
└──────────┬──────────────────────────┬────────────────────────────┘
           │                          │
  ┌────────▼────────┐      ┌──────────▼──────────────┐
  │  MySQL 8        │      │  阿里云 OSS（生产）     │
  │  Flyway V1–V18  │      │  本地磁盘 ./data/（开发）│
  └─────────────────┘      └─────────────────────────┘
```

**Fat JAR 一体部署**：`mvnw clean package` 将 React `dist/` + 两套原生 .dll/.so 一同打入 JAR。生产服务器只需 Java 21。

---

## 3. 前端结构详解

```
src/
├── main.tsx                 # Vite 入口，挂载 HashRouter
├── App.tsx                  # 全局路由表 + Layout shell
├── api.ts                   # Axios 封装：ApiResponse unwrap；http 用于 Blob 下载
├── auth.tsx                 # AuthContext：token 存储、自动刷新、登出
├── types.ts                 # 所有 API 数据类型（唯一来源，改接口先改这里）
├── hooks.ts                 # 通用 hooks
├── components.tsx           # 通用 UI 组件
├── storageUpload.ts         # OSS 预签名 PUT 上传（Content-Type 必须与签名一致）
├── photoBatchDownload.ts    # ZIP 批量下载
├── exif.ts                  # EXIF 元数据读取 (exifr)
├── MarkdownEditor.tsx       # 富文本 Markdown 编辑器
├── MarkdownRenderer.tsx     # Markdown 渲染
├── RichTextEditor.tsx       # 通用富文本
├── AuditLogsPanel.tsx       # 审计日志面板组件
├── AppErrorBoundary.tsx     # 全局错误边界
├── styles.css
└── pages/                   # 页面组件（扁平，16 个）
    ├── LoginPage.tsx
    ├── InitialPasswordPage.tsx   # 首次登录强制改密
    ├── DashboardPage.tsx
    ├── ProjectsPage.tsx
    ├── ProjectDetailPage.tsx
    ├── RequestsPage.tsx
    ├── RequestDeliveryPage.tsx
    ├── PhotosPage.tsx
    ├── BatchUploadPage.tsx
    ├── StatisticsPage.tsx
    ├── WorklogsPage.tsx
    ├── NotificationsPage.tsx
    ├── NotificationDetailPage.tsx
    ├── DirectoryPage.tsx
    ├── ManagerCampusesPage.tsx
    └── AdminPage.tsx
```

**关键规则**：
- Hash Router：路由形如 `/#/projects`，刷新不依赖服务端 SPA fallback
- `api` wrapper 自动 unwrap `{ code, data, message }` 信封
- Blob 下载用 `http` 原始 axios 实例，下载完必须 `URL.revokeObjectURL`
- 上传 `Content-Type` 必须与后端生成 presigned URL 时的值完全一致

---

## 4. 后端结构详解

```
backend/src/main/java/cn/photolib/
├── common/
│   ├── api/ApiResponse.java          # 统一响应信封 {code, data, message}
│   ├── api/PageResponse.java         # 分页响应
│   ├── error/BusinessException.java  # 预期业务错误（带 ErrorCode）
│   ├── error/ErrorCode.java          # 错误码枚举
│   └── error/GlobalExceptionHandler.java
├── auth/
│   ├── AuthController.java           # /api/v1/auth/**
│   ├── AuthService.java              # 登录、刷新、改密
│   ├── AccessTokenFilter.java        # JWT 验证过滤器
│   ├── SecurityConfig.java           # Spring Security 配置
│   └── DeploymentSecurityValidator.java  # 启动时检查生产安全配置
├── user/
├── campus/
├── directory/                        # CampusMemberController/Service（通讯录）
├── project/
├── request/
│   └── BatchRequestPublisher.java    # 批量发布需求
├── photo/
│   ├── PhotoController.java
│   ├── PhotoService.java
│   ├── NativeImageProcessor.java     # JNA 调用原生 .dll/.so
│   ├── ImageCompressor.java
│   ├── PreviewRegenerationCoordinator.java  # 压缩比变更后后台全量重建
│   ├── OriginalCleanupJob.java       # 定时清理过期原图（30天）
│   └── batch/                        # 批量上传子模块
├── adoption/
├── worklog/
├── statistics/
│   └── ExportService.java            # XLSX + ZIP 异步导出
├── notification/
│   ├── NotificationService.java
│   ├── UserNotificationController.java
│   └── MailGateway.java              # DirectMail，失败不阻塞
├── storage/
│   ├── ObjectStorageService.java     # 接口（业务代码只调用此接口）
│   ├── AliyunObjectStorageService.java
│   ├── LocalObjectStorageService.java
│   ├── StorageConfig.java
│   └── PhotoStorageReconciliationService.java
├── audit/
│   ├── AuditController.java
│   └── AuditInterceptor.java         # 写操作自动捕获
├── admin/
│   ├── AdminController.java
│   └── BrandingController.java
├── migration/
│   ├── LegacyMigrationService.java
│   └── LegacyMigrationRunner.java
└── content/
    └── DescriptionImageController.java
```

**分层规则**：Controller（参数校验/鉴权/HTTP） → Service（业务规则） → Mapper（MyBatis-Plus 查询）。跨域复用代码放 `common/`，不允许跨域直接调用 Mapper。

---

## 5. 数据库与 Flyway 迁移

迁移脚本位于 `backend/src/main/resources/db/migration/`，共 18 个版本：

| 版本 | 内容摘要 |
|---|---|
| V1 | 核心表：campus, app_user, project, photo_request, photo, adoption, worklog, auth_session, audit_log, export_job |
| V2 | photo_upload_batch, photo_upload_item, admin_alert |
| V3 | branding_setting（品牌化配置） |
| V4 | legacy_migration_item（旧→新主键映射） |
| V5 | user_notification（站内通知） |
| V6 | user_notification 增加 sender_id, content_html |
| V7 | worklog 增加 member_name, member_student_id |
| V8 | project 增加 completed_at |
| V9 | campus_member（通讯录成员表） |
| V10 | legacy_archive_record（迁移全量归档） |
| V11 | audit_log 复合索引（性能优化） |
| V12 | photo_project（照片-项目多对多，一图多项目） |
| V13 | photo_request.required_count 改为可空 |
| V14 | description_image（需求描述图片） |
| V15 | app_user.email 登录索引（清理空 email） |
| V16 | photo.thumbnail_size, preview_setting（压缩比配置落库） |
| V17 | photo_request 增加 return_reason, returned_by, returned_at |
| V18 | app_user.email 唯一约束加固 |

**铁律**：已发布的迁移脚本绝对不能修改；新的 schema 变更必须新建 V19+ 文件。

测试环境使用 H2（MySQL 兼容模式），生产使用 MySQL 8。

---

## 6. 存储抽象层

```
ObjectStorageService (接口)
├── putObject(key, inputStream, contentType)
├── getPresignedDownloadUrl(key, ttlSeconds)
├── getPresignedUploadUrl(key, contentType, ttlSeconds)
├── deleteObject(key)
└── objectExists(key)

实现：
├── LocalObjectStorageService   # STORAGE_MODE=local，文件存 ./data/object-storage/
└── AliyunObjectStorageService  # STORAGE_MODE=oss，私有 Bucket + presigned URL
```

**规则**：业务代码（Service 层）只能注入 `ObjectStorageService` 接口，绝不直接调用 OSS SDK。本地和生产行为一致，只是存储位置不同。

---

## 7. 原生图片处理组件

上传后的图片压缩、缩略图生成、后台预览重建均调用 `backend/native/` 的 Zig 原生组件：

- JPEG：libjpeg-turbo 3.1.4.1（x86-64 SIMD）
- PNG：stb（无损，保留透明通道）
- 小于目标体积的图片只读尺寸元数据，不全量解码

JAR 内含两套：`native/windows-x86_64/photolib-image.dll` 和 `native/linux-x86_64/libphotolib-image.so`，启动时按 `os.name`/`os.arch` 自动提取加载。

> ⚠️ 当前仅支持 Windows x86-64 和 Linux x86-64。macOS 和其他架构启动时会明确报错。后端测试（`mvnw test`）在 macOS 上无法运行。

---

## 8. 角色与权限模型

| 角色 | 代码 | 权限范围 |
|---|---|---|
| 管理员 | `ADMIN` | 管理全部账号、校区和业务资源；审计日志、告警 |
| 正副部长 | `MINISTER` | 管理项目/需求，浏览/上传/下载/采用图片，确认工时，导出统计 |
| 校区负责人 | `CAMPUS_MANAGER` | 接受**本校区**需求，上传图片，填报**本人参与任务**的工时 |

**关键规则**：
- 后端是授权边界——前端隐藏按钮不是访问控制
- 校区负责人只能访问本校区数据和本人参与的内容
- 系统首次启动只创建管理员账号，无公开注册
- 首次登录后强制修改初始密码

---

## 9. 关键业务流程

```
项目生命周期：
ACTIVE ──(发布需求)──► 需求 PENDING
ACTIVE ──(完成)──────► COMPLETED（锁定需求状态和采用记录）
管理员可带原因重新开放 COMPLETED 项目

需求状态机：
PENDING → CLAIMED（校区负责人接单）
CLAIMED → DELIVERED（交付图片）
CLAIMED → RETURNED（退回修改，带原因）
DELIVERED → ACCEPTED / REJECTED（部长审核）

图片采用：
只有 ACTIVE 项目可新增采用记录
项目完成后锁定采用记录（管理员可解锁）

工时流程：
校区负责人填报 → 部长/管理员确认 or 退回
统计只计入已确认工时
```

---

## 10. 本地开发环境部署（完整步骤）

### 10.1 环境要求

| 工具 | 版本 | 用途 |
|---|---|---|
| Node.js | 20+ | 前端开发 |
| Java | 21 | 后端运行 |
| MySQL | 8 | 数据库 |
| Zig | 0.16.x | 编译原生图片组件（仅需构建 JAR 时） |
| CMake | 3.24+ | 同上 |
| Ninja | 任意 | 同上 |
| NASM | 任意 | libjpeg-turbo x86-64 SIMD |

> **日常开发**（前端 + 后端热重载调试）只需 Node.js 20+、Java 21、MySQL 8。Zig/CMake/Ninja/NASM 只在需要 `mvnw clean package` 编译完整 Fat JAR 时才需要。

### 10.2 创建数据库

```sql
CREATE DATABASE photolib
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
```

表结构由 Flyway 在后端首次启动时自动创建，无需手动建表。

### 10.3 创建后端本地配置文件

在 `backend/` 目录新建 `.env`（可从 `backend/.env.example` 复制后修改）：

```properties
# 数据库连接
DB_URL=jdbc:mysql://localhost:3306/photolib?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
DB_USERNAME=root
DB_PASSWORD=your_mysql_password

# 管理员账号（首次启动自动创建）
ADMIN_USERNAME=admin
ADMIN_INITIAL_PASSWORD=your_admin_password
ADMIN_DISPLAY_NAME=系统管理员

# 本地开发：磁盘存储，无需 OSS
SPRING_PROFILES_ACTIVE=local
LOCAL_STORAGE_SIGNING_SECRET=replace_with_any_random_string

# 可选
PREVIEW_COMPRESSION_RATIO=0.6
```

> `SPRING_PROFILES_ACTIVE=local` 会激活 `application-local.yml`，将 `AUTH_SECURE_COOKIE` 设为 `false`（允许 HTTP），并将 `STORAGE_MODE` 设为 `local`（磁盘存储）。

### 10.4 启动后端

```powershell
# Windows（指定 local profile）
cd backend
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

```bash
# macOS / Linux（注意：macOS 上原生图片组件不支持，部分功能报错）
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

后端启动后：
- API 根路径：`http://localhost:8080/api/v1`
- 健康检查：`http://localhost:8080/api/v1/actuator/health`
- Flyway 自动执行 V1–V18 迁移，建好所有表
- `AdminBootstrap` 自动创建 `.env` 中配置的管理员账号（仅首次，幂等）

### 10.5 启动前端开发服务器

```bash
# 在项目根目录
npm ci
npm run dev
```

访问 `http://localhost:5173`。Vite 将 `/api/**` 请求代理到 `http://localhost:8080`，前后端联调无需额外配置跨域。

### 10.6 验证本地环境

1. 访问 `http://localhost:5173`，应看到登录页
2. 用 `.env` 中的 `ADMIN_USERNAME` / `ADMIN_INITIAL_PASSWORD` 登录
3. 首次登录会强制跳转改密页，修改密码后进入 Dashboard
4. 健康检查：`curl http://localhost:8080/api/v1/actuator/health` 应返回 `{"status":"UP"}`

### 10.7 本地文件存储位置

本地模式下，图片和导出文件存储在 `backend/data/object-storage/`（相对于后端工作目录）。该目录在 `.gitignore` 中，不会提交。

### 10.8 常见问题

| 问题 | 原因 | 解决方法 |
|---|---|---|
| 后端启动报 `DeploymentSecurityValidator` 错误 | 非 local/test profile 但 `AUTH_SECURE_COOKIE=false` | 确认 `.env` 中有 `SPRING_PROFILES_ACTIVE=local` |
| Flyway 迁移失败 | 数据库不存在或权限不足 | 检查 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` |
| 图片上传后压缩报错 | macOS / ARM 系统，原生组件不支持 | 仅支持 Windows x86-64 和 Linux x86-64 |
| 前端请求 CORS 错误 | 前后端分离启动时未走 Vite 代理 | 确认前端通过 `:5173` 访问，不要直接访问 `:8080` |
| `npm ci` 失败 | Node 版本 < 20 | 升级 Node.js 到 20+ |

---

## 11. 常用开发命令

### 前端（项目根目录）

```powershell
npm ci                # 安装依赖（CI 精确安装，不更新 lock）
npm run dev           # Vite 开发服务器 :5173，代理 /api → :8080
npm run build         # tsc -b && vite build（TypeScript 类型检查 + 构建）
npm run lint          # eslint . （提交前必跑）
```

### 后端（cd backend，Windows 用 `.\mvnw.cmd`，Linux 用 `./mvnw`）

```powershell
.\mvnw.cmd spring-boot:run                    # 启动后端 :8080
.\mvnw.cmd test                               # 全量后端测试
.\mvnw.cmd -Dtest=AuditLogMapperTests test    # 单个测试类
.\mvnw.cmd clean package                      # 完整构建 Fat JAR（含前端）
```

### 端到端 QA

```powershell
# 项目根目录，仅限本地测试数据库，绝对不要指向生产
.\scripts\qa_full_flow.ps1
```

---

## 12. 验证与测试策略

| 变更范围 | 必须运行 |
|---|---|
| 纯前端 UI | `npm run build` + `npm run lint` |
| 后端单个模块 | `.\mvnw.cmd -Dtest=XxxTests test` |
| 后端全量 | `.\mvnw.cmd test` |
| 业务链 / 权限 / 状态机 | `qa_full_flow.ps1` |
| 影响前端打包 | `.\mvnw.cmd clean package` |
| 通知模块 | 验证站内通知 + 未读角标 + 邮件失败不阻塞主流程 |
| 图片模块 | 同时验证本地磁盘和 OSS 两种实现；不删除原图和已采用图片 |
| 统计导出 | 验证中文/特殊字符/null 值/过滤条件/大数据量 |

**OSS 集成测试**：必须仅在显式提供有效凭据（`OSS_INTEGRATION_TEST=true` + 完整 OSS 配置）时才运行。普通本地和 CI 运行自动跳过。绝不提交 AccessKey 或 `.env`。

---

## 13. 关键约定与陷阱

### API 约定
- 所有接口前缀 `/api/v1`
- 正常响应：`ApiResponse<T>`；分页：`PageResponse<T>`；文件下载除外
- 预期业务错误抛 `BusinessException + ErrorCode`，不用裸 RuntimeException

### 时间处理
- 全系统时区 `Asia/Shanghai`
- 日期区间过滤必须明确包含/排除边界
- 审计导出的 `to` 参数含义是"次日零点之前"，即包含整个结束当天

### 写操作检查清单
新增写接口时逐项确认：
1. 乐观锁（version 字段）
2. 软删除（deleted_at，不直接 DELETE）
3. 角色边界（校区负责人只能操作本人/本校区数据）
4. 审计日志（`AuditInterceptor` 需捕获 resourceType / resourceId / requestId / details）

### Schema 变更
- 必须新建 Flyway 文件（V19, V20, ...），文件名格式：`V{n}__{snake_case_description}.sql`
- 已发布的 V1–V18 不能修改，哪怕是注释

### 存储规则
- 业务层只能调用 `ObjectStorageService` 接口，绝不直接用 OSS SDK
- 不删除原图（`photo.original_key`）和已被采用图片
- 上传 `Content-Type` 必须与生成 presigned URL 时的值完全一致

### 安全规则
- 前端按钮隐藏不是访问控制——后端必须独立校验权限
- 生产环境 `AUTH_SECURE_COOKIE` 必须为 `true`，否则应用拒绝启动
- 绝不提交 `.env`、AccessKey、签名密钥到版本库
