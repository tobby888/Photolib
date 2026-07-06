# PhotoWarehouse 服务器迁移

`scripts/migrate_photowarehouse.py` 把旧 PhotoWarehouse（PostgreSQL + 阿里云 OSS）迁移到 PhotoLib，
拆成两步，旧/新两台机器不需要能直连对方数据库：

- `export`：连接旧 PostgreSQL，把**全部表原样**导出成一个 JSON 包。旧库每一行都会被保留，
  因此评论、投票、评分、EXIF、项目标签等暂无新业务字段的数据不会丢失。
- `import`：读取 JSON 包，先把旧 OSS Bucket 的对象**复制到新 Bucket**，再把可映射的业务数据写入新 MySQL
  （`app_user` / `project` / `photo`），并把 JSON 包中每一行完整写入 `legacy_archive_record`。

导入靠 `legacy_migration_item` 记录新旧主键映射，是幂等的，可安全重跑。脚本在 Linux 上运行。

## 准备

先备份两个数据库和旧 Bucket，并先启动一次新版 PhotoLib，让 Flyway 升级到最新版本
（需包含 `legacy_migration_item` 与 `legacy_archive_record` 两张表）。

```bash
python3 -m venv .venv-migration
.venv-migration/bin/pip install -r scripts/photowarehouse_migration_requirements.txt
```

## 第一步：导出（旧服务器）

```bash
export OLD_DATABASE_URL='postgresql://user:password@old-db:5432/photowarehouse'

.venv-migration/bin/python scripts/migrate_photowarehouse.py export \
  --output photowarehouse-export.json
```

旧库若不是默认 `public` schema，用 `--source-schema` 或环境变量 `OLD_DB_SCHEMA` 指定。
把生成的 `photowarehouse-export.json` 传输到新服务器。

## 第二步：导入（新服务器）

停止旧系统写入后执行。旧对象会被复制到新 Bucket，成功后旧 Bucket 可删除：

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

## 行为与选项

- OSS 先复制、成功后数据库才开始事务写入；数据库失败会回滚，但已复制对象会保留供下次续跑。
  新 Bucket 中已存在且大小一致的对象会跳过，因此中断后可直接重跑。
- 默认保持对象 key 不变。若需要更换前缀，设置 `OLD_OSS_PREFIX` / `NEW_OSS_PREFIX`
  （或 `--source-prefix` / `--destination-prefix`）；数据库中的原图和预览图 key 会同步改写。
- 旧普通 `user` 映射为 `CAMPUS_MANAGER` 但默认禁用，避免未分配校区时越权；显式传 `--enable-ordinary-users` 才直接启用。
  旧 `admin` 映射为 `MINISTER`，旧 `superadmin` 映射为 `ADMIN`。
- 找不到对应用户的项目/照片归属到 `--fallback-user-id`；不指定时取新库第一个管理员。
- 对象已单独迁移完成时可加 `--skip-oss` 只写库（不建议）。

## 已知约束

- 旧库照片与项目没有直接外键，脚本只能通过“作为项目样图”反推照片所属项目，其余照片 `project_id` 为空。
- 写入 `photo.sha256` 的是 object key 的稳定摘要，不是图片内容摘要，不能用作内容去重依据。
- 旧数据无法可靠推断的字段（如图片校区）保留为空，不做猜测。

迁移后必须核对报告中的归档行数、OSS 对象数/字节数和三类业务记录数量，并抽查原图及预览图；
确认无误后再删除旧 Bucket。
