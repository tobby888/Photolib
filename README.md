# PhotoLib

面向校园摄影部门的一站式图片工作站，串联选题立项、图片需求发布、多人接单、图片上传与管理、采用统计、工时确认和数据导出。

![PhotoLib 业务流程](./Diagram.jpg)

## 快速导航

- [功能概览](#功能概览)
- [账号与权限](#账号与权限)
- [技术架构](#技术架构)
- [本地开发](#本地开发)
- [构建与部署](#构建与部署)
- [生产环境配置](#生产环境配置)
- [旧系统数据迁移](#旧系统数据迁移)
- [验证与文档](#验证与文档)

## 功能概览

- **选题与需求**：创建选题项目，按一个或多个校区发布图片需求，支持同一需求由多人接受和交付。
- **图片工作流**：支持单图、批量文件和 ZIP 上传，提供 EXIF 拍摄时间读取、单图 SHA-256 重复校验、检索、归档和限时下载。
- **项目相册与采用**：一张图片可归属多个项目相册；相册归属与采用标记相互独立。
- **通讯录与工时**：拍摄者和工时成员来自校区通讯录，历史记录保留姓名与学号快照；部长或管理员可确认、退回工时。
- **统计与导出**：按成员统计图片采用与已确认工时，通过异步任务生成 XLSX 或图片 ZIP。
- **通知与协作**：支持站内通知、管理广播、富文本消息和 DirectMail 邮件通知。
- **后台管理**：维护账号、校区、权限组、品牌设置、审计日志、存储对账和管理员告警。
- **安全控制**：使用访问令牌与 HttpOnly 刷新令牌、首次登录强制改密、私有对象存储预签名 URL、乐观锁和幂等键。

## 账号与权限

系统保留三种兼容角色，并通过权限组进一步控制功能权限与数据范围：

| 角色 | 代码 | 默认职责 |
| --- | --- | --- |
| 管理员 | `ADMIN` | 管理账号、权限组、校区及全部业务资源，查看审计与告警 |
| 摄影部正副部长 | `MINISTER` | 管理项目和需求，维护项目相册与采用记录，确认工时并导出统计 |
| 校区负责人 | `CAMPUS_MANAGER` | 处理授权校区内的需求、图片、通讯录与工时 |

首次启动时只创建管理员账号，其他账号由管理员创建，不提供公开注册。权限判断以后端为最终边界，前端隐藏按钮不代表授权。

## 技术架构

| 层级 | 技术 |
| --- | --- |
| 前端 | React 19、TypeScript、Vite 7、Ant Design 6、Axios |
| 后端 | Java 21、Spring Boot 4、Spring Security、MyBatis-Plus |
| 数据 | MySQL 8、Flyway |
| 文件与通知 | 阿里云 OSS、本地磁盘存储、阿里云 DirectMail |
| 图片处理 | Zig、libjpeg-turbo、stb、libvips、JNA |
| 导出与任务 | Apache POI、异步导出、异步 ZIP 处理 |

所有 REST API 使用 `/api/v1` 前缀。前端采用 Hash Router，既可由 Vite 独立运行，也可打包进 Spring Boot Fat JAR 后从服务根路径访问。

### 原生图片处理

成品图压缩、缩略图生成和后台预览图重建都在受控的串行/有界执行器中调用 `backend/native/` 的 Zig 原生组件：新上传走 `photoProcessingExecutor`，全库预览维护走独立的 `previewRegenerationExecutor`，避免互相占用队列。常规 JPEG 使用 libjpeg-turbo、PNG 使用固定版本的 stb 并保留透明通道；需要重新编码时，达到 100 MP、任一边达到 30,000 像素、按实际解码通道数估算的像素缓冲超过 128 MiB，或 EXIF Orientation 不为 1 的图片会改走 libvips 8.18.3 的文件式、顺序读取管线，所有缩略图也会按同一规则正确应用方向。小于成品图目标体积的文件仍只读头部并原样复制、保留 EXIF，不做完整像素解码；记录到数据库的宽高会按 EXIF 方向换算。libvips 的大图/方向分支使用 down-only、自动方向校正；JPEG 使用倍率换算质量、4:2:0、优化编码并去除元数据，PNG 使用无损 compression 9 并去除非像素元数据。原生层仍限制输入 100 MiB、输出 256 MiB、10 亿像素及单边 100,000 像素，并把 libvips 并发设为 1、关闭缓存；超阈值的渐进式 JPEG 和隔行 PNG 会被拒绝，因为这两类文件不能保证顺序解码的有界内存。ZIP 条目会流式解压到临时目录，避免完整进入 Java 堆。

构建阶段会生成并打包以下 x86-64 组件：

```text
native/windows-x86_64/photolib-image.dll
native/windows-x86_64/libvips-42.dll
native/linux-x86_64/libphotolib-image.so
native/linux-x86_64/libvips-cpp.so.8.18.3
```

应用启动后按系统和架构加载对应组件。当前仅支持 Windows x86-64 和 Linux x86-64；Linux 运行环境需要 glibc 2.28+、`libstdc++.so.6`（至少提供 `GLIBCXX_3.4.22`）与 `libgcc_s.so.1`。首次原生构建需要联网下载并校验锁定版本的 libjpeg-turbo、stb 与 libvips 依赖包及许可文本，后续可复用本地缓存。Windows/Linux 发布流水线应分别在目标系统实际加载组件并运行图片处理测试，不能只检查另一平台资源是否存在。

`PHOTO_PROCESSING_THREADS` 的取值范围是 1～32，默认 1。每个线程都可能持有一张高像素图片的原生像素缓冲，提高线程数前应先评估内存峰值并使用真实相机图片压测。

## 本地开发

### 环境要求

- Node.js 20+
- Java 21
- MySQL 8
- Zig 0.16.x、CMake 3.24+、Ninja、NASM（构建后端或运行后端测试时需要）

### 1. 创建数据库

```sql
CREATE DATABASE photolib
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
```

数据库结构由 Flyway 在后端启动时自动创建和升级。

### 2. 配置并启动后端

先复制示例配置：

```powershell
Copy-Item backend\.env.example backend\.env
```

编辑 `backend/.env`，至少设置数据库连接、管理员初始密码和本地存储签名密钥，并为本地开发增加：

```properties
SPRING_PROFILES_ACTIVE=local
DB_URL=jdbc:mysql://localhost:3306/photolib?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
DB_USERNAME=root
DB_PASSWORD=your_mysql_password
ADMIN_INITIAL_PASSWORD=replace_with_a_strong_password
LOCAL_STORAGE_SIGNING_SECRET=replace_with_a_random_secret
```

启动后端：

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Linux 使用 `./mvnw spring-boot:run`。服务地址为 `http://localhost:8080/api/v1`，健康检查地址为 `http://localhost:8080/api/v1/actuator/health`。

> 首次登录后必须修改初始密码。不要在生产环境使用默认密码、默认签名密钥或 `local` Profile。

### 3. 启动前端

在项目根目录执行：

```bash
npm ci
npm run dev
```

访问 `http://localhost:5173`。Vite 会将 `/api` 请求代理至本地后端；前后端分离部署时可通过 `VITE_API_BASE_URL` 指定 API 地址。

## 构建与部署

### 构建 Fat JAR

在开发机完成生产构建：

```powershell
cd backend
.\mvnw.cmd clean package
```

Linux 使用 `./mvnw clean package`。Maven 会自动安装隔离的 Node.js/npm、构建 React 前端、编译 Windows/Linux x86-64 原生图片组件，并生成：

```text
backend/target/photolib-backend-0.1.0-SNAPSHOT.jar
```

该 JAR 已包含后端、前端和两套原生图片组件。部署服务器不需要 Node.js、Maven、Zig 或单独的前端服务，但除 Java 21 外，Linux 仍须提供上文列出的 glibc、`libstdc++` 与 `libgcc_s` 运行库。

### Linux systemd 示例

建议使用专用用户和 `/opt/photolib` 部署目录，将 JAR 与生产 `.env` 放入该目录，并确保服务账号对图片处理临时目录具有读写权限。

```ini
[Unit]
Description=PhotoLib
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=photolib
Group=photolib
WorkingDirectory=/opt/photolib
ExecStart=/usr/bin/java -jar /opt/photolib/photolib.jar
Restart=on-failure
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

加载服务并验证：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now photolib
sudo systemctl status photolib
curl --fail http://127.0.0.1:8080/api/v1/actuator/health
```

打包后的前端从 `http://服务器地址:8080/` 访问，页面路由形如 `/#/projects`。生产环境应在 HTTPS 反向代理后运行，并只允许反向代理访问应用端口。

## 生产环境配置

敏感配置只能通过环境变量、JAR 工作目录下受限权限的 `.env` 或密钥管理服务注入，不要提交到版本库。

| 环境变量 | 说明 |
| --- | --- |
| `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` | MySQL 连接配置 |
| `ADMIN_INITIAL_PASSWORD` | 首次启动管理员密码，必须使用强随机值 |
| `AUTH_SECURE_COOKIE` | 非 `local`/`test` 环境必须为 `true` |
| `STORAGE_MODE` | 本地开发使用 `local`，生产使用 `oss` |
| `OSS_BUCKET`、`OSS_ENDPOINT`、`OSS_PUBLIC_ENDPOINT` | 私有 Bucket、服务端 Endpoint 和浏览器访问 Endpoint |
| `OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET` | 最小权限 RAM 用户凭据 |
| `OSS_CORS_ALLOWED_ORIGINS` | 允许浏览器直传的站点来源；生产环境应显式配置 |
| `PREVIEW_COMPRESSION_RATIO` | JPEG 预览图质量，取值 `(0, 1]`，默认 `0.6` |
| `PREVIEW_BOOTSTRAP_RETRY_DELAY` | 启动三方 profile 核对失败后的后台重试间隔，默认 `30s` |
| `STARTUP_MISSING_OBJECT_CLEANUP_ENABLED` | 每次启动是否清理"成品图已在对象存储中丢失"的图片记录，默认 `true`；设为 `false` 完全关闭 |
| `STARTUP_MISSING_OBJECT_CLEANUP_MAX_RATIO` | 上述清理的缺失占比熔断阈值，默认 `0.2` |
| `STARTUP_MISSING_OBJECT_CLEANUP_MIN_ABSOLUTE` | 低于该绝对张数时不按占比熔断，默认 `20` |
| `PHOTO_PROCESSING_THREADS` | 原生图片处理线程数，取值 1～32，默认 1 |
| `PHOTO_PROCESSING_TEMPORARY_DIRECTORY` | ZIP 解压与图片处理临时目录 |
| `DIRECTMAIL_*` | DirectMail 地域、发信地址和访问凭据 |

### 阿里云 OSS 检查清单

- 使用私有 Bucket 和专用 RAM 用户，不使用主账号 AccessKey。
- RAM 用户需要目标 Bucket 对象的 `PutObject`、`GetObject`、`DeleteObject` 权限；当前巡检按数据库中的精确 key 执行 HEAD，不需要 `ListObjects`。HEAD/GetObjectMeta 读取对象元数据使用 `oss:GetObject`（指定版本时才是 `oss:GetObjectVersion`）。若允许应用自动检查/补充 Bucket 与 CORS，还需相应的 Bucket/CORS 读取或写入权限。生产也可由基础设施预先配置 Bucket/CORS，并让应用只做对象操作。
- `OSS_ENDPOINT` 可使用后端同地域内网 Endpoint；`OSS_PUBLIC_ENDPOINT` 必须是浏览器可访问的公网 Endpoint。
- Endpoint 只填写地域地址，不包含 Bucket 名称，并确保 Bucket 与 Endpoint 属于同一地域。
- CORS 至少允许站点来源发起 `PUT`、`GET`、`HEAD`，并允许上传所需请求头。
- 浏览器上传的 `Content-Type` 必须与后端生成预签名 URL 时返回的值完全一致。

预览维护采用环境、数据库、对象存储三方 profile。应用刚启动处于 `BOOTSTRAPPING`：规范化为四位小数的 `PREVIEW_COMPRESSION_RATIO` 与当前生成器指纹是标准；数据库缺失或不同则先完整生成新代际，再原子切换照片引用和数据库 profile。数据库相同时仍会对每张预览执行精确 HEAD，核对数据库 size、实际 MIME，以及 OSS user metadata 中的四位倍率、JPEG 有效质量（`round(ratio * 100)`，PNG 为 `lossless`）、生成器指纹和 SHA-256；缺失、非法或不匹配的对象会定向重编码。无论全量还是定向生成，新 PUT 的对象都必须再次通过 HEAD、完整 profile metadata 与实际流式 SHA-256 复验后才能切换引用。全部成功后进入 `RUNNING`；启动核对失败不会阻塞应用启动，并按 `PREVIEW_BOOTSTRAP_RETRY_DELAY` 在独立执行器中自动重试。此后的新上传、定时巡检和定向修复每次都重新读取数据库 profile，绝不回退到 `.env`；数据库 profile 缺失或非法时运行期调用会失败、巡检会写管理员告警，并发变化则由 CAS 拒绝，均不清空既有引用。

运行期巡检不会因为对象大小碰巧一致而跳过 HEAD。明确确认对象缺失或 profile 不匹配时，才会在照片 version/key/size 与数据库 profile 都未变化的前提下 CAS 清空引用并提交修复；权限、网络或服务端 HEAD 异常会记录错误、写入管理员告警并保留旧引用。CAS 清空后的旧预览对象不承诺自动删除，需要由独立的孤儿对象维护流程处理。启动重建、巡检和定向修复共用进程内互斥维护锁；跨实例的新上传由 profile 行 CAS 保护，滚动切换还会核对 eligible 照片集合。V23 会把无法确定历史编码器的已有数据标为 `legacy/unknown`，因此升级后的首次后台核对会执行一次安全重建；V24 会逐图持久化暂存检查点，所有检查点（包括本轮刚生成的对象）在切换前都必须验证 MIME、大小、SHA-256 和完整 profile 元数据；V25 为清理增加可恢复 claim，并在原子切换中用行锁核对未被其他实例认领的精确检查点，防止复验后的对象被另一实例删除。调整压缩倍率或部署 V23～V25 前，应确认对象存储权限、磁盘空间和服务器资源充足。

## 旧系统数据迁移

旧 PhotoWarehouse（PostgreSQL + 阿里云 OSS）的迁移脚本位于 `scripts/`，采用可重入的两阶段流程：

1. `migrate_photowarehouse.py export` 从旧 PostgreSQL 导出完整 JSON 包。
2. `migrate_photowarehouse.py import` 先复制 OSS 对象，再把可映射业务数据写入新 MySQL，并归档旧库原始行。
3. `restore_project_photos.py` 根据旧标签规则重建 `photo_project` 多对多归属。

执行前必须备份数据库与 Bucket，并先启动一次新后端，让 Flyway 升级到最新版本。完整命令、环境变量、幂等规则和核对步骤见 [PhotoWarehouse 服务器迁移](./docs/PHOTO_WAREHOUSE_SERVER_MIGRATION.md)；字段映射与历史决策见 [迁移工作记录](./docs/PHOTO_WAREHOUSE_MIGRATION_WORKLOG.md)。

## 关键业务约束

- 只接受 JPG、JPEG 和 PNG；单图最大 100 MiB，成品图目标体积为 10 MiB。
- 单次批量上传最多 100 张；ZIP 最大 1.5 GB，展开后总量最大 10 GiB。
- 图片原图和导出下载地址均为短时有效的签名 URL，默认有效期 15 分钟。
- 临时原图默认保留 30 天；只清理已成功生成可用图片且满足到期条件的原始对象。
- 项目相册归属由 `photo_project` 维护；加入相册、标记采用和取消采用是相互独立的操作。
- 只有进行中的项目可发布需求或新增采用记录；项目完成后会锁定相关状态。
- 统计只计入已确认工时和有效采用记录，系统不计算薪资或酬劳。
- 预览图生成失败只影响这一张照片：照片仍然可用，图库改为直接展示成品图，后台会继续重试生成预览。
- 每次启动会核对一遍成品图是否仍在对象存储中；仅当精确 HEAD 确认对象不存在时，才把该记录软删除（`deleted=1`，可改回 `0` 恢复）并写入审计。已被项目采用的照片只告警不删除；若缺失比例超过阈值（判定为存储配置异常），则一张都不删并写管理员告警。

## 验证与文档

常用检查：

```powershell
# 前端类型检查与生产构建
npm run build

# 前端静态检查
npm run lint

# 后端测试
cd backend
.\mvnw.cmd test
```

主要文档：

- [接口文档](./API.md)：认证、数据结构、接口、状态流转与校验规则。
- [项目说明](./HELP.md)：角色、业务线与原始需求。
- [后端说明](./backend/README.md)：后端启动方式与实现范围。
- [测试说明](./docs/TESTING.md)：专项手工用例与验证建议。
- [旧系统迁移](./docs/PHOTO_WAREHOUSE_SERVER_MIGRATION.md)：PhotoWarehouse 导出、导入与项目照片归属恢复。

## 项目结构

```text
PhotoLib/
├── src/                       # React 前端
├── backend/
│   ├── native/                # Zig + libjpeg-turbo/stb/libvips 双平台图片组件
│   └── src/
│       ├── main/java/         # Spring Boot 业务代码
│       ├── main/resources/    # 配置与 Flyway 迁移
│       └── test/              # 后端测试
├── scripts/                   # QA、迁移与维护脚本
├── docs/                      # 测试、迁移与技术记录
├── API.md                     # REST API 契约
├── HELP.md                    # 产品背景与原始需求
└── Diagram.jpg                # 业务流程图
```

# 奶龙

本项目欢迎所有的奶家人加入。在项目的 `MilkDragon`目录下可以提交大量的奶龙。大家可以在pr里面注明这次提交是奶龙，作者会秒合并的。

![](./MilkDragon/惊鸿一瞥.jpg)
