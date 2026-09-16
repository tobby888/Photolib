-- 活动选题（issue #94）。
--
-- 选题分成两类：
--   CREATION —— 创作选题，工作流程与改动前完全一致；
--   EVENT    —— 活动选题，图片先整批进库，由指定的「选片人」逐张打标签，
--               项目结束后把打了 deprecated 的图片连同 OSS 对象一起清掉。
--
-- 存量数据一律是创作选题，所以列带 DEFAULT 'CREATION' 且 NOT NULL：
-- 不给默认值会让所有旧行变成 NULL，而「NULL 算哪一类」这种问题在
-- 每个读到它的地方都要重答一遍。
ALTER TABLE project ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'CREATION';

-- 活动选题的选片人。刻意不复用 request_participant：
-- 选片人是「对整个选题的相册有处置权」，与「接了某个校区的某条需求」是两件事，
-- 合表会让选题可见性规则说不清是哪一条在放行。
--
-- 没有 version / deleted：这张表只表达集合成员关系，改选片人一律是
-- 「整组替换」（先删后插，同一个事务），软删除只会让 UNIQUE 约束失效。
CREATE TABLE project_selector (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_project_selector UNIQUE (project_id, user_id),
    CONSTRAINT fk_project_selector_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_project_selector_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_project_selector_creator FOREIGN KEY (created_by) REFERENCES app_user(id),
    INDEX idx_project_selector_user (user_id)
);
