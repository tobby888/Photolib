# PhotoLib

面向公众号摄影部的一站式图片工作站，用于串联选题立项、图片需求发布、多人接单、图片上传与管理、采用统计、工时确认和数据导出。

![PhotoLib 业务流程](./Diagram.jpg)

## 功能概览

- **选题与需求**：创建选题项目，按校区发布图片需求，支持同校区多位负责人共同参与。
- **图片工作流**：支持单图、批量文件及 ZIP 上传，逐张维护拍摄者与拍摄时间，提供检索、归档和限时下载。
- **采用与统计**：记录项目采用图片，统计摄影者被采用张数，按成员汇总拍摄与修图工时。
- **工时管理**：校区负责人填报工时，部长或管理员确认、退回。
- **异步导出**：导出 XLSX 成员统计或最多 200 张图片的 ZIP。
- **系统管理**：维护账号、校区、通知、审计日志和管理员告警。
- **安全控制**：访问令牌与刷新令牌、首次登录强制改密、私有对象存储预签名 URL、乐观锁与幂等键。

## 角色与权限

| 角色 | 代码 | 主要权限 |
| --- | --- | --- |
| 管理员 | `ADMIN` | 管理账号、校区及全部业务资源，查看审计与告警 |
| 摄影部正副部长 | `MINISTER` | 管理项目和需求，浏览、上传、下载及采用图片，确认工时并导出统计 |
| 校区负责人 | `CAMPUS_MANAGER` | 接受本校区需求，上传图片，填报本人参与任务的工时 |

系统首次启动时仅创建管理员账号，其他账号均由管理员创建，不提供公开注册。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 前端 | React 19、TypeScript、Vite 7、Ant Design 6、Axios |
| 后端 | Java 21、Spring Boot 4、Spring Security、MyBatis-Plus |
| 数据 | MySQL、Flyway |
| 文件与通知 | 阿里云 OSS、阿里云 DirectMail |
| 导出与处理 | Apache POI、JPG/PNG 同格式压缩、异步 ZIP 处理 |

## 本地运行

### 环境要求

- Node.js 20 或更高版本
- Java 21
- MySQL 8

### 1. 创建数据库

```sql
CREATE DATABASE photolib
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
```

数据库表由 Flyway 在后端启动时自动创建和升级。

### 2. 配置并启动后端

进入 `backend` 目录，新建 `.env`：

```properties
DB_URL=jdbc:mysql://localhost:3306/photolib?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
DB_USERNAME=root
DB_PASSWORD=your_mysql_password

ADMIN_USERNAME=admin
ADMIN_INITIAL_PASSWORD=replace_with_a_strong_password
ADMIN_DISPLAY_NAME=系统管理员

# 本地开发使用磁盘存储，无需配置 OSS
SPRING_PROFILES_ACTIVE=local
LOCAL_STORAGE_SIGNING_SECRET=replace_with_a_random_secret
```

然后启动服务：

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

macOS 或 Linux：

```bash
cd backend
./mvnw spring-boot:run
```

后端默认地址为 `http://localhost:8080/api/v1`，健康检查地址为 `http://localhost:8080/api/v1/actuator/health`。

> 首次登录后必须修改初始密码。请勿在生产环境使用默认密码或默认签名密钥。

### 3. 启动前端

在项目根目录执行：

```bash
npm ci
npm run dev
```

访问 `http://localhost:5173`。开发服务器会将 `/api` 请求代理至 `http://localhost:8080`。

如前后端未部署在同一域名下，可通过环境变量指定 API 地址：

```properties
VITE_API_BASE_URL=https://example.com/api/v1
```

## 阿里云 OSS 初始化

生产环境默认使用阿里云 OSS 保存图片、缩略图、批量上传文件和导出文件。后端已经集成 OSS SDK，并通过预签名 URL 让浏览器直接上传文件，无需额外编写 OSS 初始化代码。

### 1. 创建 Bucket

在阿里云 OSS 控制台创建 Bucket：

- Bucket 名称：例如 `photolib-prod`。
- 地域：选择靠近后端服务器的地域，例如杭州。
- 存储类型：标准存储。
- 读写权限：私有。
- 服务端加密：生产环境建议开启。

记录 Bucket 名称和对应地域的公网、内网 Endpoint。例如杭州地域为：

