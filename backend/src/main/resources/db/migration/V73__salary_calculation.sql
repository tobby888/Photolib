-- 工资计算（统计面板「工资计算」）。
--
-- 1. salary_calculation / salary_calculation_line：一次计算是一份快照。按选题结算：清算期内结束的
--    选题，每位有记录（工时或被引）的成员一行，工资 = max(已确认工时小时数 × 时薪, 被引张数 × 单价)。
--    计算之后工时再被审核、被引再被取消都不改这份快照，要新的结果就再算一次。时薪和单价存在计算行上，
--    以后改规则时旧的计算仍能看出当时按什么算的。
-- 2. 清算单预留工资字段：批次记这次附带的是哪一份工资计算，清算单快照记那一刻的应发金额。
--    现在都不写（NULL），等清算单里带上工资时再用。
-- 3. photo_project.added_by：谁把图片添加进这个选题相册（从图库添加、从别的选题复制 / 移动）。
--    清算单的「参与的选题」据此标出「由谁添加」。上传时写的来源归属和本迁移之前的行为 NULL。

CREATE TABLE salary_calculation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    hourly_rate DECIMAL(10, 2) NOT NULL,
    adoption_rate DECIMAL(10, 2) NOT NULL,
    project_count INT NOT NULL DEFAULT 0,
    member_count INT NOT NULL DEFAULT 0,
    line_count INT NOT NULL DEFAULT 0,
    total_amount DECIMAL(14, 2) NOT NULL DEFAULT 0,
    -- 计算时还在待审核、没算进去的工时条数（提醒先审完再算）。
    pending_worklog_count INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    created_by_name VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_salary_calculation_created ON salary_calculation(deleted, created_at);

CREATE TABLE salary_calculation_line (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    calculation_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    -- 选题标题和结束时间是计算那一刻的快照。
    project_title VARCHAR(200) NOT NULL,
    project_completed_at DATETIME(6) NOT NULL,
    -- 成员归并键：有学号按学号（id:<学号>），没有学号按姓名（name:<姓名>），与成员核算同一口径。
    member_key VARCHAR(200) NOT NULL,
    student_id VARCHAR(64) NULL,
    member_name VARCHAR(100) NOT NULL,
    campus VARCHAR(500) NULL,
    shooting_minutes INT NOT NULL DEFAULT 0,
    retouching_minutes INT NOT NULL DEFAULT 0,
    confirmed_worklog_count INT NOT NULL DEFAULT 0,
    pending_worklog_count INT NOT NULL DEFAULT 0,
    pending_minutes INT NOT NULL DEFAULT 0,
    adopted_photos INT NOT NULL DEFAULT 0,
    hour_amount DECIMAL(12, 2) NOT NULL DEFAULT 0,
    adoption_amount DECIMAL(12, 2) NOT NULL DEFAULT 0,
    salary_amount DECIMAL(12, 2) NOT NULL DEFAULT 0,
    -- HOURS / ADOPTIONS / EQUAL（两者相等且大于 0）/ NONE（都为 0）
    basis VARCHAR(16) NOT NULL,
    CONSTRAINT fk_salary_line_calculation FOREIGN KEY (calculation_id) REFERENCES salary_calculation(id),
    CONSTRAINT uk_salary_line_member UNIQUE (calculation_id, project_id, member_key)
);
CREATE INDEX idx_salary_line_member ON salary_calculation_line(calculation_id, member_key);

-- 清算单预留的工资字段（暂不写入）。
ALTER TABLE settlement_mail_batch ADD COLUMN salary_calculation_id BIGINT NULL;
ALTER TABLE settlement_statement ADD COLUMN salary_calculation_id BIGINT NULL;
ALTER TABLE settlement_statement ADD COLUMN salary_amount DECIMAL(12, 2) NULL;

ALTER TABLE photo_project ADD COLUMN added_by BIGINT NULL;

-- 计算工资、查看和导出明细：默认给管理员组和部长组。
INSERT INTO permission_group_permission(group_id, permission_code)
SELECT id, 'SALARY_CALCULATE' FROM permission_group
WHERE code IN ('ADMIN', 'MINISTER');
