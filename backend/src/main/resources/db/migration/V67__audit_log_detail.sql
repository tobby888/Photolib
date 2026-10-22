-- 操作日志补全：操作名、操作对象名、结果、失败原因、设备、耗时。
-- 旧记录这些列为空，读取时由后端按 detail_json 里的路径和状态码补出能补的部分。
ALTER TABLE audit_log ADD COLUMN action_name VARCHAR(100) NULL;
ALTER TABLE audit_log ADD COLUMN resource_name VARCHAR(255) NULL;
ALTER TABLE audit_log ADD COLUMN http_status INT NULL;
ALTER TABLE audit_log ADD COLUMN success BOOLEAN NULL;
ALTER TABLE audit_log ADD COLUMN error_code VARCHAR(64) NULL;
ALTER TABLE audit_log ADD COLUMN error_message VARCHAR(500) NULL;
ALTER TABLE audit_log ADD COLUMN user_agent VARCHAR(512) NULL;
ALTER TABLE audit_log ADD COLUMN duration_ms INT NULL;

CREATE INDEX idx_audit_log_action_name ON audit_log(action_name, created_at);
CREATE INDEX idx_audit_log_success ON audit_log(success, created_at);
