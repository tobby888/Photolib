-- 邮件清算单（统计面板「发送清算单」）。
--
-- 1. worklog_review_log：工时每一次提交、确认、退回各记一行。worklog 表本身只存当前状态，
--    退回后重新提交会把 reject_reason 清空，清算单要列出「每次申报的时间」和「退回的原因」
--    只能靠这张表。历史数据按现有列回填出能确定的那部分（见文件末尾）。
-- 2. mail_template：管理员自定义的邮件模板。没有行就用代码里的内置模板，「恢复默认」即删行。
-- 3. settlement_mail_batch / settlement_mail_delivery：一次发送是一个批次，每个收件人一行投递记录。
--    active_slot 只在批次进行中为 1、结束后置 NULL，配合唯一索引保证同一时刻全站只有一个批次在发
--    （NULL 不参与唯一约束）。两个部长同时点「发送」时后到的那个插入失败，不会给同一批人发两遍。

CREATE TABLE worklog_review_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    worklog_id BIGINT NOT NULL,
    -- SUBMITTED / CONFIRMED / REJECTED
    action VARCHAR(16) NOT NULL,
    reason VARCHAR(500) NULL,
    -- 提交人（站内填报的账号；链接申报为开链接的成员）或审核人。
    actor_id BIGINT NULL,
    -- 申报时的时长快照：退回后改了时长再交，清算单里能看出每次报的是多少。
    shooting_minutes INT NULL,
    retouching_minutes INT NULL,
    -- 回填出来的行为 TRUE：时间取自 created_at / confirmed_at，只是近似。
    backfilled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_worklog_review_log_worklog FOREIGN KEY (worklog_id) REFERENCES worklog(id)
);
CREATE INDEX idx_worklog_review_log_worklog ON worklog_review_log(worklog_id, created_at);

CREATE TABLE mail_template (
    code VARCHAR(64) PRIMARY KEY,
    subject VARCHAR(200) NOT NULL,
    html_body MEDIUMTEXT NOT NULL,
    updated_by BIGINT NULL,
    updated_by_name VARCHAR(100) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    version INT NOT NULL DEFAULT 1
);

CREATE TABLE settlement_mail_batch (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    -- RUNNING / COMPLETED / INTERRUPTED
    status VARCHAR(16) NOT NULL,
    active_slot TINYINT NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    created_by BIGINT NOT NULL,
    created_by_name VARCHAR(100) NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    sent_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    CONSTRAINT uk_settlement_mail_batch_active UNIQUE (active_slot)
);
CREATE INDEX idx_settlement_mail_batch_created ON settlement_mail_batch(created_at);

CREATE TABLE settlement_mail_delivery (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    recipient_name VARCHAR(100) NOT NULL,
    recipient_email VARCHAR(255) NOT NULL,
    -- PENDING / SENDING / SENT / FAILED
    status VARCHAR(16) NOT NULL,
    worklog_count INT NOT NULL DEFAULT 0,
    adoption_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL,
    sent_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_settlement_mail_delivery_batch FOREIGN KEY (batch_id) REFERENCES settlement_mail_batch(id),
    CONSTRAINT uk_settlement_mail_delivery_user UNIQUE (batch_id, user_id)
);
CREATE INDEX idx_settlement_mail_delivery_status ON settlement_mail_delivery(batch_id, status);

-- 发送清算单：默认给管理员组和部长组。
INSERT INTO permission_group_permission(group_id, permission_code)
SELECT id, 'SETTLEMENT_MAIL_SEND' FROM permission_group
WHERE code IN ('ADMIN', 'MINISTER');

-- 回填：非草稿的工时至少提交过一次，时间取创建时间（站内先存草稿再提交的会早一些，标 backfilled）。
INSERT INTO worklog_review_log(worklog_id, action, actor_id, shooting_minutes, retouching_minutes, backfilled, created_at)
SELECT id, 'SUBMITTED', user_id, shooting_minutes, retouching_minutes, TRUE, created_at
FROM worklog WHERE status <> 'DRAFT';

INSERT INTO worklog_review_log(worklog_id, action, reason, actor_id, backfilled, created_at)
SELECT id, status, CASE WHEN status = 'REJECTED' THEN reject_reason END, confirmed_by, TRUE, confirmed_at
FROM worklog WHERE status IN ('CONFIRMED', 'REJECTED') AND confirmed_at IS NOT NULL;
