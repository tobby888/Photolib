-- Per-user photo favorites. The composite primary key both enforces idempotency
-- and supports the main access path: list one user's favorite photo ids.
CREATE TABLE photo_favorite (
    user_id BIGINT NOT NULL,
    photo_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, photo_id),
    CONSTRAINT fk_photo_favorite_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_photo_favorite_photo FOREIGN KEY (photo_id) REFERENCES photo(id),
    INDEX idx_photo_favorite_photo (photo_id)
);
