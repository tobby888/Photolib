CREATE TABLE description_image (
    id VARCHAR(26) PRIMARY KEY,
    object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    size BIGINT NOT NULL,
    uploaded_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_description_image_uploader FOREIGN KEY (uploaded_by) REFERENCES app_user(id)
);
