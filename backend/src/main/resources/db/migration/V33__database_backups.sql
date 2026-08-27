-- 数据库备份与回滚记录。
--
-- 这两张表刻意不被备份/回滚流程本身导出或写入（见 DatabaseDumpService.EXCLUDED_TABLES）：
-- 如果回滚会把备份目录也退回旧快照，管理员就会连"刚刚为这次回滚生成的兜底备份"一起丢掉。
-- 同理，created_by 不建外键——回滚会重写 app_user，旧快照里可能没有这个账号，
-- 因此这里只保留用户 id 与姓名快照，账号消失也仍能显示是谁触发的。
CREATE TABLE database_backup (
    id VARCHAR(26) PRIMARY KEY,
    -- SCHEDULED（每日自动）/ MANUAL（管理员手动）/ PRE_RESTORE（回滚前兜底）
    type VARCHAR(16) NOT NULL,
    -- RUNNING / SUCCEEDED / FAILED / EXPIRED（超过保留期，对象已删除）
    status VARCHAR(16) NOT NULL,
    object_key VARCHAR(512) NULL,
    size_bytes BIGINT NULL,
    sha256 CHAR(64) NULL,
    table_count INT NULL,
    row_count BIGINT NULL,
    -- 备份时数据库已应用的 Flyway 版本，回滚前必须与当前库一致
    schema_version VARCHAR(64) NULL,
    migration_count INT NULL,
    error_message VARCHAR(1000) NULL,
    created_by BIGINT NULL,
    created_by_name VARCHAR(100) NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    INDEX idx_database_backup_started (started_at),
    INDEX idx_database_backup_status (status, started_at)
);

CREATE TABLE database_restore (
    id VARCHAR(26) PRIMARY KEY,
    backup_id VARCHAR(26) NOT NULL,
    -- 回滚前自动生成的兜底备份，用于把误操作的回滚再退回去
    safety_backup_id VARCHAR(26) NULL,
    -- RUNNING / SUCCEEDED / FAILED
    status VARCHAR(16) NOT NULL,
    table_count INT NULL,
    row_count BIGINT NULL,
    error_message VARCHAR(1000) NULL,
    created_by BIGINT NULL,
    created_by_name VARCHAR(100) NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    INDEX idx_database_restore_started (started_at)
);
