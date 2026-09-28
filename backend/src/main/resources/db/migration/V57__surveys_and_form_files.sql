-- 问卷：给系统内成员发的问卷，以及招募 / 问卷共用的「上传文件」题目。
--
-- 问卷和招募的区别：招募面向没有账号的访客（匿名草稿 + 学号去重），问卷只发给系统里
-- 持有 SURVEY_ACCESS 的成员，按账号去重，每人每份问卷只能交一次。题目结构与招募完全
-- 相同（form_schema_json 用同一套校验），所以问卷不另起一套题型。
--
-- 三个权限相互独立：
--   SURVEY_CREATE       新建、编辑、发布和结束问卷，选择发放对象
--   SURVEY_ACCESS       作为发放对象访问和填写问卷
--   SURVEY_RESULT_VIEW  查看和导出问卷结果
CREATE TABLE survey (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(200) NOT NULL,
    -- 一句话描述：列表卡片和通知里显示，纯文本。
    description VARCHAR(1000) NULL,
    -- 简介：Markdown，插图走 description-images。2 万字的中文 + 图片链接会超过 TEXT。
    intro_markdown MEDIUMTEXT NULL,
    form_schema_json JSON NOT NULL,
    -- 截止时间可以不设；不设时一直开放到手动结束。
    ends_at DATETIME(6) NULL,
    status VARCHAR(32) NOT NULL,
    created_by BIGINT NOT NULL,
    published_by BIGINT NULL,
    published_at DATETIME(6) NULL,
    closed_by BIGINT NULL,
    closed_at DATETIME(6) NULL,
    version INT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_survey_creator FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT fk_survey_publisher FOREIGN KEY (published_by) REFERENCES app_user(id),
    CONSTRAINT fk_survey_closer FOREIGN KEY (closed_by) REFERENCES app_user(id),
    CONSTRAINT chk_survey_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED')),
    INDEX idx_survey_status_created (deleted, status, created_at)
);

-- 发放对象。只存「发给了谁」，谁能填还要同时满足：账号启用 + 仍持有 SURVEY_ACCESS。
CREATE TABLE survey_target (
    survey_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (survey_id, user_id),
    CONSTRAINT fk_survey_target_survey FOREIGN KEY (survey_id) REFERENCES survey(id),
    CONSTRAINT fk_survey_target_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    INDEX idx_survey_target_user (user_id, survey_id)
);

-- 答卷冻结提交那一刻的表单结构，问卷之后再怎么改都不影响已交的答卷怎么显示、怎么导出。
CREATE TABLE survey_response (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    survey_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    answers_json JSON NOT NULL,
    form_schema_json JSON NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_survey_response_user UNIQUE (survey_id, user_id),
    CONSTRAINT fk_survey_response_survey FOREIGN KEY (survey_id) REFERENCES survey(id),
    CONSTRAINT fk_survey_response_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    INDEX idx_survey_response_time (survey_id, submitted_at)
);

-- 「上传文件」题目的文件。招募（匿名草稿）和问卷（登录成员）共用：
--   owner_type = 'RECRUITMENT'  owner_ref = 招募草稿 id
--   owner_type = 'SURVEY'       owner_ref = 问卷 id，uploader_user_id = 填写人
-- 浏览器先拿预签名 PUT 直传对象存储（PENDING），提交答卷时服务端核对对象确实存在、
-- 大小一致后改成 ATTACHED。一直没被提交引用的 PENDING 文件，在签名过期后由清理任务
-- 连对象带行一起删掉——签名没过期之前不能删行，否则还能被重放的 PUT 会留下没人认领的对象。
CREATE TABLE form_file_upload (
    id CHAR(26) PRIMARY KEY,
    owner_type VARCHAR(32) NOT NULL,
    owner_ref VARCHAR(64) NOT NULL,
    uploader_user_id BIGINT NULL,
    field_id VARCHAR(64) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size BIGINT NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL,
    upload_url_expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    attached_at DATETIME(6) NULL,
    CONSTRAINT uk_form_file_upload_object UNIQUE (object_key),
    CONSTRAINT fk_form_file_upload_user FOREIGN KEY (uploader_user_id) REFERENCES app_user(id),
    CONSTRAINT chk_form_file_upload_owner CHECK (owner_type IN ('RECRUITMENT', 'SURVEY')),
    CONSTRAINT chk_form_file_upload_status CHECK (status IN ('PENDING', 'ATTACHED')),
    CONSTRAINT chk_form_file_upload_size CHECK (size > 0),
    INDEX idx_form_file_upload_owner (owner_type, owner_ref, status),
    INDEX idx_form_file_upload_expiry (status, upload_url_expires_at)
);

-- 默认授权：管理员和部长三个权限都有；校区负责人只作为发放对象接收问卷。
INSERT INTO permission_group_permission(group_id, permission_code)
SELECT id, 'SURVEY_CREATE' FROM permission_group
WHERE code IN ('ADMIN', 'MINISTER');

INSERT INTO permission_group_permission(group_id, permission_code)
SELECT id, 'SURVEY_RESULT_VIEW' FROM permission_group
WHERE code IN ('ADMIN', 'MINISTER');

INSERT INTO permission_group_permission(group_id, permission_code)
SELECT id, 'SURVEY_ACCESS' FROM permission_group
WHERE code IN ('ADMIN', 'MINISTER', 'CAMPUS_MANAGER');