```text
公网：https://oss-cn-hangzhou.aliyuncs.com
内网：https://oss-cn-hangzhou-internal.aliyuncs.com
```

`OSS_ENDPOINT` 供后端服务器访问 OSS，可填写同地域内网 Endpoint。`OSS_PUBLIC_ENDPOINT`
用于生成浏览器直传、预览和下载的预签名 URL，必须填写公网 Endpoint。两者都应填写地域
Endpoint，不要填写包含 Bucket 名称的访问域名。未设置 `OSS_PUBLIC_ENDPOINT` 时会兼容性
回退到 `OSS_ENDPOINT`；因此仅在 `OSS_ENDPOINT` 本身是公网地址时才可省略。

### 2. 创建并授权 RAM 用户

不要使用阿里云主账号的 AccessKey。创建一个仅供 PhotoLib 后端使用、允许 OpenAPI 调用的 RAM 用户，并为其创建 AccessKey。

按照最小权限原则，为 RAM 用户添加以下自定义权限策略。将 `photolib-prod` 替换为实际 Bucket 名称：

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "oss:PutObject",
        "oss:GetObject",
        "oss:DeleteObject"
      ],
      "Resource": [
        "acs:oss:*:*:photolib-prod/*"
      ]
    }
  ]
}
```

AccessKey Secret 仅在创建时显示一次，请立即保存到安全的密钥管理服务中。不要将 AccessKey 写入源码或提交到版本库。

### 3. 配置 Bucket 跨域访问

前端使用后端生成的预签名 URL 直接向 OSS 发起 `PUT` 请求，因此必须在 Bucket 的跨域设置中添加 CORS 规则：

| 配置项 | 建议值 |
| --- | --- |
| 来源（Origins） | 本地前端地址和生产前端域名，例如 `http://localhost:5173`、`https://photo.example.com` |
| 允许 Methods | `PUT`、`GET`、`HEAD` |
| 允许 Headers | `*` |
| 暴露 Headers | `ETag`、`x-oss-request-id` |
| 缓存时间 | `600` 秒 |

生产环境应填写实际前端域名，避免长期使用 `*` 作为允许来源。

### 4. 配置后端环境变量

开发时可在 `backend/.env` 中写入；使用 JAR 部署到 Linux 时，应将相同配置写入 JAR 所在目录的 `.env`：

```properties
STORAGE_MODE=oss
OSS_BUCKET=photolib-prod
OSS_ENDPOINT=https://oss-cn-hangzhou-internal.aliyuncs.com
OSS_PUBLIC_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com
OSS_ACCESS_KEY_ID=your_access_key_id
OSS_ACCESS_KEY_SECRET=your_access_key_secret
```

请确保 Bucket 与 Endpoint 属于同一地域。启动生产服务时不要启用 `local` Spring Profile，否则后端会切换到本地磁盘存储。

本地开发时从 `backend` 目录启动服务：

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Linux 服务器应按照下方“Linux 服务端部署”章节运行编译好的 JAR。上传文件时，前端 `PUT` 请求的 `Content-Type` 必须与后端生成预签名 URL 时返回的 `contentType` 完全一致，否则 OSS 会拒绝签名。

## 生产配置

生产环境默认使用阿里云 OSS。敏感配置只应通过环境变量或安全的密钥管理服务注入，不要提交到版本库。

| 环境变量 | 说明 |
| --- | --- |
| `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` | MySQL 连接配置 |
| `AUTH_SECURE_COOKIE` | HTTPS 环境应设为 `true` |
| `OSS_BUCKET`、`OSS_ENDPOINT` | 私有 OSS Bucket 与地域 Endpoint |
| `OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET` | OSS 访问凭据 |
| `DIRECTMAIL_REGION_ID`、`DIRECTMAIL_ACCOUNT_NAME` | DirectMail 地域与发信地址 |
| `DIRECTMAIL_ACCESS_KEY_ID`、`DIRECTMAIL_ACCESS_KEY_SECRET` | DirectMail 访问凭据 |
| `ADMIN_INITIAL_PASSWORD` | 首次启动管理员密码 |

建议使用独立的私有 Bucket，并遵循最小权限原则配置 RAM 账号。生产环境还应设置 `AUTH_SECURE_COOKIE=true`，并在 HTTPS 反向代理后运行服务。

## Linux 服务端部署

