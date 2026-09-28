-- 注册码自助注册：管理员生成注册码，持码同学填写账号资料提交申请，管理员审核通过后才建出账号。
--
-- registration_code：一枚注册码。可注册数量（max_uses）、有效期（valid_from ~ valid_until，
-- 左闭右开）和注册后的权限组都在生成时由管理员定下；权限组是校区范围时，授权校区记在
-- registration_code_campus。used_count = 该码名下「待审核 + 已通过」的申请数，提交时用
-- 条件 UPDATE 占位（并发提交不会超发），驳回时归还名额。
--
-- registration_application：一份注册申请。审核通过前不在 app_user 里建行——待审核的人
-- 不是系统成员，不能登录、不能出现在账号列表和通讯录里。密码只存哈希，通过时原样搬进
-- app_user。pending_username / pending_email 只在 PENDING 时有值（通过或驳回后清空），
-- 靠唯一索引挡住两份待审核申请抢同一个账号或邮箱；与已有账号的冲突由服务层在提交和
-- 通过时各查一次，最终由 app_user 的唯一约束兜底。
CREATE TABLE registration_code (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    permission_group_id BIGINT NOT NULL,
    max_uses INT NOT NULL,
    used_count INT NOT NULL DEFAULT 0,
    valid_from DATETIME(6) NOT NULL,
    valid_until DATETIME(6) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_registration_code_code UNIQUE (code),
    CONSTRAINT fk_registration_code_creator FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT chk_registration_code_max_uses CHECK (max_uses > 0),
    CONSTRAINT chk_registration_code_used_count CHECK (used_count >= 0),
    INDEX idx_registration_code_created (created_at)
);

CREATE TABLE registration_code_campus (
    code_id BIGINT NOT NULL,
    campus_id BIGINT NOT NULL,
    PRIMARY KEY (code_id, campus_id),
    CONSTRAINT fk_registration_code_campus_code FOREIGN KEY (code_id) REFERENCES registration_code(id),
    CONSTRAINT fk_registration_code_campus_campus FOREIGN KEY (campus_id) REFERENCES campus(id)
);

CREATE TABLE registration_application (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code_id BIGINT NOT NULL,
    username VARCHAR(64) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    pending_username VARCHAR(64) NULL,
    pending_email VARCHAR(255) NULL,
    reviewer_id BIGINT NULL,
    reviewed_at DATETIME(6) NULL,
    reject_reason VARCHAR(500) NULL,
    user_id BIGINT NULL,
    version INT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_registration_application_pending_username UNIQUE (pending_username),
    CONSTRAINT uk_registration_application_pending_email UNIQUE (pending_email),
    CONSTRAINT fk_registration_application_code FOREIGN KEY (code_id) REFERENCES registration_code(id),
    CONSTRAINT fk_registration_application_reviewer FOREIGN KEY (reviewer_id) REFERENCES app_user(id),
    CONSTRAINT fk_registration_application_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    INDEX idx_registration_application_status_created (status, created_at),
    INDEX idx_registration_application_code (code_id)
);
