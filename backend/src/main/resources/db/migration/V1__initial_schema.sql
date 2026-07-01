CREATE TABLE campus (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version INT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_campus_code UNIQUE (code)
);

CREATE TABLE app_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    role VARCHAR(32) NOT NULL,
    campus_id BIGINT NULL,
    phone VARCHAR(32) NULL,
    email VARCHAR(255) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    version INT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_user_username UNIQUE (username),
    CONSTRAINT fk_user_campus FOREIGN KEY (campus_id) REFERENCES campus(id)
);

CREATE TABLE auth_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    access_token_hash CHAR(64) NOT NULL,
    refresh_token_hash CHAR(64) NOT NULL,
    access_expires_at DATETIME(6) NOT NULL,
    idle_expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_session_access UNIQUE (access_token_hash),
    CONSTRAINT uk_session_refresh UNIQUE (refresh_token_hash),
    CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    INDEX idx_session_user (user_id)
);

CREATE TABLE project (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(200) NOT NULL,
    description TEXT NULL,
    status VARCHAR(32) NOT NULL,
    created_by BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_project_creator FOREIGN KEY (created_by) REFERENCES app_user(id),
    INDEX idx_project_status (status)
);

CREATE TABLE photo_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NULL,
    campus_id BIGINT NOT NULL,
    required_count INT NOT NULL,
    deadline DATETIME(6) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by BIGINT NOT NULL,
    first_accepted_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    cancel_reason VARCHAR(500) NULL,
    version INT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_request_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_request_campus FOREIGN KEY (campus_id) REFERENCES campus(id),
    CONSTRAINT fk_request_creator FOREIGN KEY (created_by) REFERENCES app_user(id),
    INDEX idx_request_project (project_id),
    INDEX idx_request_campus_status (campus_id, status)
);

CREATE TABLE request_participant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_request_participant UNIQUE (request_id, user_id),
    CONSTRAINT fk_participant_request FOREIGN KEY (request_id) REFERENCES photo_request(id),
    CONSTRAINT fk_participant_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);

CREATE TABLE photo (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id BIGINT NULL,
    project_id BIGINT NULL,
    title VARCHAR(200) NULL,
    description TEXT NULL,
    photographer_student_id VARCHAR(64) NOT NULL,
    photographer_name VARCHAR(100) NOT NULL,
    uploaded_by BIGINT NOT NULL,
    campus_id BIGINT NULL,
    taken_at DATETIME(6) NOT NULL,
    tags_json JSON NULL,
    width INT NULL,
    height INT NULL,
    size BIGINT NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    thumbnail_object_key VARCHAR(512) NULL,
    original_object_key VARCHAR(512) NULL,
    stored_file_name VARCHAR(255) NULL,
    sha256 CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(1000) NULL,
    original_delete_after DATETIME(6) NULL,
    version INT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_photo_request FOREIGN KEY (request_id) REFERENCES photo_request(id),
    CONSTRAINT fk_photo_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_photo_uploader FOREIGN KEY (uploaded_by) REFERENCES app_user(id),
    CONSTRAINT fk_photo_campus FOREIGN KEY (campus_id) REFERENCES campus(id),
    CONSTRAINT uk_photo_object_key UNIQUE (object_key),
    INDEX idx_photo_status_created (status, created_at),
    INDEX idx_photo_photographer (photographer_student_id, photographer_name)
);

CREATE TABLE adoption (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    photo_id BIGINT NOT NULL,
    photographer_student_id VARCHAR(64) NOT NULL,
    photographer_name VARCHAR(100) NOT NULL,
    remark VARCHAR(500) NULL,
    adopted_by BIGINT NOT NULL,
    adopted_at DATETIME(6) NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_adoption_project_photo UNIQUE (project_id, photo_id),
    CONSTRAINT fk_adoption_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_adoption_photo FOREIGN KEY (photo_id) REFERENCES photo(id),
    CONSTRAINT fk_adoption_user FOREIGN KEY (adopted_by) REFERENCES app_user(id),
    INDEX idx_adoption_photographer (photographer_student_id, photographer_name)
);

CREATE TABLE worklog (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    shooting_minutes INT NOT NULL DEFAULT 0,
    retouching_minutes INT NOT NULL DEFAULT 0,
    remark VARCHAR(1000) NULL,
    status VARCHAR(32) NOT NULL,
    reject_reason VARCHAR(500) NULL,
    confirmed_by BIGINT NULL,
    confirmed_at DATETIME(6) NULL,
    version INT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_worklog_request FOREIGN KEY (request_id) REFERENCES photo_request(id),
    CONSTRAINT fk_worklog_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_worklog_confirmer FOREIGN KEY (confirmed_by) REFERENCES app_user(id),
    INDEX idx_worklog_user_date (user_id, work_date)
);

CREATE TABLE export_job (
    id VARCHAR(26) PRIMARY KEY,
    type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    object_key VARCHAR(512) NULL,
    error_message VARCHAR(1000) NULL,
    expires_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_export_creator FOREIGN KEY (created_by) REFERENCES app_user(id)
);

CREATE TABLE notification_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NULL,
    email VARCHAR(255) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    payload_json TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);

CREATE TABLE audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_id BIGINT NULL,
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64) NULL,
    request_id VARCHAR(64) NULL,
    detail_json JSON NULL,
    ip_address VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_audit_operator FOREIGN KEY (operator_id) REFERENCES app_user(id),
    INDEX idx_audit_created (created_at),
    INDEX idx_audit_resource (resource_type, resource_id)
);