推荐在开发机完成编译，将生成的 JAR 上传到 Linux 服务器，再由 systemd 管理进程。Maven 会自动执行 `npm ci` 和 React 生产构建，并将前端文件写入 JAR 的 `static` 目录。服务器只需要安装 Java 21，无需安装 Node.js、npm、Maven，也不需要复制源代码或单独运行前端服务。

### 1. 在开发机编译

在项目根目录执行：

Windows PowerShell：

```powershell
cd backend
.\mvnw.cmd clean package
```

macOS 或 Linux：

```bash
cd backend
./mvnw clean package
```

测试通过后将生成：

```text
backend/target/photolib-backend-0.1.0-SNAPSHOT.jar
```

该 JAR 同时包含 Spring Boot 后端和 React 前端。构建过程使用 Maven 管理的隔离 Node.js 环境，不依赖开发机全局安装的 Node.js。

### 2. 准备 Linux 服务器

安装 Java 21，并确认版本：

```bash
java -version
```

创建专用系统用户和部署目录：

```bash
sudo useradd --system --home /opt/photolib --shell /usr/sbin/nologin photolib
sudo install -d -o photolib -g photolib /opt/photolib
```

不同 Linux 发行版的 `java` 路径可能不同，可通过 `command -v java` 查看。

### 3. 上传 JAR

在开发机执行，替换服务器用户名和地址：

```bash
scp backend/target/photolib-backend-0.1.0-SNAPSHOT.jar user@server:/tmp/photolib.jar
```

登录服务器后安装 JAR：

```bash
sudo install -o photolib -g photolib -m 0644 /tmp/photolib.jar /opt/photolib/photolib.jar
rm /tmp/photolib.jar
```

### 4. 创建生产环境配置

在服务器创建 `/opt/photolib/.env`：

```properties
SERVER_PORT=8080

DB_URL=jdbc:mysql://127.0.0.1:3306/photolib?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
DB_USERNAME=photolib
DB_PASSWORD=replace_with_database_password

ADMIN_USERNAME=admin
ADMIN_INITIAL_PASSWORD=replace_with_a_strong_password
ADMIN_DISPLAY_NAME=系统管理员
AUTH_SECURE_COOKIE=true

STORAGE_MODE=oss
OSS_BUCKET=photolib-prod
OSS_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com
OSS_ACCESS_KEY_ID=your_access_key_id
OSS_ACCESS_KEY_SECRET=your_access_key_secret

DIRECTMAIL_REGION_ID=cn-hangzhou
DIRECTMAIL_ACCOUNT_NAME=
DIRECTMAIL_ACCESS_KEY_ID=
DIRECTMAIL_ACCESS_KEY_SECRET=
```

限制配置文件权限，避免其他用户读取数据库密码和 AccessKey：

```bash
sudo chown photolib:photolib /opt/photolib/.env
sudo chmod 600 /opt/photolib/.env
```

应用会从当前工作目录读取 `.env`，因此后续 systemd 配置中的 `WorkingDirectory` 必须指向 `/opt/photolib`。生产环境不要设置 `SPRING_PROFILES_ACTIVE=local`。

### 5. 配置 systemd 服务

创建 `/etc/systemd/system/photolib.service`：

```ini
[Unit]
Description=PhotoLib Backend
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

如果 `command -v java` 的结果不是 `/usr/bin/java`，请修改 `ExecStart`。加载配置并启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now photolib
sudo systemctl status photolib
```

查看实时日志：

```bash
sudo journalctl -u photolib -f
```

在服务器本机验证健康状态：

```bash
curl --fail http://127.0.0.1:8080/api/v1/actuator/health
```

浏览器访问以下地址即可打开打包在 JAR 中的 React 前端：

```text
http://服务器地址:8080/api/v1/
```

前端使用 Hash 路由，页面地址形如 `/api/v1/#/projects`，刷新页面不需要额外配置 SPA 回退。

生产环境建议只允许反向代理访问 `8080` 端口，并通过 Nginx 或其他反向代理提供 HTTPS。反向代理只需将请求转发给这个 Spring Boot 服务，不需要运行 Node.js。启用 `AUTH_SECURE_COOKIE=true` 时，客户端必须通过 HTTPS 访问。

### 6. 更新版本

在开发机重新执行构建并上传新 JAR，然后在服务器替换文件并重启：

