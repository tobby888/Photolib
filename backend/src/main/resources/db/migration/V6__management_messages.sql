ALTER TABLE user_notification ADD COLUMN sender_id BIGINT NULL;
ALTER TABLE user_notification ADD COLUMN content_html TEXT NULL;
ALTER TABLE user_notification
    ADD CONSTRAINT fk_user_notification_sender FOREIGN KEY (sender_id) REFERENCES app_user(id);

CREATE TABLE message_image (
    id VARCHAR(26) PRIMARY KEY,
    object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    size BIGINT NOT NULL,
    uploaded_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_message_image_uploader FOREIGN KEY (uploaded_by) REFERENCES app_user(id)
);
