CREATE TABLE photo_upload_batch (
    id VARCHAR(26) PRIMARY KEY,
    mode VARCHAR(16) NOT NULL,
    request_id BIGINT NULL,
    project_id BIGINT NULL,
    created_by BIGINT NOT NULL,
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
    CONSTRAINT fk_batch_request FOREIGN KEY (request_id) REFERENCES photo_request(id),
    CONSTRAINT fk_batch_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_batch_creator FOREIGN KEY (created_by) REFERENCES app_user(id)
);

CREATE TABLE photo_upload_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id VARCHAR(26) NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    temp_object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size BIGINT NOT NULL,
    sha256 CHAR(64) NULL,
    title VARCHAR(200) NULL,
    description TEXT NULL,
    photographer_student_id VARCHAR(64) NULL,
    photographer_name VARCHAR(100) NULL,
    taken_at DATETIME(6) NULL,
    tags_json JSON NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(1000) NULL,
    photo_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_batch_item_batch FOREIGN KEY (batch_id) REFERENCES photo_upload_batch(id),
    CONSTRAINT fk_batch_item_photo FOREIGN KEY (photo_id) REFERENCES photo(id),
    INDEX idx_batch_item_batch (batch_id)
);

CREATE TABLE admin_alert (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    type VARCHAR(64) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    resource_type VARCHAR(64) NULL,
    resource_id VARCHAR(64) NULL,
    resolved BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    resolved_at DATETIME(6) NULL,
    resolved_by BIGINT NULL,
    CONSTRAINT fk_alert_resolver FOREIGN KEY (resolved_by) REFERENCES app_user(id),
    INDEX idx_alert_unresolved (resolved, created_at)
);
