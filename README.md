# PhotoLib

面向校园摄影部门的一站式图片工作站，串联选题立项、图片需求发布、多人接单、图片上传与管理、采纳统计、工时确认和数据导出。

![PhotoLib 业务流程](./Diagram.jpg)

## 快速导航

- [功能概览](#功能概览)
- [账号与权限](#账号与权限)
- [技术架构](#技术架构)
- [本地开发](#本地开发)
- [构建与部署](#构建与部署)
- [生产环境配置](#生产环境配置)
- [成员招募与公开报名页](#成员招募与公开报名页)
- [旧系统数据迁移](#旧系统数据迁移)
- [验证与文档](#验证与文档)

## 功能概览

- **选题与需求**：创建选题项目，按一个或多个校区发布图片需求，支持同一需求由多人接受和交付。
- **图片工作流**：支持单图、批量文件和 ZIP 上传，提供 EXIF 拍摄时间读取、单图 SHA-256 重复校验、检索、归档和限时下载。
- **项目相册与采纳**：一张图片可归属多个项目相册；相册归属与采纳标记相互独立。
- **通讯录与工时**：拍摄者和工时成员来自校区通讯录，历史记录保留姓名与学号快照；部长或管理员可确认、退回工时。
- **统计与导出**：按成员统计图片采纳与已确认工时，通过异步任务生成 XLSX 或图片 ZIP。
- **通知与协作**：支持站内通知、管理广播、富文本消息和 DirectMail 邮件通知。
- **成员招募**：部长可自定义报名表并限时发布，同学无需登录即可在公开页填写并上传原图作品，详见[成员招募与公开报名页](#成员招募与公开报名页)。
- **后台管理**：维护账号、校区、权限组、品牌设置、审计日志、存储对账和管理员告警。
- **数据库备份**：每天凌晨 0 点自动把整库业务数据备份到对象存储，系统管理员可手动备份、下载、导入外部备份文件或回滚，详见[数据库备份与回滚](#数据库备份与回滚)。该能力仅对系统管理员开放，不出现在权限面板。
- **安全控制**：使用访问令牌与 HttpOnly 刷新令牌、首次登录强制改密、登录失败限速与锁定、写操作与登录尝试全量审计、私有对象存储预签名 URL、乐观锁和幂等键。

## 账号与权限

系统保留三种兼容角色，并通过权限组进一步控制功能权限与数据范围：

| 角色 | 代码 | 默认职责 |
| --- | --- | --- |
| 管理员 | `ADMIN` | 管理账号、权限组、校区及全部业务资源，查看审计与告警 |
| 摄影部正副部长 | `MINISTER` | 管理项目和需求，维护项目相册与采纳记录，确认工时并导出统计 |
| 校区负责人 | `CAMPUS_MANAGER` | 处理授权校区内的需求、图片、通讯录与工时 |

首次启动时只创建管理员账号，其他账号由管理员创建，不提供公开注册。权限判断以后端为最终边界，前端隐藏按钮不代表授权。

例外：成员招募的公开报名页面向未登录访客开放，是系统中唯一的匿名写入口，其边界与限流要求见[成员招募与公开报名页](#成员招募与公开报名页)。

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

应用启动后按系统和架构加载对应组件。当前仅支持 Windows x86-64 和 Linux x86-64；Linux 运行环境需要 glibc 2.28+、`libstdc++.so.6`（至少提供 `GLIBCXX_3.4.22`）与 `libgcc_s.so.1`。首次原生构建需要联网下载并校验锁定版本的 libjpeg-turbo、stb 与 libvips 依赖包，后续可复用本地缓存；GPL/LGPL/MPL 许可正文改为随仓库分发在 `backend/native/licenses/`，构建时只按 SHA-256 校验、不再联网取回（gnu.org、mozilla.org 在部分构建网络上不可达，会让原生构建卡在连接超时）。Windows/Linux 发布流水线应分别在目标系统实际加载组件并运行图片处理测试，不能只检查另一平台资源是否存在。

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

### GitHub Actions 自动打包

`.github/workflows/build.yml` 在推送 `master`、推送 `v*` tag、提交 PR 或手动触发（workflow_dispatch）时运行：

- **前端检查**：`npm ci` + `npm run lint` + `npm run build`，再切到 Node 22 跑 `npm test`（前端单测用 `--experimental-strip-types` 直接执行 `.ts`，Node 20 不支持该开关）。
- **打包 JAR**：安装 JDK 21 与原生工具链（Zig、Ninja、NASM，CMake 由 runner 自带），执行 `./mvnw clean package`。该命令同时跑后端测试（H2 内存库，不连 MySQL/OSS）、构建 React 前端并编译 Windows/Linux 两套原生图片组件；随后校验 JAR 内确实含有前端资源和两套原生库，最后把 JAR 和 Surefire 报告作为 workflow artifact 上传。
- **发布 Release**：只在推送 `v*` tag 时执行，且要等前两个任务都通过——附件一定来自跑通测试和产物校验的那次构建。
  它直接取上一步的 workflow artifact（不重新构建），把附件按 tag 改名为 `photolib-backend-<tag>.jar` 后，
  用 `gh release create --generate-notes` 发布。带连字符的 tag（如 `v1.2.0-rc.1`）按预发布处理。

发布一个版本只需推 tag：

```bash
git tag v1.0.0 && git push origin v1.0.0
```

工作流固定了 Zig 版本与 SHA-256 校验值，升级 Zig 时需同步修改 `ZIG_VERSION` 与 `ZIG_SHA256`；
Node 版本 `BUILD_NODE_VERSION` 应与 `backend/pom.xml` 的 `node.version` 保持一致。
`TZ` 固定为 `Asia/Shanghai`：应用的业务时钟写死在该时区，runner 默认 UTC 会让依赖当前时间的测试失败。
附件名里的 tag 只是给下载者看的，JAR 内部版本号仍来自 `backend/pom.xml`。

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
| `LOGIN_MAX_IDENTIFIER_FAILURES` | 单个账号连续失败多少次后锁定，默认 `5` |
| `LOGIN_MAX_ADDRESS_FAILURES` | 单个客户端地址累计失败多少次后锁定，默认 `40` |
| `LOGIN_FAILURE_WINDOW` | 失败计数窗口，默认 `15m` |
| `LOGIN_LOCK_DURATION` | 锁定时长，默认 `15m` |
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
| `DATABASE_BACKUP_ENABLED` | 是否每天凌晨 0 点自动备份数据库，默认 `true` |
| `DATABASE_BACKUP_CRON` | 自动备份的 Spring 6 段 Cron，默认 `0 0 0 * * *`（Asia/Shanghai） |
| `DATABASE_BACKUP_RETENTION` | 备份文件保留时长，默认 `30d` |
| `DATABASE_BACKUP_MINIMUM_RETAINED` | 无论保留期都至少保留的成功备份份数，默认 `7` |
| `DATABASE_BACKUP_MAX_UPLOAD_BYTES` | 管理员导入备份文件的压缩后体积上限，默认 `512MB` |
| `DATABASE_BACKUP_MAX_DECOMPRESSED_BYTES` | 导入文件解压后的体积上限，默认 `2GB`，用于挡住压缩炸弹 |

### 登录防护与审计

`/api/v1/auth/login` 是匿名接口，应用内做了两层：

- **失败计数与锁定**（`login_attempt` 表，V31）。按账号和按客户端地址分别计数：账号维度防止某个账号被逐个试密码，地址维度防止同一客户端拿一个弱口令扫一批账号——后者永远不会触发账号维度的阈值。登录成功只清除账号计数，地址计数继续生效，避免刚好猜中一次就把整轮扫描的记录抹掉。计数落在数据库而不是内存里：进程内计数每次发布都会清零，也不在多实例之间共享，而那正是爆破需要的窗口。
- **失败登录进审计**。`/api/v1/auth/login` 不再被审计拦截器排除，成功和失败都会写 `audit_log`（失败时 `operator_id` 为空、`status` 为 401），并在 `detail_json` 里记录被尝试的账号标识，**不记录密码或任何请求体**。`/api/v1/auth/refresh` 仍然排除——它按会话定时触发，记录它只会淹没日志。

锁定期间的返回信息对"账号不存在"和"密码错误"一视同仁，不透露剩余时间，避免变成账号是否存在的探测口。

这两层同样是纵深防护，**不替代网关限流**：应用只能看到已经到达它的请求。同时注意登录审计会增加 `audit_log` 的写入量，请一并纳入该表的保留与清理策略。

### 数据库备份与回滚

每天凌晨 0 点（Asia/Shanghai）自动把整库业务数据备份到对象存储（生产为 OSS，本地为磁盘），对象放在 `backups/{年}/{月}/` 前缀下。系统管理员可在「系统管理 → 数据备份」手动发起备份、下载备份文件，或把数据库回滚到某个备份。该能力只对系统管理员开放，**没有对应的权限码**，因此不会出现在权限面板，也无法授予自定义权限组。

- 备份是**逻辑备份**：纯 JDBC 导出，不依赖服务器上安装 `mysqldump`；内容是 gzip 压缩的 JSON Lines，只含数据不含表结构。表结构仍由 Flyway 负责，因此**只能回滚到与当前数据库结构版本一致的备份**，跨迁移版本的备份在界面上不可回滚。
- `flyway_schema_history`、`database_backup`、`database_restore` 三张表既不导出也不回滚：前者由 Flyway 维护，后两者若被回滚会把"为这次回滚刚生成的兜底备份"一起抹掉。
- 回滚前会自动生成一份 `PRE_RESTORE` 兜底备份，误操作后可以用它再退回来；备份文件的大小与 SHA-256 校验不通过时直接中止，不会改动数据。
- 回滚在单个事务里完成：先清空备份清单中的每张表再逐表写回，中途失败整体回滚，不会留下"删了一半"的库。执行期间会话级外键校验被临时关闭，结束后必定恢复。
- 回滚只替换数据库，不会删除对象存储里的图片文件；但数据库中已不存在的图片记录将无法访问，且回滚后所有登录会话可能失效。
- 备份与回滚同一时刻只允许一个在跑。并发控制依赖进程内互斥锁与库内"是否已有任务在执行"的检查，**当前假设单实例部署**；若要多实例部署，必须先改成数据库层的抢占锁。
- 超过 `DATABASE_BACKUP_RETENTION` 的备份对象会在下一次备份后自动删除，记录保留并标记为 `EXPIRED`；无论保留期如何，最近 `DATABASE_BACKUP_MINIMUM_RETAINED` 份成功备份不会被清理。
- 备份失败会写入管理员告警 `DATABASE_BACKUP_FAILED`，回滚失败写入 `DATABASE_RESTORE_FAILED`。

**导入外部备份文件**：管理员可以把下载下来的备份文件重新上传（「导入备份文件」），校验通过后它会成为一条 `UPLOADED` 备份记录，再按普通备份回滚。导入与回滚刻意分成两步——上传和"用它覆盖整库"是两个决定。文件必须**完整**通过下列校验才会入库，任何一条不过就直接拒绝、不留记录：

- 体积不超过 `DATABASE_BACKUP_MAX_UPLOAD_BYTES`，且解压后不超过 `DATABASE_BACKUP_MAX_DECOMPRESSED_BYTES`。
- 以 gzip 魔数开头，且是完整的 gzip 归档（截断或被改写的文件会在 CRC/长度校验时暴露）。
- 清单行的格式名与格式版本可识别，声明的表非空，且不含 `flyway_schema_history`、`database_backup`、`database_restore`。
- 结构版本与迁移条数与当前库一致。
- 清单里的每张表都存在于当前库，且在文件中**恰好出现一次**。
- 每张表的字段集合与当前表结构完全一致（不缺字段、不含多余字段），字段类型属于同一兼容族（BOOLEAN/TINYINT 这类等价表示视为相同，JSON 之类的 OTHER 类型按通配处理）。
- 每行的字段数量与表头一致，取值只能是 null、字符串、数字、布尔或 `{"b64":"..."}`；表尾声明的行数必须与实际行数相符。

导入时会记录文件的 SHA-256，之后回滚仍会重新校验对象的大小与摘要，因此"导入后对象被改动"同样会在改数据之前被拦下。

### 阿里云 OSS 检查清单

- 使用私有 Bucket 和专用 RAM 用户，不使用主账号 AccessKey。
- RAM 用户需要目标 Bucket 对象的 `PutObject`、`GetObject`、`DeleteObject` 权限；当前巡检按数据库中的精确 key 执行 HEAD，不需要 `ListObjects`。HEAD/GetObjectMeta 读取对象元数据使用 `oss:GetObject`（指定版本时才是 `oss:GetObjectVersion`）。若允许应用自动检查/补充 Bucket 与 CORS，还需相应的 Bucket/CORS 读取或写入权限。生产也可由基础设施预先配置 Bucket/CORS，并让应用只做对象操作。
- `OSS_ENDPOINT` 可使用后端同地域内网 Endpoint；`OSS_PUBLIC_ENDPOINT` 必须是浏览器可访问的公网 Endpoint。
- Endpoint 只填写地域地址，不包含 Bucket 名称，并确保 Bucket 与 Endpoint 属于同一地域。
- CORS 至少允许站点来源发起 `PUT`、`GET`、`HEAD`，并允许上传所需请求头。
- 浏览器上传的 `Content-Type` 必须与后端生成预签名 URL 时返回的值完全一致。

预览维护采用环境、数据库、对象存储三方 profile。应用刚启动处于 `BOOTSTRAPPING`：规范化为四位小数的 `PREVIEW_COMPRESSION_RATIO` 与当前生成器指纹是标准；数据库缺失或不同则先完整生成新代际，再原子切换照片引用和数据库 profile。数据库相同时仍会对每张预览执行精确 HEAD，核对数据库 size、实际 MIME，以及 OSS user metadata 中的四位倍率、JPEG 有效质量（`round(ratio * 100)`，PNG 为 `lossless`）、生成器指纹和 SHA-256；缺失、非法或不匹配的对象会定向重编码。无论全量还是定向生成，新 PUT 的对象都必须再次通过 HEAD、完整 profile metadata 与实际流式 SHA-256 复验后才能切换引用。全部成功后进入 `RUNNING`；启动核对失败不会阻塞应用启动，并按 `PREVIEW_BOOTSTRAP_RETRY_DELAY` 在独立执行器中自动重试。此后的新上传、定时巡检和定向修复每次都重新读取数据库 profile，绝不回退到 `.env`；数据库 profile 缺失或非法时运行期调用会失败、巡检会写管理员告警，并发变化则由 CAS 拒绝，均不清空既有引用。

运行期巡检不会因为对象大小碰巧一致而跳过 HEAD。明确确认对象缺失或 profile 不匹配时，才会在照片 version/key/size 与数据库 profile 都未变化的前提下 CAS 清空引用并提交修复；权限、网络或服务端 HEAD 异常会记录错误、写入管理员告警并保留旧引用。CAS 清空后的旧预览对象不承诺自动删除，需要由独立的孤儿对象维护流程处理。启动重建、巡检和定向修复共用进程内互斥维护锁；跨实例的新上传由 profile 行 CAS 保护，滚动切换还会核对 eligible 照片集合。V23 会把无法确定历史编码器的已有数据标为 `legacy/unknown`，因此升级后的首次后台核对会执行一次安全重建；V24 会逐图持久化暂存检查点，所有检查点（包括本轮刚生成的对象）在切换前都必须验证 MIME、大小、SHA-256 和完整 profile 元数据；V25 为清理增加可恢复 claim，并在原子切换中用行锁核对未被其他实例认领的精确检查点，防止复验后的对象被另一实例删除。调整压缩倍率或部署 V23～V25 前，应确认对象存储权限、磁盘空间和服务器资源充足。

## 成员招募与公开报名页

部长设计报名表并限时发布，同学不登录就能在公开页填写、上传原图作品；提交后的报名集中在工作站内查看。这是系统中唯一对匿名访客开放写操作的功能，上线前请完整读完本节。

### 启用步骤

这个功能没有开关，部署新版本后即可用：

1. **执行迁移**：启动后端时 Flyway 自动应用 `V27`～`V30`。`V27` 会同时给内置权限组授权——`ADMIN`、`MINISTER`、`CAMPUS_MANAGER` 得到 `RECRUITMENT_VIEW`，`ADMIN`、`MINISTER` 额外得到 `RECRUITMENT_PUBLISH`。
2. **按需扩大发布权限**：在后台"权限组"的**成员招募**分类下勾选 `RECRUITMENT_PUBLISH`，即可让其他权限组创建和发布招募。
3. **创建并发布**：在侧边栏"成员招募"里新建 → 保存草稿 → 检查报名表 → 发布。只有状态为 `PUBLISHED` 且当前时间落在 `[开始时间, 截止时间)` 内的招募才会出现在公开页。
4. **分发公开页地址**：`https://你的站点/#/recruitment`（前端使用 Hash Router，`#` 不能省略）。登录页也提供"我要报名"入口。已登录用户访问该地址会被重定向回工作站。

发布后为了不影响正在填表的同学，报名表题目、学号项和上传项会被冻结，只能再改名称、说明和时间。关闭招募会立即停止接收新报名，并把该招募下所有未提交的草稿置为过期，已提交的报名不受影响。

### 相关配置

**没有新增环境变量**，但以下既有配置会直接影响这个功能：

| 配置 | 默认值 | 对招募的影响 |
| --- | --- | --- |
| `photolib.storage.upload-url-ttl` | `15m` | 同学浏览器直传对象存储的预签名 URL 有效期。**最需要评估的一项**，见下方说明 |
| `photolib.storage.download-url-ttl` | `15m` | 部内查看报名时，作品预览图和下载链接的有效期 |
| `OSS_CORS_ALLOWED_ORIGINS` | `*` | 公开页与工作站同源，通常无需额外配置；如果收窄到具体来源，必须包含同学实际访问的站点来源 |
| `photolib.recruitment.upload-recovery-delay-ms` | `60000` | 重新派发重启后卡在处理中的上传批次；启动延迟 `upload-recovery-initial-delay-ms`，默认 `60000` |
| `photolib.recruitment.temporary-cleanup-delay-ms` | `300000` | 回收签名已过期的临时上传对象；启动延迟 `temporary-cleanup-initial-delay-ms`，默认 `60000` |
| `photolib.recruitment.upload-cleanup-delay-ms` | `3600000` | 回收过期未提交草稿占用的对象；启动延迟 `upload-cleanup-initial-delay-ms`，默认 `600000` |
| `photolib.recruitment.upload.max-image-count` | `20` | 一次报名最多上传几张图片 |
| `photolib.recruitment.upload.max-image-bytes` | `20971520`（20 MiB） | 单张图片上限 |
| `photolib.recruitment.upload.max-archive-bytes` | `209715200`（200 MiB） | ZIP 上限 |
| `photolib.recruitment.upload.max-expanded-bytes` | `419430400`（400 MiB） | ZIP 解压后总大小上限，防解压炸弹 |

这四项**不再沿用图库的额度**。图库允许 100 张 × 100 MiB 或 1.5 GB 的 ZIP，那是给部内成员整理一次拍摄用的；报名交作品用不到，而这条链路不需要登录。按 `DRAFT_CREATE` 每 IP 每 10 分钟 8 个草稿算，沿用图库额度意味着单个 IP 十分钟内可申领约 80 GiB 的预签名 PUT 额度。当前默认把这个量级压到约 3 GiB。

调大之前请把上面这笔账重算一遍，并确认网关限流规则跟得上。报名页展示和前端预校验的数字都由服务端随任务下发（`uploadLimits`），改配置后前端会自动跟随，不需要另外改文案。

关于 `upload-url-ttl`：实际有效期取 `min(该配置, 7 天硬上限, 草稿剩余有效期)`。默认 15 分钟意味着一个 1.5 GB 的 ZIP 需要约 13 Mbit/s 的持续上行才能传完，校园网或宿舍网络达不到时同学会看到上传失败。**开启招募前请用一个真实大小的样本包实测**，需要时调大这个值——它同时作用于图库批量上传，调整前请一并评估。

对象存储的 RAM 权限与现有图库一致（`PutObject`、`GetObject`、`DeleteObject`），不需要新增授权。招募附件使用独立前缀：临时对象在 `temporary/recruitments/{草稿ID}/{批次ID}/`，通过校验的最终对象在 `recruitments/applications/{草稿ID}/`。

### 网关必须承担的防护

`/api/v1/public/recruitments/**` 完全匿名。应用内的 `AnonymousRecruitmentRateLimiter` 只是**有界的纵深防护，不能替代网关限流**：

- 它按容器上报的 `remoteAddr` 计数，并刻意不信任客户端可伪造的 `X-Forwarded-For`。部署在反向代理后面时拿到的通常是代理 IP，会被判定为共享地址并**直接放行**，而不是把所有同学限流在一起。
- 进程内最多跟踪 4096 个 key，超出后新出现的 key 同样放行，以避免把状态耗尽变成全站匿名不可用。

因此**必须**在反向代理或网关上为这些路径配置独立的分布式限流，`/api/v1/auth/login` 同理。

评估限流强度时请把额度换算成流量：每个草稿可申领的上传额度见上方 `photolib.recruitment.upload.*`（默认约 400 MiB），`DRAFT_CREATE` 是每 IP 每 10 分钟 8 个草稿，也就是单 IP 约 3 GiB / 10 分钟。限流规则没配到位时，这就是敞口的量级。

同时注意：审计拦截器会为每一次匿名写操作写入一行 `audit_log`（`operator_id` 为空、`resource_type` 为 `RECRUITMENTS`），登录尝试也会写入（`resource_type` 为 `AUTH`），请为该表制定保留与清理策略。

### 数据与隐私

- 学号经 NFKC 归一化、去空白并转大写后，在同一个招募内唯一。这只是防止重复报名的输入约束，**不构成身份认证**，不要当作实名信息使用。
- 作品按原始字节保存，不压缩、不转码，仅具有 `RECRUITMENT_VIEW` 权限的成员可通过短时签名 URL 预览和下载；对象存储 Bucket 保持私有。
- 报名内容在部内以纯文本 Markdown 渲染，不解析外部链接。

### 上线前检查

- 用测试 Bucket 验证私有访问、CORS 的 `PUT`/`GET`/`HEAD`、签名域名可从浏览器访问，以及浏览器 PUT 的 `Content-Type` 与签名时完全一致（ZIP 固定为 `application/zip`）。
- 确认网关限流规则已对 `/api/v1/public/recruitments/**` 和 `/api/v1/auth/login` 生效。
- 确认 `photolib.recruitment.upload.*` 的额度符合本次招募的实际需要，并按上方公式核算过单 IP 敞口。
- 用真实大小的图片和 ZIP 走通一次完整报名，确认 `upload-url-ttl` 足够。
- 在无痕窗口以未登录状态访问 `/#/recruitment`，确认只能看到应当公开的招募。

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
- 项目相册归属由 `photo_project` 维护；加入相册、标记采纳和取消采纳是相互独立的操作。
- 只有进行中的项目可发布需求或新增采纳记录；项目完成后会锁定相关状态。
- 统计只计入已确认工时和有效采纳记录，系统不计算薪资或酬劳。
- 预览图生成失败只影响这一张照片：照片仍然可用，图库改为直接展示成品图，后台会继续重试生成预览。
- 每次启动会核对一遍成品图是否仍在对象存储中；仅当精确 HEAD 确认对象不存在时，才把该记录软删除（`deleted=1`，可改回 `0` 恢复）并写入审计。已被项目采纳的照片只告警不删除；若缺失比例超过阈值（判定为存储配置异常），则一张都不删并写管理员告警。
- 招募报名的作品按原始字节保存，不压缩、不转码；数量与体积限制与图库批量上传一致（1～100 张、单张最大 100 MiB、ZIP 最大 1.5 GB、展开后最大 10 GiB）。
- 同一个招募任务内，归一化后的学号只能提交一次；招募发布后报名表结构不可再改，关闭后立即停止接收新报名。

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
