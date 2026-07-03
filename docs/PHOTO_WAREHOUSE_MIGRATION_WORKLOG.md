# PhotoWarehouse 数据迁移工作记录

## 1. 工作目标

本次工作在新项目 Spring Boot 后端中增加一次性、可重入的数据迁移能力。当环境变量
`IS_MIGRATE=true` 时，应用在完成 Flyway 建表和管理员初始化后连接旧 PhotoWarehouse
数据库，将能够可靠映射的数据导入新数据库。

迁移异常不会终止 Spring Boot。异常会写入 ERROR 日志，在新数据库 `admin_alert` 表中写入
类型为 `LEGACY_MIGRATION_FAILED` 的未处理告警，然后新系统继续启动。

图片文件不下载、不复制、不重新上传。新记录直接引用旧数据库保存的 OSS object key。

## 2. 启动配置

旧项目使用 PostgreSQL，新项目使用 MySQL。迁移进程同时连接两个数据库。

```env
IS_MIGRATE=true
OLD_DATABASE_URL=postgresql://user:password@legacy-db:5432/photowarehouse
```

也可以使用 JDBC URL：

```env
IS_MIGRATE=true
OLD_DATABASE_URL=jdbc:postgresql://legacy-db:5432/photowarehouse
OLD_DB_USERNAME=user
OLD_DB_PASSWORD=password
```

迁移结束并确认数据无误后应设为 `IS_MIGRATE=false`。即使未关闭，映射表也会避免重复导入。

## 3. 执行顺序与幂等机制

1. Flyway 创建或升级新数据库结构；
2. `AdminBootstrap` 确保新数据库至少存在一个管理员；
3. `LegacyMigrationRunner` 检查 `IS_MIGRATE`；
4. 验证旧数据库连接及 `users` 表；
5. 在同一个新数据库事务中依次迁移用户、项目、照片；
6. 提交事务并输出迁移数量。

新表 `legacy_migration_item` 保存主键对应关系：

| source_type | source_id | target_id | 含义 |
| --- | ---: | ---: | --- |
| `USER` | 旧 `users.id` | 新 `app_user.id` | 用户主键映射 |
| `PROJECT` | 旧 `projects.id` | 新 `project.id` | 项目主键映射 |
| `PHOTO` | 旧 `photos.id` | 新 `photo.id` | 图片主键映射 |

主键为 `(source_type, source_id)`。导入前查询此表，因此重启后只处理尚未迁移的记录。任一写入
失败会回滚本轮全部新库写入。

## 4. 用户字段对应

旧表 `users` → 新表 `app_user`：

| 旧字段 | 新字段 | 转换规则 |
| --- | --- | --- |
| `id` | 映射表 `source_id` | 不复用主键，记录新旧 ID 对应 |
| `username` | `username` | 原值；为空使用 `legacy-{旧ID}` |
| `hashed_password` | `password_hash` | 原样保留旧密码哈希 |
| `real_name` | `display_name` | 为空使用用户名，最长 100 字符 |
| `role` | `role` | 包含 `admin` → `ADMIN`，其余 → `MINISTER` |
| `email` | `email` | 原值 |
| `is_frozen` | `enabled` | 取反 |
| `created_date` | `created_at`、`updated_at` | 原值 |
| — | `campus_id` | `NULL` |
| — | `must_change_password` | `false` |
| — | `version` | `1` |
| — | `deleted` | `false` |

旧字段 `student_id`、`avatar_path`、`reset_token`、`reset_token_expiry` 在新用户模型中没有直接
对应列，当前不迁移。同名用户名已存在时，旧用户会映射到现有用户，不重复创建。

## 5. 项目字段对应

旧表 `projects` → 新表 `project`：

| 旧字段 | 新字段 | 转换规则 |
| --- | --- | --- |
| `id` | 映射表 `source_id` | 记录新旧 ID 对应 |
| `title` | `title` | 最长 200 字符；为空使用 `旧项目 {旧ID}` |
| `description` | `description` | 原值 |
| `created_by` | `created_by` | 经用户映射；找不到时使用新系统管理员 |
| `created_date` | `created_at`、`updated_at` | 原值 |
| — | `status` | `ACTIVE` |
| — | `version` | `1` |
| — | `deleted` | `false` |

