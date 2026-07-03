# PhotoLib Backend

PhotoLib 后端基于 Spring Boot 4、Spring Security、MyBatis-Plus、Flyway 和 MySQL。

## 本地启动

1. 创建 MySQL 数据库：

   ```sql
   CREATE DATABASE photolib CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
   ```

2. 根据 `.env.example` 设置环境变量。生产环境必须修改 `ADMIN_INITIAL_PASSWORD`，并启用 `AUTH_SECURE_COOKIE=true`。
3. 启动：

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

服务地址为 `http://localhost:8080/api/v1`，健康检查为
`http://localhost:8080/api/v1/actuator/health`。

首次启动会在空用户表中创建管理员。管理员首次登录后必须修改密码。

## 验证

```powershell
.\mvnw.cmd test
```

数据库结构由 `src/main/resources/db/migration` 中的 Flyway 脚本维护，禁止在生产库手工修改表结构。

## 当前实现范围

- 访问令牌与刷新令牌会话、首次登录改密、退出和会话撤销
- 管理员创建/更新/重置用户
- 校区管理
- 选题项目及状态流转
- 图片需求发布、多负责人接受、提交、完成和取消
- 工时填报、提交、确认和退回

- OSS 私有桶预签名上传/下载、JPG/PNG 同格式压缩与原图定时清理
- 单图、最多 100 张文件批次及 1.5 GB ZIP 上传
- 图片采用、排行、成员工时统计
- XLSX 统计导出及最多 200 张图片 ZIP 导出
- DirectMail 通知、三次重试、管理员告警与写操作审计
# 旧 PhotoWarehouse 数据迁移

新后端启动时可选择从旧 PhotoWarehouse PostgreSQL 数据库迁移用户、项目、照片及照片标签。
照片沿用旧记录中的 OSS object key，不会重新上传。迁移具有幂等性，重复启动不会重复导入。

```text
IS_MIGRATE=true
OLD_DATABASE_URL=postgresql://user:password@legacy-db:5432/photowarehouse
```

也可以使用 JDBC URL，并分开提供凭据：

```text
OLD_DATABASE_URL=jdbc:postgresql://legacy-db:5432/photowarehouse
OLD_DB_USERNAME=user
OLD_DB_PASSWORD=password
```

迁移失败不会阻止新后端启动。错误会写入服务日志，并以
`LEGACY_MIGRATION_FAILED` 类型写入管理员告警。修复连接或数据问题后重启即可继续迁移。