```bash
sudo systemctl stop photolib
sudo install -o photolib -g photolib -m 0644 /tmp/photolib.jar /opt/photolib/photolib.jar
rm /tmp/photolib.jar
sudo systemctl start photolib
sudo systemctl status photolib
```

更新前建议备份当前 JAR，以便在新版本启动失败时快速回滚。数据库结构由 Flyway 在应用启动时自动升级，生产数据库应同时做好备份。

## 旧系统（PhotoWarehouse）数据迁移

`scripts/migrate_photowarehouse.py` 用于把旧 PhotoWarehouse（PostgreSQL + 阿里云 OSS）的数据迁移到本系统。
脚本在 **Linux** 上运行，拆成 `export` 和 `import` 两步，两台机器不需要能直连对方数据库：

1. `export`：在旧服务器上连接旧 PostgreSQL，把**全部表原样**导出成一个 JSON 包（含评论、投票、评分、EXIF、标签等）。
2. `import`：在新服务器上读取 JSON 包，先把旧 OSS Bucket 的对象复制到新 Bucket，再把可映射的业务数据写入新 MySQL，
   并把每一行旧数据完整归档到 `legacy_archive_record`，避免任何字段丢失。

导入靠 `legacy_migration_item` 记录新旧主键映射，是幂等的，可安全重跑。**开始前请备份两个数据库和旧 Bucket，
并先启动一次新版后端让 Flyway 升级到最新版本（需包含 `legacy_migration_item` 和 `legacy_archive_record`）。**

### 1. 安装依赖（在能访问对应资源的机器上）

```bash
python3 -m venv .venv-migration
.venv-migration/bin/pip install -r scripts/photowarehouse_migration_requirements.txt
```

### 2. 导出（旧服务器）

```bash
export OLD_DATABASE_URL='postgresql://user:password@old-db:5432/photowarehouse'

.venv-migration/bin/python scripts/migrate_photowarehouse.py export \
  --output photowarehouse-export.json
```

把生成的 `photowarehouse-export.json` 传输到新服务器。

### 3. 导入（新服务器）

先停止旧系统写入，再配置新库与两套 OSS 凭据后执行。旧对象会被复制到新 Bucket（旧 Bucket 之后可删除）：

```bash
export NEW_DATABASE_URL='mysql://user:password@new-db:3306/photolib?charset=utf8mb4'

export OLD_OSS_ENDPOINT='https://oss-cn-hangzhou.aliyuncs.com'
export OLD_OSS_BUCKET='old-bucket'
export OLD_OSS_ACCESS_KEY_ID='...'
export OLD_OSS_ACCESS_KEY_SECRET='...'
export NEW_OSS_ENDPOINT='https://oss-cn-hangzhou.aliyuncs.com'
export NEW_OSS_BUCKET='photolib-prod'
export NEW_OSS_ACCESS_KEY_ID='...'
export NEW_OSS_ACCESS_KEY_SECRET='...'

.venv-migration/bin/python scripts/migrate_photowarehouse.py import \
  --input photowarehouse-export.json \
  --report photowarehouse-import-report.json
```

要点：

- OSS 先复制成功后数据库才开始写事务；数据库失败会回滚，已复制对象保留供下次续跑。新 Bucket 已存在且大小一致的对象会跳过。
- 若需要更换对象 key 前缀，设置 `OLD_OSS_PREFIX` 和 `NEW_OSS_PREFIX`（或 `--source-prefix`/`--destination-prefix`），
  数据库中的原图和预览图 key 会同步改写。
- 旧库普通 `user` 会映射为 `CAMPUS_MANAGER` 但**默认禁用**，等管理员分配校区后再启用；显式传 `--enable-ordinary-users` 才会直接启用。
- 找不到对应用户的项目/照片会归属到 `--fallback-user-id` 指定的用户（默认取新库第一个管理员）。
- 对象已单独迁移完成时可加 `--skip-oss` 只写库（不建议）。

导入完成后请核对 `photowarehouse-import-report.json` 中的归档行数、OSS 对象数/字节数和三类业务记录数量，
并抽查原图及预览图。确认无误后再删除旧 Bucket。

### 4. 恢复项目照片归属（`restore_project_photos.py`）

