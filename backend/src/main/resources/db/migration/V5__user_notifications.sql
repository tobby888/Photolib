CREATE TABLE user_notification (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content VARCHAR(1000) NULL,
    action_url VARCHAR(512) NULL,
    read_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_user_notification_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    INDEX idx_user_notification_user_created (user_id, created_at),
    INDEX idx_user_notification_user_read (user_id, read_at)
);
