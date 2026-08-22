-- Public recruitment tasks, anonymous drafts, immutable submissions and their
-- original (uncompressed) attachment upload batches.
CREATE TABLE recruitment_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    title VARCHAR(200) NOT NULL,
    intro_markdown TEXT NULL,
    form_schema_json JSON NOT NULL,
    student_id_label VARCHAR(100) NOT NULL,
    student_id_help VARCHAR(500) NULL,
    upload_label VARCHAR(100) NOT NULL,
    upload_help VARCHAR(500) NULL,
    upload_required BOOLEAN NOT NULL DEFAULT FALSE,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
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
    CONSTRAINT uk_recruitment_task_public_id UNIQUE (public_id),
    CONSTRAINT fk_recruitment_task_creator FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT fk_recruitment_task_publisher FOREIGN KEY (published_by) REFERENCES app_user(id),
    CONSTRAINT fk_recruitment_task_closer FOREIGN KEY (closed_by) REFERENCES app_user(id),
    INDEX idx_recruitment_task_public_window (status, starts_at, ends_at, deleted),
    INDEX idx_recruitment_task_created (created_at, deleted)
);

CREATE TABLE recruitment_draft (
    id CHAR(26) PRIMARY KEY,
    task_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_recruitment_draft_token UNIQUE (token_hash),
    CONSTRAINT fk_recruitment_draft_task FOREIGN KEY (task_id) REFERENCES recruitment_task(id),
    INDEX idx_recruitment_draft_expiry (status, expires_at),
    INDEX idx_recruitment_draft_task (task_id, status)
);

CREATE TABLE recruitment_upload_batch (
    id CHAR(26) PRIMARY KEY,
    draft_id CHAR(26) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    archive_object_key VARCHAR(512) NULL,
    archive_file_name VARCHAR(255) NULL,
    archive_size BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failure_count INT NOT NULL DEFAULT 0,
    failure_reason VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_recruitment_upload_batch_draft FOREIGN KEY (draft_id) REFERENCES recruitment_draft(id),
    INDEX idx_recruitment_upload_batch_draft (draft_id, status)
);

CREATE TABLE recruitment_upload_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id CHAR(26) NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    temp_object_key VARCHAR(512) NULL,
    object_key VARCHAR(512) NULL,
    content_type VARCHAR(100) NOT NULL,
    size BIGINT NOT NULL,
    sha256 CHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_recruitment_upload_item_object UNIQUE (object_key),
    CONSTRAINT fk_recruitment_upload_item_batch FOREIGN KEY (batch_id) REFERENCES recruitment_upload_batch(id),
    INDEX idx_recruitment_upload_item_batch (batch_id, status)
);

CREATE TABLE recruitment_application (
    id CHAR(26) PRIMARY KEY,
    task_id BIGINT NOT NULL,
    draft_id CHAR(26) NOT NULL,
    student_id VARCHAR(128) NOT NULL,
    normalized_student_id VARCHAR(64) NOT NULL,
    answers_json JSON NOT NULL,
    form_schema_json JSON NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_recruitment_application_draft UNIQUE (draft_id),
    CONSTRAINT uk_recruitment_application_student UNIQUE (task_id, normalized_student_id),
    CONSTRAINT fk_recruitment_application_task FOREIGN KEY (task_id) REFERENCES recruitment_task(id),
    CONSTRAINT fk_recruitment_application_draft FOREIGN KEY (draft_id) REFERENCES recruitment_draft(id),
    INDEX idx_recruitment_application_task_time (task_id, submitted_at)
);

-- Recruitment viewing is available to every built-in operational role. Publishing
-- remains an independently assignable permission and is enabled by default only
-- for administrators and ministers.
INSERT INTO permission_group_permission(group_id, permission_code)
SELECT id, 'RECRUITMENT_VIEW' FROM permission_group
WHERE code IN ('ADMIN', 'MINISTER', 'CAMPUS_MANAGER');

INSERT INTO permission_group_permission(group_id, permission_code)
SELECT id, 'RECRUITMENT_PUBLISH' FROM permission_group
WHERE code IN ('ADMIN', 'MINISTER');