旧系统里照片按**标签**归属项目（照片标签集合 ⊇ 项目标签集合），一张照片可同时出现在多个项目相册中。
上面的 `import` 只给每个项目的**样图**写了归属，其余照片归属会丢失，导致新系统项目相册只剩样图。
`scripts/restore_project_photos.py` 用同一个导出 JSON 包按标签规则重建**全部**归属，写入多对多表
`photo_project`（一图多项目完整还原）。

**前提：新库必须已经跑过包含 `photo_project` 表的迁移（`V12__photo_project_membership.sql`），
即先启动一次新版后端让 Flyway 升级到最新版本。** 依赖与迁移脚本相同（含 `PyMySQL`），无需额外安装。

先离线出报告核对（不连任何数据库，可反复运行）：

```bash
.venv-migration/bin/python scripts/restore_project_photos.py plan \
  --input photowarehouse-export.json \
  --output restore-plan.json
```

`plan` 会打印每个项目将获得的照片数，并生成 `restore-plan.json`、`restore-plan.projects.csv`
（项目汇总）和 `restore-plan.ambiguous.csv`（同时归属多个项目的照片明细）。

核对无误后连新库写入。**建议先 `--dry-run` 预演，只统计将新增的链接行数、不写库：**

```bash
export NEW_DATABASE_URL='mysql://user:password@new-db:3306/photolib?charset=utf8mb4'

# 预演（不写库）
.venv-migration/bin/python scripts/restore_project_photos.py apply \
  --plan restore-plan.json \
  --input photowarehouse-export.json \
  --target-url "$NEW_DATABASE_URL" --dry-run

# 确认无误后正式写入（去掉 --dry-run）
.venv-migration/bin/python scripts/restore_project_photos.py apply \
  --plan restore-plan.json \
  --input photowarehouse-export.json \
  --target-url "$NEW_DATABASE_URL"
```

要点：

- 靠 `photo_project` 主键 `(photo_id, project_id)` 天然幂等（重复归属自动跳过），可安全重跑。
- 旧→新主键映射优先用 `legacy_migration_item`；缺失时用照片 `object_key`、项目 `title` 兜底，
  因此 `apply` 必须同时提供 `--input` 导出包。若迁移时改过对象 key 前缀，需带上与迁移一致的
  `--source-prefix`/`--destination-prefix`（或 `OLD_OSS_PREFIX`/`NEW_OSS_PREFIX`）。
- 只想人工审阅 SQL 时，用 `--emit-sql restore.sql` 生成 `INSERT INTO photo_project ...` 语句（不执行，仍需 `--target-url` 翻译 id）。
- 无标签的项目在旧系统里本就空展示，`apply` 后仍为空，属正常。

## 关键业务约束

- 仅支持 JPG、PNG；单图不超过 100 MiB，超过 10 MiB 时由后端压缩后入库。
- 单次批量上传最多 100 张；ZIP 不超过 1.5 GB，解压后不超过 10 GiB。
- 图片原图和导出下载地址均为短时有效的签名 URL，默认有效期 15 分钟。
- 临时原图默认保留 30 天，系统每天清理；清理失败会生成管理员告警。
- 只有 `ACTIVE` 项目可发布需求或新增采用记录。
- 统计只计入已确认工时和未取消的图片采用记录；系统不计算薪资或酬劳。
- 项目完成后锁定需求状态和采用记录，管理员可在记录原因后重新开放。

## 验证与构建

前端：

```bash
npm run build
```

后端：

```powershell
cd backend
.\mvnw.cmd test
```

macOS 或 Linux 使用 `./mvnw test`。

## 项目结构

```text
PhotoLib/
├── src/                       # React 前端
├── backend/
│   └── src/
│       ├── main/java/         # Spring Boot 业务代码
│       ├── main/resources/    # 配置与 Flyway 迁移
│       └── test/              # 后端测试
├── API.md                     # REST API 契约
├── HELP.md                    # 产品背景与原始需求
└── Diagram.jpg                # 业务流程图
```

## 文档

- [接口文档](./API.md)：认证、数据结构、接口、状态流转及校验规则。
- [项目说明](./HELP.md)：角色、业务线与技术栈的原始说明。
- [后端说明](./backend/README.md)：后端启动方式与当前实现范围。
- [旧系统迁移](./docs/PHOTO_WAREHOUSE_SERVER_MIGRATION.md)：PhotoWarehouse 导出/导入迁移的详细步骤与约束。
