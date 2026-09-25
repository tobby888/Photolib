-- 网站问题反馈：让消息中心承担成员对网站的报障 / 建议。
--
-- 与「消息中心」的 user_notification 不同：反馈是「一份内容 + 一个提交人 + 若干 ADMIN
-- 处理者 + 有状态」的共享工单，因此用三张表表达——工单头（feedback）、对话
-- （feedback_reply）、状态流转（feedback_status_change）。user_notification 仍只负责
-- 通知投递，不背工单本身。
--
-- 状态机：提交即 PENDING；ADMIN 改 IN_PROGRESS / RESOLVED；RESOLVED 可重开回 IN_PROGRESS。
-- 生命周期：不删除、不撤回、不归档，所以 feedback 表没有 deleted 列（对齐 featured_entry
-- 这类「物理增删 / 无软删」的表）。
CREATE TABLE feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    submitter_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NULL,
    content_html TEXT NULL,
    category VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_feedback_submitter FOREIGN KEY (submitter_id) REFERENCES app_user(id),
    INDEX idx_feedback_submitter_created (submitter_id, created_at),
    INDEX idx_feedback_status_created (status, created_at)
);

CREATE TABLE feedback_reply (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    feedback_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    content TEXT NULL,
    content_html TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_feedback_reply_feedback FOREIGN KEY (feedback_id) REFERENCES feedback(id),
    CONSTRAINT fk_feedback_reply_author FOREIGN KEY (author_id) REFERENCES app_user(id),
    INDEX idx_feedback_reply_feedback (feedback_id, created_at)
);

CREATE TABLE feedback_status_change (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    feedback_id BIGINT NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    operator_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_feedback_status_change_feedback FOREIGN KEY (feedback_id) REFERENCES feedback(id),
    CONSTRAINT fk_feedback_status_change_operator FOREIGN KEY (operator_id) REFERENCES app_user(id),
    INDEX idx_feedback_status_change_feedback (feedback_id, created_at)
);
