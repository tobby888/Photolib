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

记录 Bucket 名称和对应地域的公网 Endpoint。例如杭州地域的 Endpoint 为：

```text
https://oss-cn-hangzhou.aliyuncs.com
```

`OSS_ENDPOINT` 应填写地域 Endpoint，不要填写包含 Bucket 名称的访问域名。

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
OSS_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com
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

推荐在开发机完成编译，将生成的 JAR 上传到 Linux 服务器，再由 systemd 管理进程。服务器只需要安装 Java 21，无需安装 Maven 或复制源代码。

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

建议只允许反向代理访问后端的 `8080` 端口，并通过 Nginx 或其他反向代理提供 HTTPS。启用 `AUTH_SECURE_COOKIE=true` 时，客户端必须通过 HTTPS 访问。

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
