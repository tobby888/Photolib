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