`deadline` 和 `sample_photo_id` 在新项目表中没有同义列。若旧图片是项目的
`sample_photo_id`，该图片迁移时会把对应项目写入 `photo.project_id`。`project_tags` 和
`project_ref_tags` 在新项目模型中没有对应结构，当前不迁移。

## 6. 图片字段对应

旧表 `photos` → 新表 `photo`：

| 旧字段/来源 | 新字段 | 转换规则 |
| --- | --- | --- |
| `photos.id` | 映射表 `source_id` | 记录新旧 ID 对应 |
| `original_filename` | `title` | 最长 200 字符 |
| `original_filename` | `stored_file_name` | 最长 255 字符 |
| `file_path` | `object_key` | 原样使用旧 OSS key，不搬运对象 |
| `preview_path` | `thumbnail_object_key` | 原样使用已有预览图 key |
| `file_size` | `size` | 空值按 `0` |
| `mime_type` | `content_type` | 空值使用 `application/octet-stream` |
| `width` | `width` | 原值 |
| `height` | `height` | 原值 |
| `taken_date` | `taken_at` | 为空时使用导入时间 |
| `imported_date` | `created_at`、`updated_at` | 原值 |
| `uploaded_by` | `uploaded_by` | 经用户映射；找不到时使用管理员 |
| `author` | `photographer_name` | 为空依次使用旧用户姓名、“旧系统用户” |
| 旧用户 `student_id` | `photographer_student_id` | 为空使用 `legacy-{旧图片ID}` |
| `photo_tags` + `tags.name` | `tags_json` | 按名称排序并序列化为 JSON 数组 |
| 引用图片的旧项目 ID | `project_id` | 经项目映射；仅适用于旧项目样片 |
| — | `request_id` | `NULL` |
| — | `campus_id` | **`NULL`，不推测校区，由管理员补录** |
| — | `description` | `NULL` |
| — | `original_object_key` | `NULL`，避免同一 OSS 对象被当作待清理原图 |
| — | `sha256` | 对 `object_key` 做 SHA-256，作为稳定占位值 |
| — | `status` | `AVAILABLE` |
| — | `version` | `1` |
| — | `deleted` | `false` |

迁移记录的 `sha256` 是 object key 的摘要，并非图片内容摘要。这避免为计算哈希而下载 bucket
中的全部文件，同时满足新表非空约束。

`filename`、`camera_make`、`camera_model`、`thumbnail_path`、`rating` 没有可靠的同义字段。
其中 `thumbnail_path` 是旧服务本地路径，不能当作 OSS key。

## 7. 未迁移的旧表

| 旧表 | 原因 |
| --- | --- |
| `nicephotos` | 新“采用记录”要求项目、采用人等信息，无法可靠推导 |
| `comments` | 新系统没有图片评论表 |
| `activity_logs` | 新审计字段和行为语义不同 |
| `system_settings` | 新品牌设置结构不同，应由管理员重新配置 |
| `project_tags` | 新项目实体没有标签字段 |
| `project_ref_tags` | 新项目实体没有参考标签字段 |

旧数据库只读且不会删除，可在业务映射明确后继续迁移。

## 8. 校区补录与管理员权限

旧图片没有可信校区，迁移明确将 `photo.campus_id` 留为 `NULL`。

管理员可通过以下接口补录或清空校区：

```http
PATCH /api/v1/photos/{id}/campus
Content-Type: application/json

{"campusId": 123, "version": 1}
```

- 仅 `ADMIN` 可调用；
- `campusId` 可为 `null`；
- 非空校区必须存在；
- 使用 `version` 乐观锁，避免覆盖并发修改。

管理员拥有图库图片的完整管理权限：查看、修改元数据、修改校区、下载、归档、恢复和删除任意
图片。管理员删除不受“图片已被采用，只能归档”的业务限制；普通上传者仍受该规则约束。

## 9. 涉及代码与验证

- `migration/LegacyMigrationRunner.java`：启动触发、异常隔离和管理员告警；
- `migration/LegacyMigrationService.java`：连接旧库、事务迁移和字段转换；
- `migration/LegacyMigrationProperties.java`：环境变量配置；
- `V4__legacy_migration_map.sql`：幂等映射表；
- `PhotoController.java`、`PhotoService.java`：管理员校区补录和删除权限；
- `pom.xml`：PostgreSQL JDBC 驱动。

实施期间执行了 Maven 编译、Flyway V1 至 V4 建表、Spring ApplicationContext 启动测试、旧库
URL 规范化测试及图片权限测试。
